package net.ib.ixpert.ops.wuwagent.model

import com.google.gson.annotations.SerializedName

data class OllamaMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class OllamaChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<OllamaMessage>,
    @SerializedName("stream") val stream: Boolean = false,
    @SerializedName("options") val options: Map<String, Any>? = null
)

data class OllamaChatResponse(
    @SerializedName("model") val model: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("message") val message: OllamaMessage?,
    @SerializedName("done") val done: Boolean?,
    @SerializedName("error") val error: String? = null,
    /** OpenAI 계열: choices[0].finish_reason ("length"면 토큰 한도로 응답이 잘린 것).
     *  최종적으로 클라이언트가 doneReason 등을 이 필드로 통일해서 채워줌. 확인 불가한 경우 null. */
    @SerializedName("finish_reason") val finishReason: String? = null,
    /** Ollama 네이티브 API 전용 필드(done_reason). OpenAIClient에서는 항상 null.
     *  OllamaClient가 이 값을 finishReason으로 매핑해 반환함. */
    @SerializedName("done_reason") val doneReason: String? = null
)
