package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Test
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.assertTrue

class VllmToolCallPilotTest {

    @Test
    fun testVllmToolCallingStability() {
        val serverUrl = "http://vllm.ixpertops.cloud/v1/chat/completions" // 사용자 지정 엔드포인트
        val gson: Gson = GsonBuilder().create()

        val requestBody = mapOf(
            "model" to "Qwen/Qwen3.8-27B-FP8", // 모델명은 서버에 띄워진 대로 무시되거나 매칭됨 (vLLM 기본값)
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are an agentic coding assistant. You must use tools when asked."),
                mapOf("role" to "user", "content" to "나는 OrdrInqrListResponse라는 클래스에 대해 알고 싶어. 도구를 사용해서 이 클래스의 정보를 찾아봐.")
            ),
            "tools" to listOf(
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to "getNodeInfoByName",
                        "description" to "Retrieve information about a graph node by its class name.",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "className" to mapOf(
                                    "type" to "string",
                                    "description" to "The name of the class to search for, e.g., 'OrdrInqrListResponse'"
                                )
                            ),
                            "required" to listOf("className")
                        )
                    )
                )
            ),
            "tool_choice" to "auto",
            "temperature" to 0.1, // 가장 중요한 온도 설정
            "stream" to false
        )

        var successCount = 0
        var silentFailureCount = 0
        var exceptionCount = 0
        val totalRuns = 5

        println("=== Starting vLLM Tool Calling Stability Test ===")
        println("Target URL: $serverUrl")
        println("Temperature: 0.1")
        println("-------------------------------------------------")

        for (i in 1..totalRuns) {
            println("--- Run $i ---")
            try {
                val url = URL(serverUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val payload = gson.toJson(requestBody)
                OutputStreamWriter(conn.outputStream).use { it.write(payload) }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val responseJson = InputStreamReader(conn.inputStream).use { it.readText() }
                    val responseMap = gson.fromJson(responseJson, Map::class.java)
                    
                    val choices = responseMap["choices"] as? List<*>
                    val firstChoice = choices?.firstOrNull() as? Map<*, *>
                    val message = firstChoice?.get("message") as? Map<*, *>
                    val toolCalls = message?.get("tool_calls") as? List<*>

                    if (toolCalls != null && toolCalls.isNotEmpty()) {
                        val toolCall = toolCalls.first() as? Map<*, *>
                        val function = toolCall?.get("function") as? Map<*, *>
                        val name = function?.get("name") as? String
                        val arguments = function?.get("arguments") as? String

                        println("✅ Tool Call Selected: $name")
                        println("✅ Arguments: $arguments")

                        if (name == "getNodeInfoByName" && arguments != null && arguments.contains("OrdrInqrListResponse")) {
                            successCount++
                        } else {
                            println("❌ Malformed arguments or wrong tool: $arguments")
                        }
                    } else {
                        println("⚠️ Silent Failure: No tool calls returned.")
                        val msgContent = message?.get("content")
                        println("Response Content: " + msgContent)
                        silentFailureCount++
                    }
                } else {
                    println("❌ HTTP Error: $responseCode")
                    InputStreamReader(conn.errorStream ?: conn.inputStream).use { println(it.readText()) }
                    exceptionCount++
                }
            } catch (e: Exception) {
                println("❌ Exception occurred: ${e.message}")
                exceptionCount++
            }
        }

        println("=================================================")
        println("=== Final Result ===")
        println("Success Rate: $successCount / $totalRuns")
        println("Silent Failures: $silentFailureCount")
        println("Exceptions / HTTP Errors: $exceptionCount")
        
        assertTrue(successCount > 0, "Tool calling failed completely.")
    }
}
