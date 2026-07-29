package net.ib.ixpert.ops.wuwagent.agent.completeness

import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScoredCandidate
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

class ApcPilotHarnessTest {

    @Test
    fun `find path`() {
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val graph = gson.fromJson(graphFile.readText(Charsets.UTF_8), ProjectGraph::class.java)
        
        val bizId = graph.files.entries.find { it.value.className == "APCMMOpnApiO011BIZ" }?.key
        val demId = graph.files.entries.find { it.value.className == "ACMBTBAPC031DEM" }?.key
        
        println("BIZ ID: $bizId")
        println("DEM ID: $demId")
        
        if (bizId != null && demId != null) {
            val adj = mutableMapOf<String, MutableList<String>>()
            graph.relationships.forEach { rel ->
                adj.getOrPut(rel.source) { mutableListOf() }.add(rel.target)
            }
            
            fun findPath(start: String, end: String, visited: MutableSet<String>, path: MutableList<String>): Boolean {
                if (start == end) return true
                visited.add(start)
                for (neighbor in adj[start] ?: emptyList()) {
                    if (neighbor !in visited) {
                        path.add(neighbor)
                        if (findPath(neighbor, end, visited, path)) return true
                        path.removeAt(path.size - 1)
                    }
                }
                return false
            }
            
            val path = mutableListOf<String>()
            val found = findPath(bizId, demId, mutableSetOf(), path)
            if (found) {
                println("Path found! BIZ -> " + path.map { graph.files[it]?.className ?: it }.joinToString(" -> "))
            } else {
                println("No path found between BIZ and DEM.")
            }
        }
    }

    @Test
    fun `measure compressed node tokens`() {
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val graph = gson.fromJson(graphFile.readText(Charsets.UTF_8), ProjectGraph::class.java)
        
        var totalNodes = 0
        var totalChars = 0
        var sampleNodes = mutableListOf<String>()
        
        graph.files.values.forEach { fileObj ->
            val className = fileObj.className ?: ""
            val packageName = fileObj.packageName ?: ""
            val path = fileObj.path
            
            val compressedRep = if (className.isNotEmpty() && packageName.isNotEmpty()) {
                "$className ($packageName)"
            } else {
                "${path.substringAfterLast('/')} (${path.substringBeforeLast('/', "")})"
            }
            
            totalChars += compressedRep.length
            totalNodes++
            
            if (sampleNodes.size < 5) {
                sampleNodes.add(compressedRep)
            }
        }
        
        val avgChars = totalChars.toDouble() / totalNodes
        // Using average VLLM tokenizer approximation: 1 token ~ 3.5 characters for camelCase and dots
        val estimatedTokensPerNode = avgChars / 3.5
        
        println("=== Token Budget Reverse-Engineering ===")
        println("Total Nodes: $totalNodes")
        println("Average Characters per Compressed Node: $avgChars")
        println("Estimated Tokens per Compressed Node (chars/3.5): $estimatedTokensPerNode")
        
        val tokenLimit = 40960
        val outputBudget = 1500
        val promptOverhead = 2000
        val availableTokensForCandidates = tokenLimit - outputBudget - promptOverhead
        val maxN = (availableTokensForCandidates / estimatedTokensPerNode).toInt()
        
        println("Available Tokens for Candidates: $availableTokensForCandidates")
        println("Max N (Available Tokens / Estimated Tokens per Node): $maxN")
        
        println("\n=== Samples ===")
        sampleNodes.forEach { println(it) }
    }


