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
        val verdict: String, // REQUIRED, UNNECESSARY
        val reason: String
    )
    
    private data class FileVerdict(
        val filePath: String?,
        val verdict: String?,
        val reason: String?
    )
    
    private data class BatchResponse(
        val fileVerdicts: List<FileVerdict>?,
        val reasoning: String?
    )
    
    data class VerificationOutput(
        val files: List<TargetFileSpec>,
        val reasoning: String
    )

    fun verify(userQuery: String, candidates: List<TargetFileSpec>, additionalSystemPrompt: String = ""): VerificationOutput {
        if (candidates.isEmpty()) return VerificationOutput(candidates, "")

        // 신규 파일도 검증 대상에 포함
        val targetModifySpecs = candidates

        val systemMessage = """
            당신은 코드 변경 범위 검증자입니다.
            아래 요구사항을 구현하기 위해 수정이 필요한 파일 목록을 검증합니다.
            반드시 제공된 `submit_verification` 도구를 호출하여 결과를 제출하세요.

            ## 역할
            - 후보 파일 목록에서 "수정하지 않아도 요구사항 구현에 아무런 영향이 없는 파일"만 제거하세요.
            - 파일 간의 의존 관계를 반드시 고려하세요.
              예: Service에서 새로운 데이터를 저장해야 한다면, 해당 데이터를 담는 Entity도 수정이 필요합니다.

            ## 판정 원칙
            - 새로운 필드, 상태값, 메서드를 추가해야 요구사항을 구현할 수 있다면, 현재 해당 코드가 없더라도 수정 대상입니다.
            - 제거할 확실한 근거가 없으면 유지하세요.
            - 요구사항의 데이터 흐름(입력 → 처리 → 저장)을 추적하여 판단하세요.
            - reason은 반드시 해당 파일에서 구체적으로 어떤 수정이 필요한지를 포함해야 합니다. 빈 문자열이나 '수정이 필요합니다' 같은 동어반복은 허용되지 않습니다.
            - 중요: JSON 응답 생성 시, reason이나 reasoning 필드 값 내부에 실제 줄바꿈 문자(\n)를 사용하지 마세요. 줄바꿈 대신 띄어쓰기나 마침표를 사용하세요.

            ## 신규 파일 판정 원칙
            - (신규 제안) 정보가 있는 파일은 "새로 만들 필요가 있는가?"를 판단합니다.
            - 다음 중 하나라도 해당하면 UNNECESSARY로 판정하세요:
              · 요구사항이 단일 기능 추가/수정이고, 기존 파일 1~2개 수정으로 구현 가능한 경우
              · 기존 REQUIRED 파일의 수정으로 동일한 목적을 달성할 수 있는 경우
              · 요구사항의 복잡도 대비 과설계인 경우 (단순 기능에 별도 계층 분리 등)
              · 기존 패키지에 동일 역할의 클래스/파일이 이미 존재하는 경우
            - 신규 파일이 REQUIRED가 되려면, 기존 파일에 해당 책임을 추가했을 때 단일 책임 원칙이 명백히 위반되는 경우여야 합니다.
        """.trimIndent()

        val tool = net.ib.ixpert.ops.wuwagent.model.ToolDefinition(
            type = "function",
            function = net.ib.ixpert.ops.wuwagent.model.FunctionDefinition(
                name = "submit_verification",
                description = "파일 목록 검증 결과를 제출합니다.",
                parameters = net.ib.ixpert.ops.wuwagent.model.FunctionParameters(
                    type = "object",
                    properties = mapOf(
                        "fileVerdicts" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "array",
                            description = "각 후보 파일에 대한 판정 결과 목록",
                            items = net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                                type = "object",
                                properties = mapOf(
                                    "filePath" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                                        type = "string",
                                        description = "후보 파일의 전체 경로"
                                    ),
                                    "verdict" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                                        type = "string",
                                        description = "판정 결과 (REQUIRED 또는 UNNECESSARY)",
                                        enum = listOf("REQUIRED", "UNNECESSARY")
                                    ),
                                    "reason" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                                        type = "string",
                                        description = "판정 사유 (REQUIRED인 경우 구체적인 수정 내용)"
                                    )
                                ),
                                required = listOf("filePath", "verdict", "reason")
                            )
                        ),
                        "reasoning" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "string",
                            description = "전체 판정 근거 종합 요약 (반드시 1), 2), 3)과 같이 번호를 매겨서 항목별로 작성할 것)"
                        )
                    ),
                    required = listOf("fileVerdicts", "reasoning")
                )
            )
        )

        val MAX_BATCH_SIZE = 30
        val topFiles = targetModifySpecs.take(MAX_BATCH_SIZE)
        val autoKept = targetModifySpecs.drop(MAX_BATCH_SIZE)

        val finalSystemMessage = if (additionalSystemPrompt.isNotBlank()) {
            "$systemMessage\n\n$additionalSystemPrompt"
        } else systemMessage

        val prompt = buildBatchPrompt(userQuery, topFiles, graph, mdRoot)
        val messages = listOf(net.ib.ixpert.ops.wuwagent.model.ChatMessage(role = "user", content = prompt))
        
        val response = try {
            llmClient.chatWithTools(
                systemPrompt = finalSystemMessage,
                messages = messages,
                maxTokens = 4000,
                tools = listOf(tool),
                toolChoice = mapOf("type" to "function", "function" to mapOf("name" to "submit_verification"))
            )
        } catch (e: Exception) {
            logger.error("Failed to call LLM: ${e.message}")
            null
        }
        
        val verifiedTopFiles = mutableListOf<TargetFileSpec>()
        val toolCall = response?.toolCalls?.firstOrNull { it.function.name == "submit_verification" }
        var argsStr = toolCall?.function?.arguments
        var finalReasoning = ""
        
        if (argsStr == null) {
            val content = response?.content ?: ""
            val match = Regex("```(?:json)?\\s*(\\{.*\\})\\s*```", RegexOption.DOT_MATCHES_ALL).find(content)
            if (match != null) {
                argsStr = match.groupValues[1]
            } else if (content.trim().startsWith("{")) {
                argsStr = content
            }
        }
        
        if (argsStr != null) {
            try {
                val cleanedStr = argsStr
                    .replace(Regex("^```(?:json)?\\s*"), "")
                    .replace(Regex("\\s*```$"), "")
                    .trim()
                val res = gson.fromJson(cleanedStr, BatchResponse::class.java)
                val rawVerdicts = res.fileVerdicts ?: emptyList()
                
                // 후처리 로직: reason이 비어있거나 너무 짧으면 UNNECESSARY로 강등
                val verdicts = rawVerdicts.map { verdict ->
                    val reasonLen = verdict.reason?.trim()?.length ?: 0
                    if (verdict.verdict == "REQUIRED" && reasonLen < 10) {
                        verdict.copy(verdict = "UNNECESSARY", reason = "판정 근거 미제시로 제외")
                    } else {
                        verdict
                    }
                }
                
                finalReasoning = res.reasoning ?: ""
                
                topFiles.forEachIndexed { index, spec ->
                    val specFileName = spec.path.substringAfterLast("/")
                    val verdictObj = verdicts.find { it.filePath == spec.path }
                        ?: verdicts.find { it.filePath != null && spec.path.endsWith(it.filePath.substringAfterLast("/")) }
                        ?: verdicts.find { it.filePath != null && specFileName.startsWith(it.filePath.substringAfterLast("/")) }
                    
                    // 만약 LLM이 결과를 아예 안 주면 REQUIRED로 폴백, 일부 파일만 줬다면 누락된 파일은 UNNECESSARY로 간주
                    val rawVerdict = if (verdicts.isEmpty()) "REQUIRED" else (verdictObj?.verdict ?: "UNNECESSARY")
                    val normalizedVerdict = rawVerdict.replace("*", "").trim().uppercase()
                    
                    if (normalizedVerdict != "UNNECESSARY") {
                        val reason = verdictObj?.reason ?: "Stage 3 검증 통과 (명시적 사유 없음)"
                        verifiedTopFiles.add(spec.copy(description = reason))
                    }
                }
                
                // [FIX] topFiles에 없었으나 LLM이 새로 추가한 파일(예: Completeness Guard 피드백으로 인한 누락 파일) 처리
                verdicts.forEach { verdictObj ->
                    if (verdictObj.verdict == "REQUIRED" && verdictObj.filePath != null) {
                        val isAlreadyIncluded = verifiedTopFiles.any { 
                            it.path == verdictObj.filePath ||
                            it.path.endsWith("/" + verdictObj.filePath.substringAfterLast("/"))
                        }
                        if (!isAlreadyIncluded) {
                            // [FIX] 환각 방지: 메타그래프에 실제 존재하는 노드인지 검증
                            val className = verdictObj.filePath.substringAfterLast("/").substringBeforeLast(".")
                            val existsInGraph = graph.files.containsKey(verdictObj.filePath) || 
                                              graph.files.values.any { it.className.equals(className, ignoreCase = true) }
                                              
                            if (existsInGraph) {
                                verifiedTopFiles.add(TargetFileSpec(
                                    order = verifiedTopFiles.size + 1,
                                    path = verdictObj.filePath,
                                    type = "MODIFY", 
                                    description = verdictObj.reason ?: "Added by Guard feedback"
                                ))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to parse LLM response: $argsStr", e)
                // 파싱 완전 실패 시 (Fail-Open): 
                // 기존 파일(MODIFY)은 보수적으로 살려두되, 
                // 검증되지 않은 신규 제안 파일(CREATE)은 위험(오염) 소지가 크므로 철저히 버립니다.
                verifiedTopFiles.addAll(topFiles.filter { it.type != "CREATE" && it.type != "신규" })
            }
        } else {
            logger.warn("No valid tool call found, keeping all files as fallback.")
            verifiedTopFiles.addAll(topFiles)
        }
        
        if (verifiedTopFiles.isEmpty()) {
            logger.warn("Stage 3: 전체 UNNECESSARY 판정 – 안전장치로 상위 3개 유지 (기존 파일 한정)")
            verifiedTopFiles.addAll(topFiles.filter { it.type != "CREATE" && it.type != "신규" }.take(3))
        }
        
        return VerificationOutput(verifiedTopFiles + autoKept, finalReasoning)
    }

    private fun buildBatchPrompt(userQuery: String, specs: List<TargetFileSpec>, graph: ProjectGraph, mdRoot: Path): String {
        return buildString {
            appendLine("## 요구사항")
            appendLine(userQuery)
            appendLine()
            appendLine("## 후보 파일 목록")
            specs.forEach { spec ->
                appendLine("- ${spec.path}")
                appendLine("--- 정보 ---")
                if (spec.type == "CREATE" || spec.type == "신규") {
                    appendLine("- (신규 제안)")
                    appendLine(spec.description)
                } else {
                    val context = buildFileContext(spec.path, graph, mdRoot)
                    appendLine(context)
                }
                appendLine("-------------")
            }
            appendLine()
            appendLine("## 지시사항")
            appendLine("위 후보 파일 목록 각각에 대해 판정 결과(REQUIRED 또는 UNNECESSARY)와 사유를 반환하세요.")
            appendLine("전체 판정 근거 요약(reasoning)을 작성할 때는 가독성을 위해 반드시 '1) ...', '2) ...' 와 같이 번호를 매겨서 작성하세요.")
        }
    }

    private fun buildFileContext(path: String, graph: ProjectGraph, mdRoot: Path): String {
        val fileName = extractFileName(path)
        val mdFile = mdRoot.resolve("$fileName.md")
        val node = graph.files[path]
        
        if (mdFile.exists()) {
            return extractMdSections(mdFile, listOf(1, 3, 5), node)
        }
        
        if (node == null) return "정보 없음"
        return buildGraphContext(node)
    }

    private fun extractFileName(path: String): String {
        return path.substringAfterLast("/")
    }

    private fun extractMdSections(mdFile: Path, sections: List<Int>, node: FileNode?): String {
        val content = mdFile.readText()
        val lines = content.lines()
        val result = StringBuilder()
        
        var currentSection = 0
        var linesInSection = 0
        for (line in lines) {
            if (line.startsWith("## ")) {
                currentSection++
                linesInSection = 0
            }
            if (currentSection in sections) {
                if (currentSection == 3 && linesInSection > 10) {
                    if (linesInSection == 11) {
                        val methods = node?.methodNames ?: emptyList()
                        val getterSetterCount = methods.count { it.startsWith("get") || it.startsWith("set") || it.startsWith("is") }
                        val ratio = if (methods.isNotEmpty()) getterSetterCount.toDouble() / methods.size else 0.0
                        
                        if (ratio >= 0.7) {
                            result.appendLine("... (이하 생략 - 메서드 다수 존재, 주로 getter/setter로 추정)")
                        } else {
                            result.appendLine("... (이하 생략 - 메서드 다수 존재)")
                        }
                    }
                } else {
                    result.appendLine(line)
                }
                linesInSection++
            }
        }
        return result.toString().trim()
    }

    private fun summarizeMethods(methods: List<String>): String {
        if (methods.size <= 20) return "methods: ${methods.joinToString(", ")}"
        
        val getterSetterCount = methods.count { 
            it.startsWith("get") || it.startsWith("set") || it.startsWith("is") 
        }
        val ratio = getterSetterCount.toDouble() / methods.size
        
        return if (ratio > 0.7) {
            "methods: ${methods.size}개 (주로 getter/setter, 비즈니스 메서드 ${methods.size - getterSetterCount}개)"
        } else {
            val keyMethods = methods.filter { !it.startsWith("get") && !it.startsWith("set") && !it.startsWith("is") }
            "methods: ${keyMethods.joinToString(", ")} 외 getter/setter ${getterSetterCount}개"
        }
    }

    private fun buildGraphContext(node: FileNode): String {
        return buildString {
            appendLine("- fileType: ${node.fileType}, layer: ${node.layer}")
            appendLine("- className: ${node.className}")
            node.superClass?.let { appendLine("- superClass: $it") }
            appendLine("- ${summarizeMethods(node.methodNames)}")
            appendLine("- injections: ${node.injections.joinToString(", ").ifEmpty { "없음" }}")
            appendLine("- dependedBy: ${node.dependedBy.joinToString(", ").ifEmpty { "없음" }}")
            if (node.koreanComments.isNotEmpty()) {
                appendLine("- koreanComments:")
                node.koreanComments.take(5).forEach { appendLine("  - $it") }
            }
        }
    }
}
