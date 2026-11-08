package com.zhibai.app.rootfs

import android.content.Context
import android.os.Build
import java.io.File
import java.nio.file.Files

/**
 * Built-in Linux rootfs (PRoot) environment.
 *
 * Ported from RikkaHub (github.com/rikkahub/rikkahub, workspace module) so the agent
 * can run a real Linux userland — apt/apk, python3, tar, full coreutils — WITHOUT
 * the Termux app (which may be blocked from installing on this device).
 *
 * Layout: <externalFilesDir>/rootfs/{linux,tmp}
 *   linux/  the extracted rootfs (Ubuntu base by default)
 *   tmp/    PRoot scratch space
 * The agent workspace dir is bind-mounted into the rootfs at [/workspace].
 */
object Rootfs {
    /** Rootfs 内挂载工作区文件的目录 */
    const val MOUNT_WORKSPACE = "/workspace"

    /** Rootfs 内执行命令时的 HOME */
    const val HOME = "/root"

    /** 由宿主机透传的内核伪文件系统 */
    val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

    private const val BASE_DIR = "rootfs"
    private const val LINUX_DIR = "linux"
    private const val TMP_DIR = "tmp"

    fun baseDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, BASE_DIR)

    fun linuxDir(context: Context): File = File(baseDir(context), LINUX_DIR)

    fun tempDir(context: Context): File = File(baseDir(context), TMP_DIR)

    fun ensureDirs(context: Context) {
        linuxDir(context).mkdirs()
        tempDir(context).mkdirs()
    }

    /** Rootfs 是否可用, 以 /bin/sh 存在为准 (Alpine 的 /bin/sh 是宿主机上解析不到的软链, 软链本身存在即算) */
    fun isUsable(linuxDir: File): Boolean =
        linuxDir.isDirectory && hasEntry(linuxDir, "bin/sh")

    /** Rootfs 内使用的 shell: 优先 bash, 没有时 (如 Alpine minirootfs) 回退到 /bin/sh */
    fun shell(linuxDir: File): String =
        if (hasEntry(linuxDir, "bin/bash")) "/bin/bash" else "/bin/sh"

    fun hasEntry(dir: File, path: String): Boolean {
        val entry = File(dir, path)
        return entry.isFile || Files.isSymbolicLink(entry.toPath())
    }

    fun hasProotLibs(context: Context): Boolean {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        return File(dir, "libproot_exec.so").isFile && File(dir, "libproot_loader.so").isFile
    }

    fun prootLibDir(context: Context): File = File(context.applicationInfo.nativeLibraryDir)

    /**
     * 默认 rootfs 下载地址 (与 RikkaHub UI 同款 Ubuntu base; glibc 利于 pip wheel)。
     * 空串 = 本 ABI 没有打包 proot 库 (仅 arm64-v8a / x86_64)。
     */
    fun defaultUrl(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return ""
        return when {
            abi.startsWith("arm64") ->
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
            abi.startsWith("x86_64") ->
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-amd64.tar.gz"
            else -> ""
        }
    }
}

enum class RootfsInstallStage { DOWNLOADING, EXTRACTING, INSTALLED }

data class RootfsInstallProgress(
    val stage: RootfsInstallStage,
    val bytesRead: Long = 0L,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String = "",
)
