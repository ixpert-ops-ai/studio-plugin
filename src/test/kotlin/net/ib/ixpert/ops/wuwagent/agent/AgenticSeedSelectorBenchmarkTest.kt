package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.assertTrue

class AgenticSeedSelectorBenchmarkTest {

    class VllmClient : LLMClient {
        private val gson = Gson()
        private val serverUrl = "http://vllm.ixpertops.cloud/v1/chat/completions"
        private val modelName = "Qwen/Qwen3.6-35B-A3B-FP8"
        
        var callCount = 0
        
        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse? {
            callCount++
            val messagesList = listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userCode)
            )
            val requestBody = mapOf(
                "model" to modelName,
                "messages" to messagesList,
                "stream" to false,
                "temperature" to 0.0,
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
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return null
        }
        
        override fun chatWithTools(systemPrompt: String, messages: List<net.ib.ixpert.ops.wuwagent.model.ChatMessage>, maxTokens: Int?, tools: List<net.ib.ixpert.ops.wuwagent.model.ToolDefinition>?, toolChoice: Any?, temperature: Double?): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
            callCount++
            val userMsg = messages.lastOrNull { it.role == "user" }?.content ?: ""
            
            return net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse(
                id = "mock-id",
                choices = listOf(
                    net.ib.ixpert.ops.wuwagent.model.ChatChoice(
                        index = 0,
                        message = net.ib.ixpert.ops.wuwagent.model.ChatMessage(
                            role = "assistant",
                            content = "{}",
                            toolCalls = listOf(
                                net.ib.ixpert.ops.wuwagent.model.ToolCall(
                                    id = "dummy",
                                    type = "function",
                                    function = net.ib.ixpert.ops.wuwagent.model.ToolCallFunction(
                                        name = "submit_seeds",
                                        arguments = """{"seedClasses":[], "changeIntent":"MODIFY", "layerHint":[], "frontendRelevant":false, "reasoning":"MOCK_CANDIDATES: ${userMsg.replace("\"", "\\\"").replace("\n", " ")}"}"""
                                    )
                                )
                            )
                        ),
                        finishReason = "tool_calls"
                    )
                )
            )
        }
        
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
    }

    data class TestCase(val type: String, val srText: String, val targetKeyword: String, val targetGtClass: String)

    @Test
    fun `benchmark Agentic Iterative Search`() {
        val file = File("C:/Workspace/graph/project-graph-i/project-graph.json")
        if (!file.exists()) return
        val jsonString = file.readText(Charsets.UTF_8)
        val graph = Gson().fromJson(jsonString, ProjectGraph::class.java).normalizeLegacyCollections()

        fun b64(s: String) = String(java.util.Base64.getDecoder().decode(s), Charsets.UTF_8)
        val testCases = listOf(
            TestCase("Easy", b64("7KO866y4IOqysOygnCDsoIQg66y07Ya17J6lIOyeheq4iCDquLDrlawg7Jew7J6l"), "주문", "OrderProcessBaseRequest"),
            TestCase("Easy", b64("66eI7LyA7YyFIOq4sO2ZjeCghCDsv6Dtj7Ag67Cc6riJIOygnO2VnCDsobDqsbQg67OA6rK9"), "마케팅", "CponInfoRequest"),
            TestCase("Easy", b64("7KCE7IucIOuplO2duCDrsLDrhIgg66Gk66eBIOyGjeuPhCDslrTrk5zrr7zsl5DshJwg7ISk7KCV"), "전시", "BnnrMngtServiceImpl"),
            TestCase("Easy", b64("7Zy066m0IO2ajOybkCDsoITtmZgg67CSIOyInOuCtCDsnbTrqZTsnbwg67Cc7IahIOuwsOy5mA=="), "회원", "MbrInfoServiceImpl"),
            
            TestCase("Trap", b64("7KO866y47KCc7J6R7Jes67aAIOyDge2SiCDshoHsnbEg7Lu065+8IOy2lOqwgA=="), "상품", "PdSaveServiceImpl"),
            TestCase("Trap", b64("66mU7J24IO2ZlOuptCDsg4Hri6gg7J6l67C06rWs64uIIOuLtOq0biDsg4Htkogg6rCc7IiYIO2RnOyLnCDrsLDsp4Ag64W47Lac"), "전시", "GnbMenuMstServiceImpl"),
            TestCase("Trap", b64("7IOB7ZKIIOuwsOyGgSDsp4Dsl7DsnoAg65Sw66W4IOu2gOu2hCDstajshYwg67CSIO2ZmOu2iCDquIjsl6Eg7J6s6rOE7IKw"), "클레임", "ClaimMgmtServiceImpl"),
            TestCase("Trap", b64("7Yq57KCVIOyehOyngeybkCDtmozsm5Ag7IKs64K065Oc66a8IOyjvOusuCDrgrTsl60g7KGw7ZqMIOyhsOqxtCDstpTqsIA="), "주문", "OrdrInqrListResponse")
        )

        println("\n=======================================================")
        println("🚀 Agentic Iterative Search Benchmark (8 Cases, 5 Iters)")
        println("=======================================================\n")
        
        var totalScore = 0
        var easyScore = 0
        var trapScore = 0

        for (tc in testCases) {
            println("\n--- [${tc.type}] SR: \"${tc.srText}\" ---")
            var matchCount = 0
            val iterations = 1
            
            for (i in 1..iterations) {
                
                println("  [Iter $i] Starting Selection...")
                println("  [Iter 1] Starting Pure BM25 Baseline Selection...")
                val allBackend = graph.files.values
                val allFrontend = graph.resourceNodes.filter { 
                    it.path.endsWith(".jsp") || it.path.endsWith(".html") || 
                    it.path.endsWith(".js") || it.path.endsWith(".vue") || it.path.endsWith(".tsx") 
                }
                
                fun tokenize(text: String): List<String> {
                    val words = text.lowercase().replace(Regex("[^a-z0-9\\uAC00-\\uD7A3\\s]"), " ")
                        .split(Regex("\\s+"))
                        .filter { it.isNotBlank() }
                    val t = mutableListOf<String>()
                    for (w in words) {
                        if (w.length >= 2) t.addAll(w.windowed(2)) else t.add(w)
                    }
                    return t
                }
                
                val documents = mutableMapOf<String, Pair<String, List<String>>>()
                var totalLength = 0
                allBackend.forEach { fileObj ->
                    val contentBuilder = StringBuilder()
                    contentBuilder.append(fileObj.className ?: "").append(" ")
                    contentBuilder.append(fileObj.path).append(" ")
                    contentBuilder.append(fileObj.localName ?: "").append(" ")
                    fileObj.koreanComments?.forEach { contentBuilder.append(it).append(" ") }
                    fileObj.demMethods?.forEach { dm ->
                        contentBuilder.append(dm.methodName ?: "").append(" ")
                        contentBuilder.append(dm.localName ?: "").append(" ")
                    }
                    val docTokens = tokenize(contentBuilder.toString())
                    val promptStr = "${fileObj.className} (${fileObj.packageName})"
                    documents[fileObj.path] = promptStr to docTokens
                    totalLength += docTokens.size
                }
                allFrontend.forEach { resourceNode ->
                    val docTokens = tokenize(resourceNode.path)
                    val promptStr = "${resourceNode.path.substringAfterLast('/')} (${resourceNode.path.substringBeforeLast('/', "")})"
                    documents[resourceNode.path] = promptStr to docTokens
                    totalLength += docTokens.size
                }
                
                val N = documents.size
                val avgdl = if (N > 0) totalLength.toDouble() / N else 1.0
                val k1 = 1.5
                val b_param = 0.75
                
                val qTokens = tokenize(tc.srText)
                
                val df = mutableMapOf<String, Int>()
                for (q in qTokens) df[q] = documents.values.count { it.second.contains(q) }
                
                val scores = documents.map { (_, pair) ->
                    var score = 0.0
                    val docLen = pair.second.size
                    for (q in qTokens) {
                        val tf = pair.second.count { it == q }
                        if (tf > 0) {
                            val n = df[q] ?: 0
                            val idf = Math.log((N - n + 0.5) / (n + 0.5) + 1.0)
                            score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b_param + b_param * (docLen / avgdl)))
                        }
                    }
                    pair.first to score
                }.sortedByDescending { it.second }
                
                val rankedPrompts = scores.map { it.first }
                val targetRank = rankedPrompts.indexOfFirst { it.contains(tc.targetGtClass, ignoreCase = true) } + 1
                
                val hasGt = targetRank in 1..30
                if (hasGt) matchCount++
                
                if (!hasGt) {
                    val gtDoc = documents.entries.find { it.value.first.contains(tc.targetGtClass, ignoreCase = true) }
                    if (gtDoc != null) {
                        val gtTokens = gtDoc.value.second
                        println("    [DEBUG ${tc.type}] Target GT found in documents: ${gtDoc.key}")
                        val koreanTokens = gtTokens.filter { it.matches(Regex(".*[\\uAC00-\\uD7A3]+.*")) }
                        println("    [DEBUG ${tc.type}] Target GT Korean tokens: ${koreanTokens.take(20)}")
                        val intersection = qTokens.filter { gtTokens.contains(it) }
                        println("    [DEBUG ${tc.type}] Token Intersection: ${intersection.map { t -> t.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) } }}")
                        val gtScore = scores.find { it.first == gtDoc.value.first }?.second
                        println("    [DEBUG ${tc.type}] Target GT BM25 Score: $gtScore")
                    } else {
                        println("    [DEBUG ${tc.type}] Target GT NOT FOUND IN DOCUMENTS!")
                    }
                }
                
                println("    => Target GT (${tc.targetGtClass}) Rank: ${if (targetRank > 0) targetRank else "Not Found"}")
                
                if (hasGt) {
                    val finalContext = rankedPrompts.take(30).joinToString(", ")
                    val isClaimTrap = tc.targetGtClass.contains("ClaimMgmtServiceImpl")
                    if (isClaimTrap) {
                        val orderNodes = Regex("or\\.[a-zA-Z0-9_]+").findAll(finalContext).count { !it.value.contains("claim", ignoreCase = true) }
                        println("    => Claim Trap Contamination (Order nodes count in Top 30): $orderNodes")
                    }
                }
            }
            println("=> Result for [${tc.type}]: $matchCount / 1\n")
            if (matchCount >= 1) {
                totalScore++
                if (tc.type == "Easy") easyScore++ else trapScore++
            }
        }
        
        println("=======================================================")
        println("🎯 Pure BM25 Baseline Results")
        println("Easy Cases: $easyScore / 4")
        println("Trap Cases: $trapScore / 4")
        println("Total Score: $totalScore / 8")
        println("=======================================================\n")
    }
}
