package com.zhibai.app.agent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ToolCallRequest(
    val tool: String,
    val args: Map<String, Any> = emptyMap(),
    val reason: String = "",
    /** Human-facing plain-language summary of what this call will do. Never shown to the AI reviewer. */
    val annotation: String = ""
)

/**
 * Build a concise, human-readable annotation for a tool call so the user can decide
 * whether to approve it. This is intentionally NOT fed to aiApproveTool — it is a
 * user-facing framing of the concrete effect, not a signal for the automated reviewer.
 */
fun describeToolCall(tool: String, args: Map<String, Any>): String {
    fun arg(key: String): String = args[key]?.toString()?.trim().orEmpty()
    val target = arg("path").ifBlank { arg("url").ifBlank { arg("package").ifBlank { arg("pattern") } } }
    return when (tool) {
        "read_file", "get_file_info" -> "读取文件「$target」的内容，不会修改它。"
        "write_file" -> "把内容写入「$target」，若文件已存在会覆盖原内容。"
        "edit_file" -> "在「${arg("path")}」中替换一段文本。"
        "delete_file" -> "删除「$target」，删除后通常无法恢复。"
        "move_file" -> "把「${arg("source")}」移动/重命名为「${arg("destination")}」。"
        "copy_file" -> "复制「${arg("source")}」到「${arg("destination")}」。"
        "list_files" -> "列出目录「${arg("path").ifBlank { "工作目录" }}」下的文件。"
        "search_files" -> "按名称在「${arg("path").ifBlank { "工作目录" }}」搜索匹配「${arg("pattern")}」的文件。"
        "grep" -> "在文件内容中搜索「${arg("pattern")}」。"
        "download_file" -> "从网络下载文件到工作目录。"
        "view_media" -> "查看/分析媒体文件「$target」的内容。"
        "open_app" -> "启动应用「${arg("package")}」。"
        "list_apps" -> "列出手机上已安装的应用。"
        "fetch_url" -> "访问并读取网页「$target」的内容。"
        "run_adb" -> "以 shell 权限执行命令「${arg("command")}」，影响面较大。"
        "request_permission" -> "请求「${arg("permission")}」系统权限。"
        "create_questionnaire" -> "向用户展示一张问卷并等待填写。"
        else -> "执行 $tool。"
    }
}

data class ToolCallResult(
    val tool: String,
    val success: Boolean,
    val output: String,
    val riskLevel: Int = 1
)

object ToolCallParser {
    private val gson = Gson()

    fun parseToolCalls(text: String): List<ToolCallRequest> {
        val calls = mutableListOf<ToolCallRequest>()

        // Pattern 1: ```tool_call code block
        val blockRegex = Regex("```\\s*tool_call\\s*\\n([\\s\\S]*?)```")
        for (match in blockRegex.findAll(text)) {
            parseJsonToolCall(match.groupValues[1].trim())?.let { calls.add(it) }
        }

        // Pattern 2: Any code block with "tool" JSON
        val anyBlockRegex = Regex("```(?:json)?\\s*\\n([\\s\\S]*?)```")
        for (match in anyBlockRegex.findAll(text)) {
            val json = match.groupValues[1].trim()
            if (json.contains("\"tool\"")) {
                parseJsonToolCall(json)?.let { call ->
                    if (calls.none { it.tool == call.tool }) calls.add(call)
                }
            }
        }

        // Pattern 3: Inline JSON with "tool" key
        val inlineRegex = Regex("\\{[^{}]*\"tool\"\\s*:\\s*\"[^\"]+\"[^{}]*\\}")
        for (match in inlineRegex.findAll(text)) {
            parseJsonToolCall(match.value)?.let { call ->
                if (calls.none { it.tool == call.tool }) calls.add(call)
            }
        }

        return calls
    }