    fun tokenize(text: String): List<String> {
        val words = text.lowercase().replace(Regex("[^a-z0-9가-힣\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        
        val tokens = mutableListOf<String>()
        for (word in words) {
            if (word.length >= 2) {
                tokens.addAll(word.windowed(2))
            } else {
                tokens.add(word)
            }
        }
        return tokens
    }

    @Test
    fun `runK1Regression`() {
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        if (!graphFile.exists()) return
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val graph = gson.fromJson(graphFile.readText(Charsets.UTF_8), ProjectGraph::class.java)

        val srTextA = "오픈앱카드 온라인결제처리를 위한 '온라인결제정보요청' 송신 API 처리 시, 롯데카드의 경우에만 card_member_id 필수 세팅"
        val targetA = "src/main/java/sc/chn/aps/apc/mm/mm16/biz/APCMMOpnApiO011BIZ.java"

        val srTextB = """
            스마일페이 A2A등록을 위한 전문(APC_F_EBK_00001)을 신전문으로 변경
            신전문에서는 카드번호 대신 카드번호ID로 리턴필요. 네이티브에서 전달받는 세션값도 추가로 리턴 필요( 해당 세션값은 스마일 페이 --> BV1 --> APC로 인증확인 요청을 위해 필요) 
            -현재 A2A 스파일페이 등록 서비스 호출시, 앱<-->앱카드서비스 세션값에 대한 VO정보가 없어 필드 추가 필요  
            SAPCMM0507S02 세션키 생성하여 앱 전달, SAPCMM0507I01 세션키 필드 추가, SAPCMM0507I01에서 세션값을 받은 대상이면 신전문 호출 , 세션값이 없을 경우 구전문 호출 
            1. SAPCMM0507S02 스마일페이사용자정보조회2(단건) -세션생성 
            1-1 APCMMSmpyUsrrCtfInfSVO : sesInfCn 세션정보내용 (String 150) 추가 
            1-2 APCMMSmpySpacdBIZ (selSmpyUsrrCtfInf) - 카드정보 조회 후 세션 생선 - 서비스 리턴 시 osvo 에 sesInfCn 세팅하여 전달 
            2. SAPCMM0507I01 스마일페이카드등록 - 앱-->APC 세션전달 
            2-1 APCMMSmpyCardRgSVO : sesInfCn 세션정보내용 (String 150) 추가 
            2-2 APCMMSmpySpacdBIZ(insSmpyCard) : isvo의  sesInfCn 체크해서 값잇을 경우 신규 전문 호출 없을경우 기존 전문 호출 
        """.trimIndent()
        
        val srTextC = "스마일페이 A2A 등록 서비스 호출 시 세션값 정보를 전송하기 위해 세션정보 필드를 추가하고, 조회 시 세션을 생성하여 등록 시 세션값이 존재하면 신전문을 호출하고 없으면 구전문을 호출하도록 변경"
        val targetBC = "src/main/java/sc/chn/aps/apc/mm/mm05/biz/APCMMSmpySpacdBIZ.java"

        val k1Values = listOf(1.5, 1.0, 0.8)
        
        println("=== K1 Regression Test Matrix ===")
        
        fun runCase(caseName: String, text: String, target: String, usePreFilter: Boolean) {
            val qTokens = tokenize(text)
            // Pre-filter check
            if (usePreFilter) {
                val englishTokens = Regex("[a-zA-Z]{3,}").findAll(text).map { it.value }.toList()
                val matched = graph.files.values.filter { node ->
                    englishTokens.any { node.className.contains(it, ignoreCase = true) }
                }
                if (matched.isNotEmpty() && matched.any { it.path == target }) {
                    println("Case $caseName (Pre-filter): Target MATCHED by Exact/Prefix filter. (BM25 rank skipped)")
                    return
                }
            }

            // Prepare docs
            val documents = mutableMapOf<String, List<String>>()
            var totalLength = 0
            for ((_, fileObj) in graph.files) {
                val contentBuilder = StringBuilder()
                contentBuilder.append(fileObj.className).append(" ")
                contentBuilder.append(fileObj.packageName).append(" ")
                contentBuilder.append(fileObj.localName ?: "").append(" ")
                fileObj.koreanComments?.forEach { contentBuilder.append(it).append(" ") }
                fileObj.demMethods?.forEach { dm ->
                    contentBuilder.append(dm.methodName ?: "").append(" ")
                    contentBuilder.append(dm.localName ?: "").append(" ")
                }
                val docTokens = tokenize(contentBuilder.toString())
                documents[fileObj.path] = docTokens
                totalLength += docTokens.size
            }
            val N = documents.size
            val avgdl = totalLength.toDouble() / N
            val df = mutableMapOf<String, Int>()
            for (q in qTokens) df[q] = documents.values.count { it.contains(q) }

            for (k1 in k1Values) {
                val b = 0.75
                val scores = documents.map { (path, docTokens) ->
                    var score = 0.0
                    val docLen = docTokens.size
                    for (q in qTokens) {
                        val tf = docTokens.count { it == q }
                        if (tf > 0) {
                            val n = df[q] ?: 0
                            val idf = Math.log((N - n + 0.5) / (n + 0.5) + 1.0)
                            score += idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * (docLen / avgdl)))
                        }
                    }
                    path to score
                }.sortedByDescending { it.second }
                
                val rank = scores.indexOfFirst { it.first == target } + 1
                println("Case $caseName [k1=$k1]: Rank = $rank")
                if (k1 == 1.5) {
                    println("  Top 30 Distribution:")
                    scores.take(30).forEachIndexed { index, pair ->
                        println("    ${index + 1}. [${String.format("%.4f", pair.second)}] ${pair.first.substringAfterLast('/')}")
                    }
                    val targetScore = scores.find { it.first == target }?.second ?: 0.0
                    println("  => Target Score: ${String.format("%.4f", targetScore)}")
                }
            }
        }

        runCase("A", srTextA, targetA, usePreFilter = false)
        runCase("B", srTextB, targetBC, usePreFilter = true) // Case B uses pre-filter
        runCase("C", srTextC, targetBC, usePreFilter = false)

        println("\n=== Case C with Stage 0.5 (Scope Selection: mm05) ===")
        val mm05Files = graph.files.values.filter { it.path.contains("/mm/mm05/") }
        val mm05Documents = mutableMapOf<String, Pair<String, List<String>>>()
        var totalLengthMm05 = 0
        mm05Files.forEach { fileObj ->
            val contentBuilder = StringBuilder()
            contentBuilder.append(fileObj.className ?: "").append(" ")
            contentBuilder.append(fileObj.path).append(" ")
            contentBuilder.append(fileObj.localName ?: "").append(" ")
            fileObj.koreanComments?.forEach { contentBuilder.append(it).append(" ") }
            val docTokens = tokenize(contentBuilder.toString())
            val promptStr = "${fileObj.className} (${fileObj.packageName})"
            mm05Documents[fileObj.path] = promptStr to docTokens
            totalLengthMm05 += docTokens.size
        }
        val N_mm05 = mm05Documents.size
        val avgdl_mm05 = if (N_mm05 > 0) totalLengthMm05.toDouble() / N_mm05 else 1.0
        val qTokensC = tokenize(srTextC)
        val df_mm05 = mutableMapOf<String, Int>()
        for (q in qTokensC) df_mm05[q] = mm05Documents.values.count { it.second.contains(q) }

        val scoresMm05 = mm05Documents.map { (path, pair) ->
            var score = 0.0
            val docLen = pair.second.size
            for (q in qTokensC) {
                val tf = pair.second.count { it == q }
                if (tf > 0) {
                    val n = df_mm05[q] ?: 0
                    val idf = Math.log((N_mm05 - n + 0.5) / (n + 0.5) + 1.0)
                    score += idf * (tf * (1.5 + 1)) / (tf + 1.5 * (1 - 0.75 + 0.75 * (docLen / avgdl_mm05)))
                }
            }
            path to score
        }.sortedByDescending { it.second }

        val rankMm05 = scoresMm05.indexOfFirst { it.first == targetBC } + 1
        println("Case C [k1=1.5, Scope=mm05]: Rank = $rankMm05 (Total files in scope: $N_mm05)")
        println("  Top 5 Distribution:")
        scoresMm05.take(5).forEachIndexed { index, pair ->
            println("    ${index + 1}. [${String.format("%.4f", pair.second)}] ${pair.first.substringAfterLast('/')}")
        }
    }

