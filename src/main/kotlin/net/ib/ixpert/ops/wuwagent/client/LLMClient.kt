package net.ib.ixpert.ops.wuwagent.client

import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse

interface LLMClient {
    fun chat(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int? = null,
        onChunk: ((String) -> Unit)? = null
    ): OllamaChatResponse?

    fun fetchModels(baseUrl: String, apiKey: String): List<String>?
}
