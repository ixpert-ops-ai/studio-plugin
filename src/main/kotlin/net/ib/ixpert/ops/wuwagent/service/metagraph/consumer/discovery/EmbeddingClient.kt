package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

object EmbeddingClient {
    private val logger = Logger.getInstance(EmbeddingClient::class.java)
    private val gson = Gson()
    
    // Hardcoded path for the prototype
    private val scriptDir = File("C:/Workspace/DEV-ASSISTANT/IDE-PLUGIN/intelliJ/ai-assistant-plugin/scratch/embeddings-test")
    private val scriptPath = "embedding-client.js"

    fun getTopKFiles(query: String, topK: Int = 30): List<String> {
        try {
            val processBuilder = ProcessBuilder(
                "node",
                scriptPath,
                query,
                topK.toString()
            )
            processBuilder.directory(scriptDir)
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT) // Print errors to console for debug
            
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            
            val exited = process.waitFor(60, TimeUnit.SECONDS)
            if (!exited || process.exitValue() != 0) {
                logger.error("Node embedding script failed. Exit value: ${if(exited) process.exitValue() else "timeout"}")
                return emptyList()
            }
            
            val type = object : TypeToken<List<String>>() {}.type
            return gson.fromJson(output, type)
            
        } catch (e: Exception) {
            logger.error("Failed to run embedding client", e)
            return emptyList()
        }
    }
}
