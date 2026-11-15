package com.zhibai.app.agent

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.zhibai.app.data.model.ResponseFormat
import com.zhibai.app.data.model.JsonSchema

data class StructuredToolCallResponse(
    val thinking: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<StructuredToolCall>? = null,
    val response: String? = null
)

data class StructuredToolCall(
    val tool: String,
    // Nullable on purpose: gson instantiates data classes via Unsafe, so a JSON
    // object that omits "args" leaves this null despite the Kotlin default.
    val args: Map<String, Any>? = null,
    val reason: String? = null
)

object ToolCallSchema {

    /**
     * Tree-walking parse — deliberately NOT `gson.fromJson(x, StructuredToolCallResponse::class.java)`.
     * R8 renames/optimizes these classes (they sit outside the kept
     * `data.model.**` package), so reflective binding produced List<LinkedTreeMap>
     * and the first field access threw ClassCastException, killing the tool run.
     */
    fun parse(raw: String): StructuredToolCallResponse? {
        val el = try { JsonParser.parseString(raw) } catch (_: Exception) { return null }
        if (!el.isJsonObject) return null
        val o = el.asJsonObject
        val toolCalls = o.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { item ->
                if (!item.isJsonObject) return@mapNotNull null
                val t = item.asJsonObject
                StructuredToolCall(
                    tool = t.str("tool") ?: return@mapNotNull null,
                    args = t.get("args")?.takeIf { it.isJsonObject }?.asJsonObject
                        ?.let { ao -> ao.entrySet().associate { it.key to it.value.toAny() } },
                    reason = t.str("reason")
                )
            }
        return StructuredToolCallResponse(
            thinking = o.str("thinking"),
            toolCalls = toolCalls,
            response = o.str("response")
        )
    }

    private fun JsonObject.str(k: String): String? =
        get(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonElement.toAny(): Any = when {
        isJsonPrimitive -> {
            val p = asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asDouble
                else -> p.asString
            }
        }
        isJsonObject -> asJsonObject.entrySet().associate { it.key to it.value.toAny() }
        isJsonArray -> asJsonArray.map { it.toAny() }
        else -> ""
    }

    fun toResponseFormat(): ResponseFormat {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "thinking" to mapOf(
                    "type" to "string",
                    "description" to "Your internal reasoning about what to do"
                ),
                "tool_calls" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "tool" to mapOf(
                                "type" to "string",
                                "description" to "Name of the tool to call"
                            ),
                            "args" to mapOf(
                                "type" to "object",
                                "description" to "Arguments for the tool",
                                "additionalProperties" to true
                            ),
                            "reason" to mapOf(
                                "type" to "string",
                                "description" to "Why you are calling this tool"
                            )
                        ),
                        "required" to listOf("tool", "args", "reason"),
                        "additionalProperties" to false
                    ),
                    "description" to "List of tool calls to execute (empty if none needed)"
                ),
                "response" to mapOf(
                    "type" to "string",
                    "description" to "Your reply to the user. When calling tools, ALSO fill this with one short sentence telling the user what you are about to do (shown live). When no tools are needed, this is your full final answer."
                )
            ),
            // OpenAI strict json_schema requires ALL properties to be listed as required;
            // optional semantics use ["type","null"] unions instead of omission.
            "required" to listOf("thinking", "tool_calls", "response"),
            "additionalProperties" to false
        )

        return ResponseFormat(
            type = "json_schema",
            jsonSchema = JsonSchema(
                name = "tool_call_response",
                strict = true,
                schema = schema
            )
        )
    }
}
