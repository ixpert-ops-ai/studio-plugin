package net.ib.ixpert.ops.wuwagent.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.annotations.SerializedName

// 1. Message Models
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatMessage(
    val role: String,
    val content: String?,
    
    @JsonProperty("reasoning_content")
    @SerializedName("reasoning_content")
    val reasoningContent: String? = null,
    
    @JsonProperty("tool_calls")
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    
    @JsonProperty("tool_call_id")
    @SerializedName("tool_call_id")
    val toolCallId: String? = null,
    
    val name: String? = null
)

// 2. Tool Definition Models
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: FunctionParameters
)

data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, PropertyDefinition>,
    val required: List<String> = emptyList()
)

data class PropertyDefinition(
    val type: String,
    val description: String? = null,
    val enum: List<String>? = null,
    val items: PropertyDefinition? = null,
    val properties: Map<String, PropertyDefinition>? = null,
    val required: List<String>? = null
)

// 3. Tool Call Models
@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolCall(
    val id: String,
    val type: String,
    val function: ToolCallFunction
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolCallFunction(
    val name: String,
    val arguments: String
)

// 4. Chat Completion Models
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatChoice(
    val index: Int?,
    val message: ChatMessage?,
    @JsonProperty("finish_reason")
    @SerializedName("finish_reason")
    val finishReason: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionResponse(
    val id: String?,
    val choices: List<ChatChoice>?
) {
    val reasoningContent: String? get() = choices?.firstOrNull()?.message?.reasoningContent
    val toolCalls: List<ToolCall>? get() = choices?.firstOrNull()?.message?.toolCalls
    val content: String? get() = choices?.firstOrNull()?.message?.content
}
