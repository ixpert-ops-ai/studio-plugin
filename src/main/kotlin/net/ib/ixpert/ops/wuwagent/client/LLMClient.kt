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
}
