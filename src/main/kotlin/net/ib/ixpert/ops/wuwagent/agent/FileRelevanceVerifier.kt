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

    /** 출력 축소 형식(V2): 후보 번호만 쓰고, 제거 대상은 사유를 쓰지 않는다. */
    private data class RequiredFile(
        val n: Int?,
        val reason: String?
    )

    private data class CompactResponse(
        val requiredFiles: List<RequiredFile>?,
        val removedFiles: List<Int>?,
        val reasoning: String?
    )
    
    data class VerificationOutput(
        val files: List<TargetFileSpec>,
        val reasoning: String,
        val removed: List<String> = emptyList(),   // Stage 3에서 제거된 후보 경로(사유는 받지 않는다)
        val unjudged: List<String> = emptyList(),  // 제거·유지 어느 쪽에도 없어 유지된 후보 경로(판정 누락)
        val candidatePaths: List<String> = emptyList(),  // 이번 호출의 번호표(1번부터의 후보 경로 순서)
        val removedReasons: Map<String, String> = emptyMap(),  // 제거 사유(모델이 준 것만. 출력 축소 형식은 근거 미제시 제거에만 있다)
        /** 검증 응답에 도구 호출이 없어 후보 전체를 그대로 유지했다(대체 경로). */
        val usedFallback: Boolean = false
    ) {
        /** 화면에 보일 요약: 모델이 인용한 후보 번호를 파일명으로 바꾼다(판정·저장 값은 그대로). */
        fun displayReasoning(): CitationResult = substituteCandidateNumbers(reasoning, candidatePaths)

        /** 결과 요약에 보일 안내 줄. keptPaths에 남은 파일은 제외 목록에서 뺀다. 둘 다 비면 빈 목록. */
        fun noticeLines(keptPaths: Set<String> = emptySet()): List<String> {
            val lines = mutableListOf<String>()
            fun names(paths: List<String>): String {
                val n = paths.map { it.substringAfterLast('/') }
                return n.take(NOTICE_NAME_LIMIT).joinToString(", ") + if (n.size > NOTICE_NAME_LIMIT) " 외 ${n.size - NOTICE_NAME_LIMIT}개" else ""
            }
            val shownRemoved = removed.filter { it !in keptPaths }
            if (shownRemoved.isNotEmpty()) lines.add("- Stage 3에서 제외된 후보 ${shownRemoved.size}개: ${names(shownRemoved)} (사유: 제공하지 않음)")
            if (unjudged.isNotEmpty()) lines.add("- 판정 누락으로 유지된 후보: ${names(unjudged)}")
            return lines
        }
    }

    companion object {
        /** REQUIRED 사유가 이보다 짧으면 근거 미제시로 보고 제거한다(V2의 40자 이내 사유를 고려해 10자에서 낮춤). */
        const val MIN_REASON_LENGTH = 5
        const val NOTICE_NAME_LIMIT = 10
        /** REQUIRED로 적었지만 사유가 짧아 코드가 제거한 후보의 사유(모델이 removedFiles에 넣은 것이 아니므로 암묵적 탈락으로 본다). */
        const val WEAK_REASON = "판정 근거 미제시로 제외"
        const val CITATION_NAME_LIMIT = 3

        // 앞에 파일 이름이 붙을 수 있는 괄호 인용. 괄호 안은 정수와 쉼표·공백만 허용한다.
        private val NUMBER_CITATION = Regex("""((?:[A-Za-z0-9_][A-Za-z0-9_.\-]*)?)\(\s*(\d+(?:[\s,]+\d+)*)\s*\)""")
        private val DIGITS = Regex("""\d+""")

        data class CitationResult(val text: String, val mismatches: List<String>)

        private fun joinCitedNames(names: List<String>): String =
            names.take(CITATION_NAME_LIMIT).joinToString(", ") + if (names.size > CITATION_NAME_LIMIT) " 외 ${names.size - CITATION_NAME_LIMIT}개" else ""

        /**
         * 요약 속 `(번호,번호)` 인용을 번호표(paths, 1번부터)의 파일명으로 바꾼다.
         * - 1~N 밖의 번호가 하나라도 있거나 정수·쉼표·공백 외 문자가 있으면 원문을 둔다.
         * - `이름(번호들)`: 가리키는 후보가 모두 그 이름과 정확히 같으면(확장자·대소문자 무시) 번호 1개는 번호만 지우고, 여러 개는 괄호를 파일명 목록으로 바꾼다.
         *   모두 그 이름으로 끝나기만 하면(접미사 일치) 번호 자리에 파일명을 넣는다(DEM(1) → DEM(ACMBTBAPC024DEM.java)).
         *   하나라도 어느 쪽도 아니면 원문을 두고 mismatches에 기록한다.
         */
        fun substituteCandidateNumbers(text: String, paths: List<String>): CitationResult {
            if (paths.isEmpty() || text.isEmpty()) return CitationResult(text, emptyList())
            val files = paths.map { it.substringAfterLast('/').substringAfterLast('\\') }
            val mismatches = mutableListOf<String>()
            val out = NUMBER_CITATION.replace(text) { m ->
                val prefix = m.groupValues[1]
                val nums = DIGITS.findAll(m.groupValues[2]).map { it.value.toIntOrNull() ?: 0 }.distinct().toList()
                if (nums.any { it < 1 || it > files.size }) return@replace m.value
                val names = nums.map { files[it - 1] }
                if (prefix.isEmpty()) {
                    "(" + joinCitedNames(names) + ")"
                } else if (names.all { n -> n.equals(prefix, ignoreCase = true) || n.substringBeforeLast('.').equals(prefix, ignoreCase = true) }) {
                    if (names.size == 1) prefix else prefix + "(" + joinCitedNames(names) + ")"
                } else if (names.all { n -> n.endsWith(prefix, ignoreCase = true) || n.substringBeforeLast('.').endsWith(prefix, ignoreCase = true) }) {
                    prefix + "(" + joinCitedNames(names) + ")"
                } else {
                    mismatches.add(m.value + " → " + names.joinToString(", "))
                    m.value
                }
            }
            return CitationResult(out, mismatches)
        }
    }

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

            ## 출력 형식(중요)
            - 후보 파일마다 [번호]가 붙어 있습니다. 경로를 다시 쓰지 말고 번호만 사용하세요.
            - 제거할 후보는 removedFiles에 번호만 넣고 사유는 쓰지 마세요.
            - 수정이 필요한 후보는 requiredFiles에 번호와 구체적인 수정 내용(reason)을 쓰세요.
            - JSON은 들여쓰기·줄바꿈 없이 한 줄로 출력하세요.
            - 각 reason은 40자 이내 한 문장으로 쓰고, reasoning은 '1) ...', '2) ...' 형식으로 두 항목 이내로 쓰세요.
        """.trimIndent()

        val tool = net.ib.ixpert.ops.wuwagent.model.ToolDefinition(
            type = "function",
            function = net.ib.ixpert.ops.wuwagent.model.FunctionDefinition(
                name = "submit_verification",
                description = "파일 목록 검증 결과를 제출합니다.",
                parameters = net.ib.ixpert.ops.wuwagent.model.FunctionParameters(
                    type = "object",
                    properties = mapOf(
                        "requiredFiles" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "array",
                            description = "수정이 필요한 후보 목록",
                            items = net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                                type = "object",
                                properties = mapOf(
                                    "n" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                                        type = "integer",
                                        description = "후보 번호"
                                    ),
                                    "reason" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                                        type = "string",
                                        description = "구체적인 수정 내용(40자 이내)"
                                    )
                                ),
                                required = listOf("n", "reason")
                            )
                        ),
                        "removedFiles" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "array",
                            description = "제거할 후보 번호 목록(사유 불필요)",
                            items = net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(type = "integer")
                        ),
                        "reasoning" to net.ib.ixpert.ops.wuwagent.model.PropertyDefinition(
                            type = "string",
                            description = "전체 판정 근거 요약('1) ...' 형식)"
                        )
                    ),
                    required = listOf("requiredFiles", "removedFiles", "reasoning")
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
        val removedPaths = mutableListOf<String>()
        val removedReasons = LinkedHashMap<String, String>()
        val unjudgedPaths = mutableListOf<String>()
        var compactHandled = false
        val toolCall = response?.toolCalls?.firstOrNull { it.function.name == "submit_verification" }
        var argsStr = toolCall?.function?.arguments
        var finalReasoning = ""
        var usedFallback = false
        
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
            val compact = parseCompact(argsStr)
            if (compact != null) {
                finalReasoning = compact.reasoning ?: ""
                applyCompact(compact, topFiles, verifiedTopFiles, removedPaths, unjudgedPaths, removedReasons)
                compactHandled = true
            }
        }

        if (compactHandled) {
            // 출력 축소 형식 처리 완료
        } else if (argsStr != null) {
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
                    if (verdict.verdict == "REQUIRED" && reasonLen < MIN_REASON_LENGTH) {
                        verdict.copy(verdict = "UNNECESSARY", reason = WEAK_REASON)
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
                    } else {
                        removedPaths.add(spec.path)
                        verdictObj?.reason?.takeIf { it.isNotBlank() }?.let { removedReasons[spec.path] = it }
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
            usedFallback = true
            verifiedTopFiles.addAll(topFiles)
        }
        
        if (verifiedTopFiles.isEmpty()) {
            logger.warn("Stage 3: 전체 UNNECESSARY 판정 – 안전장치로 상위 3개 유지 (기존 파일 한정)")
            verifiedTopFiles.addAll(topFiles.filter { it.type != "CREATE" && it.type != "신규" }.take(3))
        }
        
        val keptPaths = (verifiedTopFiles + autoKept).map { it.path }.toSet()
        return VerificationOutput(
            files = verifiedTopFiles + autoKept,
            reasoning = finalReasoning,
            removed = removedPaths.filter { it !in keptPaths },
            unjudged = unjudgedPaths,
            candidatePaths = topFiles.map { it.path },
            removedReasons = removedReasons.filterKeys { it !in keptPaths },
            usedFallback = usedFallback
        )
    }

    /** 새 형식(requiredFiles/removedFiles)이면 해석한 결과, 아니거나 해석할 수 없으면 null(구 형식 폴백). */
    private fun parseCompact(argsStr: String): CompactResponse? {
        return try {
            val cleaned = argsStr
                .replace(Regex("^```(?:json)?\\s*"), "")
                .replace(Regex("\\s*```$"), "")
                .trim()
            val el = com.google.gson.JsonParser.parseString(cleaned)
            if (!el.isJsonObject) return null
            val o = el.asJsonObject
            if (!o.has("requiredFiles") && !o.has("removedFiles")) return null
            gson.fromJson(cleaned, CompactResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 규칙 R3: removedFiles에만 있으면 제거, requiredFiles에 있으면 유지(양쪽에 있어도 유지), 둘 다 없으면 유지하고 판정 누락으로 기록.
     * 범위 밖 번호는 무시한다. 사유가 MIN_REASON_LENGTH보다 짧은 requiredFiles 항목은 근거 미제시로 제거한다.
     */
    private fun applyCompact(
        res: CompactResponse,
        topFiles: List<TargetFileSpec>,
        kept: MutableList<TargetFileSpec>,
        removed: MutableList<String>,
        unjudged: MutableList<String>,
        removedReasons: MutableMap<String, String> = mutableMapOf()
    ) {
        val n = topFiles.size
        val required = LinkedHashMap<Int, String?>()
        res.requiredFiles?.forEach { r ->
            val idx = r.n
            if (idx != null && idx in 1..n && !required.containsKey(idx)) required[idx] = r.reason
        }
        val removedSet = res.removedFiles?.filter { it in 1..n }?.toSet() ?: emptySet()
        if (required.isEmpty() && removedSet.isEmpty()) {
            logger.warn("Stage 3: 판정 목록이 비어 있어 모든 후보를 유지하고 판정 누락으로 기록합니다.")
        }
        topFiles.forEachIndexed { i, spec ->
            val num = i + 1
            when {
                required.containsKey(num) -> {
                    val reason = required[num]?.trim() ?: ""
                    if (reason.length < MIN_REASON_LENGTH) { removed.add(spec.path); removedReasons[spec.path] = WEAK_REASON }
                    else kept.add(spec.copy(description = reason))
                }
                num in removedSet -> removed.add(spec.path)
                else -> {
                    kept.add(spec.copy(description = "Stage 3 판정 누락으로 유지"))
                    unjudged.add(spec.path)
                }
            }
        }
    }

    private fun buildBatchPrompt(userQuery: String, specs: List<TargetFileSpec>, graph: ProjectGraph, mdRoot: Path): String {
        return buildString {
            appendLine("## 요구사항")
            appendLine(userQuery)
            appendLine()
            appendLine("## 후보 파일 목록")
            specs.forEachIndexed { index, spec ->
                appendLine("- [${index + 1}] ${spec.path}")
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
            appendLine("위 후보 파일 목록에서 수정이 필요한 후보는 requiredFiles에, 제거할 후보는 removedFiles(번호만)에 담고 reasoning을 작성하세요.")
        }
    }

    private fun buildFileContext(path: String, graph: ProjectGraph, mdRoot: Path): String {
        val fileName = extractFileName(path)
        val mdFile = mdRoot.resolve("$fileName.md")
        val node = graph.files[path]
        
        if (mdFile.exists()) {
            return extractMdSections(mdFile, listOf(1, 3, 5), node)
        }
        
        if (node != null) {
            return buildGraphContext(node)
        }

        val resourceNode = graph.resourceNodes.find { it.path == path || it.path.endsWith("/$fileName") }
        if (resourceNode != null) {
            return buildResourceContext(resourceNode)
        }
        
        return "정보 없음"
    }

    private fun buildResourceContext(resource: net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode): String {
        return buildString {
            appendLine("- fileType: ${resource.type}, layer: ${resource.layer}")
            appendLine("- path: ${resource.path}")
            if (resource.linkedTo.isNotEmpty()) {
                appendLine("- linkedTo: ${resource.linkedTo.joinToString(", ")}")
            }
            val meta = resource.metadata
            if (meta.isNotEmpty()) {
                val inputFields = (meta["input_field"] as? List<*>)?.mapNotNull { it?.toString() }
                if (!inputFields.isNullOrEmpty()) {
                    appendLine("- inputFields: ${inputFields.take(10).joinToString(", ")}")
                }
                val methods = (meta["methods"] as? List<*>)?.mapNotNull { it?.toString() }
                if (!methods.isNullOrEmpty()) {
                    appendLine("- methods: ${methods.take(10).joinToString(", ")}")
                }
                val sqlIds = (meta["sql_id"] as? List<*>)?.mapNotNull { it?.toString() }
                if (!sqlIds.isNullOrEmpty()) {
                    appendLine("- sqlIds: ${sqlIds.take(10).joinToString(", ")}")
                }
                val scriptSrc = (meta["script_src"] as? List<*>)?.mapNotNull { it?.toString() }
                if (!scriptSrc.isNullOrEmpty()) {
                    appendLine("- scriptSrc: ${scriptSrc.joinToString(", ")}")
                }
            }
        }
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