    fun detectIntentFromText(text: String): ToolCallRequest? {
        val lower = text.lowercase()

        // File operations
        val readFileMatch = Regex("(?:读取?|打开|查看|read)\\s*(?:文件|file)?\\s*[：:]*\\s*[\"']?([^\"'\\n]+)[\"']?").find(lower)
        if (readFileMatch != null) {
            val path = readFileMatch.groupValues[1].trim()
            if (path.isNotBlank() && path.length < 200) {
                return ToolCallRequest("read_file", mapOf("path" to path), "Reading file")
            }
        }

        // List files
        if (lower.contains("列出") || lower.contains("list") || lower.contains("查看目录") || lower.contains("查看文件")) {
            val pathMatch = Regex("(?:目录|文件夹|folder|directory|path)[：:]*\\s*[\"']?([^\"'\\n]+)[\"']?").find(lower)
            val path = pathMatch?.groupValues?.get(1)?.trim() ?: ""
            return ToolCallRequest("list_files", mapOf("path" to path.ifBlank { "." }), "Listing files")
        }

        // Open app
        val openAppMatch = Regex("(?:打开|启动|open|launch)\\s*(?:应用|app)?\\s*[：:]*\\s*(.+)").find(lower)
        if (openAppMatch != null) {
            val appName = openAppMatch.groupValues[1].trim()
            if (appName.isNotBlank()) {
                return ToolCallRequest("open_app", mapOf("package" to appName), "Opening app")
            }
        }

        // Fetch URL
        val urlMatch = Regex("(https?://[^\\s]+)").find(text)
        if (urlMatch != null && (lower.contains("抓取") || lower.contains("fetch") || lower.contains("获取网页") || lower.contains("读取网页"))) {
            return ToolCallRequest("fetch_url", mapOf("url" to urlMatch.value), "Fetching URL")
        }

        // Device info
        if (lower.contains("设备信息") || lower.contains("device info") || lower.contains("手机信息")) {
            return ToolCallRequest("get_device_info", emptyMap(), "Getting device info")
        }

        return null
    }

    private fun parseJsonToolCall(json: String): ToolCallRequest? {
        return try {
            val call = gson.fromJson(json, ToolCallRequest::class.java)
            if (call.tool.isNotBlank()) call else null
        } catch (_: Exception) {
            val toolMatch = Regex("\"tool\"\\s*:\\s*\"([^\"]+)\"").find(json) ?: return null
            val toolName = toolMatch.groupValues[1]
            val argsMatch = Regex("\"args\"\\s*:\\s*(\\{[^}]*\\})").find(json)
            val args = if (argsMatch != null) {
                try {
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    gson.fromJson<Map<String, Any>>(argsMatch.groupValues[1], type) ?: emptyMap()
                } catch (_: Exception) { emptyMap<String, Any>() }
            } else emptyMap()
            val reasonMatch = Regex("\"reason\"\\s*:\\s*\"([^\"]*?)\"").find(json)
            val reason = reasonMatch?.groupValues?.getOrElse(1) { "" } ?: ""
            ToolCallRequest(tool = toolName, args = args, reason = reason)
        }
    }

    fun getToolDescription(): String {
        return """
## Tool Calling
To use a tool, output a tool call in this format:

```tool_call
{"tool": "tool_name", "args": {"key": "value"}, "reason": "brief reason"}
```

After outputting a tool call, STOP generating. The system will execute the tool and return the result in the next message.
You can chain multiple tool calls - call one tool, get result, call another if needed.
Only provide your final answer when all necessary tool calls are complete.

Available tools:
| Tool | Risk | Description |
|------|------|-------------|
| read_file | 1 | Read file content (args: path) |
| write_file | 3 | Write to file (args: path, content) |
| list_files | 1 | List directory (args: path) |
| delete_file | 4 | Delete file/folder (args: path) |
| open_app | 2 | Launch app (args: package) |
| list_apps | 1 | List installed apps |
| run_adb | 5 | Execute shell command (args: command) |
| get_device_info | 1 | Device information |
| fetch_url | 2 | Fetch web page (args: url, max_length) |
| save_skill | 2 | Save workflow (args: name, content) |
        """.trimIndent()
    }
}
