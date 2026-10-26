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
        toolChoice: Any? = "auto",
        temperature: Double? = null
    ): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
        return null
    }

    /**
     * JSON 스키마(구조화 출력)를 걸어 비스트림으로 호출한다. 서버가 지원하지 않거나 거부하거나 오류면 null을 돌려주고,
     * 호출한 쪽은 기존 `chat` 경로로 대체한다. 기본 구현은 지원하지 않음(null)이다.
     */
    fun chatJsonSchema(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int?,
        schemaName: String,
        schema: Map<String, Any>
    ): OllamaChatResponse? = null

    fun fetchModels(baseUrl: String, apiKey: String): List<String>?
}
