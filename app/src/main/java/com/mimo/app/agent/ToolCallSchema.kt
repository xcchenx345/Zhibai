package com.mimo.app.agent

import com.google.gson.annotations.SerializedName
import com.mimo.app.data.model.ResponseFormat
import com.mimo.app.data.model.JsonSchema

data class StructuredToolCallResponse(
    val thinking: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<StructuredToolCall>? = null,
    val response: String? = null
)

data class StructuredToolCall(
    val tool: String,
    val args: Map<String, Any> = emptyMap(),
    val reason: String? = null
)

object ToolCallSchema {

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
            "required" to listOf("thinking"),
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
