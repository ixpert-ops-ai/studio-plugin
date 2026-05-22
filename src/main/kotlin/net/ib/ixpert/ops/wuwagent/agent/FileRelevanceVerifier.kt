package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

class FileRelevanceVerifier(
    private val llmClient: LLMClient,
    private val graph: ProjectGraph,
    private val mdRoot: Path
) {
    private val logger = Logger.getInstance(FileRelevanceVerifier::class.java)
    private val gson = Gson()

    data class VerificationResult(
        val path: String,
        val verdict: String, // REQUIRED, OPTIONAL, UNNECESSARY
        val reason: String
    )
    
    data class BatchResponse(
        val verifications: List<VerificationResult>
    )

    fun verify(userQuery: String, candidates: List<TargetFileSpec>): List<TargetFileSpec> {
        if (candidates.isEmpty()) return candidates

        val fileContexts = candidates.map { spec ->
            spec to buildFileContext(spec.path, graph, mdRoot)
        }

        // 4000 토큰(글자수 8000) 기준으로 Batch 분할
        val batches = splitIntoBatches(fileContexts, 8000)
        
        val allResults = mutableListOf<VerificationResult>()
        
        for (batch in batches) {
            val prompt = buildBatchPrompt(userQuery, batch)
            val systemMessage = """
당신은 코드 수정 필요성을 판단하는 아키텍처 검증자입니다.
절대 규칙:
- DAO/Repository는 프레임워크가 예외를 자동 전파하므로 오류 처리 요구 시 수정 불필요
- 사용자 대면 메시지 출력 요구 시 Controller 또는 View 계층 반드시 포함
            """.trimIndent()
            
            // Ollama client call
            val response = llmClient.chat(
                systemPrompt = systemMessage,
                userCode = prompt
            )
            
            val responseText = response?.message?.content ?: continue
            val parsed = parseBatchResponse(responseText)
            allResults.addAll(parsed)
        }
        
        val keptPaths = allResults.filter { it.verdict != "UNNECESSARY" }.map { it.path }.toSet()
        
        if (keptPaths.isEmpty()) {
            logger.warn("Stage 3: 전체 UNNECESSARY 판정 – Stage 2 결과 유지")
            return candidates
        }
        
        return candidates.filter { it.path in keptPaths }
    }

    private fun splitIntoBatches(contexts: List<Pair<TargetFileSpec, String>>, maxChars: Int): List<List<Pair<TargetFileSpec, String>>> {
        val batches = mutableListOf<List<Pair<TargetFileSpec, String>>>()
        var currentBatch = mutableListOf<Pair<TargetFileSpec, String>>()
        var currentChars = 0
        
        for (item in contexts) {
            val estimated = item.second.length
            if (currentBatch.isNotEmpty() && currentChars + estimated > maxChars) {
                batches.add(currentBatch)
                currentBatch = mutableListOf()
                currentChars = 0
            }
            currentBatch.add(item)
            currentChars += estimated
        }
        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch)
        }
        return batches
    }

    private fun buildBatchPrompt(userQuery: String, batch: List<Pair<TargetFileSpec, String>>): String {
        return buildString {
            appendLine("## 파일 수정 필요성 검증")
            appendLine("### 요구사항")
            appendLine(userQuery)
            appendLine()
            appendLine("### 대상 파일 목록")
            batch.forEachIndexed { index, (spec, context) ->
                appendLine("---")
                appendLine("파일 ${index + 1}: ${spec.path}")
                appendLine("Stage 2 선정 이유: ${spec.description}")
                appendLine("파일 분석 정보:")
                appendLine(context)
            }
            appendLine("---")
            appendLine("### 판정 규칙")
            appendLine("1. 요구사항을 충족하기 위해 이 파일의 코드를 실제로 수정해야 하면 REQUIRED")
            appendLine("2. 수정하면 좋지만 필수는 아니면 OPTIONAL")
            appendLine("3. 이 파일을 수정할 필요가 없으면 UNNECESSARY")
            appendLine()
            appendLine("반드시 아래 JSON 형식으로만 응답하세요. 다른 설명은 출력하지 마세요.")
            appendLine("""
{
  "verifications": [
    {
      "path": "파일경로",
      "verdict": "REQUIRED",
      "reason": "한 줄 근거"
    }
  ]
}
            """.trimIndent())
        }
    }

    private fun parseBatchResponse(response: String): List<VerificationResult> {
        val jsonText = extractJson(response)
        return try {
            val batchRes = gson.fromJson(jsonText, BatchResponse::class.java)
            batchRes?.verifications ?: emptyList()
        } catch (e: JsonSyntaxException) {
            logger.error("Failed to parse batch response: $jsonText", e)
            emptyList()
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf("{")
        val end = text.lastIndexOf("}")
        return if (start != -1 && end != -1 && end >= start) {
            text.substring(start, end + 1)
        } else {
            text
        }
    }

    private fun buildFileContext(path: String, graph: ProjectGraph, mdRoot: Path): String {
        val fileName = extractFileName(path)
        val mdFile = mdRoot.resolve("$fileName.md")
        
        if (mdFile.exists()) {
            return extractMdSections(mdFile, listOf(1, 3, 5))
        }
        
        val node = graph.files[path] ?: return "정보 없음"
        return buildGraphContext(node)
    }

    private fun extractFileName(path: String): String {
        return path.substringAfterLast("/")
    }

    private fun extractMdSections(mdFile: Path, sections: List<Int>): String {
        val content = mdFile.readText()
        val lines = content.lines()
        val result = StringBuilder()
        
        var currentSection = 0
        for (line in lines) {
            if (line.startsWith("## ")) {
                currentSection++
            }
            if (currentSection in sections) {
                result.appendLine(line)
            }
        }
        return result.toString().trim()
    }

    private fun buildGraphContext(node: FileNode): String {
        return buildString {
            appendLine("- fileType: ${node.fileType}, layer: ${node.layer}")
            appendLine("- className: ${node.className}")
            node.superClass?.let { appendLine("- superClass: $it") }
            appendLine("- methods: ${node.methodNames.joinToString(", ")}")
            appendLine("- injections: ${node.injections.joinToString(", ").ifEmpty { "없음" }}")
            appendLine("- dependedBy: ${node.dependedBy.joinToString(", ").ifEmpty { "없음" }}")
            if (node.koreanComments.isNotEmpty()) {
                appendLine("- koreanComments:")
                node.koreanComments.take(5).forEach { appendLine("  - $it") }
            }
        }
    }
}
