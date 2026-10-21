package net.ib.ixpert.ops.wuwagent.client

import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse

interface LLMClient {
    fun chat(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int? = null,
        onChunk: ((String) -> Unit)? = null
    ): OllamaChatResponse?

    fun chatWithTools(
        systemPrompt: String,
        messages: List<net.ib.ixpert.ops.wuwagent.model.ChatMessage>,
        maxTokens: Int? = null,
        tools: List<net.ib.ixpert.ops.wuwagent.model.ToolDefinition>? = null,
        toolChoice: Any? = "auto"
    ): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
        return null
    }

    fun fetchModels(baseUrl: String, apiKey: String): List<String>?

    /**
     * [fetchModels]와 같지만 실패 시 예외를 삼키지 않고 그대로 던진다 (서버 상태 확인에서 원인 분류용).
     * 목록 형식을 인식하지 못한 경우에만 null.
     */
    fun fetchModelsOrThrow(baseUrl: String, apiKey: String): List<String>? = fetchModels(baseUrl, apiKey)
}
