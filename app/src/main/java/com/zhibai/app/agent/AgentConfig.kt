package com.zhibai.app.agent

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File

/**
 * Agent configuration file (config.json) at workspace root.
 * Only affects Agent mode. Corrupted file → Agent disabled + user prompt.
 *
 * Auto-repair: try fix JSON syntax → fallback to defaults.
 */
data class AgentConfigData(
    val appName: String = "MiMo"
)

object AgentConfig {
    const val FILE_NAME = "config.json"
    private val gson = Gson()

    /** Default config written when file missing or unrepairable. */
    fun defaultJson(): String = gson.toJson(AgentConfigData())

    /** Result of loading config. */
    sealed class LoadResult {
        data class Ok(val config: AgentConfigData) : LoadResult()
        /** Config was corrupted but auto-repaired (or reset to default). */
        data class Repaired(val config: AgentConfigData, val action: String) : LoadResult()
        /** Config is beyond repair — Agent should be disabled until user acts. */
        data class Broken(val reason: String) : LoadResult()
        /** No config file yet — create default. */
        data class Created(val config: AgentConfigData) : LoadResult()
    }

    fun configFile(workspaceDir: File): File = File(workspaceDir, FILE_NAME)

    /**
     * Load config with auto-repair pipeline:
     * 1. Missing → create default
     * 2. Valid JSON → parse & return
     * 3. Invalid JSON → attempt syntax repair
     * 4. Repair succeeded → return repaired + action note
     * 5. Repair failed → reset to default + action note
     * 6. Even default write fails → Broken
     */
    fun load(workspaceDir: File): LoadResult {
        val file = configFile(workspaceDir)
        return try {
            if (!file.exists()) {
                file.writeText(defaultJson())
                return LoadResult.Created(AgentConfigData())
            }

            val raw = file.readText()
            if (raw.isBlank()) {
                file.writeText(defaultJson())
                return LoadResult.Repaired(AgentConfigData(), "文件为空，已重置为默认配置")
            }

            // Try direct parse
            try {
                val json = JsonParser.parseString(raw)
                if (!json.isJsonObject) throw IllegalArgumentException("not a JSON object")
                val config = gson.fromJson(json, AgentConfigData::class.java)
                return LoadResult.Ok(config ?: AgentConfigData())
            } catch (_: Exception) {
                // Fall through to repair
            }

            // Attempt syntax repair
            val repaired = attemptRepair(raw)
            if (repaired != null) {
                try {
                    val json = JsonParser.parseString(repaired)
                    if (json.isJsonObject) {
                        val config = gson.fromJson(json, AgentConfigData::class.java) ?: AgentConfigData()
                        file.writeText(gson.toJson(config))
                        return LoadResult.Repaired(config, "已自动修复 JSON 语法错误")
                    }
                } catch (_: Exception) { /* continue to reset */ }
            }

            // Reset to default
            file.writeText(defaultJson())
            LoadResult.Repaired(AgentConfigData(), "配置损坏严重，已重置为默认配置")
        } catch (e: Exception) {
            LoadResult.Broken("无法读写配置文件: ${e.message}")
        }
    }

    /**
     * Best-effort JSON syntax repair:
     * - Strip trailing commas
     * - Close unclosed braces/brackets
     * - Remove unquoted keys (common corruption)
     * - Strip BOM / control chars
     */
    private fun attemptRepair(raw: String): String? {
        return try {
            var s = raw.trim()
                .removePrefix("﻿") // BOM
                .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "") // control chars

            if (s.isBlank()) return null

            // Must look like JSON object
            if (!s.startsWith("{")) s = "{$s"
            if (!s.endsWith("}")) s = "$s}"

            // Remove trailing commas before } or ]
            s = s.replace(Regex(",\\s*([}\\]])"), "$1")

            // Quote unquoted keys:  key: → "key":
            s = s.replace(Regex("(?m)([{,]\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*:")) { m ->
                "${m.groupValues[1]}\"${m.groupValues[2]}\":"
            }

            // Balance braces/brackets
            val opens = s.count { it == '{' }
            val closes = s.count { it == '}' }
            if (opens > closes) s += "}".repeat(opens - closes)
            val openB = s.count { it == '[' }
            val closeB = s.count { it == ']' }
            if (openB > closeB) s += "]".repeat(openB - closeB)

            // Validate result parses
            JsonParser.parseString(s)
            s
        } catch (_: Exception) {
            null
        }
    }

    /** Force-reset config to default. Returns true on success. */
    fun reset(workspaceDir: File): Boolean {
        return try {
            configFile(workspaceDir).writeText(defaultJson())
            true
        } catch (_: Exception) { false }
    }

    /** Read config without repair (for display purposes). Returns null if missing/broken. */
    fun readOrNull(workspaceDir: File): AgentConfigData? {
        return try {
            val file = configFile(workspaceDir)
            if (!file.exists()) return null
            gson.fromJson(file.readText(), AgentConfigData::class.java)
        } catch (_: Exception) { null }
    }
}
