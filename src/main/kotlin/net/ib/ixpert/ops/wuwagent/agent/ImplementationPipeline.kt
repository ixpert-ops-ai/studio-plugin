package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.validation.SignatureValidator
import java.io.File

/**
 * [Phase 2b] 요구사항 분석 결과(TargetFiles)를 바탕으로 실제 소스 코드를 수집하고,
 * 순차적으로 LLM에 전달하여 코드 수정을 수행하는 파이프라인.
 */
class ImplementationPipeline(
    private val client: LLMClient,
    private val project: Project
) {
    private val logger = Logger.getInstance(ImplementationPipeline::class.java)

    enum class EditStrategy {
        WHOLE,
        ACTION_BASED,
        DTO_ONLY
    }

    /**
     * Contract-First 생성 순서를 결정합니다.
     * Top-Down 순서: DTO/Entity → Interface → Impl(Persistence) → Impl(Business) → Utility → Controller
     *
     * 이 순서가 중요한 이유:
     * - Interface를 Impl보다 먼저 생성하면 @Override를 통해 시그니처가 자연스럽게 일치
     * - Controller(Caller)를 마지막에 생성하면 확정된 Service/Utility 시그니처를 참조 가능
     * - DTO를 가장 먼저 생성하면 모든 파일이 동일한 데이터 구조를 참조
     *
     * MetaGraph가 있으면 fileType/isInterface 기반으로 정확히 분류하고,
     * 없으면 파일명 패턴 기반 Fallback으로 동작합니다.
     *
     * NOTE: ExcelDownUtil 같은 AbstractView 상속 유틸은 MetaGraph에서 fileType=VIEW로
     * 분류될 수 있으므로, fileType과 파일명 문자열 매칭을 OR 조건으로 결합하여 처리합니다.
     */
    private fun getGenerationOrder(path: String): Int {
        val lowerPath = path.lowercase().replace("\\", "/")
        val graphLoader = project.getService(net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.GraphLoader::class.java)
        val graph = graphLoader?.loadGraph()
        val fileNode = graph?.files?.values?.find {
            it.path.replace("\\", "/").endsWith(lowerPath.removePrefix("/"))
        }

        return when {
            // 1순위: DTO/Entity/VO — 다른 모든 파일이 참조하는 데이터 구조를 먼저 확정
            fileNode?.fileType in listOf(
                net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.DTO,
                net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.VO,
                net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.ENTITY
            ) -> 1
            lowerPath.let { it.contains("dto") || it.contains("entity") || it.contains("vo") || it.contains("model") }
                && !lowerPath.contains("controller") -> 1

            // 2순위: Interface (DAO, Service, Repository, Mapper 인터페이스)
            fileNode?.isInterface == true -> 2
            !lowerPath.contains("impl") && lowerPath.let {
                it.endsWith("dao.java") || it.endsWith("service.java") ||
                it.endsWith("repository.java") || it.endsWith("mapper.java")
            } -> 2

            // 3순위: Persistence 구현체 (DaoImpl, RepositoryImpl)
            lowerPath.contains("impl") && lowerPath.let {
                it.contains("dao") || it.contains("repository") || it.contains("mapper")
            } -> 3

            // 4순위: Business 구현체 (ServiceImpl)
            lowerPath.contains("impl") && lowerPath.contains("service") -> 4

            // 5순위: Utility — fileType=UTIL 또는 파일명에 "util"/"helper"/"exporter" 포함
            // (ExcelDownUtil 등 AbstractView 상속 클래스는 MetaGraph에서 VIEW로 분류되므로 파일명 매칭 필요)
            fileNode?.fileType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.UTIL -> 5
            lowerPath.let { it.contains("util") || it.contains("helper") || it.contains("exporter") } -> 5

            // 6순위: Controller/Caller — 확정된 Service interface와 Utility 시그니처를 참조
            fileNode?.fileType in listOf(
                net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.CONTROLLER,
                net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.REST_CONTROLLER
            ) -> 6
            lowerPath.contains("controller") -> 6

            // 7순위: 기타 (Config, Filter, Interceptor 등)
            else -> 7
        }
    }

    private val psiMethodExtractor = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.PsiMethodExtractor(project)

    fun execute(
        analysisResult: RequirementAnalysisResult,
        onChunk: (String) -> Unit
    ) {
        val sortedTargets = analysisResult.targetFiles.sortedWith(
            compareBy<net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec> { getGenerationOrder(it.path) }
        )
        
        logger.info("ImplementationPipeline: 총 ${sortedTargets.size}개 파일 순차 처리 시작")

        // [Phase 1] Contract-First: 코드 생성 전에 시그니처 계약을 확정
        val contract = try {
            ContractResolver(project, client).resolve(analysisResult)
        } catch (e: Exception) {
            logger.warn("ContractResolver 실행 실패, Contract 없이 진행합니다: ${e.message}")
            null
        }
        if (contract != null) {
            logger.info("Contract 확정: 공유 메서드 ${contract.sharedMethods.size}개")
            onChunk("\n> 📋 **시그니처 계약 확정**: ${contract.sharedMethods.size}개 메서드의 타입이 사전 합의되었습니다.\n\n")
        }

        val contextChain = mutableListOf<String>()
        val generatedSnippets = mutableMapOf<String, String>()

        // 전략별 파일 추적
        val fullFileResults = mutableListOf<String>()
        val snippetFileResults = mutableListOf<String>()
        val skippedFileResults = mutableListOf<String>()

        for ((index, target) in sortedTargets.withIndex()) {
            val progressHeader = "\n\n### 🔄 [${index + 1}/${sortedTargets.size}] `${target.path}` 처리 중...\n\n"
            onChunk(progressHeader)

            // 파일 및 프롬프트 생성 로직
            var isNewFile = target.type.contains("신규")
            if (isNewFile) {
                val absolutePath = "${project.basePath}/${target.path}".replace("//", "/")
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
                if (virtualFile != null && virtualFile.exists()) {
                    logger.warn("기존 파일을 신규로 잘못 분류함. '수정'으로 자동 교정: ${target.path}")
                    isNewFile = false
                }
            }
            val graphLoader = project.getService(net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.GraphLoader::class.java)
            val graph = graphLoader?.loadGraph()
            
            val isDtoOrEntity = if (graph != null) {
                val normalizedPath = target.path.replace("\\", "/").removePrefix("/")
                val fileNode = graph.files.values.find { it.path.replace("\\", "/").endsWith(normalizedPath) }
                if (fileNode != null) {
                    fileNode.fileType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.DTO ||
                    fileNode.fileType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.VO ||
                    fileNode.fileType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.ENTITY
                } else {
                    target.path.lowercase().let { p ->
                        p.contains("dto") || p.contains("entity") || p.contains("vo") || p.contains("model")
                    }
                }
            } else {
                target.path.lowercase().let { p ->
                    p.contains("dto") || p.contains("entity") || p.contains("vo") || p.contains("model")
                }
            }

            val isInterface = target.path.lowercase().let { p ->
                !p.contains("impl") && (
                    p.endsWith("dao.java") ||
                    p.endsWith("service.java") ||
                    p.endsWith("repository.java") ||
                    p.endsWith("mapper.java")
                )
            }
            // 인터페이스는 신규가 아니면 무조건 ACTION_BASED (Snippet) 적용
            val isLargeFile = !isNewFile && !isDtoOrEntity && (isInterface || runReadAction { psiMethodExtractor.isLargeFile(target.path) })
            
            val systemPrompt = when {
                isDtoOrEntity && !isNewFile -> buildDtoOnlySystemPrompt()
                isLargeFile -> buildLargeFileSystemPrompt(isInterface)
                else -> buildSmallFileSystemPrompt(isInterface)
            }
            // [Phase 2] Contract 섹션 구성: 해당 파일의 계약 정보를 프롬프트에 주입
            val contractSection = buildContractSection(contract, target)

            val userPrompt = when {
                isDtoOrEntity && !isNewFile -> buildDtoOnlyUserPrompt(target, contextChain, analysisResult.summary, sortedTargets)
                else -> buildUserPromptForFile(target, contextChain, analysisResult.summary, sortedTargets, contractSection)
            }

            // Fallback guide returned from buildUserPromptForFile means skipping
            if (userPrompt.startsWith("> ⚠️")) {
                onChunk(userPrompt)
                skippedFileResults.add(target.path)
                continue
            }

            logger.info("파일 판별: ${target.path} → isInterface=$isInterface, isLargeFile=$isLargeFile, isDtoOrEntity=$isDtoOrEntity")
            logger.info("Processing target: ${target.path}")
            
            var fullResponse = ""
            var consecutiveRepeatCount = 0
            var abortReason: String? = null // null=정상, "REPEAT", "LENGTH"
            val MAX_RESPONSE_CHARS = 15_000

            try {
                val response = client.chat(systemPrompt, userPrompt, onChunk = { chunk ->
                    if (abortReason != null) return@chat

                    fullResponse += chunk

                    // === 가드 1: 응답 길이 상한 ===
                    if (fullResponse.length > MAX_RESPONSE_CHARS) {
                        logger.warn("응답 길이 상한 초과 (${fullResponse.length}자): ${target.path}")
                        abortReason = "LENGTH"
                        return@chat
                    }

                    // === 가드 2: 무한 루프(동일 패턴 반복) 감지 ===
                    if (fullResponse.length > 500) {
                        val last500 = fullResponse.takeLast(500)
                        val first100 = last500.take(100)
                        if (last500.split(first100).size > 3) {
                            logger.warn("무한 루프 감지 (패턴 반복): ${target.path}")
                            abortReason = "REPEAT"
                            return@chat
                        }
                    }

                    // UI로 실시간 전송 (태그 제거 없이 필터링)
                    onChunk(chunk)
                })

                if (abortReason != null) {
                    val msg = when (abortReason) {
                        "REPEAT" -> "\n\n> ⚠️ **반복 패턴이 감지되어 생성을 중단했습니다.** `${target.path}`는 수동 수정이 필요합니다.\n\n"
                        "LENGTH" -> "\n\n> ⚠️ **응답이 비정상적으로 길어 생성을 중단했습니다.** `${target.path}`는 수동 수정이 필요합니다.\n\n"
                        else -> ""
                    }
                    onChunk(msg)
                    if (abortReason == "LENGTH") {
                        generatedSnippets[target.path] = fullResponse
                    }
                    skippedFileResults.add(target.path)
                    continue
                }

                val finalResponseText = response?.message?.content ?: fullResponse

                // 인터페이스 파일인 경우 환각 메서드 필터링, DTO 파일인 경우 스니펫 병합
                var processedResponse = if (isInterface) {
                    val filtered = runReadAction { filterInterfaceHallucination(finalResponseText, target, sortedTargets, contextChain) }
                    if (filtered != finalResponseText) {
                        logger.info("인터페이스 환각 필터링 적용: ${target.path}")
                        // 필터링된 결과를 사용자에게 다시 표시 (원본 스트리밍 응답이 이미 출력되었으므로 추가 안내)
                        onChunk("\n\n> 🔧 **인터페이스 환각 메서드가 감지되어 자동 제거되었습니다.** 아래의 수정된 코드를 사용하세요:\n\n")
                        onChunk("// 파일: ${target.path} (수정됨)\n```java\n$filtered\n```\n")
                    }
                    filtered
                } else if (isDtoOrEntity && !isNewFile) {
                    val absolutePath = "${project.basePath}/${target.path}".replace("//", "/")
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
                    if (virtualFile != null && virtualFile.exists()) {
                        val originalSource = String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
                        val merged = ImplementationPipelineUtils.mergeDtoSnippet(originalSource, finalResponseText)
                        onChunk("\n\n> 🔧 **DTO 자동 병합 완료**\n\n")
                        merged
                    } else {
                        finalResponseText
                    }
                } else {
                    finalResponseText
                }

                generatedSnippets[target.path] = processedResponse // Phase 2c 단위 테스트 생성을 위해 응답 캐시 저장

                if (isLargeFile) {
                    snippetFileResults.add(target.path)
                } else {
                    fullFileResults.add(target.path)
                }

                // 시그니처 일관성 검증
                val signatureMap = SignatureValidator.extractSignaturesFromContext(contextChain)
                val consistencyWarnings = SignatureValidator.validateConsistency(processedResponse, signatureMap)
                if (consistencyWarnings.isNotEmpty()) {
                    onChunk("\n\n> ⚠️ **시그니처 일관성 주의:**\n")
                    consistencyWarnings.forEach { onChunk("> - $it\n") }
                }

                // [Phase 3] Contract 기반 반환 타입 검증 + 1회 자동 재생성
                val fileContract = contract?.fileContracts?.find { it.filePath == target.path }
                if (fileContract != null) {
                    val contractWarnings = SignatureValidator.validateAgainstContract(processedResponse, fileContract)
                    if (contractWarnings.isNotEmpty()) {
                        onChunk("\n\n> ⚠️ **Contract 시그니처 위반 감지 — 자동 재생성을 시도합니다:**\n")
                        contractWarnings.forEach { onChunk("> - $it\n") }

                        // 1회 자동 retry: 위반 내용을 명시하여 재생성 요청
                        val correctionPrompt = buildString {
                            appendLine(userPrompt)
                            appendLine()
                            appendLine("## ⛔ 이전 생성 결과에서 다음 시그니처 위반이 감지되었습니다:")
                            contractWarnings.forEach { appendLine("- $it") }
                            appendLine()
                            appendLine("위 위반 사항을 반드시 수정하여 다시 생성하세요.")
                            appendLine("계약에 명시된 반환 타입과 파라미터 타입을 정확히 사용해야 합니다.")
                        }

                        onChunk("\n> 🔄 **재생성 중...**\n\n")

                        var retryResponse = ""
                        try {
                            client.chat(systemPrompt, correctionPrompt, onChunk = { chunk ->
                                retryResponse += chunk
                                onChunk(chunk)
                            })
                        } catch (e: Exception) {
                            logger.warn("Contract 위반 재생성 실패: ${e.message}")
                        }

                        if (retryResponse.isNotBlank()) {
                            // 재생성 결과로 교체
                            val retryWarnings = SignatureValidator.validateAgainstContract(retryResponse, fileContract)
                            if (retryWarnings.isEmpty()) {
                                onChunk("\n> ✅ **재생성 성공 — 시그니처가 계약과 일치합니다.**\n\n")
                                processedResponse = retryResponse
                                generatedSnippets[target.path] = processedResponse
                            } else {
                                onChunk("\n> ⚠️ **재생성 후에도 위반이 남아있습니다. 수동 확인이 필요합니다:**\n")
                                retryWarnings.forEach { onChunk("> - $it\n") }
                                // 무한 루프 방지: 2회 이상 retry하지 않음
                            }
                        }
                    }
                }

                if (processedResponse.contains("[MODIFIED_SIGNATURES]")) {
                    val signaturesText = processedResponse.substringAfter("[MODIFIED_SIGNATURES]").trim()
                    if (signaturesText.isNotBlank()) {
                        // [수정 2] 응답 코드 블록 내에 해당 시그니처의 메서드명이 존재하는지 검증
                        val responseBody = processedResponse.substringBefore("[MODIFIED_SIGNATURES]")
                        val validSignatures = signaturesText.lines().filter { line ->
                            val methodNameMatch = Regex("""\b([a-zA-Z_]\w*)\s*\(""").find(line)
                            if (methodNameMatch != null) {
                                val methodName = methodNameMatch.groupValues[1]
                                responseBody.contains(methodName)
                            } else {
                                true // 정규식에 안 잡히는 특이한 형식은 일단 허용
                            }
                        }.joinToString("\n")

                        if (validSignatures.isNotBlank()) {
                            contextChain.add("### `${target.path}` 변경사항\n$validSignatures")
                        } else {
                            logger.warn("유효한 시그니처를 찾지 못함 (응답 코드에 없음): $signaturesText")
                        }
                    }
                } else {
                    logger.warn("[MODIFIED_SIGNATURES] 블록이 생성되지 않음: ${target.path}")
                }
            } catch (e: Exception) {
                logger.error("Error processing file ${target.path}", e)
                onChunk("\n\n> ❌ **코드 생성 중 에러가 발생하여 이 파일을 건너뜁니다:** `${e.message}`\n\n")
                skippedFileResults.add(target.path)
                continue
            }
        }
        TestGenerationPipeline.lastImplementContext = TestGenerationPipeline.ImplementContext(
            targetFiles = sortedTargets,
            contextChain = contextChain.toList(),
            generatedSnippets = generatedSnippets
        )

        onChunk(buildCompletionMessage(fullFileResults, snippetFileResults, skippedFileResults))
    }

    private fun buildCompletionMessage(
        fullFiles: List<String>,
        snippetFiles: List<String>,
        skippedFiles: List<String>
    ): String {
        val sb = StringBuilder("\n\n---\n\n## ✅ 코드 생성 완료\n\n")

        if (fullFiles.isNotEmpty()) {
            sb.append("### 📄 전체 코드 생성 완료 (Apply 적용 가능)\n")
            sb.append("다음 파일들은 전체 코드가 생성되었습니다. 코드를 확인 후 Apply 버튼으로 적용할 수 있습니다.\n")
            fullFiles.forEach { sb.append("- `$it`\n") }
            sb.append("\n")
        }

        if (snippetFiles.isNotEmpty()) {
            sb.append("### 📝 스니펫 생성 완료 (수동 적용 필요)\n")
            sb.append("다음 파일들은 대형 파일이므로 변경/추가할 메서드 스니펫만 생성되었습니다.\n")
            sb.append("`// 📍` 위치 힌트를 참고하여 해당 위치에 직접 삽입하거나 교체해주세요.\n")
            snippetFiles.forEach { sb.append("- `$it`\n") }
            sb.append("\n")
        }

        if (skippedFiles.isNotEmpty()) {
            sb.append("### ⚠️ 건너뛴 파일\n")
            sb.append("다음 파일들은 반복 감지, 파싱 실패 또는 오류로 처리되지 않았습니다. 수동 확인이 필요합니다.\n")
            skippedFiles.forEach { sb.append("- `$it`\n") }
            sb.append("\n")
        }

        sb.append("💡 생성된 코드에 대한 테스트를 자동으로 만들려면 `/test-all`을 입력하세요.")
        return sb.toString()
    }

    private fun buildUserPromptForFile(
        targetFile: TargetFileSpec,
        contextChain: List<String>,
        requirementSummary: String,
        allTargetFiles: List<TargetFileSpec>,
        contractSection: String = ""
    ): String {
        var actualType = targetFile.type
        
        // [수정 1] "신규" 판별 검증 로직: 실제 파일이 존재하면 "수정"으로 교정
        if (actualType.contains("신규")) {
            val absolutePath = "${project.basePath}/${targetFile.path}".replace("//", "/")
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            if (virtualFile != null && virtualFile.exists()) {
                logger.warn("기존 파일을 신규로 잘못 분류함. '수정'으로 자동 교정: ${targetFile.path}")
                actualType = "수정"
            }
        }

        // 복사본(TargetFileSpec) 생성 (수정된 타입 반영)
        val correctedTarget = targetFile.copy(type = actualType)

        // DTO/Entity/VO는 대형 파일이어도 FullFileStrategy 적용
        val isDtoOrEntity = correctedTarget.path.lowercase().let { p ->
            p.contains("dto") || p.contains("entity") || p.contains("vo") || p.contains("model")
        }

        return if (correctedTarget.type.contains("신규")) {
            buildNewFilePrompt(correctedTarget, contextChain, requirementSummary, allTargetFiles, contractSection)
        } else if (!isDtoOrEntity && runReadAction { psiMethodExtractor.isLargeFile(correctedTarget.path) }) {
            buildLargeFilePrompt(correctedTarget, contextChain, requirementSummary, allTargetFiles, contractSection)
        } else {
            buildSmallFilePrompt(correctedTarget, contextChain, requirementSummary, allTargetFiles, contractSection)
        }
    }

    private fun buildCommonUserPrompt(
        targetFile: TargetFileSpec,
        contextChain: List<String>,
        requirementSummary: String,
        allTargetFiles: List<TargetFileSpec>,
        sourceCodeSection: String,
        contractSection: String = ""
    ): String {
        val overallPlan = allTargetFiles.joinToString("\n") { 
            val marker = if (it.path == targetFile.path) "👉 " else "   "
            "${marker}- [${it.type}] ${it.path} : ${it.description}" 
        }
        val contextChainStr = if (contextChain.isEmpty()) "없음" else contextChain.joinToString("\n")
        
        val isDaoLayer = targetFile.path.lowercase().let { p ->
            p.contains("dao") || p.contains("repository")
        }

        val layerRole = when {
            targetFile.path.contains("Controller") -> "REST API 요청을 처리하고 결과를 반환하는 Controller 계층입니다."
            targetFile.path.contains("Service") -> "비즈니스 로직을 수행하고 DAO를 호출하는 Service 계층입니다. DB 직접 접근(SQL)을 하지 않습니다."
            targetFile.path.contains("Dao") || targetFile.path.contains("Repository") -> "DB에 직접 접근하여 SQL을 실행하는 DAO 계층입니다. 비즈니스 로직이나 인증 검증을 하지 않습니다."
            else -> "프로젝트의 구성 요소입니다."
        }

        val guidelines = buildString {
            var num = 1
            appendLine("## 🚨 CRITICAL LAYER CONSTRAINT")
            appendLine("- **현재 역할**: $layerRole")
            appendLine("- **위반 금지**: 해당 계층의 역할을 벗어나는 코드를 작성하지 마세요 (예: Service에서 MyBatis/SQL 직접 사용 금지).")
            appendLine("")
            appendLine("## 💡 필수 구현 지침")
            appendLine("${num++}. 위 **수정/추가 내용**에 명시된 모든 로직을 반드시 구현해야 합니다.")
            appendLine("${num++}. 특히 메서드명이나 필드명이 명시되어 있다면 토씨 하나 틀리지 않고 정확히 사용하세요.")
            appendLine("${num++}. '우회(bypass)', '허용' 등의 조건이 있으면 해당 조건일 때 **통과(skip/continue/chain.doFilter)**시키는 로직을 작성하세요. 차단(block)이 아닙니다.")
            appendLine("${num++}. 위 '수정/추가 내용'에 **언급되지 않은** 메서드를 새로 만들지 마세요.")
            appendLine("${num++}. 위 '수정/추가 내용'에 **언급되지 않은** 필드나 변수를 추가하지 마세요.")
            if (isDaoLayer) {
                appendLine("${num++}. ⚠️ **이 파일은 DAO/Repository 계층입니다.** 인증(JWT/Session) 검증 로직을 절대 넣지 마세요. 인증은 Controller 또는 Filter에서 처리합니다.")
            }
            appendLine("${num++}. 이전 단계에서 생성된 메서드를 호출할 때, [이전 컨텍스트]의 시그니처와 파라미터 수/타입이 일치하도록 작성하세요.")
            appendLine("${num++}. **static/instance 호출 일치**: [이전 컨텍스트]에 'static' 키워드가 있다면 ClassName.methodName()으로 호출하고, 없으면 객체를 주입받아 호출하세요.")
        }

        val bypassTemplate = if (targetFile.description.lowercase().let { it.contains("bypass") || it.contains("우회") || it.contains("허용") }) {
            """
                
                ⚠️ **바이패스 구현 필수 패턴**:
                요구사항에 '바이패스'나 '우회'가 있다면 아래 패턴을 그대로 사용하세요.
                ```java
                if (조건) { 
                    chain.doFilter(request, response); // 다음 필터로 진행
                    return; // 현재 로직 중단
                }
                ```
                조건 불일치 시 에러를 반환하거나 인증을 강제하는 로직을 추가하지 마세요.
            """.trimIndent()
        } else ""

        return """
            ## 🎯 현재 작업 대상 (가장 중요)
            - **파일 경로**: `${targetFile.path}`
            - **작업 유형**: `${targetFile.type}`
            - **수정/추가 내용**: `${targetFile.description}`
            
            **주의**: 오직 위 파일 하나에 대해서만 코드를 생성하세요. 계획에 없는 다른 파일(Security Filter 등)을 임의로 생성하지 마세요.
            $bypassTemplate
            $contractSection
            
            $guidelines
            
            ---
            
            ## 요구사항 요약
            $requirementSummary
            
            ## 전체 작업 계획
            $overallPlan
            
            ## 이전 단계까지의 수정 요약 (참고용 컨텍스트)
            $contextChainStr
            ${if (targetFile.path.lowercase().let { !it.contains("impl") && (it.endsWith("dao.java") || it.endsWith("service.java") || it.endsWith("repository.java") || it.endsWith("mapper.java")) }) "\n⚠️ **주의**: 이 파일은 인터페이스입니다. 위의 [이전 단계까지의 수정 요약]을 반드시 확인하고, 구현체에서 생성된 메서드 시그니처와 완전히 동일하게 선언하세요." else ""}
            
            ---
            
            ## 파일 분석 / 소스 코드
            $sourceCodeSection
        """.trimIndent()
    }

    private fun buildNewFilePrompt(
        targetFile: TargetFileSpec,
        contextChain: List<String>,
        requirementSummary: String,
        allTargetFiles: List<TargetFileSpec>,
        contractSection: String = ""
    ): String {
        return buildCommonUserPrompt(
            targetFile, contextChain, requirementSummary, allTargetFiles, 
            "이 파일은 신규 생성입니다. 요구사항에 맞는 전체 코드를 새로 작성하세요.",
            contractSection
        )
    }

    private fun buildDtoOnlySystemPrompt(): String {
        return """
            당신은 Spring Boot 시니어 개발자입니다.
            현재 타겟 파일은 기존에 존재하는 DTO/VO 파일입니다.
            DTO에는 오직 새로 추가되는 필드와 해당 필드의 Getter/Setter만 스니펫 형태로 생성해야 합니다.
            
            ## DTO 전용 규칙
            1. 클래스 선언부, 기존 필드, 기존 메서드를 절대 출력하지 마세요.
            2. 오직 새 필드(`private Type fieldName;`)와 새 메서드(`public Type getFieldName() {...}`)만 출력하세요.
            3. 응답은 반드시 ````java` 코드 블록 하나만 포함해야 합니다.
            4. 주석이나 부가 설명은 절대 작성하지 마세요.
            5. 코드 블록의 맨 끝에는 `[MODIFIED_SIGNATURES]`와 함께 추가된 메서드 시그니처를 한 줄씩 요약하세요.
            
            출력 예시:
            ```java
            private String newField;
            
            public String getNewField() { return newField; }
            public void setNewField(String newField) { this.newField = newField; }
            ```
            [MODIFIED_SIGNATURES]
            + public String getNewField()
            + public void setNewField(String newField)
        """.trimIndent()
    }

    private fun buildDtoOnlyUserPrompt(
        targetFile: TargetFileSpec,
        contextChain: List<String>,
        requirementSummary: String,
        allTargetFiles: List<TargetFileSpec>
    ): String {
        return buildCommonUserPrompt(
            targetFile, contextChain, requirementSummary, allTargetFiles, 
            "이 파일은 기존 DTO 파일입니다. 전체 코드를 재작성하지 말고, 새롭게 추가할 필드와 메서드만 생성하세요."
        )
    }

    private fun buildSmallFilePrompt(
        targetFile: TargetFileSpec,
        contextChain: List<String>,
        requirementSummary: String,
        allTargetFiles: List<TargetFileSpec>,
        contractSection: String = ""
    ): String {
        val absolutePath = "${project.basePath}/${targetFile.path}".replace("//", "/")
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
        
        if (virtualFile == null || !virtualFile.exists()) {
            logger.warn("VirtualFile을 찾을 수 없습니다: $absolutePath")
            return "> ⚠️ **파일을 찾을 수 없어 건너뜁니다:** `${targetFile.path}`\n\n"
        }
        val sourceCode = String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
        return buildCommonUserPrompt(targetFile, contextChain, requirementSummary, allTargetFiles, "```java\n$sourceCode\n```", contractSection)
    }

    private fun buildLargeFilePrompt(
        targetFile: TargetFileSpec,
        contextChain: List<String>,
        requirementSummary: String,
        allTargetFiles: List<TargetFileSpec>,
        contractSection: String = ""
    ): String {
        val skeleton = runReadAction {
            psiMethodExtractor.extract(
                filePath = targetFile.path,
                taskDescription = targetFile.description,
                taskType = targetFile.type
            )
        }

        if (skeleton == null) {
            logger.warn("스켈레톤 추출 실패, 스킵: ${targetFile.path}")
            return "> ⚠️ **파일 파싱 실패로 건너뜁니다:** `${targetFile.path}`\n\n"
        }
        
        val sourceCodeSection = """
            이 파일은 대형 파일이므로 클래스 구조와 연관 메서드 스니펫만 제공됩니다.
            
            ${skeleton.toPromptText()}
        """.trimIndent()
        
        return buildCommonUserPrompt(targetFile, contextChain, requirementSummary, allTargetFiles, sourceCodeSection, contractSection)
    }

    private fun buildSmallFileSystemPrompt(isInterface: Boolean = false): String {
        val basePrompt = """
            당신은 Spring Boot 프로젝트를 구현하는 시니어 백엔드 개발자입니다.
            주어진 요구사항과 작업 계획에 따라, 현재 타겟 파일의 코드를 작성/수정해야 합니다.
            
            ## 코드 작성 규칙
            1. 기존 코드의 스타일과 아키텍처를 반드시 유지하세요.
            2. 필요한 import 문을 모두 포함하여 컴파일 가능한 "전체 코드"를 반환하세요.
            3. 생략(`...`) 없이 모든 메서드와 로직을 완전하게 작성하세요.
            4. 이전 파일들에서 추가/변경된 메서드가 있다면, 그 시그니처를 참고하여 코드를 작성하세요.
               (특히, 이전 파일 수정 요약에 기재된 메서드 시그니처(메서드명, 파라미터 타입, 반환 타입)를 정확히 사용하세요. 임의로 메서드명을 변경하지 마세요.)
            5. **절대로 존재하지 않는 변수나 필드를 임의로 생성하지 마세요.**
               기존 코드에 없는 필드(예: userAuthGroupLevel1, alimtalk_image_link_custom 등)를 
               반복적으로 선언하는 것은 금지입니다. 오직 요구사항에 명시된 필드만 추가하세요.
            6. 변수 선언은 요구사항에서 요청한 것만 최소한으로 작성하세요.
               비슷한 이름의 변수를 번호를 붙여 반복 생성하지 마세요.
            7. 요구사항에 '우회(bypass)', '허용', '예외' 등의 조건이 있다면 로직 방향을 철저히 검토하세요.
               - 예시: "Bearer JWT가 있으면 필터 바이패스" -> 올바른 구현: `if (token != null && token.startsWith("Bearer ")) { chain.doFilter(req, res); return; }`
               - 잘못된 구현 (절대 금지): 토큰이 없을 때 401 반환하고 토큰 있을 때 검증 후 진행.
            8. DAO/Repository 파일에는 인증(JWT/Session) 검증 로직을 넣지 마세요.
            9. **오직 현재 타겟 파일 하나만 생성하세요.** 요구사항에 없는 새로운 파일(Filter, EntryPoint 등)을 임의로 만들지 마세요.
            10. 유틸리티 클래스(JwtUtil 등) 호출 시, 이전 단계에서 정의된 static/instance 패턴을 정확히 따르세요. `@Component`로 정의된 경우 static 호출을 하지 마세요.
            11. **Controller 처리 규칙**: 
                - 기존 `@Value` 필드나 `@Autowired` 필드를 절대 재선언하거나 나열하지 마세요.
                - 기존 엔드포인트 메서드의 시그니처나 바디를 반복 출력하지 마세요.
                - 오직 새로 추가하거나 수정하는 부분(메서드나 필드)만 `[CODE]` 블록에 작성하세요.
        """.trimIndent()

        val interfaceRule = if (isInterface) """
            
            ## 인터페이스 파일 추가 규칙
            7. 이 파일은 인터페이스입니다. 다음을 반드시 준수하세요:
               - 기존에 선언된 메서드 시그니처를 절대 변경하지 마세요.
               - 새 메서드를 추가할 경우, 해당 메서드의 구현체가 이번 작업 계획에 포함되어 있는지 확인하세요.
               - 존재하지 않는 예외 클래스나 타입을 throws 절에 사용하지 마세요.
               - 메서드 추가는 최소한으로 하고, 기존 메서드의 시그니처를 활용하는 것을 우선하세요.
               - 구현체에서 이미 예외 처리를 하고 있다면 인터페이스에 새 메서드를 추가할 필요가 없습니다.
        """.trimIndent() else ""

        val outputFormat = """

            ## 출력 포맷
            반드시 아래의 마크다운 형식을 지켜서 출력하세요. 코드 블록 앞에는 반드시 파일 경로를 주석으로 명시해야 합니다.
            그 외의 부가 설명은 하지 마세요. 단, 응답의 맨 마지막 줄에만 `[MODIFIED_SIGNATURES]` 태그를 달고, 
            이번 파일에서 새롭게 추가되거나 변경된 public 메서드의 시그니처를 한 줄씩 요약해서 적어주세요. (다음 파일의 컨텍스트로 사용됨)

            // 파일: (현재 파일 경로)
            ```java
            (전체 소스 코드)
            ```
            [MODIFIED_SIGNATURES]
            + public List<SurveyDto> findAllForExport()
        """.trimIndent()

        return basePrompt + interfaceRule + outputFormat
    }

    private fun buildLargeFileSystemPrompt(isInterface: Boolean = false): String {
        val basePrompt = """
            당신은 Spring Boot 시니어 개발자입니다.
            
            아래 규칙을 반드시 준수하세요:
            1. 오직 **새로 추가할 메서드**의 완전한 코드만 반환하세요.
            2. **기존 메서드를 교체하거나 재작성하지 마세요.** 
               기존 메서드에 변경이 필요한 경우, 변경이 필요한 부분만 주석으로 설명하세요.
               예: // 📍 수정 가이드: {파일명} -> {메서드명}() 내 catch 블록에 아래 코드를 추가하세요.
            3. 각 메서드 블록 위에 위치 힌트를 반드시 포함하세요:
               - 신규 메서드 추가: // 📍 삽입 위치: {파일명} → 클래스 바디 하단 (새 메서드)
               - 기존 메서드 부분 수정: // 📍 수정 가이드: {파일명} → {메서드명}() 내 {위치} 에 추가
            4. 기존 메서드를 교체할 때, 변경하지 않는 로직 부분도 생략하지 말고 전체를 작성하세요. 
               `// ... 기존 코드 ...` 같은 생략 표현을 절대 사용하지 마세요. (신규 메서드인 경우만 해당)
            5. 필요한 import문이 있으면 코드 블록 최상단에 별도로 나열하세요.
            6. 기존 코드 스타일(어노테이션 사용 패턴, 네이밍 컨벤션)을 유지하세요.
            7. 이전 파일 수정 요약에 기재된 메서드 시그니처(메서드명, 파라미터 타입, 반환 타입)를 정확히 사용하세요. 임의로 메서드명을 변경하지 마세요.
            8. 코드 블록 마지막에 [MODIFIED_SIGNATURES] 태그로 변경/추가된 메서드 시그니처를 나열하세요.
            9. **절대로 존재하지 않는 변수나 필드를 임의로 생성하지 마세요.**
               기존 코드에 없는 필드를 반복적으로 선언하는 것은 금지입니다. 오직 요구사항에 명시된 필드만 추가하세요.
            10. 변수 선언은 요구사항에서 요청한 것만 최소한으로 작성하세요.
                비슷한 이름의 변수를 번호를 붙여 반복 생성하지 마세요.
            11. 요구사항에 '우회(bypass)', '허용' 등의 조건이 있다면 로직 방향을 철저히 검토하세요.
                '바이패스' = 해당 조건이면 처리를 건너뛰고 다음 단계로 진행(chain.doFilter 등).
                절대로 '바이패스' 조건에서 차단(block/reject/return error)하지 마세요.
            12. DAO/Repository 파일에는 인증(JWT/Session) 검증 로직을 넣지 마세요.
            13. **오직 현재 타겟 파일 하나만 생성하세요.** 계획에 없는 추가 파일을 임의로 생성하지 마세요.
            14. 유틸리티 클래스 호출 시 static/instance 성격을 구분하세요. `@Component` 인스턴스 메서드를 static으로 호출하지 마세요.
            15. **신규 메서드 생성 제한**: 요구사항에 명시되지 않은 메서드를 임의로 대량 생성하지 마세요. (최대 5개 제한)
            16. **생략 금지**: 기존 메서드를 교체할 때 `// ... 기존 코드 ...` 같은 생략 표현을 절대 사용하지 마세요.
            17. **Controller 처리 규칙**: 
                - 기존 `@Value` 필드나 `@Autowired` 필드를 절대 재선언하거나 나열하지 마세요.
                - 기존 엔드포인트 메서드의 시그니처나 바디를 반복 출력하지 마세요.
                - 오직 새로 추가하거나 수정하는 부분(메서드나 필드)만 `[CODE]` 블록에 작성하세요.
                - 코드 주석은 메서드당 최대 1줄로 제한합니다. 설명적 주석을 반복해서 출력하지 마세요.
        """.trimIndent()

        val interfaceRule = if (isInterface) """
            11. 이 파일은 인터페이스입니다. 다음을 반드시 준수하세요:
                - 기존에 선언된 메서드 시그니처를 절대 변경하지 마세요.
                - 새 메서드를 추가할 경우, 해당 메서드의 구현체가 이번 작업 계획에 포함되어 있는지 확인하세요.
                - 존재하지 않는 예외 클래스나 타입을 throws 절에 사용하지 마세요.
        """.trimIndent() else ""

        return basePrompt + "\n" + interfaceRule
    }

    /**
     * 인터페이스 파일의 LLM 응답에서 환각 메서드를 필터링합니다.
     * 원본 소스에 없고, 작업 계획의 구현체 description에도 언급되지 않은 메서드를 제거합니다.
     */
    private fun filterInterfaceHallucination(
        responseText: String,
        targetFile: TargetFileSpec,
        allTargetFiles: List<TargetFileSpec>,
        contextChain: List<String>
    ): String {
        logger.warn("=== filterInterfaceHallucination 호출됨: ${targetFile.path} ===")
        logger.warn("=== 응답 길이: ${responseText.length}자 ===")

        val absolutePath = "${project.basePath}/${targetFile.path}".replace("//", "/")
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return responseText
        val originalSource = String(virtualFile.contentsToByteArray(), Charsets.UTF_8)

        // 원본에서 메서드명 추출 (간단한 패턴: 반환타입 메서드명( )
        val methodNamePattern = Regex("""\b(\w+)\s*\([^)]*\)\s*(?:throws\s+[^;{]+)?\s*[;{]""")
        val originalMethods = methodNamePattern.findAll(originalSource)
            .map { it.groupValues[1] }
            .filter { it !in listOf("if", "for", "while", "switch", "catch", "synchronized") }
            .toSet()

        logger.info("원본 메서드명: $originalMethods (${targetFile.path})")

        // 응답에서 코드 블록 내용 추출
        val codeBlockPattern = Regex("""```java\s*\n([\s\S]*?)```""")
        val codeBlock = codeBlockPattern.find(responseText)?.groupValues?.get(1) ?: responseText

        // 응답 코드에서 메서드명 추출
        val responseMethods = methodNamePattern.findAll(codeBlock)
            .map { it.groupValues[1] }
            .filter { it !in listOf("if", "for", "while", "switch", "catch", "synchronized") }
            .toSet()

        logger.info("응답 메서드명: $responseMethods (${targetFile.path})")

        // 새로 추가된 메서드 식별
        val newMethods = responseMethods - originalMethods
        if (newMethods.isEmpty()) return responseText

        logger.info("신규 메서드 감지: $newMethods (${targetFile.path})")

        // [수정 3] 구현체 descriptions가 아닌, contextChain 전체에서 메서드명을 검색하여 검증
        val contextText = contextChain.joinToString("\n").lowercase()

        val hallucinatedMethods = newMethods.filter { methodName ->
            !contextText.contains(methodName.lowercase())
        }

        if (hallucinatedMethods.isEmpty()) return responseText

        logger.warn("인터페이스 환각 메서드 감지: $hallucinatedMethods (${targetFile.path})")

        // 환각 메서드 포함 줄과 관련 Javadoc 제거
        var filteredResponse = responseText
        hallucinatedMethods.forEach { methodName ->
            // 메서드 선언 줄 제거 (세미콜론으로 끝나는 인터페이스 메서드)
            // 앞에 붙은 Javadoc 주석과 함께 제거 시도
            logger.warn("인터페이스 환각 메서드 제거 시도: $methodName in ${targetFile.path}")
            val pattern = Regex("""(?:/\*\*[\s\S]*?\*/\s*\n)?\s*.*\b$methodName\s*\([^)]*\)\s*(?:throws\s+[^;]+)?\s*;[^\n]*\n?""")
            filteredResponse = filteredResponse.replace(pattern, "")
            logger.info("환각 메서드 제거 완료: $methodName")
        }

        filteredResponse = filteredResponse.replace(Regex("\n{3,}"), "\n\n")
        return filteredResponse
    }
    // 가이드 및 프롬프트 생성 유틸들

    /**
     * [Phase 2] Contract 기반 프롬프트 섹션을 구성합니다.
     * 확정된 시그니처 계약과 Few-shot 예시(올바른/잘못된)를 포함합니다.
     *
     * @param contract 전체 Contract (null이면 빈 문자열 반환)
     * @param targetFile 현재 생성 대상 파일
     * @return 프롬프트에 삽입할 Contract 섹션 텍스트
     */
    private fun buildContractSection(contract: ImplementationContract?, targetFile: TargetFileSpec): String {
        if (contract == null) return ""

        val fileContract = contract.fileContracts.find { it.filePath == targetFile.path }
        if (fileContract == null || fileContract.methods.isEmpty()) return ""

        return buildString {
            appendLine()
            appendLine("## 📋 확정 시그니처 계약 (반드시 준수)")
            appendLine("아래 시그니처는 이미 다른 파일들과 합의된 계약입니다. **절대 변경하지 마세요.**")
            appendLine()

            for (method in fileContract.methods) {
                appendLine("- **메서드명**: `${method.methodName}`")
                appendLine("- **반환 타입**: `${method.returnType}`")
                appendLine("- **파라미터**: `${method.paramSignature}`")
                if (method.isStatic) appendLine("- **호출 방식**: `static`")
                if (method.calledBy.isNotEmpty()) {
                    appendLine("- **이 메서드의 호출자**: ${method.calledBy.joinToString(", ") { "`$it`" }}")
                }
                if (method.calls.isNotEmpty()) {
                    appendLine("- **이 메서드가 호출하는 대상**: ${method.calls.joinToString(", ") { "`$it`" }}")
                }
                appendLine()
            }

            // 호출 관계 컨텍스트 (파일 단위)
            if (fileContract.calledFrom.isNotEmpty() || fileContract.callsTo.isNotEmpty()) {
                appendLine("## 🔗 호출 관계 (이 파일의 위치)")
                if (fileContract.calledFrom.isNotEmpty()) {
                    appendLine("**이 파일을 호출하는 곳:**")
                    fileContract.calledFrom.distinctBy { it.callerClass }.forEach {
                        appendLine("- `${it.callerClass}` → 이 파일의 메서드를 호출합니다. 반환 타입이 호출자의 기대와 일치해야 합니다.")
                    }
                }
                if (fileContract.callsTo.isNotEmpty()) {
                    appendLine("**이 파일이 호출하는 곳:**")
                    fileContract.callsTo.distinctBy { it.calleeClass }.forEach {
                        appendLine("- 이 파일 → `${it.calleeClass}`의 메서드를 호출합니다. 파라미터 타입이 대상의 시그니처와 일치해야 합니다.")
                    }
                }
                appendLine()
            }

            // 계층 아키텍처 규칙 주입
            val lowerPath = targetFile.path.lowercase()
            appendLine("## 🏗️ 계층 아키텍처 규칙")
            when {
                lowerPath.contains("service") && !lowerPath.contains("controller") -> {
                    appendLine("이 파일은 **Service 계층**입니다:")
                    appendLine("- ❌ `HttpServletRequest`, `HttpServletResponse`를 파라미터로 받지 마세요 — Controller의 역할입니다.")
                    appendLine("- ❌ `SqlSession`, MyBatis XML 매퍼를 직접 호출하지 마세요 — DAO의 역할입니다.")
                    appendLine("- ✅ DAO/Repository를 주입받아 호출하고, 비즈니스 로직만 처리하세요.")
                    appendLine("- ✅ 파라미터는 `Map`, `DTO`, 또는 기본 타입만 사용하세요.")
                }
                lowerPath.contains("dao") || (lowerPath.contains("repository") && !lowerPath.contains("controller")) -> {
                    appendLine("이 파일은 **DAO/Repository 계층**입니다:")
                    appendLine("- ❌ `HttpServletRequest`, `HttpServletResponse`를 파라미터로 받지 마세요.")
                    appendLine("- ❌ 인증(JWT/Session) 검증 로직을 넣지 마세요 — Controller/Filter의 역할입니다.")
                    appendLine("- ✅ 오직 DB 접근(SQL 실행, MyBatis 호출)만 담당하세요.")
                }
                lowerPath.contains("controller") -> {
                    appendLine("이 파일은 **Controller 계층**입니다:")
                    appendLine("- ❌ `SqlSession`, MyBatis XML 매퍼를 직접 호출하지 마세요 — DAO의 역할입니다.")
                    appendLine("- ✅ Service를 주입받아 호출하고, 요청/응답 변환만 처리하세요.")
                    appendLine("- ✅ `HttpServletRequest`, `HttpServletResponse`는 이 계층에서만 사용 가능합니다.")
                }
                lowerPath.let { it.contains("util") || it.contains("helper") } -> {
                    appendLine("이 파일은 **Utility 계층**입니다:")
                    appendLine("- ✅ 입력 파라미터 타입을 Contract에 명시된 타입과 정확히 일치시키세요.")
                    appendLine("- ✅ Service나 Controller에서 전달받은 데이터를 가공하는 역할입니다.")
                }
                else -> {}
            }
            appendLine()

            // Few-shot: 올바른 예시
            appendLine("## ✅ 올바른 예시")
            for (method in fileContract.methods) {
                val roleHint = when (fileContract.role) {
                    FileRole.INTERFACE_DECLARATION -> {
                        "```java\n${method.returnType} ${method.methodName}(${method.paramSignature});\n```"
                    }
                    FileRole.OVERRIDE_IMPLEMENTATION -> {
                        "```java\n@Override\npublic ${method.returnType} ${method.methodName}(${method.paramSignature}) {\n    // 구현 코드\n}\n```"
                    }
                    FileRole.CALLER -> {
                        "```java\n${method.returnType} result = service.${method.methodName}(paramMap);\n```"
                    }
                    else -> ""
                }
                if (roleHint.isNotBlank()) appendLine(roleHint)
            }

            // Few-shot: 잘못된 예시
            appendLine()
            appendLine("## ❌ 잘못된 예시 (절대 금지)")
            for (method in fileContract.methods) {
                val wrongType = when {
                    method.returnType.contains("HashMap") -> "List<SurveyDto>"
                    method.returnType.contains("List") -> "JSONArray"
                    else -> "Object"
                }
                appendLine("```java")
                appendLine("public $wrongType ${method.methodName}(...) // ← 반환 타입 변경 금지")
                appendLine("```")
            }

            appendLine()
            appendLine("## ⚠️ 금지 사항")
            for (method in fileContract.methods) {
                appendLine("- `${method.methodName}`의 반환 타입을 `${method.returnType}` 외의 타입으로 변경 금지")
                appendLine("- 파라미터 타입을 `${method.paramSignature}` 외의 타입으로 변경 금지")
            }
            appendLine()
        }
    }
}
