package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File

class AgenticContaminationTest {
    
    // Simplistic tokenization logic mirroring LlmSeedSelector.tokenize
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val currentToken = StringBuilder()
        
        fun addCurrent() {
            if (currentToken.isNotEmpty()) {
                val str = currentToken.toString().toLowerCase()
                tokens.add(str)
                currentToken.clear()
            }
        }
        
        for (char in text) {
            when {
                char.isLetterOrDigit() || char in '가'..'힣' -> currentToken.append(char)
                char.isWhitespace() || char in listOf('_', '-', '.', '/') -> addCurrent()
                char.isUpperCase() -> { addCurrent(); currentToken.append(char) }
                else -> addCurrent() // delimiters
            }
        }
        addCurrent()
        return tokens
    }

    @Test
    fun `test precision vs recall with multiple injected keywords`() {
        val file = File("C:/Workspace/graph/project-graph-i/project-graph.json")
        if (!file.exists()) return
        val jsonString = file.readText(Charsets.UTF_8)
        val graph = Gson().fromJson(jsonString, ProjectGraph::class.java).normalizeLegacyCollections()

        val srText = "상품 배송 지연에 따른 부분 취소 및 환불 금액 재계산"
        val qTokens = tokenize(srText).toMutableList()

        val keywords = listOf("상품", "주문", "클레임", "환불")
        val dict = DomainDictionary.load(graph)

        println("--- Injecting Keywords & Prefixes ---")
        keywords.forEach { keyword ->
            println("Injecting keyword: $keyword")
            for (i in 1..5) qTokens.add(keyword)
            
            val prefixes = dict.translate(keyword)
            if (prefixes.isNotEmpty()) {
                println("  -> Prefixes: $prefixes")
                prefixes.forEach { p ->
                    for (i in 1..5) qTokens.add(p)
                }
            }
        }

        // BM25
        val documents = mutableMapOf<String, Pair<String, List<String>>>()
        var totalLength = 0
        
        graph.files.values.forEach { fileObj ->
            val contentBuilder = StringBuilder()
            contentBuilder.append(fileObj.path).append(" ")
            contentBuilder.append(fileObj.className ?: "").append(" ")
            contentBuilder.append(fileObj.packageName ?: "").append(" ")
            fileObj.koreanComments?.forEach { contentBuilder.append(it).append(" ") }
            
            val docTokens = tokenize(contentBuilder.toString())
            val promptStr = "${fileObj.className} (${fileObj.packageName})"
            documents[fileObj.path] = promptStr to docTokens
            totalLength += docTokens.size
        }
        
        val N = documents.size
        val avgdl = if (N > 0) totalLength.toDouble() / N else 1.0
        
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
                    score += idf * (tf * 2.5) / (tf + 1.5 * (0.25 + 0.75 * (docLen / avgdl)))
                }
            }
            pair.first to score
        }.sortedByDescending { it.second }
        
        println("\n--- Top 30 Results ---")
        val top30 = scores.take(30)
        var orderCount = 0
        var claimCount = 0
        
        top30.forEachIndexed { i, pair ->
            val name = pair.first
            if (name.contains(".or.") && !name.contains("claim")) orderCount++
            if (name.contains("claim", ignoreCase = true) || name.contains("clm", ignoreCase = true) || name.contains("rfd", ignoreCase = true)) claimCount++
            println("${i+1}. $name (Score: ${String.format("%.2f", pair.second)})")
        }
        
        println("\n=> Total Order (non-claim) nodes in Top 30: $orderCount")
        println("=> Total Claim/Refund nodes in Top 30: $claimCount")
        
        // GT Rank search for ClaimMgmtServiceImpl
        val gtRank = scores.indexOfFirst { it.first.contains("ClaimMgmtServiceImpl") } + 1
        println("=> GT (ClaimMgmtServiceImpl) Rank: $gtRank")
    }
}
