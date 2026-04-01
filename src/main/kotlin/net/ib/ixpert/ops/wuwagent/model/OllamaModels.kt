package net.ib.ixpert.ops.wuwagent.model

import com.google.gson.annotations.SerializedName

data class OllamaMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class OllamaChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<OllamaMessage>,
    @SerializedName("stream") val stream: Boolean = false
)

data class OllamaChatResponse(
    @SerializedName("model") val model: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("message") val message: OllamaMessage?,
    @SerializedName("done") val done: Boolean?
)
