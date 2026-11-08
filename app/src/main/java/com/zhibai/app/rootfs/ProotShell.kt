package com.zhibai.app.rootfs

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Run a command inside the built-in rootfs via the packaged PRoot binaries.
 *
 * Ported from RikkaHub (github.com/rikkahub/rikkahub): ProotShellRunner (command line),
 * WorkspaceShellRunner (readResult/StreamCollector), ProotTermination (terminateProot).
 *
 * execute() returns null ONLY when proot cannot be used (rootfs missing, .so missing,
 * process failed to start) so callers can fall through to su/Termux/sh; any real run
 * result — including non-zero exit and timeout — comes back as a formatted string.
 */
object ProotShell {
    private const val PROOT_EXEC = "libproot_exec.so"
    private const val PROOT_LOADER = "libproot_loader.so"
    private const val ARGV0 = "zhibai"

    // Rootfs 内存放前台命令进程号的目录 (host: linuxDir/tmp/zhibai_exec/)
    private const val EXEC_DIR = "/tmp/zhibai_exec"

    // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
    private const val EXEC_SCRIPT = "cd -- \"\$1\" && eval \"\$2\""

    // 先把 shell 与 PRoot 的进程号写入 $3, 终止时据此给整组进程发信号, 见 terminateProot
    private const val TRACKED_SCRIPT = "echo \"\$\$ \$PPID\" > \"\$3\"; $EXEC_SCRIPT"

    // 清空宿主机环境变量后 Rootfs 内的基础环境
    private val ROOTFS_ENV = listOf(
        "/usr/bin/env",
        "-i",
        "HOME=${Rootfs.HOME}",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
    )

    private val patcher = RootfsPatcher()
    fun execute(
        nativeLibDir: File,
        linuxDir: File,
        workspaceDir: File,
        tempDir: File,
        command: String,
        timeoutSec: Long,
    ): String? {
        if (!Rootfs.isUsable(linuxDir)) return null
        val exec = File(nativeLibDir, PROOT_EXEC)
        val loader = File(nativeLibDir, PROOT_LOADER)
        if (!exec.isFile || !loader.isFile) return null

        val pidFile = File(linuxDir, "${EXEC_DIR.trimStart('/')}/${UUID.randomUUID()}.pid")
        pidFile.parentFile?.mkdirs()
        tempDir.mkdirs()
        patcher.patch(linuxDir)
        val process = try {
            ProcessBuilder(
                buildCommand(exec, linuxDir, workspaceDir, command, pidFile)
            )
                .redirectErrorStream(false)
                .directory(workspaceDir)
                .apply {
                    environment()["PROOT_LOADER"] = loader.absolutePath
                    environment()["PROOT_TMP_DIR"] = tempDir.absolutePath
                    environment()["TMPDIR"] = tempDir.absolutePath
                }
                .start()
        } catch (_: Exception) {
            return null
        }
        return try {
            val timeoutMillis = timeoutSec.coerceIn(1, 300) * 1000
            val result = process.readResult(timeoutMillis) {
                terminateProot(this, pidFile)
            }
            if (result.timedOut) {
                "Error: timeout after ${timeoutSec.coerceIn(1, 300)}s"
            } else {
                buildString {
                    if (result.exitCode != 0) appendLine("Exit code: ${result.exitCode}")
                    if (result.stdout.isNotBlank()) appendLine(result.stdout.trimEnd())
                    if (result.stderr.isNotBlank()) appendLine("STDERR: ${result.stderr.trimEnd()}")
                    if (result.stdout.isBlank() && result.stderr.isBlank()) appendLine("(no output)")
                }.take(8000)
            }
        } finally {
            pidFile.delete()
        }
    }
    private fun buildCommand(
        exec: File,
        linuxDir: File,
        workspaceDir: File,
        command: String,
        pidFile: File,
    ): List<String> {
        val guestPidFile = "$EXEC_DIR/${pidFile.name}"
        val shell = Rootfs.shell(linuxDir)
        val args = mutableListOf(exec.absolutePath)
        args += listOf(
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            Rootfs.MOUNT_WORKSPACE,
            "-b",
            "${workspaceDir.absolutePath}:${Rootfs.MOUNT_WORKSPACE}",
        )
        listOf("/sdcard", "/storage").forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += "$path:${path.trimEnd('/')}"
            }
        }
        Rootfs.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                args += "-b"
                args += path
            }
        }
        args += ROOTFS_ENV
        args += listOf(
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
            shell,
            "-l",
            "-c",
            TRACKED_SCRIPT,
            ARGV0,
            Rootfs.MOUNT_WORKSPACE,
            command,
            guestPidFile,
        )
        return args
    }
}

