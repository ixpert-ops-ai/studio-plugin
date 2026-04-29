package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import java.io.File

/**
 * [Phase 2b] 요구사항 분석 결과(TargetFiles)를 바탕으로 실제 소스 코드를 수집하고,
 * 순차적으로 LLM에 전달하여 코드 수정을 수행하는 파이프라인.
 */
class ImplementationPipeline(
    private val client: OllamaClient,
    private val project: Project
) {
    private val logger = Logger.getInstance(ImplementationPipeline::class.java)

    /**
     * 파일 경로를 기반으로 계층 가중치를 계산합니다. (Phase 2a의 계층 역순 정렬 보완)
     */
    private fun getLayerWeight(path: String): Int {
        val lowerPath = path.lowercase()
        return when {
            lowerPath.contains("dao") || lowerPath.contains("repository") || lowerPath.contains("entity") -> 1 // PERSISTENCE
            lowerPath.contains("service") || lowerPath.contains("biz") -> 2 // BUSINESS
            lowerPath.contains("controller") || lowerPath.contains("api") || lowerPath.contains("web") -> 3 // PRESENTATION
            else -> 4 // COMMON (dto, util, config 등)
        }
    }

    private val psiMethodExtractor = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.PsiMethodExtractor(project)

    fun execute(
        analysisResult: RequirementAnalysisResult,
        onChunk: (String) -> Unit
    ) {
        val sortedTargets = analysisResult.targetFiles.sortedBy { getLayerWeight(it.path) }
        
        logger.info("ImplementationPipeline: 총 ${sortedTargets.size}개 파일 순차 처리 시작")

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
            val isNewFile = target.type.contains("신규")
            val isDtoOrEntity = target.path.lowercase().let { p ->
                p.contains("dto") || p.contains("entity") || p.contains("vo") || p.contains("model")
            }
            val isInterface = target.path.lowercase().let { p ->
                !p.contains("impl") && (
                    p.endsWith("dao.java") ||
                    p.endsWith("service.java") ||
                    p.endsWith("repository.java") ||
                    p.endsWith("mapper.java")
                )
            }
            val isLargeFile = !isNewFile && !isDtoOrEntity && psiMethodExtractor.isLargeFile(target.path)
            
            val systemPrompt = if (isLargeFile) buildLargeFileSystemPrompt(isInterface) else buildSmallFileSystemPrompt(isInterface)
            val userPrompt = buildUserPromptForFile(target, contextChain, analysisResult.summary, sortedTargets)

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
                val response = client.callChatApiStream(systemPrompt, userPrompt) { chunk ->
                    if (abortReason != null) return@callChatApiStream

                    fullResponse += chunk

                    // === 가드 1: 응답 길이 상한 ===
                    if (fullResponse.length > MAX_RESPONSE_CHARS) {
                        logger.warn("응답 길이 상한 초과 (${fullResponse.length}자): ${target.path}")
                        abortReason = "LENGTH"
                        return@callChatApiStream
                    }

                    // === 가드 2: 반복 패턴 감지 ===
                    val lines = fullResponse.lines()
                    if (lines.size > 10) {
                        val recentLines = lines.takeLast(10)
                        val uniquePatterns = recentLines.map { line ->
                            line.trim().replace(Regex("\\d+"), "")
                        }.filter { it.isNotEmpty() }.toSet()

                        if (uniquePatterns.size <= 2 && recentLines.all { it.trim().isNotEmpty() }) {
                            consecutiveRepeatCount++
                            if (consecutiveRepeatCount >= 3) {
                                logger.warn("반복 패턴 감지 (고유 패턴 ${uniquePatterns.size}개): ${target.path}")
                                abortReason = "REPEAT"
                                return@callChatApiStream
                            }
                        } else {
                            consecutiveRepeatCount = 0
                        }
                    }

                    onChunk(chunk)
                }

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

                // 인터페이스 파일인 경우 환각 메서드 필터링
                val processedResponse = if (isInterface) {
                    val filtered = filterInterfaceHallucination(finalResponseText, target, sortedTargets)
                    if (filtered != finalResponseText) {
                        logger.info("인터페이스 환각 필터링 적용: ${target.path}")
                    }
                    filtered
                } else {
                    finalResponseText
                }

                generatedSnippets[target.path] = processedResponse // Phase 2c 단위 테스트 생성을 위해 응답 캐시 저장

                if (isLargeFile) {
                    snippetFileResults.add(target.path)
                } else {
                    fullFileResults.add(target.path)
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
        allTargetFiles: List<TargetFileSpec>
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
            buildNewFilePrompt(correctedTarget, contextChain, requirementSummary, allTargetFiles)
        } else if (!isDtoOrEntity && psiMethodExtractor.isLargeFile(correctedTarget.path)) {
            buildLargeFilePrompt(correctedTarget, contextChain, requirementSummary, allTargetFiles)
        } else {
            buildSmallFilePrompt(correctedTarget, contextChain, requirementSummary, allTargetFiles)
        }
    }

    private fun buildCommonUserPrompt(
        targetFile: TargetFileSpec,
        contextChain: List<String>,
        requirementSummary: String,
        allTargetFiles: List<TargetFileSpec>,
        sourceCodeSection: String
    ): String {
        val overallPlan = allTargetFiles.joinToString("\n") { 
            val marker = if (it.path == targetFile.path) "👉 " else "   "
            "${marker}- [${it.type}] ${it.path} : ${it.description}" 
        }
        val contextChainStr = if (contextChain.isEmpty()) "없음" else contextChain.joinToString("\n")
        
        return """
            ## 요구사항 요약
            $requirementSummary
            
            ## 전체 작업 계획
            $overallPlan
            
            ## 이전 단계까지의 수정 요약 (참고용 컨텍스트)
            $contextChainStr
            
            ---
            
            ## 🎯 현재 작업 대상
            - 경로: ${targetFile.path}
            - 유형: ${targetFile.type}
            - 작업 내용: ${targetFile.description}
            
            ## 파일 분석 / 소스 코드
            $sourceCodeSection
        """.trimIndent()
    }

    private fun buildNewFilePrompt(
        targetFile: TargetFileSpec,
        contextChain: List<String>,
        requirementSummary: String,
        allTargetFiles: List<TargetFileSpec>
    ): String {
        return buildCommonUserPrompt(
            targetFile, contextChain, requirementSummary, allTargetFiles, 
            "이 파일은 신규 생성입니다. 요구사항에 맞는 전체 코드를 새로 작성하세요."
        )
    }

    private fun buildSmallFilePrompt(
        targetFile: TargetFileSpec,
        contextChain: List<String>,
        requirementSummary: String,
        allTargetFiles: List<TargetFileSpec>
    ): String {
        val absolutePath = "${project.basePath}/${targetFile.path}".replace("//", "/")
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
        
        if (virtualFile == null || !virtualFile.exists()) {
            logger.warn("VirtualFile을 찾을 수 없습니다: $absolutePath")
            return "> ⚠️ **파일을 찾을 수 없어 건너뜁니다:** `${targetFile.path}`\n\n"
        }
        val sourceCode = String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
        return buildCommonUserPrompt(targetFile, contextChain, requirementSummary, allTargetFiles, "```java\n$sourceCode\n```")
    }

    private fun buildLargeFilePrompt(
        targetFile: TargetFileSpec,
        contextChain: List<String>,
        requirementSummary: String,
        allTargetFiles: List<TargetFileSpec>
    ): String {
        val skeleton = psiMethodExtractor.extract(
            filePath = targetFile.path,
            taskDescription = targetFile.description,
            taskType = targetFile.type
        )

        if (skeleton == null) {
            logger.warn("스켈레톤 추출 실패, 스킵: ${targetFile.path}")
            return "> ⚠️ **파일 파싱 실패로 건너뜁니다:** `${targetFile.path}`\n\n"
        }
        
        val sourceCodeSection = """
            이 파일은 대형 파일이므로 클래스 구조와 연관 메서드 스니펫만 제공됩니다.
            
            ${skeleton.toPromptText()}
        """.trimIndent()
        
        return buildCommonUserPrompt(targetFile, contextChain, requirementSummary, allTargetFiles, sourceCodeSection)
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
        allTargetFiles: List<TargetFileSpec>
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

        // 구현체 파일들의 description에서 키워드 추출
        val implDescriptions = allTargetFiles
            .filter { it.path.lowercase().contains("impl") }
            .joinToString(" ") { it.description + " " + it.path }
            .lowercase()

        val hallucinatedMethods = newMethods.filter { methodName ->
            !implDescriptions.contains(methodName.lowercase())
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
}
