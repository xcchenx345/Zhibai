package com.mimo.app.agent

import android.content.Context
import android.os.Environment
import com.mimo.app.data.api.MiMoApiService
import com.google.gson.Gson
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLConnection
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class AgentTool(
    val name: String,
    val description: String,
    val riskLevel: Int,
    var enabled: Boolean = true,
    var requireConfirmation: Boolean = false,
    val category: String = "other",
    val execute: suspend (Map<String, Any>, Context) -> String
)

data class AgentTask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val prompt: String,
    val status: String = "pending",
    val result: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class ToolPermission(
    val toolName: String,
    val enabled: Boolean,
    val requireConfirmation: Boolean
)

data class PendingConfirmation(
    val toolName: String,
    val args: Map<String, Any>,
    val description: String,
    val continuation: CancellableContinuation<Boolean>
)

class AgentEngine(
    private val context: Context,
    private val apiService: MiMoApiService,
    private val workspaceOverride: String? = null
) {
    private val gson = Gson()
    val workspaceDir: File = resolveWorkspace()

    private fun tryMakeWritable(dir: File): Boolean {
        return try {
            if (!dir.exists()) dir.mkdirs()
            if (dir.isDirectory) {
                val probe = File(dir, ".write_probe")
                probe.writeText("ok")
                probe.delete()
                true
            } else false
        } catch (_: Exception) { false }
    }

    private fun resolveWorkspace(): File {
        val external = Environment.getExternalStorageDirectory()
        workspaceOverride?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
            val custom = if (raw.startsWith("/")) File(raw) else File(external, raw)
            if (tryMakeWritable(custom)) return custom
        }
        val default = File(external, "知白")
        if (tryMakeWritable(default)) return default
        return File(context.filesDir, "mimo_agent").apply { mkdirs() }
    }
    private val skillsDir = File(workspaceDir, "skills").apply { mkdirs() }
    private val outputDir = File(workspaceDir, "output").apply { mkdirs() }

    val tools = mutableListOf<AgentTool>()
    private val scheduledTasks = mutableListOf<AgentTask>()

    var pendingConfirmation: PendingConfirmation? = null
        private set

    private val disabledTools = ConcurrentHashMap.newKeySet<String>()
    private val confirmTools = ConcurrentHashMap.newKeySet<String>()

    private val sensitivePrefixes = listOf("/proc", "/sys", "/data/data", "/dev")

    var zhipuApiKey: String = ""

    /** Invoked when a tool call is waiting for user approval (wires to UI dialog). */
    var onConfirmationRequested: ((toolName: String, args: Map<String, Any>, description: String) -> Unit)? = null

    companion object {
        const val MODE_ASK_ALL = 0        // 所有调用需要审批
        const val MODE_DEFAULT = 1        // 默认权限：低风险放行，高风险询问
        const val MODE_AUTO = 2           // 全部放行不询问
        const val MODE_AI_APPROVE = 3     // AI 先审批，AI 拒绝则流放给用户

        /** Master switch for ALL phone-control features (AutoGLM / PhoneAgent). Set true to re-enable. */
        const val PHONE_AGENT_ENABLED = false
    }

    init {
        try { initAgentFiles() } catch (_: Exception) {}
        registerBuiltInTools()
    }

    fun loadZhipuKeyFromPrefs(context: Context) {
        if (zhipuApiKey.isBlank()) {
            try {
                val prefs = context.getSharedPreferences("mimo_settings", Context.MODE_PRIVATE)
                zhipuApiKey = prefs.getString("zhipu_api_key", "") ?: ""
            } catch (_: Exception) {}
        }
    }

    fun resolvePath(path: String): File {
        val file = File(path)
        return if (file.isAbsolute) file else File(workspaceDir, path)
    }

    fun validatePath(file: File): Boolean {
        val canonical = file.canonicalPath
        if (sensitivePrefixes.any { canonical.startsWith(it) }) return false
        return true
    }

    /** True if the app holds "All files access" (required to see non-app files on shared storage). */
    fun hasAllFilesAccess(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            try { android.os.Environment.isExternalStorageManager() } catch (_: Exception) { false }
        } else {
            true
        }
    }

    /** Warning suffix when listing shared storage without All files access (FUSE hides file entries). */
    private fun storageWarning(dir: File): String {
        if (hasAllFilesAccess()) return ""
        val external = try { Environment.getExternalStorageDirectory().canonicalPath } catch (_: Exception) { return "" }
        return if (dir.canonicalPath.startsWith(external)) {
            "\n\n⚠️ \"All files access\" is NOT granted — Android is hiding file entries (only folders visible). " +
                "Grant it in Settings > Apps > MiMo > Permissions > All files access, then retry."
        } else ""
    }

    private fun initAgentFiles() {
        val files = mapOf(
            "IDENTITY.md" to """# Identity
You are MiMo Agent, a local AI assistant running on the user's Android device.
You operate autonomously within the user's granted permissions.
Your workspace is at ${workspaceDir.absolutePath}.""".trimIndent(),

            "SOUL.md" to """# Soul
## Core Principles
- Be genuinely useful, not performatively helpful
- Have opinions, be direct, skip pleasantries
- Try to figure things out yourself before asking
- Respect user's time - concise answers, clean code

## Personality
- Efficient and straightforward
- Slightly opinionated but respectful
- Action-oriented over explanation-oriented""".trimIndent(),

            "AGENTS.md" to """# Agent Protocol
## When to Act Autonomously
- File operations within workspace ($workspaceDir)
- Reading device info
- Listing installed apps

## When to Ask Permission
- Modifying files outside workspace (risk level 3+)
- Deleting files (risk level 4+)
- ADB commands (risk level 5)

## Workflow
1. Understand the request
2. Plan the steps
3. Execute with appropriate caution
4. Report results concisely""".trimIndent(),

            "TOOLS.md" to """# Available Tools
- read_file: Read file content (risk: 1)
- write_file: Write to file (risk: 3)
- list_files: List directory contents (risk: 1)
- delete_file: Delete file/folder (risk: 4)
- move_file: Move/rename file (risk: 3)
- copy_file: Copy file (risk: 2)
- search_files: Search files by name pattern (risk: 1)
- get_file_info: Get file metadata (risk: 1)
- grep: Search inside file contents, regex (risk: 1)
- edit_file: Replace exact string in a file (risk: 3)
- current_time: Current date/time (risk: 1)
- download_file: Download URL into workspace (risk: 3)
- request_permission: Ask user to grant system permission (risk: 1)
- open_app: Launch Android app (risk: 2)
- list_apps: List installed apps (risk: 1)
- execute_phone_action: Execute phone action via AutoGLM (risk: 3)
- run_adb: Execute shell command (risk: 5)
- get_device_info: Device information (risk: 1)
- fetch_url: Fetch web page content (risk: 2)
- save_skill: Save reusable workflow (risk: 2)
- read_skill: Read saved skill (risk: 1)
- list_skills: List all skills (risk: 1)""".trimIndent(),

            "MEMORY.md" to """# Memory
## Learned Preferences
(none yet)

## Past Context
(none yet)""".trimIndent(),

            "USER.md" to """# User Profile
- Location: Wenzhou, Zhejiang, China
- Timezone: Asia/Shanghai (UTC+8)
- Prefers concise, efficient tools
- Technical enthusiast""".trimIndent(),

            "HEARTBEAT.md" to """# Heartbeat
Last active: ${java.time.LocalDateTime.now()}
Status: Ready
Workspace: ${workspaceDir.absolutePath}
Skills: ${skillsDir.listFiles()?.size ?: 0}"""
        )

        files.forEach { (name, content) ->
            val file = File(workspaceDir, name)
            if (!file.exists()) file.writeText(content)
        }
    }

    fun getAgentContext(): String {
        updateHeartbeat()
        return buildString {
            appendLine("=== Agent Workspace Context ===")
            appendLine("Workspace: ${workspaceDir.absolutePath}")
            appendLine("Skills: ${skillsDir.listFiles()?.map { it.nameWithoutExtension }?.joinToString() ?: "none"}")
            appendLine()
            listOf("IDENTITY.md", "SOUL.md", "TOOLS.md").forEach { name ->
                val file = File(workspaceDir, name)
                if (file.exists()) {
                    appendLine("### $name")
                    appendLine(file.readText())
                    appendLine()
                }
            }
        }
    }

    fun updateHeartbeat() {
        try {
            File(workspaceDir, "HEARTBEAT.md").writeText(
                """# Heartbeat
Last active: ${java.time.LocalDateTime.now()}
Status: Active
Workspace: ${workspaceDir.absolutePath}
Skills: ${skillsDir.listFiles()?.size ?: 0}
Tasks: ${scheduledTasks.size}""".trimIndent()
            )
        } catch (_: Exception) {}
    }

    private fun registerBuiltInTools() {
        // read_file
        tools.add(AgentTool(
            name = "read_file", description = "Read the content of a file", riskLevel = 1, category = "file"
        ) { args, _ ->
            val path = args["path"] as? String ?: return@AgentTool "Error: no path"
            val file = resolvePath(path)
            if (!validatePath(file)) return@AgentTool "Error: access denied"
            if (!file.exists()) return@AgentTool "Error: file not found"
            if (file.length() > 1024 * 1024) return@AgentTool "Error: file too large (>1MB)"
            file.readText()
        })

        // write_file
        tools.add(AgentTool(
            name = "write_file", description = "Write content to a file", riskLevel = 3, category = "file"
        ) { args, _ ->
            val path = args["path"] as? String ?: return@AgentTool "Error: no path"
            val content = args["content"] as? String ?: return@AgentTool "Error: no content"
            val file = resolvePath(path)
            if (!validatePath(file)) return@AgentTool "Error: access denied"
            file.parentFile?.mkdirs()
            file.writeText(content)
            "Written ${content.length} bytes to ${file.absolutePath}"
        })

        // list_files
        tools.add(AgentTool(
            name = "list_files", description = "List files in a directory", riskLevel = 1, category = "file"
        ) { args, _ ->
            val path = args["path"] as? String ?: workspaceDir.absolutePath
            val dir = resolvePath(path)
            if (!validatePath(dir)) return@AgentTool "Error: access denied"
            if (!dir.exists() || !dir.isDirectory) return@AgentTool "Error: not a directory"
            val list = dir.listFiles()?.joinToString("\n") { "${if (it.isDirectory) "[DIR]" else "[FILE]"} ${it.name}" } ?: "Empty directory"
            list + storageWarning(dir)
        })

        // delete_file
        tools.add(AgentTool(
            name = "delete_file", description = "Delete a file or directory", riskLevel = 4, category = "file"
        ) { args, _ ->
            val path = args["path"] as? String ?: return@AgentTool "Error: no path"
            val file = resolvePath(path)
            if (!validatePath(file)) return@AgentTool "Error: access denied"
            if (!file.exists()) return@AgentTool "Error: file not found"
            if (file.deleteRecursively()) "Deleted ${file.absolutePath}" else "Error: failed to delete"
        })

        // move_file
        tools.add(AgentTool(
            name = "move_file", description = "Move or rename a file/directory", riskLevel = 3, category = "file"
        ) { args, _ ->
            val source = args["source"] as? String ?: return@AgentTool "Error: no source"
            val dest = args["destination"] as? String ?: return@AgentTool "Error: no destination"
            val srcFile = resolvePath(source)
            val dstFile = resolvePath(dest)
            if (!validatePath(srcFile) || !validatePath(dstFile)) return@AgentTool "Error: access denied"
            if (!srcFile.exists()) return@AgentTool "Error: source not found"
            dstFile.parentFile?.mkdirs()
            if (srcFile.renameTo(dstFile)) {
                "Moved ${srcFile.absolutePath} -> ${dstFile.absolutePath}"
            } else {
                srcFile.copyTo(dstFile, overwrite = true)
                srcFile.deleteRecursively()
                "Moved (copy+delete) ${srcFile.absolutePath} -> ${dstFile.absolutePath}"
            }
        })

        // copy_file
        tools.add(AgentTool(
            name = "copy_file", description = "Copy a file or directory", riskLevel = 2, category = "file"
        ) { args, _ ->
            val source = args["source"] as? String ?: return@AgentTool "Error: no source"
            val dest = args["destination"] as? String ?: return@AgentTool "Error: no destination"
            val srcFile = resolvePath(source)
            val dstFile = resolvePath(dest)
            if (!validatePath(srcFile) || !validatePath(dstFile)) return@AgentTool "Error: access denied"
            if (!srcFile.exists()) return@AgentTool "Error: source not found"
            dstFile.parentFile?.mkdirs()
            if (srcFile.isDirectory) {
                srcFile.copyRecursively(dstFile, overwrite = true)
            } else {
                srcFile.copyTo(dstFile, overwrite = true)
            }
            "Copied ${srcFile.absolutePath} -> ${dstFile.absolutePath}"
        })

        // search_files
        tools.add(AgentTool(
            name = "search_files", description = "Search files by name pattern", riskLevel = 1, category = "file"
        ) { args, _ ->
            val pattern = args["pattern"] as? String ?: return@AgentTool "Error: no pattern"
            val path = args["path"] as? String ?: workspaceDir.absolutePath
            val dir = resolvePath(path)
            if (!validatePath(dir)) return@AgentTool "Error: access denied"
            if (!dir.exists() || !dir.isDirectory) return@AgentTool "Error: not a directory"
            val regex = try { Regex(pattern, RegexOption.IGNORE_CASE) } catch (_: Exception) { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
            val results = mutableListOf<String>()
            dir.walkTopDown().maxDepth(10).forEach { f ->
                if (results.size >= 50) return@forEach
                if (regex.containsMatchIn(f.name)) {
                    results.add("${if (f.isDirectory) "[DIR]" else "[FILE]"} ${f.absolutePath}")
                }
            }
            if (results.isEmpty()) "No files found matching '$pattern'" else results.joinToString("\n")
            .plus(storageWarning(dir))
        })

        // get_file_info
        tools.add(AgentTool(
            name = "get_file_info", description = "Get file metadata (size, type, modified date)", riskLevel = 1, category = "file"
        ) { args, _ ->
            val path = args["path"] as? String ?: return@AgentTool "Error: no path"
            val file = resolvePath(path)
            if (!validatePath(file)) return@AgentTool "Error: access denied"
            if (!file.exists()) return@AgentTool "Error: file not found"
            buildString {
                appendLine("Path: ${file.absolutePath}")
                appendLine("Type: ${if (file.isDirectory) "Directory" else "File"}")
                appendLine("Size: ${file.length()} bytes")
                appendLine("Modified: ${java.util.Date(file.lastModified())}")
                if (file.isFile) {
                    val mime = URLConnection.guessContentTypeFromName(file.name) ?: "unknown"
                    appendLine("MIME: $mime")
                }
                appendLine("Readable: ${file.canRead()}")
                appendLine("Writable: ${file.canWrite()}")
            }
        })

        // grep — search file CONTENTS (like a real coding agent)
        tools.add(AgentTool(
            name = "grep",
            description = "Search inside file contents (like ripgrep). Args: pattern (regex or literal), path (dir, default workspace), include (glob like *.kt, optional)",
            riskLevel = 1, category = "file"
        ) { args, _ ->
            val pattern = args["pattern"] as? String ?: return@AgentTool "Error: no pattern"
            val path = args["path"] as? String ?: workspaceDir.absolutePath
            val include = args["include"] as? String
            val dir = resolvePath(path)
            if (!validatePath(dir)) return@AgentTool "Error: access denied"
            if (!dir.exists()) return@AgentTool "Error: path not found"
            val regex = try { Regex(pattern) } catch (_: Exception) { Regex(Regex.escape(pattern)) }
            val includeGlob = include?.let { glob -> Regex(Regex.escape(glob).replace("\\*", ".*")) }
            val results = mutableListOf<String>()
            val filesToScan = if (dir.isFile) listOf(dir) else dir.walkTopDown().maxDepth(10)
                .filter { it.isFile }
                .filter { f -> includeGlob == null || includeGlob.matches(f.name) }
                .toList()
            for (f in filesToScan) {
                if (results.size >= 50) break
                if (f.length() > 2_000_000) continue // skip >2MB files
                try {
                    f.forEachLine { line ->
                        if (results.size >= 50) return@forEachLine
                        if (regex.containsMatchIn(line)) {
                            val rel = f.relativeToOrSelf(dir).path
                            results.add("$rel:${line.trim().take(200)}")
                        }
                    }
                } catch (_: Exception) {}
            }
            if (results.isEmpty()) "No matches for '$pattern'" else results.joinToString("\n")
        })

        // edit_file — precise string replacement (no full rewrite needed)
        tools.add(AgentTool(
            name = "edit_file",
            description = "Replace an exact string in a file (single occurrence). Args: path, old_text (must match exactly), new_text",
            riskLevel = 3, category = "file"
        ) { args, _ ->
            val path = args["path"] as? String ?: return@AgentTool "Error: no path"
            val oldText = args["old_text"] as? String ?: return@AgentTool "Error: no old_text"
            val newText = args["new_text"] as? String ?: return@AgentTool "Error: no new_text"
            val file = resolvePath(path)
            if (!validatePath(file)) return@AgentTool "Error: access denied"
            if (!file.exists() || !file.isFile) return@AgentTool "Error: file not found"
            val content = try { file.readText() } catch (e: Exception) { return@AgentTool "Error: ${e.message}" }
            val count = content.split(oldText).size - 1
            when {
                count == 0 -> return@AgentTool "Error: old_text not found in file"
                count > 1 -> return@AgentTool "Error: old_text matches $count times — make it more specific (add surrounding context)"
                else -> {
                    file.writeText(content.replaceFirst(oldText, newText))
                    "Replaced 1 occurrence in ${file.absolutePath}"
                }
            }
        })

        // current_time
        tools.add(AgentTool(
            name = "current_time", description = "Get current date and time (device timezone)", riskLevel = 1, category = "system"
        ) { _, _ ->
            val now = java.time.LocalDateTime.now()
            val zone = java.time.ZoneId.systemDefault()
            "$now (${zone.id}) · ${java.time.LocalDate.now().dayOfWeek}"
        })

        // download_file
        tools.add(AgentTool(
            name = "download_file",
            description = "Download a file from a URL into the workspace. Args: url, filename (optional)",
            riskLevel = 3, category = "network"
        ) { args, _ ->
            val url = args["url"] as? String ?: return@AgentTool "Error: no url"
            val filename = (args["filename"] as? String)?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?: java.net.URL(url).path.substringAfterLast('/').ifBlank { "download.bin" }
            if (!filename.contains('.')) return@AgentTool "Error: filename must have an extension"
            try {
                val conn = java.net.URL(url).openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                }
                val total = conn.contentLengthLong
                if (total > 100L * 1024 * 1024) return@AgentTool "Error: file too large (${total / 1024 / 1024}MB > 100MB)"
                conn.getInputStream().use { input ->
                    File(workspaceDir, filename).outputStream().use { out ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                }
                val saved = File(workspaceDir, filename)
                "Downloaded ${saved.length()} bytes to ${saved.absolutePath}"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // open_app
        tools.add(AgentTool(
            name = "open_app", description = "Open an Android app by package name", riskLevel = 2, category = "app"
        ) { args, ctx ->
            val packageName = args["package"] as? String ?: return@AgentTool "Error: no package"
            try {
                val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    "Opened $packageName"
                } else {
                    "Error: app not found"
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // list_apps
        tools.add(AgentTool(
            name = "list_apps", description = "List installed apps", riskLevel = 1, category = "app"
        ) { _, ctx ->
            ctx.packageManager.getInstalledApplications(0)
                .filter { ctx.packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .map { "${it.loadLabel(ctx.packageManager)} (${it.packageName})" }
                .sorted()
                .joinToString("\n")
        })

        // execute_phone_action - AutoGLM phone control (hidden unless master switch is on)
        if (PHONE_AGENT_ENABLED) {
            tools.add(AgentTool(
                name = "execute_phone_action", description = "Execute a phone action using AutoGLM (tap, swipe, type, open app, etc.)", riskLevel = 3, category = "phone"
            ) { args, ctx ->
                val instruction = args["instruction"] as? String ?: return@AgentTool "Error: no instruction"
                loadZhipuKeyFromPrefs(ctx)
                val apiKey = zhipuApiKey.ifBlank { return@AgentTool "Error: no zhipu API key configured. Please set it in Phone Agent settings." }
                if (!MiMoAccessibilityService.isRunning) {
                    return@AgentTool "NEED_PERMISSION:accessibility|Accessibility service is not enabled — phone control requires it"
                }
                try {
                    val autoGLM = AutoGLMService()
                    autoGLM.updateApiKey(apiKey)
                    val adb = AdbExecutor()
                    if (!ScreenCaptureService.hasPermission()) {
                        return@AgentTool "NEED_PERMISSION:projection|Screen capture (MediaProjection) permission is required to see the screen"
                    }
                    val screenshot = adb.captureScreen(ctx)
                    if (screenshot != null) {
                        val action = kotlinx.coroutines.runBlocking {
                            autoGLM.execute(screenshot, instruction)
                        }
                        if (action != null) {
                            val result = when (action.type) {
                                "tap" -> { adb.tap(action.x ?: 0, action.y ?: 0); "Tapped at (${action.x}, ${action.y})" }
                                "swipe" -> { adb.swipe(action.x1 ?: 0, action.y1 ?: 0, action.x2 ?: 0, action.y2 ?: 0); "Swiped" }
                                "type" -> { adb.inputText(action.text ?: ""); "Typed: ${action.text}" }
                                "back" -> { adb.pressBack(); "Pressed back" }
                                "home" -> { adb.pressHome(); "Pressed home" }
                                "launch" -> { adb.launchApp(action.package_name ?: ""); "Launched ${action.package_name}" }
                                "done" -> "Action completed"
                                "take_over" -> "Waiting for user to complete action"
                                else -> "Unknown action: ${action.type}"
                            }
                            "Action executed: $result"
                        } else {
                            "Error: failed to get action from AI"
                        }
                    } else {
                        "Error: failed to capture screen"
                    }
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            })
        }

        // run_adb
        tools.add(AgentTool(
            name = "run_adb", description = "Execute an ADB shell command", riskLevel = 5, category = "system"
        ) { args, _ ->
            val command = args["command"] as? String ?: return@AgentTool "Error: no command"
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = process.inputStream.bufferedReader().readText()
                val error = process.errorStream.bufferedReader().readText()
                process.waitFor()
                if (error.isNotBlank()) "STDERR: $error\nSTDOUT: $output"
                else output.ifBlank { "Command executed (no output)" }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // get_device_info
        tools.add(AgentTool(
            name = "get_device_info", description = "Get device information", riskLevel = 1, category = "system"
        ) { _, ctx ->
            buildString {
                append("Model: ${android.os.Build.MODEL}\n")
                append("Brand: ${android.os.Build.BRAND}\n")
                append("Android: ${android.os.Build.VERSION.RELEASE}\n")
                append("SDK: ${android.os.Build.VERSION.SDK_INT}\n")
                append("Battery: ${getBatteryLevel(ctx)}%\n")
                append("Free space: ${Environment.getExternalStorageDirectory().freeSpace / 1024 / 1024}MB")
            }
        })

        // fetch_url
        tools.add(AgentTool(
            name = "fetch_url", description = "Fetch and read a web page content (returns text)", riskLevel = 2, category = "network"
        ) { args, _ ->
            val url = args["url"] as? String ?: return@AgentTool "Error: no url"
            val maxLen = (args["max_length"] as? Number)?.toInt() ?: 5000
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android) MiMoAgent/1.0")
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: return@AgentTool "Error: empty response"
                    val text = html
                        .replace(Regex("<script[\\s\\S]*?</script>"), "")
                        .replace(Regex("<style[\\s\\S]*?</style>"), "")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (text.length > maxLen) text.take(maxLen) + "\n...(truncated)" else text
                } else {
                    "Error: HTTP ${response.code}"
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // save_skill
        tools.add(AgentTool(
            name = "save_skill", description = "Save a reusable skill/workflow", riskLevel = 2, category = "skill"
        ) { args, _ ->
            val name = args["name"] as? String ?: return@AgentTool "Error: no name"
            val content = args["content"] as? String ?: return@AgentTool "Error: no content"
            val file = File(skillsDir, "$name.md")
            file.writeText(content)
            "Skill '$name' saved"
        })

        // read_skill
        tools.add(AgentTool(
            name = "read_skill", description = "Read a saved skill", riskLevel = 1, category = "skill"
        ) { args, _ ->
            val name = args["name"] as? String ?: return@AgentTool "Error: no name"
            val file = File(skillsDir, "$name.md")
            if (file.exists()) file.readText() else "Error: skill not found"
        })

        // list_skills
        tools.add(AgentTool(
            name = "list_skills", description = "List all saved skills", riskLevel = 1, category = "skill"
        ) { _, _ ->
            skillsDir.listFiles()?.filter { it.extension == "md" }
                ?.joinToString("\n") { it.nameWithoutExtension } ?: "No skills saved"
        })

        // request_permission - ask the user to grant a system permission & jump to Settings
        tools.add(AgentTool(
            name = "request_permission",
            description = "Ask the user to grant a system permission. Use when a needed permission is missing. Args: permission (accessibility|overlay|storage|projection|notification|mic), reason (why it is needed)",
            riskLevel = 1, category = "system"
        ) { args, _ ->
            val permission = (args["permission"] as? String ?: "storage").lowercase()
            val reason = args["reason"] as? String ?: "Required to continue the task"
            "NEED_PERMISSION:$permission|$reason"
        })
    }

    suspend fun executeTool(toolName: String, args: Map<String, Any>, mode: Int): String {
        val tool = tools.find { it.name == toolName }
            ?: return "Error: unknown tool '$toolName'"
        if (!tool.enabled) {
            return "BLOCKED: Tool '$toolName' is disabled"
        }

        val alwaysConfirm = tool.requireConfirmation || confirmTools.contains(toolName)

        val needUserConfirm = when (mode) {
            MODE_ASK_ALL -> true
            MODE_AUTO -> false
            MODE_AI_APPROVE -> {
                // AI reviews first; on deny (or AI failure) fall back to user approval
                val aiApproved = aiApproveTool(tool, args)
                if (aiApproved == true) false else true
            }
            else -> { // MODE_DEFAULT
                // per-tool confirmation flags, or high-risk (4-5) tools
                alwaysConfirm || tool.riskLevel > 3
            }
        }

        if (needUserConfirm) {
            val confirmed = requestConfirmation(toolName, args, tool.description)
            if (!confirmed) return "DENIED: User denied tool '$toolName'"
        }
        return withContext(Dispatchers.IO) {
            try {
                tool.execute(args, context)
            } catch (e: Exception) {
                "Error executing $toolName: ${e.message}"
            }
        }
    }

    /**
     * AI approval: asks the model whether this tool call is safe to run.
     * true = approved, false = denied, null = AI unavailable (caller falls back to user).
     */
    private suspend fun aiApproveTool(tool: AgentTool, args: Map<String, Any>): Boolean? {
        return try {
            val argsJson = try { gson.toJson(args) } catch (_: Exception) { "{}" }
            val prompt = """You are a permission reviewer for an AI phone assistant.
Decide whether this tool call should be auto-approved without asking the user.

Tool: ${tool.name} (risk ${tool.riskLevel}/5)
Description: ${tool.description}
Arguments: $argsJson

Rules:
- Approve read-only or low-impact actions (reading files, listing, device info, fetching URLs).
- Deny destructive or sensitive actions (delete, overwrite outside workspace, shell commands, payments, privacy data).
- Deny if arguments look harmful or ambiguous.

Reply with EXACTLY one word: APPROVE or DENY."""
            val request = com.mimo.app.data.model.ChatRequest(
                model = "mimo-v2.6-flash",
                messages = listOf(com.mimo.app.data.model.Message(role = "user", content = prompt)),
                maxCompletionTokens = 8,
                temperature = 0f,
                stream = false,
                thinking = com.mimo.app.data.model.ThinkingConfig(type = "disabled")
            )
            val response = apiService.chatCompletionStructured(request)
            val text = response.choices.firstOrNull()?.message?.content?.toString()?.trim()?.uppercase() ?: return null
            when {
                text.contains("APPROVE") -> true
                text.contains("DENY") -> false
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun requestConfirmation(toolName: String, args: Map<String, Any>, description: String): Boolean {
        return suspendCancellableCoroutine { continuation ->
            pendingConfirmation = PendingConfirmation(toolName, args, description, continuation)
            onConfirmationRequested?.invoke(toolName, args, description)
            continuation.invokeOnCancellation {
                pendingConfirmation = null
            }
        }
    }

    fun approvePendingConfirmation() {
        val pending = pendingConfirmation ?: return
        pendingConfirmation = null
        pending.continuation.resume(true)
    }

    fun denyPendingConfirmation() {
        val pending = pendingConfirmation ?: return
        pendingConfirmation = null
        pending.continuation.resume(false)
    }

    fun getToolPermissions(): List<ToolPermission> {
        return tools.map { tool ->
            ToolPermission(
                toolName = tool.name,
                enabled = !disabledTools.contains(tool.name),
                requireConfirmation = confirmTools.contains(tool.name) || tool.requireConfirmation
            )
        }
    }

    fun updateToolPermission(toolName: String, enabled: Boolean?, requireConfirmation: Boolean?) {
        tools.find { it.name == toolName }?.let { tool ->
            if (enabled != null) {
                tool.enabled = enabled
                if (!enabled) disabledTools.add(toolName) else disabledTools.remove(toolName)
            }
            if (requireConfirmation != null) {
                tool.requireConfirmation = requireConfirmation
                if (requireConfirmation) confirmTools.add(toolName) else confirmTools.remove(toolName)
            }
        }
    }

    fun getToolsDescription(): String {
        return tools.filter { it.enabled }.joinToString("\n") { "- ${it.name} (risk:${it.riskLevel}): ${it.description}" }
    }

    fun getToolsByCategory(): Map<String, List<AgentTool>> {
        return tools.groupBy { it.category }
    }

    fun saveWorkspaceFile(name: String, content: String): File {
        val file = File(outputDir, name)
        file.writeText(content)
        return file
    }

    fun addScheduledTask(prompt: String): AgentTask {
        val task = AgentTask(prompt = prompt)
        scheduledTasks.add(task)
        return task
    }

    private fun getBatteryLevel(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}