private data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
private const val MAX_OUTPUT_CHARS = 128 * 1024

/** 等待命令结束并收集输出; 超时或线程被中断时用 [terminate] 结束进程 */
private fun Process.readResult(
    timeoutMillis: Long,
    terminate: Process.() -> Unit = { destroyForcibly() },
): CommandResult {
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    // 没有 stdin 输入时立即关闭管道, 让子进程读到 EOF;
    // 否则 cat/gh/kubectl 等按 isatty 判断的工具会把这根永不写入的管道当成待输入流而永久阻塞
    try {
        outputStream.close()
    } catch (_: IOException) {
        // 子进程已提前退出导致管道关闭, 忽略
    }
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            terminate()
        }
        stdout.join(1_000)
        stderr.join(1_000)
        return CommandResult(
            exitCode = if (finished) exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
        )
    } catch (e: InterruptedException) {
        // 调用方线程被中断（如协程取消时的 runInterruptible），杀掉进程避免命令继续执行
        terminate()
        // 进程被杀后 stdout/stderr 会关闭, 这里 join 回收两个采集线程, 避免每次取消泄漏一对线程
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}
private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
) {
    private val builder = StringBuilder()

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    // 超出上限后继续读到 EOF 并丢弃，否则管道写满会阻塞子进程导致其无法退出
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        }
    }.apply {
        // 设为 daemon: 即使 proot grandchild 残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}
private const val KILL_WAIT_MS = 2_000L
private const val KILL_ROUNDS = 10
private const val KILL_ROUND_WAIT_MS = 200L
private const val PID_WAIT_MS = 1_000L

/**
 * 终止 PRoot 及其中运行的所有进程, 阻塞到 PRoot 退出。
 *
 * PRoot 被 SIGKILL 后它跟踪的进程会脱离跟踪继续运行, 所以不能直接 [Process.destroyForcibly];
 * 也不能靠 PRoot 收到 SIGQUIT 后清理: 它从应用进程继承了屏蔽 SIGQUIT 的信号掩码, 信号一直挂起。
 * 做法是在 PRoot 还活着时 SIGKILL 它跟踪的所有进程, 最后一个退出后 PRoot 自己退出;
 * 命令可能正在派生新进程, 所以多扫几轮。
 * [graceMillis] 大于 0 时先给命令派生的进程发 SIGTERM, 留出这段时间让它们正常退出。
 *
 * 进程号由 shell 启动时写入 [pidFile] (`$$ $PPID`), 即 shell 自身与 PRoot 的进程号。
 */
internal fun terminateProot(process: Process, pidFile: File, graceMillis: Long = 0) {
    val pids = readPids(process, pidFile)
    if (pids == null) {
        process.destroyForcibly()
        process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
        return
    }
    val (shellPid, prootPid) = pids
    if (graceMillis > 0) {
        // 命令本身先收到 SIGTERM; 直接结束 shell 会让 --kill-on-exit 立刻 SIGKILL 其余进程
        (traceesOf(prootPid) - shellPid).ifEmpty { listOf(shellPid) }
            .forEach { signal(it, OsConstants.SIGTERM) }
        if (process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) return
    }
    repeat(KILL_ROUNDS) {
        traceesOf(prootPid).forEach { signal(it, OsConstants.SIGKILL) }
        if (process.waitFor(KILL_ROUND_WAIT_MS, TimeUnit.MILLISECONDS)) return
    }
    process.destroyForcibly()
    process.waitFor(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
}

// 刚启动时 shell 可能还没写入进程号, 稍等片刻
private fun readPids(process: Process, pidFile: File): Pair<Int, Int>? {
    val deadline = System.currentTimeMillis() + PID_WAIT_MS
    while (true) {
        val pids = runCatching { pidFile.readText() }.getOrNull()
            ?.trim()
            ?.split(' ')
            ?.mapNotNull { it.toIntOrNull() }
        if (pids != null && pids.size == 2) return pids[0] to pids[1]
        if (!process.isAlive || System.currentTimeMillis() > deadline) return null
        Thread.sleep(50)
    }
}
/** 被 [tracerPid] 跟踪的进程, 即 PRoot 内运行的所有进程 (包括脱离了进程树的守护进程) */
private fun traceesOf(tracerPid: Int): List<Int> =
    File("/proc").listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        val tracer = runCatching {
            File(dir, "status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()
            }
        }.getOrNull()
        pid.takeIf { tracer == tracerPid }
    }

private fun signal(pid: Int, signal: Int) {
    try {
        Os.kill(pid, signal)
    } catch (_: ErrnoException) {
        // 进程已经退出
    }
}
