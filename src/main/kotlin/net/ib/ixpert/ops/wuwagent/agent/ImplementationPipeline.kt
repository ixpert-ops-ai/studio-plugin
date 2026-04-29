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

        for ((index, target) in sortedTargets.withIndex()) {
            val progressHeader = "\n\n### 🔄 [${index + 1}/${sortedTargets.size}] `${target.path}` 처리 중...\n\n"
            onChunk(progressHeader)

            // 파일 및 프롬프트 생성 로직
            val isNewFile = target.type.contains("신규")
            val isDtoOrEntity = target.path.lowercase().let { p ->
                p.contains("dto") || p.contains("entity") || p.contains("vo") || p.contains("model")
            }
            val isLargeFile = !isNewFile && !isDtoOrEntity && psiMethodExtractor.isLargeFile(target.path)
            
            val systemPrompt = if (isLargeFile) buildLargeFileSystemPrompt() else buildSmallFileSystemPrompt()
            val userPrompt = buildUserPromptForFile(target, contextChain, analysisResult.summary, sortedTargets)

            // Fallback guide returned from buildUserPromptForFile means skipping
            if (userPrompt.startsWith("> ⚠️")) {
                onChunk(userPrompt)
                continue
            }

            logger.info("Processing target: ${target.path}")
            
            var fullResponse = ""
            var consecutiveRepeatCount = 0
            val MAX_RESPONSE_CHARS = 15_000

            try {
                val response = client.callChatApiStream(systemPrompt, userPrompt) { chunk ->
                    fullResponse += chunk

                    // === 가드 1: 응답 길이 상한 ===
                    if (fullResponse.length > MAX_RESPONSE_CHARS) {
                        logger.warn("응답 길이 상한 초과 (${fullResponse.length}자): ${target.path}")
                        throw ResponseTooLongException(target.path)
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
                                throw RepetitionDetectedException(target.path)
                            }
                        } else {
                            consecutiveRepeatCount = 0
                        }
                    }

                    onChunk(chunk)
                }

                val finalResponseText = response?.message?.content ?: fullResponse
                generatedSnippets[target.path] = finalResponseText // Phase 2c 단위 테스트 생성을 위해 응답 캐시 저장

                if (finalResponseText.contains("[MODIFIED_SIGNATURES]")) {
                    val signaturesText = finalResponseText.substringAfter("[MODIFIED_SIGNATURES]").trim()
                    if (signaturesText.isNotBlank()) {
                        // [수정 2] 응답 코드 블록 내에 해당 시그니처의 메서드명이 존재하는지 검증
                        val responseBody = finalResponseText.substringBefore("[MODIFIED_SIGNATURES]")
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
            } catch (e: RepetitionDetectedException) {
                onChunk("\n\n> ⚠️ **반복 패턴이 감지되어 생성을 중단했습니다.** `${target.path}`는 수동 수정이 필요합니다.\n\n")
                continue
            } catch (e: ResponseTooLongException) {
                onChunk("\n\n> ⚠️ **응답이 비정상적으로 길어 생성을 중단했습니다.** `${target.path}`는 수동 수정이 필요합니다.\n\n")
                generatedSnippets[target.path] = fullResponse
                continue
            } catch (e: Exception) {
                logger.error("Error processing file ${target.path}", e)
                onChunk("\n\n> ❌ **코드 생성 중 에러가 발생하여 이 파일을 건너뜁니다:** `${e.message}`\n\n")
                continue
            }
        }
        TestGenerationPipeline.lastImplementContext = TestGenerationPipeline.ImplementContext(
            targetFiles = sortedTargets,
            contextChain = contextChain.toList(),
            generatedSnippets = generatedSnippets
        )

        onChunk("\n\n✅ **모든 파일의 자동 코드 수정 제안이 완료되었습니다.**\n수정된 코드를 확인하시고 Apply 버튼을 눌러 적용해주세요.")
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

    private fun buildSmallFileSystemPrompt(): String {
        return """
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
    }

    private fun buildLargeFileSystemPrompt(): String {
        return """
            당신은 Spring Boot 시니어 개발자입니다.
            
            아래 규칙을 반드시 준수하세요:
            1. 오직 수정하거나 새로 추가할 메서드의 완전한 코드만 반환하세요.
            2. 변경되지 않는 기존 메서드는 절대 출력하지 마세요.
            3. 각 메서드 블록 위에 위치 힌트를 반드시 포함하세요:
               - 기존 메서드 수정: // 📍 대체 위치: {파일명} → {메서드명}() 메서드 교체
               - 신규 메서드 추가: // 📍 삽입 위치: {파일명} → 클래스 바디 하단 (새 메서드)
            4. 기존 메서드를 교체할 때, 변경하지 않는 로직 부분도 생략하지 말고 전체를 작성하세요. 
               `// ... 기존 코드 ...` 같은 생략 표현을 절대 사용하지 마세요.
            5. 필요한 import문이 있으면 코드 블록 최상단에 별도로 나열하세요.
            6. 기존 코드 스타일(어노테이션 사용 패턴, 네이밍 컨벤션)을 유지하세요.
            7. 이전 파일 수정 요약에 기재된 메서드 시그니처(메서드명, 파라미터 타입, 반환 타입)를 정확히 사용하세요. 임의로 메서드명을 변경하지 마세요.
            8. 코드 블록 마지막에 [MODIFIED_SIGNATURES] 태그로 변경/추가된 메서드 시그니처를 나열하세요.
            9. **절대로 존재하지 않는 변수나 필드를 임의로 생성하지 마세요.**
               기존 코드에 없는 필드(예: userAuthGroupLevel1, alimtalk_image_link_custom 등)를 
               반복적으로 선언하는 것은 금지입니다. 오직 요구사항에 명시된 필드만 추가하세요.
            10. 변수 선언은 요구사항에서 요청한 것만 최소한으로 작성하세요.
                비슷한 이름의 변수를 번호를 붙여 반복 생성하지 마세요.
        """.trimIndent()
    }
    private class RepetitionDetectedException(val filePath: String) : RuntimeException()
    private class ResponseTooLongException(val filePath: String) : RuntimeException()
}
