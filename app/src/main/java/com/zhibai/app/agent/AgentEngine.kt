package com.zhibai.app.agent

import android.content.Context
import android.os.Environment
import com.zhibai.app.data.api.MiMoApiService
import com.zhibai.app.i18n.LangManager
import com.zhibai.app.rootfs.ProotShell
import com.zhibai.app.rootfs.Rootfs
import com.zhibai.app.rootfs.RootfsInstaller
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

/**
 * Pre-rootfs default of the generated TOOLS.md. Existing workspaces still carry
 * this byte-identical text (the file is only written when missing); matching it
 * lets initAgentFiles regenerate the file so run_shell/run_rootfs show up, while
 * any user or agent edits to TOOLS.md are left untouched.
 */
private val LEGACY_TOOLS_MD = """# Available Tools
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
- view_media: View/analyze an image, audio, or video file and get a description/transcript (risk: 1)
- request_permission: Ask user to grant system permission (risk: 1)
- open_app: Launch Android app (risk: 2)
- list_apps: List installed apps (risk: 1)
- run_adb: Execute shell command (risk: 5)
- get_device_info: Device information (risk: 1)
- fetch_url: Fetch web page content (risk: 2)
- save_skill: Save reusable workflow (risk: 2)
- read_skill: Read saved skill (risk: 1)
- list_skills: List all skills (risk: 1)""".trimIndent()

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

/** One question inside an AI-created questionnaire card. */
data class QuestionnaireItem(
    val id: String = "",
    val type: String = "text",          // single | multi | text | rating
    val question: String = "",
    val options: List<String> = emptyList(),
    val required: Boolean = true,
    val maxRating: Int = 5
)

data class PendingQuestionnaire(
    val questions: List<QuestionnaireItem>,
    val continuation: CancellableContinuation<String>
)

