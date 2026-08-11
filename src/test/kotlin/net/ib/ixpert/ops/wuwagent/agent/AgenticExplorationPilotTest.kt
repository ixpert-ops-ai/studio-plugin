package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ln
import kotlin.test.assertTrue

class AgenticExplorationPilotTest {

    @Test
    fun testStage2ExplorationTC7() {
        val serverUrl = "http://vllm.ixpertops.cloud/v1/chat/completions"
        val gson = GsonBuilder().create()

        // 1. 그래프 로드
        val file = File("C:/Workspace/graph/project-graph-i/project-graph.json")
        if (!file.exists()) {
            println("Graph file not found!")
            return
        }
        val graph = gson.fromJson(file.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()

        // IDF 계산용 전역 빈도 (클래스명, 메서드명, 주석)
        val dfMap = mutableMapOf<String, Int>()
        val allNodes = graph.files.values
        val totalDocs = allNodes.size.toDouble()

        for (node in allNodes) {
            val tokens = mutableSetOf<String>()
            // 클래스명 토큰
            node.className.split(Regex("(?=[A-Z])|[^a-zA-Z0-9]"))
                .filter { it.length > 2 }.map { it.lowercase() }.forEach { tokens.add(it) }
            
            // 메서드명 토큰
            node.methods.forEach { m ->
                m.name.split(Regex("(?=[A-Z])|[^a-zA-Z0-9]"))
                    .filter { it.length > 2 }.map { it.lowercase() }.forEach { tokens.add(it) }
            }

            // 주석 토큰
            val comments = node.koreanComments.joinToString(" ")
            comments.split(Regex("[\\s\\p{Punct}]+"))
                .filter { it.length > 1 }.forEach { tokens.add(it) }

            tokens.forEach { dfMap[it] = dfMap.getOrDefault(it, 0) + 1 }
        }

        fun searchCodebase(query: String): String {
            val queryTokens = query.split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotEmpty() }.map { it.lowercase() }
            if (queryTokens.isEmpty()) return "[]"

            val scores = mutableListOf<Pair<String, Double>>()
            for (node in allNodes) {
                var score = 0.0
                
                // 클래스명 체크
                val classTokens = node.className.split(Regex("(?=[A-Z])|[^a-zA-Z0-9]")).map { it.lowercase() }
                
                // 주석 체크
                val commentTokens = mutableListOf<String>()
                val comments = node.koreanComments.joinToString(" ")
                commentTokens.addAll(comments.split(Regex("[\\s\\p{Punct}]+")))

                for (qt in queryTokens) {
                    val df = dfMap[qt] ?: continue
                    val idf = ln(totalDocs / df)

                    // 클래스명 매치 (높은 가중치)
                    if (classTokens.contains(qt)) score += idf * 2.0
                    // 주석 매치
                    if (commentTokens.any { it.contains(qt, ignoreCase = true) }) score += idf * 1.0
                }

                if (score > 0) {
                    scores.add(Pair(node.className, score))
                }
            }

            val top10 = scores.sortedByDescending { it.second }.take(10)
            val resultList = top10.map { (className, score) ->
                val node = graph.files.values.find { it.className == className }
                mapOf(
                    "className" to className,
                    "type" to (node?.fileType?.name ?: "Unknown"),
                    "score" to String.format("%.2f", score),
                    "comment" to (node?.koreanComments?.joinToString(" ")?.take(50) ?: "")
                )
            }
            return gson.toJson(resultList)
        }

        fun getNodeInfoByName(className: String): String {
            val node = graph.files.values.find { it.className == className } ?: return "Node not found."
            val info = mapOf(
                "className" to node.className,
                "type" to node.fileType.name,
                "comment" to node.koreanComments.joinToString(" "),
                "methods" to node.methods.map { m ->
                    mapOf("name" to m.name, "returnType" to m.returnType)
                }
            )
            return gson.toJson(info)
        }

        fun getRelatedNodes(className: String): String {
            val entry = graph.files.entries.find { it.value.className == className }
            if (entry == null) return "Node not found."
            
            val nodeKey = entry.key
            val uses = mutableListOf<String>()
            val calledBy = mutableListOf<String>()
            
            // uses (엣지 방향: node -> target)
            val outgoing = graph.relationships.filter { it.source == nodeKey }
            outgoing.forEach { 
                val targetName = graph.files[it.target]?.className ?: it.target
                uses.add(it.type.name + " -> " + targetName) 
            }
            
            // called by (엣지 방향: target -> node)
            val incoming = graph.relationships.filter { it.target == nodeKey }
            incoming.forEach { 
                val sourceName = graph.files[it.source]?.className ?: it.source
                calledBy.add(it.type.name + " <- " + sourceName) 
            }
            
            val result = mapOf(
                "outgoingEdges" to uses.take(15), // Truncate to 15
                "incomingEdges" to calledBy.take(15) // Truncate to 15
            )
            return gson.toJson(result)
        }

        val tools = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "searchCodebase",
                    "description" to "Search the codebase using BM25/IDF scoring. Returns up to 10 most relevant classes.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "query" to mapOf("type" to "string", "description" to "Search keywords (e.g., '주문 취소 배송')")
                        ),
                        "required" to listOf("query")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "getNodeInfoByName",
                    "description" to "Retrieve detailed information about a specific class (type, comment, methods).",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "className" to mapOf("type" to "string", "description" to "Exact class name")
                        ),
                        "required" to listOf("className")
                    )
                )
            ),
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "getRelatedNodes",
                    "description" to "Retrieve dependencies and usages of a class (incoming/outgoing edges).",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "className" to mapOf("type" to "string", "description" to "Exact class name")
                        ),
                        "required" to listOf("className")
                    )
                )
            ),
              mapOf(
                  "type" to "function",
                  "function" to mapOf(
                      "name" to "submitAnswer",
                      "description" to "Call this tool when you have found the target class that implements the logic. If you are not absolutely certain due to lack of method bodies, you may submit up to 3 ranked candidates.",
                      "parameters" to mapOf(
                          "type" to "object",
                          "properties" to mapOf(
                              "candidates" to mapOf(
                                  "type" to "array",
                                  "items" to mapOf("type" to "string"),
                                  "description" to "A ranked list of up to 3 candidate class names, from most likely to least likely."
                              ),
                              "reasoning" to mapOf("type" to "string", "description" to "For each candidate, explicitly state which core conditions of the user requirement are fulfilled and which are uncertain based on the circumstantial evidence.")
                          ),
                          "required" to listOf("candidates", "reasoning")
                      )
                  )
              )
        )

        val tc8Prompt = "요구사항: '마이페이지의 1:1 문의 내역이나 상품 Q&A 내역에서, 고객이 작성한 문의글 중 \"주문\"과 연동된 문의인 경우, 목록이나 상세 화면에서 해당 주문의 현재 배송 상태(결제완료, 배송중, 배송완료 등)가 함께 표시되도록 기능 개선.'\n이 로직(응답 데이터 모델 등)을 담당하는 핵심 클래스를 찾아내시오. 도구를 사용하여 탐색하고, 확신이 들면 submitAnswer를 호출하시오."

        val runs = 5
        var totalReach = 0
        var totalSubmit = 0
        
        for (run in 1..runs) {
            println("==================================================")
            println("🚀 Starting Run $run for TC8 (Agentic Exploration)")
            println("==================================================")
            
            val messages: MutableList<Map<String, Any>> = mutableListOf(
                mapOf("role" to "system", "content" to "You are an expert software architect. You can explore the codebase using tools. Find the target class based on the user's abstract request. 하나의 요건에는 여러 도메인/기능 키워드가 섞여 있을 수 있으니, 한 경로가 막다른 길이면 다른 키워드를 시작점(Seed)으로 되돌아가 재탐색하라. Do not guess, use tools to verify.\n\nIMPORTANT STRUCTURAL LIMITATION:\nThe codebase graph you are exploring ONLY contains class names, method signatures, comments, and relationships (edges). It DOES NOT contain method bodies (source code) or Enum constants. Do not waste time trying to search for specific implementation details inside method bodies or enum values, as they are physically unavailable.\n\nVERIFICATION PROTOCOL:\nBecause you lack method bodies, you might not be able to definitively narrow down to a single perfect class. Do NOT force a single answer if you are not absolutely certain. Instead, you must find up to 3 of the most likely candidates and rank them. For EACH candidate, you must explicitly break down the user requirement into core conditions and state what is FULFILLED and what is UNCERTAIN based ONLY on the circumstantial evidence (comments, edges). Submit these candidates using the submitAnswer tool.\n\nIMPORTANT: Before calling a tool, briefly describe in one or two sentences how you view the current situation and why you are choosing your next action in the response content. Do not output tool calls immediately without writing your thoughts first."),
                mapOf("role" to "user", "content" to tc8Prompt)
            )

            var isAnswered = false
            var isReached = false
            var turnCount = 0
            val maxTurns = 25
            var previousToolSignature = ""

            while (!isAnswered && turnCount < maxTurns) {
                turnCount++
                println("\n--- Turn " + turnCount + " ---")

                val requestBody = mapOf(
                    "model" to "Qwen/Qwen3.6-35B-A3B-FP8",
                    "messages" to messages,
                    "tools" to tools,
                    "tool_choice" to "auto",
                    "temperature" to 0.1,
                    "enable_thinking" to true
                )

                try {
                    val url = URL(serverUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true

                    OutputStreamWriter(conn.outputStream).use { it.write(gson.toJson(requestBody)) }

                    if (conn.responseCode == 200) {
                        val responseJson = InputStreamReader(conn.inputStream).use { it.readText() }
                        val responseMap = gson.fromJson(responseJson, Map::class.java)
                        val choices = responseMap["choices"] as? List<*>
                        val firstChoice = choices?.firstOrNull() as? Map<*, *>
                        val message = firstChoice?.get("message") as? Map<String, Any>

                        if (message == null) {
                            println("❌ Failed to parse message.")
                            break
                        }

                        val content = message["content"] as? String
                        if (!content.isNullOrEmpty()) {
                            println("🧠 Agent reasoning: \n$content")
                            if (content.contains("OrdrInqrListResponse")) isReached = true
                        }

                        val toolCalls = message["tool_calls"] as? List<Map<String, Any>>
                        
                        if (toolCalls != null && toolCalls.isNotEmpty()) {
                            val toolCall = toolCalls.first()
                            val function = toolCall["function"] as Map<String, Any>
                            val name = function["name"] as String
                            val arguments = function["arguments"] as String

                            println("🤖 Agent calls: " + name + "(" + arguments + ")")

                            // 무한 루프 체크
                            val currentSignature = name + ":" + arguments
                            if (currentSignature == previousToolSignature) {
                                println("⚠️ Exact duplicate tool call detected! Breaking loop.")
                                break
                            }
                            previousToolSignature = currentSignature

                            messages.add(message) // Add assistant message

                            if (name == "submitAnswer") {
                                println("🎉 Agent submitted answer! Reasoning: " + arguments)
                                if (arguments.contains("OrdrInqrListResponse")) {
                                    totalSubmit++
                                    println("✅ CORRECT ANSWER!")
                                } else {
                                    println("❌ INCORRECT ANSWER.")
                                }
                                isAnswered = true
                            } else {
                                // 도구 실행
                                val argsMap = try { gson.fromJson(arguments, Map::class.java) as Map<*, *> } catch (e: Exception) { emptyMap<Any, Any>() }
                                val toolResult = when (name) {
                                    "searchCodebase" -> searchCodebase(argsMap["query"] as? String ?: "")
                                    "getNodeInfoByName" -> getNodeInfoByName(argsMap["className"] as? String ?: "")
                                    "getRelatedNodes" -> getRelatedNodes(argsMap["className"] as? String ?: "")
                                    else -> "Unknown tool."
                                }

                                if (toolResult.contains("OrdrInqrListResponse")) isReached = true

                                println("🛠️ Tool Result length: " + toolResult.length + " chars")
                                
                                val toolMessage: MutableMap<String, Any> = mutableMapOf(
                                    "role" to "tool",
                                    "name" to name,
                                    "content" to toolResult
                                )
                                // tool_call_id is required by OpenAI spec if present
                                val toolCallId = toolCall["id"] as? String
                                if (toolCallId != null) {
                                    toolMessage["tool_call_id"] = toolCallId
                                }
                                
                                messages.add(toolMessage)
                            }
                        } else {
                            val content = message["content"] as? String
                            println("🤖 Agent answers directly: " + content)
                            messages.add(message)
                            
                            if (content?.contains("OrdrInqrListResponse") == true) {
                                println("🎉 Agent found the answer directly!")
                                totalSubmit++
                            }
                            isAnswered = true
                        }
                    } else {
                        println("❌ HTTP Error: " + conn.responseCode)
                        InputStreamReader(conn.errorStream ?: conn.inputStream).use { println(it.readText()) }
                        break
                    }
                } catch (e: Exception) {
                    println("❌ Exception: " + e.message)
                    e.printStackTrace()
                    break
                }
            }

            if (!isAnswered && turnCount >= maxTurns) {
                println("⚠️ Reached max turns (25). Agent failed to find the answer.")
            }
            
            if (isReached) {
                totalReach++
                println("📍 REACHED GT in this run.")
            } else {
                println("🙈 FAILED TO REACH GT in this run.")
            }
        }
        
        println("==================================================")
        println("=== Final Agentic Pilot Result for TC8 ===")
        println("Reach Rate: " + totalReach + " / " + runs)
        println("Submit (Rank-1/Top-3) Rate: " + totalSubmit + " / " + runs)
    }
}
