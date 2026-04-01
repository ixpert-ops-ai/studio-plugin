package net.ib.ixpert.ops.wuwagent.prompt

import com.intellij.openapi.diagnostic.Logger
import java.io.InputStream
import java.nio.charset.StandardCharsets

object PromptManager {
    private val logger = Logger.getInstance(PromptManager::class.java)

    /**
     * 지정된 이름의 프롬프트 파일을 resources/prompt 경로에서 읽어 반환합니다.
     */
    fun loadPrompt(fileName: String): String {
        return try {
            val resourcePath = "/prompt/$fileName"
            val inputStream: InputStream? = PromptManager::class.java.getResourceAsStream(resourcePath)
            
            if (inputStream != null) {
                String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
            } else {
                logger.error("Prompt file not found at path: $resourcePath")
                "You are a helpful coding assistant. Please answer the user's question."
            }
        } catch (e: Exception) {
            logger.error("Failed to load prompt: $fileName", e)
            "You are a helpful coding assistant. Please answer the user's question."
        }
    }
}