    @Test
    fun `run pilot SR against APC graph`() {
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        if (!graphFile.exists()) {
            println("Graph not found")
            return
        }

        val gson = GsonBuilder().disableHtmlEscaping().create()
        val graph = gson.fromJson(graphFile.readText(Charsets.UTF_8), ProjectGraph::class.java)

        val ollamaClient = object : LLMClient {
            val serverUrl = "https://vllm.ixpertops.cloud/v1/chat/completions"
            val modelName = "Qwen/Qwen3.6-35B-A3B-FP8"

            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? {
                return listOf(modelName)
            }

            override fun chat(
                systemPrompt: String,
                userCode: String,
                maxTokens: Int?,
                onChunk: ((String) -> Unit)?
            ): OllamaChatResponse? {
                try {
                    val messages = listOf(
                        mapOf("role" to "system", "content" to systemPrompt),
                        mapOf("role" to "user", "content" to userCode)
                    )
                    val jsonPayload = GsonBuilder().create().toJson(
                        mapOf(
                            "model" to modelName,
                            "messages" to messages,
                            "stream" to false,
                            "max_tokens" to (maxTokens ?: 1500),
                            "temperature" to 0.0
                        )
                    )

                    val connection = java.net.URL(serverUrl).openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer sk-test")
                    connection.doOutput = true
                    connection.outputStream.write(jsonPayload.toByteArray(Charsets.UTF_8))

                    val responseCode = connection.responseCode
                    if (responseCode != 200) {
                        println("LLM API Error: ${connection.errorStream?.reader()?.readText()}")
                        return null
                    }

                    val responseStr = InputStreamReader(connection.inputStream, StandardCharsets.UTF_8).readText()
                    val responseJson = com.google.gson.JsonParser.parseString(responseStr).asJsonObject
                    val content = responseJson.getAsJsonArray("choices")
                        .get(0).asJsonObject
                        .getAsJsonObject("message")
                        .get("content").asString

                    return OllamaChatResponse(
                        model = modelName,
                        createdAt = "",
                        message = OllamaMessage("assistant", content),
                        done = true
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    return null
                }
            }

            override fun chatWithTools(
                systemPrompt: String,
                messages: List<ChatMessage>,
                maxTokens: Int?,
                tools: List<ToolDefinition>?,
                toolChoice: Any?
            ): ChatCompletionResponse? {
                try {
                    val msgs = mutableListOf(mapOf("role" to "system", "content" to systemPrompt))
                    msgs.addAll(messages.map { mapOf("role" to it.role, "content" to (it.content ?: "")) })
                    
                    val payload = mutableMapOf<String, Any>(
                        "model" to modelName,
                        "messages" to msgs,
                        "stream" to false,
                        "max_tokens" to (maxTokens ?: 1500),
                        "temperature" to 0.0
                    )
                    
                    if (tools != null) {
                        payload["tools"] = tools
                    }
                    if (toolChoice != null) {
                        payload["tool_choice"] = toolChoice
                    }

                    val jsonPayload = GsonBuilder().create().toJson(payload)
                    val connection = java.net.URL(serverUrl).openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer sk-test")
                    connection.doOutput = true
                    connection.outputStream.write(jsonPayload.toByteArray(Charsets.UTF_8))

                    val responseCode = connection.responseCode
                    if (responseCode != 200) {
                        println("LLM API Error: ${connection.errorStream?.reader()?.readText()}")
                        return null
                    }

                    val responseStr = InputStreamReader(connection.inputStream, StandardCharsets.UTF_8).readText()
                    return GsonBuilder().create().fromJson(responseStr, ChatCompletionResponse::class.java)
                } catch (e: Exception) {
                    e.printStackTrace()
                    return null
                }
            }
        }

        val srText = """
            스마일페이 A2A 등록 서비스 호출 시 세션값 정보를 전송하기 위해 세션정보 필드를 추가하고, 조회 시 세션을 생성하여 등록 시 세션값이 존재하면 신전문을 호출하고 없으면 구전문을 호출하도록 변경
        """.trimIndent()
        
        println("====================")
        println("Running Stage 1~3 Pipeline")
        println("====================")

        val result = AdaptiveFileDiscovery.filter(
            primaryReq = srText,
            secondaryReq = "",
            graph = graph,
            client = ollamaClient,
            project = null,
            onProgress = { println(it) }
        )

        println("\n=== Final Predicted Files (Relevance Score Descending) ===")
        val relevantFilesCount = result.metadata?.filteredTo ?: 0
        println("Count: $relevantFilesCount files selected.")
        
        val groundTruth = setOf(
            "APCMMSmpySpacdBIZ.java",
            "APCMMSmpyCardRgSVO.java",
            "APCMMSmpyUsrrCtfInfSVO.java"
        )
        
        result.relevantFiles.sortedByDescending { it.score }.take(30).forEach { candidate ->
            val isHit = groundTruth.any { candidate.path.endsWith(it) }
            val mark = if (isHit) "[O]" else "[X]"
            val sourceStr = if (candidate.discoveryReason == "SEED") "[SOURCE: SEED_BM25]" else "[SOURCE: HOP_EXPANSION]"
            println("$mark [SCORE: ${candidate.score}] $sourceStr ${candidate.path}")
            println("  - via: ${candidate.discoveryReason}, hop: ${candidate.hopDistance}, from: ${candidate.fromPath}")
        }

        val top30Paths = result.relevantFiles.sortedByDescending { it.score }.take(30).map { it.path }
        val truePositives = groundTruth.filter { gt -> top30Paths.any { it.endsWith(gt) } }
        val falsePositives = top30Paths.size - truePositives.size
        val falseNegatives = groundTruth.size - truePositives.size

        val recall = if (groundTruth.isNotEmpty()) truePositives.size.toDouble() / groundTruth.size else 0.0
        val precision = if (top30Paths.isNotEmpty()) truePositives.size.toDouble() / top30Paths.size else 0.0

        println("\n=== Evaluation Results ===")
        println("Ground Truth size: ${groundTruth.size}")
        println("True Positives: ${truePositives.size} $truePositives")
        println("False Negatives: $falseNegatives")
        println("False Positives: $falsePositives")
        println("Recall: ${"%.2f".format(recall * 100)}%")
        println("Precision: ${"%.2f".format(precision * 100)}%")
    }

    @Test
    fun `testHardLimitOnApc`() = kotlinx.coroutines.runBlocking {
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        if (!graphFile.exists()) return@runBlocking
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val graph = gson.fromJson(graphFile.readText(Charsets.UTF_8), ProjectGraph::class.java)
        
        val client = object : LLMClient {
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? = null
            override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?): ChatCompletionResponse? = null
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = null
        }
        val pipeline = net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline(null, client)
        
        var caughtExceptionMessage: String? = null
        try {
            pipeline.analyze("스마일페이 세션 정보 조회", "", graph)
        } catch (e: IllegalArgumentException) {
            caughtExceptionMessage = e.message
        }
        
        println("=== Hard Limit Test ===")
        if (caughtExceptionMessage != null) {
            println("SUCCESS: Caught exception -> $caughtExceptionMessage")
        } else {
            println("FAILED: Expected IllegalArgumentException but none was thrown.")
        }
    }
}