class AgentEngine(
    private val context: Context,
    private val apiService: MiMoApiService,
    private val workspaceOverride: String? = null,
    private val taskManager: AgentTaskManager = AgentTaskManager()
) {
    private val gson = Gson()
    val workspaceDir: File = resolveWorkspace()

    init {
        // Gallery privacy: keep workspace files out of MediaScanner's index.
        markWorkspacePrivate(workspaceDir)
    }

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
        val docs = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        } catch (_: Exception) { null }
        val preferred = docs?.let { File(it, "ZhiBai") }
        if (preferred != null && tryMakeWritable(preferred)) {
            // One-time move of the legacy workspaces into Documents/ZhiBai.
            migrateWorkspace(File(external, "知白"), preferred)
            migrateWorkspace(File(context.filesDir, "mimo_agent"), preferred)
            return preferred
        }
        val default = File(external, "知白")
        if (tryMakeWritable(default)) return default
        return File(context.filesDir, "mimo_agent").apply { mkdirs() }
    }

    /** Move a legacy workspace's entries into [dest]; entries already present are kept. */
    private fun migrateWorkspace(from: File, to: File) {
        try {
            if (!from.isDirectory) return
            val entries = from.listFiles() ?: return
            for (e in entries) {
                val target = File(to, e.name)
                if (target.exists()) continue
                if (!e.renameTo(target)) {
                    e.copyTo(target, overwrite = false)
                    e.deleteRecursively()
                }
            }
            from.listFiles()?.takeIf { it.isEmpty() }?.let { from.delete() }
        } catch (_: Exception) {}
    }

    /**
     * Workspace must stay visible in the file manager but stay OUT of the gallery:
     * MediaScanner indexes every image under shared storage, so the agent's working
     * files (and their previews) ended up in the photo roll. A .nomedia in the root
     * keeps all subdirs unindexed while the folder itself remains browsable; entries
     * already indexed before the .nomedia existed are purged from MediaStore here.
     */
    private fun markWorkspacePrivate(dir: File) {
        try {
            File(dir, ".nomedia").apply { if (!exists()) createNewFile() }
        } catch (_: Exception) {}
        // NEVER purge MediaStore rows here: ContentResolver.delete() removes the
        // underlying FILE from disk, so the old "gallery cleanup" physically
        // deleted every image/video in the workspace on each engine start.
        // .nomedia alone keeps future scans away.
    }

    private val skillsDir = File(workspaceDir, "skills").apply { mkdirs() }
    private val outputDir = File(workspaceDir, "output").apply { mkdirs() }

    val tools = mutableListOf<AgentTool>()
    private val scheduledTasks = mutableListOf<AgentTask>()

    /** Live tool output sink — invoked per line while a tool runs (UI shows it in real time). */
    @Volatile
    var liveOutputSink: ((String) -> Unit)? = null

    /** File-mutation sink — write/edit/delete/move/copy report +N/-M line deltas here. */
    @Volatile
    var fileChangeSink: ((com.zhibai.app.data.model.FileChange) -> Unit)? = null

    private fun emitOutput(line: String) {
        try { liveOutputSink?.invoke(line) } catch (_: Exception) {}
    }

    private fun emitFileChange(path: String, added: Int, removed: Int, oldContent: String? = null) {
        try {
            fileChangeSink?.invoke(com.zhibai.app.data.model.FileChange(
                path = path, added = added, removed = removed, oldContent = oldContent
            ))
        } catch (_: Exception) {}
    }

    /** Line-level add/remove count between two texts (prefix/suffix trim, no LCS). */
    private fun lineDelta(old: String, new: String): Pair<Int, Int> {
        val o = old.split('\n')
        val n = new.split('\n')
        var prefix = 0
        while (prefix < o.size && prefix < n.size && o[prefix] == n[prefix]) prefix++
        var suffix = 0
        while (suffix < o.size - prefix && suffix < n.size - prefix && o[o.size - 1 - suffix] == n[n.size - 1 - suffix]) suffix++
        return (n.size - prefix - suffix) to (o.size - prefix - suffix)
    }

    var pendingConfirmation: PendingConfirmation? = null
        private set

    private val disabledTools = ConcurrentHashMap.newKeySet<String>()
    private val confirmTools = ConcurrentHashMap.newKeySet<String>()

    private val sensitivePrefixes = listOf("/proc", "/sys", "/data/data", "/dev")

    var zhipuApiKey: String = ""

    /** Invoked when a tool call is waiting for user approval (wires to UI dialog). */
    var onConfirmationRequested: ((toolName: String, args: Map<String, Any>, description: String) -> Unit)? = null

    var pendingQuestionnaire: PendingQuestionnaire? = null
        private set

    /** Invoked when create_questionnaire suspends waiting for the user to fill the card. */
    var onQuestionnaireRequested: ((questions: List<QuestionnaireItem>) -> Unit)? = null

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

    fun loadPhoneAgentBaseUrl(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences("mimo_settings", Context.MODE_PRIVATE)
            prefs.getString("zhipu_base_url", "https://open.bigmodel.cn/api/paas/v4")
                ?: "https://open.bigmodel.cn/api/paas/v4"
        } catch (_: Exception) {
            "https://open.bigmodel.cn/api/paas/v4"
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

    /** Map a host path to the path seen inside the rootfs (bind mounts); null = unreachable. */
    private fun toGuestPath(file: File): String? {
        val abs = file.canonicalPath
        val ws = workspaceDir.canonicalPath
        return when {
            abs == ws -> Rootfs.MOUNT_WORKSPACE
            abs.startsWith("$ws/") -> Rootfs.MOUNT_WORKSPACE + abs.substring(ws.length)
            abs == "/sdcard" || abs.startsWith("/sdcard/") -> abs
            abs == "/storage" || abs.startsWith("/storage/") -> abs
            else -> null
        }
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
- view_media: View/analyze an image, audio, or video file and get a description/transcript (risk: 1)
- request_permission: Ask user to grant system permission (risk: 1)
- open_app: Launch Android app (risk: 2)
- list_apps: List installed apps (risk: 1)
- run_shell: Execute a shell command — FIRST choice for any shell work; runs in the built-in Linux rootfs (apt/python3 available after install_rootfs) (risk: 3)
- install_rootfs: One-time install of the built-in Linux rootfs (enables apt/python3 in run_shell) (risk: 3)
- run_adb: Shell command via wireless ADB (real shell privileges). Use ONLY for ADB-specific access (adb debugging, adb-only operations) — prefer run_shell for ordinary commands (risk: 5)
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
            val staleLegacy = name == "TOOLS.md" && file.exists() &&
                file.readText().trim() == LEGACY_TOOLS_MD
            if (!file.exists() || staleLegacy) file.writeText(content)
        }
    }

    /** Force-write workspace files (used by onboarding personalization). */
    fun writeWorkspaceFiles(files: Map<String, String>) {
        files.forEach { (name, content) ->
            if (name.contains('/') || name.contains('\\') || name.contains("..")) return@forEach
            File(workspaceDir, name).writeText(content)
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

    data class SkillEntry(val name: String, val path: String, val isDir: Boolean)

    /** List skills: flat .md files + subdirectories containing SKILL.md. */
    fun listSkillsDetailed(): List<SkillEntry> {
        val entries = mutableListOf<SkillEntry>()
        skillsDir.listFiles()?.forEach { f ->
            when {
                f.isDirectory && File(f, "SKILL.md").exists() ->
                    entries.add(SkillEntry(f.name, File(f, "SKILL.md").absolutePath, true))
                f.isFile && f.extension.equals("md", ignoreCase = true) ->
                    entries.add(SkillEntry(f.nameWithoutExtension, f.absolutePath, false))
            }
        }
        return entries.sortedBy { it.name.lowercase() }
    }

    /** Read a skill's content by its display name. */
    fun readSkillContent(name: String): String? {
        // Directory skill: skills/<name>/SKILL.md
        val dirSkill = File(File(skillsDir, name), "SKILL.md")
        if (dirSkill.exists()) return dirSkill.readText()
        // Flat skill: skills/<name>.md
        val flat = File(skillsDir, "$name.md")
        if (flat.exists()) return flat.readText()
        return null
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
            val existed = file.exists()
            val old = if (existed) try { file.readText() } catch (_: Exception) { "" } else ""
            file.writeText(content)
            val (added, removed) = lineDelta(old, content)
            // null oldContent = brand-new file → undo deletes it
            emitFileChange(file.absolutePath, added, removed, oldContent = if (existed) old else null)
            emitOutput("Written ${content.length} bytes to ${file.absolutePath}")
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
            val oldText = if (file.isFile) try { file.readText() } catch (_: Exception) { "" } else ""
            val lineCount = if (oldText.isNotEmpty()) oldText.count { it == '\n' } + 1 else 0
            val ok = file.deleteRecursively()
            if (ok) {
                emitFileChange(file.absolutePath, 0, lineCount, oldContent = oldText)
                emitOutput("Deleted ${file.absolutePath}")
                "Deleted ${file.absolutePath}"
            } else "Error: failed to delete"
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
                    val newContent = content.replaceFirst(oldText, newText)
                    file.writeText(newContent)
                    val (added, removed) = lineDelta(oldText, newText)
                    emitFileChange(file.absolutePath, added, removed, oldContent = content)
                    emitOutput("Replaced 1 occurrence in ${file.absolutePath}")
                    "Replaced 1 occurrence in ${file.absolutePath}"
                }
            }
        })

        // extract_archive — pure-JVM unzip (zip/jar/apk). No root, no Termux: works
        // anywhere the app can write; the counterpart of Rikkahub's bundled-terminal pkg.
        tools.add(AgentTool(
            name = "extract_archive",
            description = "Extract a zip/jar/apk archive. Args: path, dest (optional target dir, default: archive name without extension beside it)",
            riskLevel = 3, category = "file"
        ) { args, _ ->
            val path = args["path"] as? String ?: return@AgentTool "Error: no path"
            val file = resolvePath(path)
            if (!validatePath(file)) return@AgentTool "Error: access denied"
            if (!file.exists() || !file.isFile) return@AgentTool "Error: file not found: $path"
            val dest = (args["dest"] as? String)?.takeIf { it.isNotBlank() }?.let { resolvePath(it) }
                ?: java.io.File(file.parentFile, file.nameWithoutExtension)
            if (!validatePath(dest)) return@AgentTool "Error: access denied"
            try {
                val destRoot = dest.canonicalFile.toPath()
                if (!dest.exists() && !dest.mkdirs()) return@AgentTool "Error: cannot create ${dest.absolutePath}"
                var count = 0
                java.util.zip.ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val out = java.io.File(dest, e.name).canonicalFile.toPath()
                        if (!out.startsWith(destRoot)) return@AgentTool "Error: zip-slip blocked: ${e.name}"
                        if (e.isDirectory) { java.io.File(dest, e.name).mkdirs(); continue }
                        java.io.File(dest, e.name).parentFile?.mkdirs()
                        zip.getInputStream(e).use { input ->
                            java.io.File(dest, e.name).outputStream().use { output -> input.copyTo(output) }
                        }
                        count++
                        if (count > 20000) return@AgentTool "Error: too many entries (>20000)"
                    }
                }
                "Extracted $count files to ${dest.absolutePath}"
            } catch (e: Exception) { "Error: ${e.message}" }
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

        // view_media — autonomously look at an image / listen to audio / watch a video
        tools.add(AgentTool(
            name = "view_media",
            description = "View/analyze a local media file (image, audio, or video) and get a description or transcription. Args: path (required), question (optional — what to look for / ask about the media)",
            riskLevel = 1, category = "media"
        ) { args, _ ->
            val path = args["path"] as? String ?: return@AgentTool "Error: no path"
            val question = args["question"] as? String
            val file = resolvePath(path)
            if (!validatePath(file)) return@AgentTool "Error: access denied"
            if (!file.exists() || !file.isFile) return@AgentTool "Error: file not found"

            val mime = URLConnection.guessContentTypeFromName(file.name.lowercase()) ?: ""
            val isImage = mime.startsWith("image/")
            val isAudio = mime.startsWith("audio/")
            val isVideo = mime.startsWith("video/")
            if (!isImage && !isAudio && !isVideo) {
                return@AgentTool "Error: unsupported media type '$mime' (only image/audio/video)"
            }

            // size caps: image 15MB, audio 25MB, video 40MB (base64 blows up ~1.33x)
            val capBytes = when {
                isImage -> 15L * 1024 * 1024
                isAudio -> 25L * 1024 * 1024
                else -> 40L * 1024 * 1024
            }
            if (file.length() > capBytes) {
                return@AgentTool "Error: file too large (${file.length() / 1024 / 1024}MB > ${capBytes / 1024 / 1024}MB)"
            }

            val b64 = try {
                android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                return@AgentTool "Error reading file: ${e.message}"
            }

            val prompt = question?.takeIf { it.isNotBlank() } ?: when {
                isImage -> "Describe this image in detail. If there is text, transcribe it."
                isAudio -> "Transcribe and summarize this audio."
                else -> "Describe what happens in this video."
            }

            val userMsg = apiService.buildUserMessage(
                text = prompt,
                imageBase64 = if (isImage) b64 else null,
                audioBase64 = if (isAudio) b64 else null,
                audioMimeType = if (isAudio) mime else null,
                videoBase64 = if (isVideo) b64 else null,
                videoMimeType = if (isVideo) mime else null,
                mimeType = mime.ifBlank { "image/jpeg" }
            )
            val req = com.zhibai.app.data.model.ChatRequest(
                model = "mimo-v2.6-flash",
                messages = listOf(
                    com.zhibai.app.data.model.Message(role = "system", content = "You are a precise media analyst. Describe/transcribe only what is actually present. Be concise."),
                    userMsg
                ),
                maxCompletionTokens = 2048,
                temperature = 0.3f,
                stream = false,
                thinking = com.zhibai.app.data.model.ThinkingConfig(type = "disabled")
            )
            try {
                val desc = apiService.chatCompleteText(req)
                if (desc.isNullOrBlank()) "Error: model returned no description for ${file.name}"
                else "[${file.name} · $mime]\n$desc"
            } catch (e: Exception) {
                "Error analyzing media: ${e.message}"
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

        // === Device tools ===

        // get_location
        tools.add(AgentTool(
            name = "get_location", description = "Get current GPS location (latitude, longitude)", riskLevel = 2, category = "device"
        ) { _, ctx ->
            try {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val provider = when {
                    lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) -> android.location.LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) -> android.location.LocationManager.NETWORK_PROVIDER
                    else -> return@AgentTool "Error: no location provider enabled"
                }
                @Suppress("MissingPermission")
                val loc = lm.getLastKnownLocation(provider)
                    ?: return@AgentTool "Error: no location available. Grant location permission and try again."
                "Lat: ${loc.latitude}, Lng: ${loc.longitude}, Accuracy: ${loc.accuracy}m, Provider: $provider"
            } catch (e: SecurityException) {
                "NEED_PERMISSION:location|Location permission is required to read the current position"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // open_file — hand a workspace/absolute file to the system viewer (FileProvider)
        tools.add(AgentTool(
            name = "open_file",
            description = "Open a file with the system viewer (images, documents, audio, video). Args: path",
            riskLevel = 2, category = "app"
        ) { args, ctx ->
            val path = args["path"] as? String ?: return@AgentTool "Error: no path"
            val file = resolvePath(path)
            if (!validatePath(file)) return@AgentTool "Error: access denied"
            if (!file.exists()) return@AgentTool "Error: file not found: ${file.absolutePath}"
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.packageName + ".fileprovider", file
                )
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                "Opened ${file.absolutePath} with the system viewer"
            } catch (e: Exception) {
                "Error: cannot open file: ${e.message}"
            }
        })

        // open_url — open a web page in the browser
        tools.add(AgentTool(
            name = "open_url",
            description = "Open a web page in the default browser. Args: url",
            riskLevel = 2, category = "network"
        ) { args, ctx ->
            val url = args["url"] as? String ?: return@AgentTool "Error: no url"
            val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(normalized)).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                "Opened $normalized in the browser"
            } catch (e: Exception) {
                "Error: cannot open url: ${e.message}"
            }
        })

        // show_file — present a generated file to the user as a file card in chat
        tools.add(AgentTool(
            name = "show_file",
            description = "Show a file to the user as a preview card in the conversation. Use after creating or finding a file the user should see. Args: path",
            riskLevel = 1, category = "file"
        ) { args, _ ->
            val path = args["path"] as? String ?: return@AgentTool "Error: no path"
            val file = resolvePath(path)
            if (!validatePath(file)) return@AgentTool "Error: access denied"
            if (!file.exists()) return@AgentTool "Error: file not found: ${file.absolutePath}"
            "SHOW_FILE:${file.absolutePath}"
        })

        // set_alarm
        tools.add(AgentTool(
            name = "set_alarm", description = "Set an alarm. Args: hour (0-23), minute (0-59), label (optional)", riskLevel = 1, category = "device"
        ) { args, ctx ->
            val hour = (args["hour"] as? Number)?.toInt() ?: return@AgentTool "Error: no hour"
            val minute = (args["minute"] as? Number)?.toInt() ?: 0
            val label = args["label"] as? String ?: "Alarm"
            try {
                val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                }
                ctx.startActivity(intent)
                "Alarm set for ${hour}:${minute.toString().padStart(2, '0')} - $label"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // set_timer
        tools.add(AgentTool(
            name = "set_timer", description = "Set a timer. Args: seconds (duration), label (optional)", riskLevel = 1, category = "device"
        ) { args, ctx ->
            val seconds = (args["seconds"] as? Number)?.toLong() ?: return@AgentTool "Error: no seconds"
            val label = args["label"] as? String ?: "Timer"
            try {
                val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                }
                ctx.startActivity(intent)
                "Timer set for ${seconds}s - $label"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // set_volume
        tools.add(AgentTool(
            name = "set_volume", description = "Set media volume. Args: level (0-15)", riskLevel = 2, category = "device"
        ) { args, ctx ->
            val level = (args["level"] as? Number)?.toInt()?.coerceIn(0, 15) ?: return@AgentTool "Error: no level (0-15)"
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, level, 0)
                "Media volume set to $level/15"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // get_volume
        tools.add(AgentTool(
            name = "get_volume", description = "Get current media volume level", riskLevel = 1, category = "device"
        ) { _, ctx ->
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val current = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                "Media volume: $current/$max"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // toggle_flashlight
        tools.add(AgentTool(
            name = "toggle_flashlight", description = "Toggle flashlight on/off. Args: state (on/off)", riskLevel = 2, category = "device"
        ) { args, ctx ->
            val state = (args["state"] as? String)?.lowercase() ?: return@AgentTool "Error: no state (on/off)"
            try {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val cameraId = cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return@AgentTool "Error: no flashlight available"
                cm.setTorchMode(cameraId, state == "on")
                "Flashlight ${if (state == "on") "ON" else "OFF"}"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // get_network_info
        tools.add(AgentTool(
            name = "get_network_info", description = "Get network status (WiFi, mobile data, signal)", riskLevel = 1, category = "device"
        ) { _, ctx ->
            try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val network = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(network)
                val wifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                val mobile = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
                val connected = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val type = when {
                    wifi -> "WiFi"
                    mobile -> "Mobile Data"
                    else -> "None"
                }
                val ssid = if (wifi) {
                    try {
                        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        @Suppress("MissingPermission")
                        wm.connectionInfo?.ssid?.replace("\"", "") ?: "unknown"
                    } catch (_: Exception) { "unknown" }
                } else ""
                "Type: $type, Connected: $connected${if (ssid.isNotBlank()) ", SSID: $ssid" else ""}"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // get_battery_info
        tools.add(AgentTool(
            name = "get_battery_info", description = "Get battery level, charging status, temperature", riskLevel = 1, category = "device"
        ) { _, ctx ->
            try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                val temp = try {
                    val f = java.io.File("/sys/class/power_supply/battery/temp")
                    if (f.exists()) (f.readText().trim().toFloat() / 10f).toString() + "°C" else "unknown"
                } catch (_: Exception) { "unknown" }
                "Battery: $level%, Charging: $charging, Temp: $temp"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // get_device_info_extended
        tools.add(AgentTool(
            name = "get_device_details", description = "Get detailed device info: RAM, storage, screen, CPU", riskLevel = 1, category = "device"
        ) { _, ctx ->
            try {
                val rt = Runtime.getRuntime()
                val totalMem = rt.totalMemory() / 1024 / 1024
                val freeMem = rt.freeMemory() / 1024 / 1024
                val stat = android.os.StatFs(ctx.filesDir.path)
                val totalStorage = stat.blockCountLong * stat.blockSizeLong / 1024 / 1024
                val freeStorage = stat.availableBlocksLong * stat.blockSizeLong / 1024 / 1024
                val dm = ctx.resources.displayMetrics
                val dpi = dm.densityDpi
                buildString {
                    appendLine("Model: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("RAM: ${totalMem}MB total, ${freeMem}MB free")
                    appendLine("Storage: ${totalStorage}MB total, ${freeStorage}MB free")
                    appendLine("Screen: ${dm.widthPixels}x${dm.heightPixels} @ ${dpi}dpi")
                    appendLine("CPU: ${android.os.Build.HARDWARE}")
                }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // make_call (dangerous - needs confirmation)
        tools.add(AgentTool(
            name = "make_call", description = "Open phone dialer with number. Args: number (phone number)", riskLevel = 4, category = "phone"
        ) { args, ctx ->
            val number = args["number"] as? String ?: return@AgentTool "Error: no number"
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_DIAL,
                    android.net.Uri.parse("tel:$number"))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                "Dialer opened for $number"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // open_settings
        tools.add(AgentTool(
            name = "open_settings", description = "Open a system settings page. Args: page (wifi|bluetooth|location|sound|display|battery|apps|storage|about)", riskLevel = 2, category = "device"
        ) { args, ctx ->
            val page = (args["page"] as? String)?.lowercase() ?: return@AgentTool "Error: no page"
            val action = when (page) {
                "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
                "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "sound" -> android.provider.Settings.ACTION_SOUND_SETTINGS
                "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
                "battery" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
                "apps" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS
                "storage" -> android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                "about" -> android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS
                else -> return@AgentTool "Error: unknown page '$page'. Use: wifi|bluetooth|location|sound|display|battery|apps|storage|about"
            }
            try {
                val intent = android.content.Intent(action).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                "Opened settings: $page"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // share_text
        tools.add(AgentTool(
            name = "share_text", description = "Open share sheet with text. Args: text (content to share)", riskLevel = 2, category = "device"
        ) { args, ctx ->
            val text = args["text"] as? String ?: return@AgentTool "Error: no text"
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(android.content.Intent.createChooser(intent, "Share via"))
                "Share sheet opened"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // vibrate
        tools.add(AgentTool(
            name = "vibrate", description = "Vibrate the device. Args: milliseconds (duration, default 200)", riskLevel = 1, category = "device"
        ) { args, ctx ->
            val ms = (args["milliseconds"] as? Number)?.toLong() ?: 200L
            try {
                val vm = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vm.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vm.vibrate(ms)
                }
                "Vibrated for ${ms}ms"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        })

        // === Extended device tools ===

        // clipboard_read
        tools.add(AgentTool(
            name = "clipboard_read", description = "Read current clipboard content", riskLevel = 1, category = "device"
        ) { _, ctx ->
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = cm.primaryClip
                if (clip == null || clip.itemCount == 0) "Clipboard is empty"
                else (0 until clip.itemCount).joinToString("\n") { i ->
                    clip.getItemAt(i).coerceToText(ctx)?.toString() ?: "(unavailable)"
                }
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // clipboard_write
        tools.add(AgentTool(
            name = "clipboard_write", description = "Write text to clipboard. Args: text", riskLevel = 2, category = "device"
        ) { args, ctx ->
            val text = args["text"] as? String ?: return@AgentTool "Error: no text"
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("agent", text))
                "Copied to clipboard (${text.length} chars)"
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // set_brightness
        tools.add(AgentTool(
            name = "set_brightness", description = "Set screen brightness. Args: level (0-255)", riskLevel = 2, category = "device"
        ) { args, ctx ->
            val level = (args["level"] as? Number)?.toInt()?.coerceIn(0, 255) ?: return@AgentTool "Error: no level (0-255)"
            try {
                android.provider.Settings.System.putInt(ctx.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, level)
                "Brightness set to $level/255"
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // set_sound_mode
        tools.add(AgentTool(
            name = "set_sound_mode", description = "Set ringer mode. Args: mode (normal|vibrate|silent)", riskLevel = 2, category = "device"
        ) { args, ctx ->
            val mode = (args["mode"] as? String)?.lowercase() ?: return@AgentTool "Error: no mode"
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                when (mode) {
                    "normal" -> am.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    "vibrate" -> am.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                    "silent" -> am.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                    else -> return@AgentTool "Error: use normal|vibrate|silent"
                }
                "Sound mode: $mode"
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // get_sound_mode
        tools.add(AgentTool(
            name = "get_sound_mode", description = "Get current ringer mode", riskLevel = 1, category = "device"
        ) { _, ctx ->
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                when (am.ringerMode) {
                    android.media.AudioManager.RINGER_MODE_NORMAL -> "Sound mode: normal"
                    android.media.AudioManager.RINGER_MODE_VIBRATE -> "Sound mode: vibrate"
                    android.media.AudioManager.RINGER_MODE_SILENT -> "Sound mode: silent"
                    else -> "Sound mode: unknown"
                }
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // close_app
        tools.add(AgentTool(
            name = "close_app", description = "Force-stop an app. Args: package (package name)", riskLevel = 3, category = "app"
        ) { args, ctx ->
            val pkg = args["package"] as? String ?: return@AgentTool "Error: no package"
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(pkg)
                "App $pkg stopped"
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // get_app_info
        tools.add(AgentTool(
            name = "get_app_info", description = "Get app details. Args: package", riskLevel = 1, category = "app"
        ) { args, ctx ->
            val pkg = args["package"] as? String ?: return@AgentTool "Error: no package"
            try {
                val pm = ctx.packageManager
                val info = pm.getPackageInfo(pkg, 0)
                val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))
                val size = try {
                    pm.getApplicationInfo(pkg, 0).sourceDir?.let { java.io.File(it).length() / 1024 / 1024 } ?: 0
                } catch (_: Exception) { 0 }
                buildString {
                    appendLine("Name: $label")
                    appendLine("Package: $pkg")
                    appendLine("Version: ${info.versionName} (${info.longVersionCode})")
                    appendLine("Size: ~${size}MB")
                    appendLine("First install: ${java.time.Instant.ofEpochMilli(info.firstInstallTime)}")
                    appendLine("Last update: ${java.time.Instant.ofEpochMilli(info.lastUpdateTime)}")
                }
            } catch (e: Exception) { "Error: app not found: $pkg" }
        })

        // install_apk
        tools.add(AgentTool(
            name = "install_apk", description = "Install an APK file. Args: path (path to .apk file). Requires user confirmation.", riskLevel = 4, category = "app"
        ) { args, ctx ->
            val path = args["path"] as? String ?: return@AgentTool "Error: no path"
            // "Install unknown apps" toggle must be granted per-app before any install prompt
            if (!ensureInstallPermission(ctx)) {
                return@AgentTool "需要「安装未知应用」权限：已打开系统设置，请开启「允许来自此来源的应用」开关后让我重试。"
            }
            val file = resolvePath(path)
            if (!validatePath(file)) return@AgentTool "Error: access denied"
            if (!file.exists() || !file.isFile) return@AgentTool "Error: file not found: $path"
            if (!file.name.endsWith(".apk", ignoreCase = true)) return@AgentTool "Error: not an APK file"
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", file)
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                "Install prompt opened for ${file.name} (${file.length() / 1024 / 1024}MB). User must confirm in system dialog."
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // uninstall_app
        tools.add(AgentTool(
            name = "uninstall_app", description = "Uninstall an app by package name. Args: package. Requires user confirmation.", riskLevel = 5, category = "app"
        ) { args, ctx ->
            val pkg = args["package"] as? String ?: return@AgentTool "Error: no package"
            if (pkg == ctx.packageName) return@AgentTool "Error: cannot uninstall self"
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_DELETE,
                    android.net.Uri.parse("package:$pkg")).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                "Uninstall confirmation opened for $pkg. User must confirm."
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // download_and_install
        tools.add(AgentTool(
            name = "download_and_install", description = "Download an APK from URL and open install prompt. Args: url. Requires user confirmation.", riskLevel = 5, category = "app"
        ) { args, ctx ->
            val url = args["url"] as? String ?: return@AgentTool "Error: no url"
            // Check BEFORE downloading — no point pulling the file without install rights
            if (!ensureInstallPermission(ctx)) {
                return@AgentTool "需要「安装未知应用」权限：已打开系统设置，请开启「允许来自此来源的应用」开关后让我重试。"
            }
            try {
                val filename = java.net.URL(url).path.substringAfterLast('/').ifBlank { "download.apk" }
                if (!filename.endsWith(".apk", ignoreCase = true)) return@AgentTool "Error: URL must point to an .apk file"
                val conn = java.net.URL(url).openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 120_000
                }
                val total = conn.contentLengthLong
                if (total > 200L * 1024 * 1024) return@AgentTool "Error: APK too large (${total / 1024 / 1024}MB > 200MB)"
                val saved = java.io.File(workspaceDir, filename)
                conn.getInputStream().use { input ->
                    saved.outputStream().use { out -> input.copyTo(out, bufferSize = 64 * 1024) }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", saved)
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                "Downloaded ${saved.name} (${saved.length() / 1024 / 1024}MB). Install prompt opened — user must confirm."
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // search_contacts
        tools.add(AgentTool(
            name = "search_contacts", description = "Search contacts by name. Args: query (name to search)", riskLevel = 2, category = "phone"
        ) { args, ctx ->
            val query = args["query"] as? String ?: return@AgentTool "Error: no query"
            try {
                val results = mutableListOf<String>()
                ctx.contentResolver.query(
                    android.provider.ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        android.provider.ContactsContract.Contacts._ID,
                        android.provider.ContactsContract.Contacts.DISPLAY_NAME,
                        android.provider.ContactsContract.Contacts.PHOTO_ID
                    ),
                    "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$query%"), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        results.add(name)
                        if (results.size >= 20) break
                    }
                }
                if (results.isEmpty()) "No contacts found matching '$query'"
                else "Found ${results.size} contacts:\n" + results.joinToString("\n") { "- $it" }
            } catch (e: SecurityException) {
                "NEED_PERMISSION:storage|Contacts permission is required"
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // get_alarms
        tools.add(AgentTool(
            name = "get_alarms", description = "List set alarms", riskLevel = 1, category = "device"
        ) { _, ctx ->
            try {
                val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                "Opened alarm app to show alarms"
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // get_step_count
        tools.add(AgentTool(
            name = "get_step_count", description = "Get today's step count (requires fitness sensor)", riskLevel = 1, category = "device"
        ) { _, ctx ->
            try {
                val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
                val stepSensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
                    ?: return@AgentTool "Error: no step counter sensor available"
                "Step sensor available: ${stepSensor.name}. Note: real-time step reading requires a foreground service."
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // get_screen_state
        tools.add(AgentTool(
            name = "get_screen_state", description = "Get screen state: brightness, rotation, timeout", riskLevel = 1, category = "device"
        ) { _, ctx ->
            try {
                val brightness = android.provider.Settings.System.getInt(ctx.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, -1)
                val timeout = android.provider.Settings.System.getInt(ctx.contentResolver,
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1)
                val rotation = android.provider.Settings.System.getInt(ctx.contentResolver,
                    android.provider.Settings.System.ACCELEROMETER_ROTATION, -1)
                "Brightness: $brightness/255, Screen timeout: ${timeout/1000}s, Auto-rotate: ${if (rotation == 1) "on" else "off"}"
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // set_screen_timeout
        tools.add(AgentTool(
            name = "set_screen_timeout", description = "Set screen timeout. Args: seconds (e.g. 30, 60, 300)", riskLevel = 2, category = "device"
        ) { args, ctx ->
            val seconds = (args["seconds"] as? Number)?.toInt()?.coerceIn(15, 1800) ?: return@AgentTool "Error: no seconds"
            try {
                android.provider.Settings.System.putInt(ctx.contentResolver,
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
                "Screen timeout set to ${seconds}s"
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // get_installed_size
        tools.add(AgentTool(
            name = "get_storage_usage", description = "Get storage usage by type", riskLevel = 1, category = "device"
        ) { _, ctx ->
            try {
                val stat = android.os.StatFs(ctx.filesDir.path)
                val total = stat.blockCountLong * stat.blockSizeLong
                val free = stat.availableBlocksLong * stat.blockSizeLong
                val used = total - free
                val df = java.text.DecimalFormat("#.##")
                buildString {
                    appendLine("Total: ${df.format(total / 1024.0 / 1024 / 1024)} GB")
                    appendLine("Used: ${df.format(used / 1024.0 / 1024 / 1024)} GB")
                    appendLine("Free: ${df.format(free / 1024.0 / 1024 / 1024)} GB")
                    appendLine("Usage: ${df.format(used.toDouble() / total * 100)}%")
                }
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // execute_phone_action - AutoGLM phone control (hidden unless master switch is on)
        if (PHONE_AGENT_ENABLED) {
            tools.add(AgentTool(
                name = "execute_phone_action", description = "Execute a phone action using AutoGLM (tap, swipe, type, open app, etc.)", riskLevel = 3, category = "phone"
            ) { args, ctx ->
                val instruction = args["instruction"] as? String ?: return@AgentTool "Error: no instruction"
                loadZhipuKeyFromPrefs(ctx)
                val apiKey = zhipuApiKey.ifBlank { return@AgentTool "Error: no zhipu API key configured. Set it in Settings → AutoGLM 手机助手." }
                try {
                    val adbOk = kotlinx.coroutines.runBlocking {
                        com.zhibai.app.adb.AdbManager.executeShell("echo ok", 8000)?.contains("ok") == true
                    }
                    if (!adbOk) {
                        return@AgentTool "NEED_PERMISSION:adb|Wireless ADB not connected — pair in Settings → AutoGLM → 无线调试 ADB"
                    }
                    val autoGLM = AutoGLMService()
                    autoGLM.updateApiKey(apiKey)
                    autoGLM.updateBaseUrl(loadPhoneAgentBaseUrl(ctx))
                    val adb = AdbExecutor()
                    val screenshot = kotlinx.coroutines.runBlocking { adb.captureScreen() }
                    if (screenshot != null) {
                        val action = kotlinx.coroutines.runBlocking {
                            autoGLM.execute(screenshot, instruction)
                        }
                        if (action != null) {
                            val result = kotlinx.coroutines.runBlocking {
                                when (action.type) {
                                    "tap" -> { adb.tap(action.x ?: 0, action.y ?: 0); "Tapped at (${action.x}, ${action.y})" }
                                    "swipe" -> { adb.swipe(action.x1 ?: 0, action.y1 ?: 0, action.x2 ?: 0, action.y2 ?: 0); "Swiped" }
                                    "type" -> { adb.inputText(action.text ?: ""); "Typed: ${action.text}" }
                                    "back" -> { adb.pressBack(); "Pressed back" }
                                    "home" -> { adb.pressHome(); "Pressed home" }
                                    "launch" -> { adb.launchApp(action.app ?: action.package_name ?: ""); "Launched ${action.app ?: action.package_name}" }
                                    "done" -> "Action completed"
                                    "take_over" -> "Waiting for user to complete action"
                                    else -> "Unknown action: ${action.type}"
                                }
                            }
                            "Action executed: $result"
                        } else {
                            val raw = autoGLM.lastRawContent?.take(200) ?: "empty"
                            "Error: failed to get action from AI. raw=$raw"
                        }
                    } else {
                        "Error: failed to capture screen"
                    }
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            })
        }

        // run_adb — 优先走无线调试真 ADB（shell 权限），未配对回退 App 沙箱 sh
        tools.add(AgentTool(
            name = "run_adb", description = "Execute a shell command via wireless-debugging ADB (real shell privileges). Use ONLY for ADB-specific access — prefer run_shell for ordinary commands. Falls back to app-sandbox shell if ADB is not connected", riskLevel = 5, category = "system"
        ) { args, _ ->
            val command = args["command"] as? String ?: return@AgentTool "Error: no command"
            try {
                val adbResult = com.zhibai.app.adb.AdbManager.executeShell(command)
                if (adbResult != null) {
                    adbResult.ifBlank { "Command executed (no output)" }
                } else {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                    val output = process.inputStream.bufferedReader().readText()
                    val error = process.errorStream.bufferedReader().readText()
                    process.waitFor()
                    if (error.isNotBlank()) "STDERR: $error\nSTDOUT: $output"
                    else output.ifBlank { "Command executed (no output)" }
                }
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
            listSkillsDetailed().joinToString("\n") { it.name }.ifEmpty { "No skills saved" }
        })

        // run_subagent — hand a self-contained subtask to a fresh mini tool loop
        tools.add(AgentTool(
            name = "run_subagent",
            description = "Delegate a self-contained subtask to a subagent with its own tool loop. Args: task (what to do), context (optional background). Returns the subagent's final answer.",
            riskLevel = 2, category = "task"
        ) { args, _ ->
            val task = args["task"] as? String ?: return@AgentTool "Error: no task"
            val context = (args["context"] as? String)?.takeIf { it.isNotBlank() }
            runSubagent(task, context, depth = 1)
        })

        // spawn_subagent — named subagent; several spawned in ONE round run concurrently
        tools.add(AgentTool(
            name = "spawn_subagent",
            description = "Spawn a NAMED subagent with its own mini tool loop for ONE independent subtask. Args: task (what to do), context (optional background), name (optional short subagent name). Multiple spawn_subagent calls in the same round run CONCURRENTLY. Returns the subagent's final answer. Subagents cannot spawn further subagents.",
            riskLevel = 2, category = "task"
        ) { args, _ ->
            val task = args["task"] as? String ?: return@AgentTool "Error: no task"
            val context = (args["context"] as? String)?.takeIf { it.isNotBlank() }
            val name = (args["name"] as? String)?.takeIf { it.isNotBlank() }
            runSubagent(task, context, depth = 1, name = name)
        })

        // request_permission - ask the user to grant a system permission & jump to Settings
        tools.add(AgentTool(
            name = "request_permission",
            description = "Ask the user to grant a system permission. Use when a needed permission is missing. Args: permission (accessibility|overlay|storage|projection|notification|mic|calendar|alarm|location), reason (why it is needed)",
            riskLevel = 1, category = "system"
        ) { args, _ ->
            val permission = (args["permission"] as? String ?: "storage").lowercase()
            val reason = args["reason"] as? String ?: "Required to continue the task"
            "NEED_PERMISSION:$permission|$reason"
        })

        // === Task checklist tools ===
        tools.add(AgentTool(
            name = "add_task",
            description = "Add a task to the checklist. Args: title (task description)",
            riskLevel = 1, category = "task"
        ) { args, _ ->
            val title = args["title"] as? String ?: return@AgentTool "Error: no title"
            val task = taskManager.addTask(title)
            "Added ${task.label}: $title"
        })

        tools.add(AgentTool(
            name = "update_task",
            description = "Update a task's status. Args: id (task number, e.g. 1), status (pending|in_progress|done|cancelled)",
            riskLevel = 1, category = "task"
        ) { args, _ ->
            val id = (args["id"] as? Number)?.toInt() ?: return@AgentTool "Error: no id"
            val status = (args["status"] as? String ?: "").lowercase()
            val s = when (status) {
                "pending" -> CheckTaskStatus.PENDING
                "in_progress" -> CheckTaskStatus.IN_PROGRESS
                "done" -> CheckTaskStatus.DONE
                "cancelled" -> CheckTaskStatus.CANCELLED
                else -> return@AgentTool "Error: status must be pending|in_progress|done|cancelled"
            }
            taskManager.updateStatus(id, s)
            "T$id → $status"
        })

        tools.add(AgentTool(
            name = "strike_task",
            description = "Remove a task from the checklist (marks it as failed). Args: id (task number)",
            riskLevel = 1, category = "task"
        ) { args, _ ->
            val id = (args["id"] as? Number)?.toInt() ?: return@AgentTool "Error: no id"
            taskManager.strikeTask(id)
            "Struck T$id"
        })

        // === Script execution ===

        // run_shell
        tools.add(AgentTool(
            name = "run_shell", description = "Execute a shell command (built-in proot rootfs when installed, else root or app shell). Args: command, timeout (optional seconds, default 30)", riskLevel = 3, category = "system"
        ) { args, ctx ->
            val cmd = args["command"] as? String ?: return@AgentTool "Error: no command"
            val timeout = (args["timeout"] as? Number)?.toLong() ?: 30L
            try {
                // 0) Built-in proot rootfs (no Termux/root needed).
                //    null = rootfs unusable/not packaged → fall through;
                //    a real run result (even timeout/non-zero exit) is returned as-is.
                val linux = Rootfs.linuxDir(ctx)
                if (Rootfs.isUsable(linux)) {
                    ProotShell.execute(
                        Rootfs.prootLibDir(ctx), linux, workspaceDir,
                        Rootfs.tempDir(ctx), cmd, timeout
                    )?.let { return@AgentTool it }
                }
                // 1) Root shell (Rootfs): full filesystem access.
                //    null = su binary missing/denied at exec → fall through;
                //    a real run result (even non-zero exit) is returned as-is.
                execCapture(arrayOf("su", "-c", cmd), timeout)?.let { return@AgentTool it }
                // 2) Fallback: Android system shell (limited PATH)
                execCapture(arrayOf("sh", "-c", cmd), timeout)?.let { return@AgentTool it }
                "Error: no shell available"
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // install_rootfs — one-time download+install of the built-in Linux rootfs
        tools.add(AgentTool(
            name = "install_rootfs",
            description = "One-time: download and install the built-in Linux rootfs (Ubuntu base, ~30MB). Args: url (optional, .tar.gz URL; default Ubuntu base for this ABI). Enables apt/full userland in run_shell; /workspace = agent workspace dir.",
            riskLevel = 3, category = "system"
        ) { args, ctx ->
            if (!Rootfs.hasProotLibs(ctx)) {
                val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                return@AgentTool "Error: proot binaries not packaged for ABI $abi"
            }
            val url = (args["url"] as? String)?.takeIf { it.isNotBlank() }
                ?: Rootfs.defaultUrl().ifBlank {
                    return@AgentTool "Error: proot binaries not packaged for this ABI"
                }
            val path = url.substringBefore('?').substringBefore('#')
            if (!path.endsWith(".tar.gz")) return@AgentTool "Error: only .tar.gz rootfs images are supported"
            Rootfs.ensureDirs(ctx)
            try {
                RootfsInstaller().install(Rootfs.linuxDir(ctx), Rootfs.tempDir(ctx), url)
            } catch (e: InterruptedException) {
                return@AgentTool "Error: rootfs install cancelled"
            } catch (e: Exception) {
                return@AgentTool "Error: rootfs install failed: ${e.message}"
            }
            if (!Rootfs.isUsable(Rootfs.linuxDir(ctx))) {
                return@AgentTool "Error: install finished but rootfs is not usable"
            }
            "Rootfs installed. Verify with run_shell {\"command\":\"cat /etc/os-release\"} (shows Ubuntu). " +
                "Enable Python via run_shell {\"command\":\"apt update && apt install -y python3\"}. " +
                "/workspace is the agent workspace dir."
        })

        // run_python — built-in rootfs
        tools.add(AgentTool(
            name = "run_python", description = "Run a Python script. Args: code (Python source) or path (path to .py file). Uses built-in rootfs (install_rootfs first, then apt install python3).", riskLevel = 3, category = "system"
        ) { args, ctx ->
            val code = args["code"] as? String
            val path = args["path"] as? String
            if (code.isNullOrBlank() && path.isNullOrBlank()) return@AgentTool "Error: provide code or path"

            try {
                // Write code to a temp file in workspace (shared by rootfs paths)
                val scriptFile = if (path != null) resolvePath(path) else {
                    val f = java.io.File(workspaceDir, "_agent_script.py")
                    f.writeText(code!!)
                    f
                }
                if (!validatePath(scriptFile)) return@AgentTool "Error: access denied"
                if (!scriptFile.exists()) return@AgentTool "Error: script not found"

                // Built-in rootfs with python3 installed
                val linux = Rootfs.linuxDir(ctx)
                if (Rootfs.isUsable(linux) && Rootfs.hasEntry(linux, "usr/bin/python3")) {
                    val guest = toGuestPath(scriptFile)
                        ?: return@AgentTool "Error: script path not reachable from rootfs (use workspace or /sdcard): ${scriptFile.absolutePath}"
                    ProotShell.execute(
                        Rootfs.prootLibDir(ctx), linux, workspaceDir,
                        Rootfs.tempDir(ctx), "python3 '${guest.replace("'", "'\"'\"'")}'", 60
                    )?.let { return@AgentTool it }
                    // null = proot unavailable → error below
                }

                "Error: no Python runtime available. Run install_rootfs, then run_shell {\"command\":\"apt update && apt install -y python3\"}."
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // run_node — built-in rootfs Node.js (apt install nodejs)
        tools.add(AgentTool(
            name = "run_node", description = "Run a Node.js script. Args: code or path. Uses built-in rootfs (install_rootfs first, then apt install nodejs).", riskLevel = 3, category = "system"
        ) { args, ctx ->
            val code = args["code"] as? String
            val path = args["path"] as? String
            if (code.isNullOrBlank() && path.isNullOrBlank()) return@AgentTool "Error: provide code or path"

            try {
                val scriptFile = if (path != null) resolvePath(path) else {
                    val f = java.io.File(workspaceDir, "_agent_script.js")
                    f.writeText(code!!)
                    f
                }
                if (!validatePath(scriptFile)) return@AgentTool "Error: access denied"

                // Built-in rootfs with node installed
                val linux = Rootfs.linuxDir(ctx)
                if (Rootfs.isUsable(linux) && Rootfs.hasEntry(linux, "usr/bin/node")) {
                    val guest = toGuestPath(scriptFile)
                        ?: return@AgentTool "Error: script path not reachable from rootfs (use workspace or /sdcard): ${scriptFile.absolutePath}"
                    ProotShell.execute(
                        Rootfs.prootLibDir(ctx), linux, workspaceDir,
                        Rootfs.tempDir(ctx), "node '${guest.replace("'", "'\"'\"'")}'", 60
                    )?.let { return@AgentTool it }
                    // null = proot unavailable → error below
                }

                "Error: no Node.js runtime available. Run install_rootfs, then run_shell {\"command\":\"apt update && apt install -y nodejs\"}."
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // run_java — compile & run via built-in rootfs (apt install openjdk-17-jdk)
        tools.add(AgentTool(
            name = "run_java", description = "Compile and run a Java source file. Args: code (Java source) or path (path to .java file). Uses built-in rootfs (install_rootfs first, then apt install openjdk-17-jdk).", riskLevel = 3, category = "system"
        ) { args, ctx ->
            val code = args["code"] as? String
            val path = args["path"] as? String
            if (code.isNullOrBlank() && path.isNullOrBlank()) return@AgentTool "Error: provide code or path"

            try {
                val srcFile = if (path != null) resolvePath(path) else {
                    val f = java.io.File(workspaceDir, "_agent_script.java")
                    f.writeText(code!!)
                    f
                }
                if (!validatePath(srcFile)) return@AgentTool "Error: access denied"
                if (!srcFile.exists()) return@AgentTool "Error: source not found"

                // Determine class name from file name (public class must match)
                val className = srcFile.nameWithoutExtension

                // Built-in rootfs with javac installed
                val linux = Rootfs.linuxDir(ctx)
                if (Rootfs.isUsable(linux) && Rootfs.hasEntry(linux, "usr/bin/javac")) {
                    val guestFile = toGuestPath(srcFile)
                        ?: return@AgentTool "Error: source path not reachable from rootfs (use workspace or /sdcard): ${srcFile.absolutePath}"
                    val guestDir = guestFile.substringBeforeLast('/', Rootfs.MOUNT_WORKSPACE)
                    val q = { s: String -> "'" + s.replace("'", "'\"'\"'") + "'" }
                    val bashCmd = "cd ${q(guestDir)} && javac ${q("$className.java")} && java ${q(className)}"
                    ProotShell.execute(
                        Rootfs.prootLibDir(ctx), linux, workspaceDir,
                        Rootfs.tempDir(ctx), bashCmd, 120
                    )?.let { return@AgentTool it }
                    // null = proot unavailable → error below
                }

                "Error: no Java runtime available. Run install_rootfs, then run_shell {\"command\":\"apt update && apt install -y openjdk-17-jdk\"}."
            } catch (e: Exception) { "Error: ${e.message}" }
        })

        // create_questionnaire — AI 发问卷卡片，阻塞到用户提交，返回答案 JSON
        tools.add(AgentTool(
            name = "create_questionnaire",
            description = "Show the user an interactive questionnaire card (single/multi/text/rating questions) and wait for their answers. Returns answers as JSON. Use when you need the user's decisions, preferences, or input before continuing.",
            riskLevel = 1, category = "ui"
        ) { args, _ ->
            val raw = args["questions"]
                ?: return@AgentTool "Error: missing 'questions' array"
            val items = try {
                val json = when (raw) {
                    is String -> raw
                    else -> gson.toJson(raw)
                }
                gson.fromJson(json, Array<QuestionnaireItem>::class.java).toList()
            } catch (e: Exception) {
                return@AgentTool "Error: invalid questions JSON: ${e.message}"
            }
            if (items.isEmpty()) return@AgentTool "Error: questions array is empty"
            for (q in items) {
                if (q.id.isBlank() || q.question.isBlank()) return@AgentTool "Error: each question needs non-blank id and question"
                if (q.type !in setOf("single", "multi", "text", "rating")) return@AgentTool "Error: unknown type '${q.type}' for ${q.id}"
                if (q.type in setOf("single", "multi") && q.options.isEmpty()) return@AgentTool "Error: ${q.id} (${q.type}) needs options"
            }
            requestQuestionnaire(items)
        })

        // switch_phase — questionnaire-style confirmation card (same card UI as
        // create_questionnaire). The engine can't touch the ViewModel's phase
        // state, so it returns a PHASE_SWITCH: token that ChatViewModel
        // intercepts after execution to actually flip the phase.
        tools.add(AgentTool(
            name = "switch_phase",
            description = "Send the user a confirmation card to switch between PLAN and BUILD phase. Args: phase (plan|build). The phase changes only after the user confirms on the card.",
            riskLevel = 1, category = "ui"
        ) { args, _ ->
            val target = (args["phase"] as? String)?.trim()?.lowercase()
            if (target != "plan" && target != "build") {
                return@AgentTool "Error: phase must be plan or build"
            }
            val question = if (target == "build") {
                LangManager.t("agent.phase.askBuild", "规划已完成，切换到 Build 执行阶段？")
            } else {
                LangManager.t("agent.phase.askPlan", "需要重新规划，切换到 Plan 规划阶段？")
            }
            val optConfirm = LangManager.t("agent.phase.confirm", "确认切换")
            val answers = requestQuestionnaire(listOf(
                QuestionnaireItem(
                    id = "phase",
                    type = "single",
                    question = question,
                    options = listOf(optConfirm, LangManager.t("agent.phase.stay", "留在当前阶段")),
                    required = true,
                )
            ))
            when {
                answers.contains("USER_CANCELLED") -> "PHASE_SWITCH:cancelled"
                answers.contains(optConfirm) -> "PHASE_SWITCH:$target"
                else -> "PHASE_SWITCH:declined"
            }
        })
    }

    /**
     * Mini tool loop for run_subagent/spawn_subagent: a fresh model round gets the
     * subtask and the tool list, may call tools for up to [maxRounds] rounds, then
     * answers in prose. Depth-capped so a subagent cannot spawn sub-subagents.
     * [name] labels the subagent in its prompt and live lines; [live] routes progress
     * lines to one card (null → the engine-wide sink, i.e. the run_subagent card).
     */
    private suspend fun runSubagent(
        task: String,
        context: String?,
        depth: Int,
        name: String? = null,
        live: ((String) -> Unit)? = null
    ): String {
        if (depth > 2) return "Error: subagent nesting too deep"
        val maxRounds = 4
        val label = name?.takeIf { it.isNotBlank() } ?: "subagent"
        fun emit(line: String) {
            val tagged = "[$label] $line"
            if (live != null) {
                try { live(tagged) } catch (_: Exception) {}
            } else emitOutput(tagged)
        }
        emit(LangManager.t("agent.subtask.start", "开始子任务：{task}").replace("{task}", task.replace('\n', ' ').take(80)))
        val apiMessages = mutableListOf<com.zhibai.app.data.model.Message>(
            com.zhibai.app.data.model.Message(role = "system", content = """
You are ${if (name.isNullOrBlank()) "a focused subagent" else "subagent \"$label\""} of a phone assistant. Complete the EXACT subtask you are given.
Use tools when needed (reply with the tool_calls JSON schema), then answer with a concise
final result in plain text — the parent agent will use it directly.

Available tools:
${getToolsDescription()}
""".trimIndent()),
            com.zhibai.app.data.model.Message(role = "user", content = buildString {
                append(task)
                if (!context.isNullOrBlank()) append("\n\nBackground:\n").append(context)
            })
        )
        for (round in 0 until maxRounds) {
            emit(LangManager.t("agent.subtask.round", "第 {n} 轮：请求模型…").replace("{n}", (round + 1).toString()))
            val request = com.zhibai.app.data.model.ChatRequest(
                model = "mimo-v2.6-flash",
                messages = apiMessages,
                maxCompletionTokens = 8192,
                temperature = 0.3f,
                stream = false,
                thinking = com.zhibai.app.data.model.ThinkingConfig(type = "disabled"),
                responseFormat = ToolCallSchema.toResponseFormat()
            )
            val raw = try {
                apiService.chatCompletionStructured(request).choices.firstOrNull()?.message?.content?.toString() ?: ""
            } catch (e: Exception) {
                return "Error: subagent request failed: ${e.message}"
            }
            val parsed = ToolCallSchema.parse(raw) ?: ToolCallSchema.parse(
                raw.substringAfter("{", "").let { if (it.isEmpty()) raw else "{$it" }
            )
            val calls = parsed?.toolCalls.orEmpty()
            if (calls.isEmpty()) {
                val answer = (parsed?.response ?: raw).ifBlank { "Error: subagent returned empty result" }
                emit(LangManager.t("agent.subtask.done", "完成：{answer}").replace("{answer}", answer.replace('\n', ' ').take(100)))
                return answer
            }
            val results = mutableListOf<String>()
            calls.forEach { tc ->
                val tcArgs = tc.args ?: emptyMap<String, Any>()
                if (tc.tool == "run_subagent" || tc.tool == "spawn_subagent") {
                    emit(LangManager.t("agent.subtask.rejectNested", "拒绝 {tool}：子代理不能再派生子代理").replace("{tool}", tc.tool))
                    results.add("[tool result: ${tc.tool}]\nError: subagents cannot spawn subagents")
                    return@forEach
                }
                emit(LangManager.t("agent.subtask.call", "调用 {tool}").replace("{tool}", tc.tool))
                val out = try {
                    executeTool(tc.tool, tcArgs, MODE_DEFAULT)
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                emit("${tc.tool} → ${out.replace('\n', ' ').take(120)}")
                results.add("[tool result: ${tc.tool}]\n${out.take(4000)}")
            }
            apiMessages.add(com.zhibai.app.data.model.Message(role = "assistant", content = raw))
            apiMessages.add(com.zhibai.app.data.model.Message(role = "user", content = results.joinToString("\n\n")))
        }
        emit(LangManager.t("agent.subtask.roundLimit", "达到工具轮次上限"))
        return "Error: subagent hit the tool-round limit before answering"
    }

    /**
     * Execute one tool under [mode] permission rules. [live] (optional) receives this
     * call's progress lines directly — used by spawn_subagent so each concurrent
     * subagent streams into its own card instead of the engine-wide sink.
     */
    suspend fun executeTool(toolName: String, args: Map<String, Any>, mode: Int, live: ((String) -> Unit)? = null): String {
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
                if (toolName == "spawn_subagent" || toolName == "run_subagent") {
                    // Direct dispatch so [live] can stream this subagent's progress
                    // into its own card (tool.execute has no live-output parameter).
                    val task = args["task"] as? String ?: return@withContext "Error: no task"
                    val bg = (args["context"] as? String)?.takeIf { it.isNotBlank() }
                    val name = (args["name"] as? String)?.takeIf { it.isNotBlank() }
                    runSubagent(task, bg, depth = 1, name = name, live = live)
                } else {
                    tool.execute(args, context)
                }
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
            val request = com.zhibai.app.data.model.ChatRequest(
                model = "mimo-v2.6-flash",
                messages = listOf(com.zhibai.app.data.model.Message(role = "user", content = prompt)),
                maxCompletionTokens = 8,
                temperature = 0f,
                stream = false,
                thinking = com.zhibai.app.data.model.ThinkingConfig(type = "disabled")
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

    /** Suspend until the user submits the questionnaire card. Returns answers JSON. */
    private suspend fun requestQuestionnaire(questions: List<QuestionnaireItem>): String {
        return suspendCancellableCoroutine { continuation ->
            pendingQuestionnaire = PendingQuestionnaire(questions, continuation)
            onQuestionnaireRequested?.invoke(questions)
            continuation.invokeOnCancellation {
                pendingQuestionnaire = null
            }
        }
    }

    /** Called by UI when the user submits the filled questionnaire. */
    fun submitQuestionnaire(answersJson: String) {
        val pending = pendingQuestionnaire ?: return
        pendingQuestionnaire = null
        pending.continuation.resume(answersJson)
    }

    /** Called by UI when the user dismisses the questionnaire without submitting. */
    fun cancelQuestionnaire() {
        val pending = pendingQuestionnaire ?: return
        pendingQuestionnaire = null
        pending.continuation.resume("""{"_status":"USER_CANCELLED"}""")
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

    /**
     * Ensure the per-app "install unknown apps" toggle is granted.
     * If not, jump to the system settings screen so the user can enable it.
     * Returns true when installs are allowed (always true below API 26).
     */
    private fun ensureInstallPermission(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return true
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        return try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            ctx.startActivity(intent)
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Run [cmd] with a timeout, capturing stdout/stderr.
     * Returns null ONLY when the process could not start (binary missing) so callers
     * can fall through to the next shell; any real run result — including timeout or
     * non-zero exit — comes back as a formatted string.
     */
    private fun execCapture(cmd: Array<String>, timeoutSec: Long): String? {
        val process = try { Runtime.getRuntime().exec(cmd) } catch (e: Exception) { return null }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread {
            try {
                process.inputStream.bufferedReader().use { r -> r.forEachLine {
                    stdout.appendLine(it)
                    emitOutput(it)
                } }
            } catch (_: Exception) { } // stream closed by destroyForcibly on timeout
        }
        val errThread = Thread {
            try {
                process.errorStream.bufferedReader().use { r -> r.forEachLine {
                    stderr.appendLine(it)
                    emitOutput(it)
                } }
            } catch (_: Exception) { }
        }
        outThread.start(); errThread.start()
        val finished = try {
            process.waitFor(timeoutSec.coerceIn(1, 300), java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) { false }
        if (!finished) {
            process.destroyForcibly()
            outThread.join(1000); errThread.join(1000)
            return "Error: timeout after ${timeoutSec}s"
        }
        outThread.join(2000); errThread.join(2000)
        val exitCode = process.exitValue()
        return buildString {
            if (exitCode != 0) appendLine("Exit code: $exitCode")
            if (stdout.isNotBlank()) appendLine(stdout.toString().trimEnd())
            if (stderr.isNotBlank()) appendLine("STDERR: ${stderr.toString().trimEnd()}")
            if (stdout.isBlank() && stderr.isBlank()) appendLine("(no output)")
        }.take(8000)
    }
}
