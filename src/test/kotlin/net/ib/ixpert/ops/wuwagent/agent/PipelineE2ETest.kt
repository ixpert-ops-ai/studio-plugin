package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import com.google.gson.Gson
import com.google.gson.JsonObject
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ToolCall
import net.ib.ixpert.ops.wuwagent.model.ToolCallFunction
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.io.InputStreamReader

data class TestCase(val type: String, val srText: String, val targetGtClass: String)

class PipelineE2ETestVllmClient(val temperature: Double = 0.0) : LLMClient {
    private val gson = Gson()
    private val serverUrl = "http://vllm.ixpertops.cloud/v1/chat/completions"
    private val modelName = "Qwen/Qwen3.8-27B-FP8"
    
    override fun chat(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int?,
        onChunk: ((String) -> Unit)?
    ): OllamaChatResponse? {
        val messagesList = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userCode)
        )
        val requestBody = mapOf(
            "model" to modelName,
            "messages" to messagesList,
            "stream" to false,
            "temperature" to temperature,
            "max_tokens" to (maxTokens ?: 300)
        )
        
        val url = URL(serverUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        
        try {
            conn.outputStream.use { os ->
                val input = gson.toJson(requestBody).toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }
            
            if (conn.responseCode in 200..299) {
                val responseStr = InputStreamReader(conn.inputStream, Charsets.UTF_8).use { it.readText() }
                val jsonMap = gson.fromJson(responseStr, Map::class.java)
                val choices = jsonMap["choices"] as? List<Map<String, Any>>
                val content = (choices?.firstOrNull()?.get("message") as? Map<String, Any>)?.get("content") as? String ?: ""
                return OllamaChatResponse(
                    model = modelName,
                    createdAt = "",
                    message = OllamaMessage("assistant", content),
                    done = true
                )
            } else {
                val errorStr = InputStreamReader(conn.errorStream, Charsets.UTF_8).use { it.readText() }
                println("VLLM Error: ${conn.responseCode} - $errorStr")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    override fun chatWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int?,
        tools: List<ToolDefinition>?,
        toolChoice: Any?, temperature: Double?
    ): ChatCompletionResponse? {
        val userMsg = messages.lastOrNull { it.role == "user" }?.content ?: ""
        val toolName = tools?.firstOrNull()?.function?.name ?: "verify_files"
        
        // Append JSON schema instruction
        val schema = gson.toJson(tools?.firstOrNull()?.function?.parameters)
        val extraInstruction = "\n\n[CRITICAL INSTRUCTION]\nYou must respond with raw JSON only. Do not wrap it in markdown blocks. Output exactly the following JSON schema:\n$schema"
        
        val chatResponse = chat(systemPrompt + extraInstruction, userMsg, maxTokens, null)
        val jsonText = chatResponse?.message?.content ?: "{}"
        
        val cleanJson = jsonText.replace("```json", "").replace("```", "").trim()
        val finalJson = if (cleanJson.isBlank()) "{}" else cleanJson
        
        println("\n[VLLM JSON OUTPUT for $toolName]")
        println(finalJson)
        println("[END VLLM OUTPUT]\n")
        
        return ChatCompletionResponse(
            id = "test",
            choices = listOf(
                ChatChoice(
                    index = 0,
                    finishReason = "tool_calls",
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "dummy",
                                type = "function",
                                function = ToolCallFunction(
                                    name = toolName,
                                    arguments = finalJson
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    override fun fetchModels(baseUrl: String, apiKey: String): List<String>? {
        return emptyList()
    }
}

class PipelineE2ETest : BasePlatformTestCase() {

    @Test
    fun testPipelineE2E() {
        val file = File("C:/Workspace/graph/project-graph-i/project-graph.json")
        if (!file.exists()) {
            println("Graph file not found at ${file.absolutePath}!")
            return
        }
        val jsonString = file.readText(Charsets.UTF_8)
        val projectGraph = Gson().fromJson(jsonString, ProjectGraph::class.java).normalizeLegacyCollections()

        val testCases = listOf(
            TestCase("Easy", "주문 결제 연장 결제기한 처리", "OrderProcessBaseRequest"),
            TestCase("Easy", "새 쿠폰앱 전환 기획전 마케팅 푸시", "CponInfoRequest"),
            TestCase("Easy", "앱 전면홈 띠배너 운영자 관리 기능", "BnnrMngtServiceImpl"),
            TestCase("Easy", "탈퇴 회원 전환 전 임시 분리 보관", "MbrInfoServiceImpl"),
            TestCase("Trap", "주문상품 배송 준비 상태 추가", "PdSaveServiceImpl"),
            TestCase("Trap", "새 화면 (GNB) 전시 노출 순서 변경", "GnbMenuMstServiceImpl"),
            TestCase("Trap", "상품 불량 및 파손 부분 클레임 환불 계좌 처리", "ClaimMgmtServiceImpl"),
            TestCase("Trap", "특정 임직원 회원 사내드림 주문 내역 조회 조건 추가", "OrdrInqrListResponse")
        )

        val client = PipelineE2ETestVllmClient()

        for (tc in testCases) {
            println("\n\n=========================================================================")
            println("▶ [${tc.type}] SR: ${tc.srText}")
            println("=========================================================================")
            
            val gtInGraph = projectGraph.files.any { it.value.className.contains(tc.targetGtClass, ignoreCase = true) }
            println("[PRE-CHECK] Is Target GT (${tc.targetGtClass}) in the parsed projectGraph? -> $gtInGraph")

            System.setProperty("E2E_TARGET_GT", tc.targetGtClass)

            runBlocking {
                try {
                    val pipeline = RequirementAnalysisPipeline(null, client)
                    pipeline.analyze(
                        primaryReq = tc.srText,
                        secondaryReq = "",
                        projectGraph = projectGraph,
                        enhancedRequirements = emptyList()
                    ) { chunk ->
                        print(chunk)
                    }
                } catch (e: Exception) {
                    println("Pipeline Error: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
}
