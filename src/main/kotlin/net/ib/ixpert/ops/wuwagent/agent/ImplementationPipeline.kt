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

    fun execute(
        analysisResult: RequirementAnalysisResult,
        onChunk: (String) -> Unit
    ) {
        // 1. 파일 목록 계층 기반 정렬
        val sortedTargets = analysisResult.targetFiles.sortedBy { getLayerWeight(it.path) }
        
        // 전체 작업 계획 문자열 생성
        val overallPlan = sortedTargets.joinToString("\n") { 
            "- [${it.type}] ${it.path} : ${it.description}" 
        }
        
        logger.info("ImplementationPipeline: 총 ${sortedTargets.size}개 파일 순차 처리 시작")

        // 이전 파일들의 수정 내역(메서드 시그니처 등)을 누적할 컨텍스트 체인
        val contextChain = mutableListOf<String>()

        // 2. 파일별 순차 처리 루프
        for ((index, target) in sortedTargets.withIndex()) {
            val progressHeader = "\n\n### 🔄 [${index + 1}/${sortedTargets.size}] `${target.path}` 처리 중...\n\n"
            onChunk(progressHeader)

            // 파일 내용 읽기
            var sourceCode = ""
            if (target.type != "신규") {
                val absolutePath = "${project.basePath}/${target.path}".replace("//", "/")
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
                if (virtualFile != null && virtualFile.exists()) {
                    sourceCode = String(virtualFile.contentsToByteArray(), Charsets.UTF_8)
                } else {
                    val fallbackFile = File(absolutePath)
                    if (fallbackFile.exists()) {
                        sourceCode = fallbackFile.readText(Charsets.UTF_8)
                    } else {
                        logger.warn("파일을 찾을 수 없습니다: $absolutePath")
                        sourceCode = "// [오류] 기존 파일의 소스 코드를 읽을 수 없습니다.\n"
                    }
                }
            }

            // System Prompt 구성
            val systemPrompt = """
                당신은 Spring Boot 프로젝트를 구현하는 시니어 백엔드 개발자입니다.
                주어진 요구사항과 작업 계획에 따라, 현재 타겟 파일의 코드를 작성/수정해야 합니다.
                
                ## 코드 작성 규칙
                1. 기존 코드의 스타일과 아키텍처를 반드시 유지하세요.
                2. 필요한 import 문을 모두 포함하여 컴파일 가능한 "전체 코드"를 반환하세요.
                3. 생략(`...`) 없이 모든 메서드와 로직을 완전하게 작성하세요.
                4. 이전 파일들에서 추가/변경된 메서드가 있다면, 그 시그니처를 참고하여 코드를 작성하세요.
                
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

            // User Prompt 구성
            val contextChainStr = if (contextChain.isEmpty()) "없음" else contextChain.joinToString("\n")
            val sourceCodeSection = if (target.type == "신규") {
                "이 파일은 신규 생성입니다. 요구사항에 맞는 전체 코드를 새로 작성하세요."
            } else {
                "```java\n$sourceCode\n```"
            }

            val userPrompt = """
                ## 요구사항 요약
                ${analysisResult.summary}
                
                ## 전체 작업 계획
                $overallPlan
                
                ## 이전 단계까지의 수정 요약 (참고용 컨텍스트)
                $contextChainStr
                
                ---
                
                ## 🎯 현재 작업 대상
                - 경로: ${target.path}
                - 유형: ${target.type}
                - 작업 내용: ${target.description}
                
                ## 기존 소스 코드
                $sourceCodeSection
            """.trimIndent()

            logger.info("Processing target: ${target.path}")
            
            // LLM 호출 및 스트리밍 (청크 단위 파싱 및 필터링 가능하지만, 여기서는 직접 브로드캐스트)
            var fullResponse = ""
            val response = client.callChatApiStream(systemPrompt, userPrompt) { chunk ->
                // 청크를 클라이언트(Webview)에 전송 (하지만 [MODIFIED_SIGNATURES] 부분은 사용자에게 불필요할 수 있으므로,
                // 스트리밍 시 일단 다 보내고 나중에 필터링하거나, 스트리밍에서는 그대로 노출시킴.
                // 여기서는 투명하게 다 보여주는 방식 선택)
                onChunk(chunk)
                fullResponse += chunk
            }

            // 응답 후처리: [MODIFIED_SIGNATURES] 파싱하여 컨텍스트 체인에 추가
            val finalResponseText = response?.message?.content ?: fullResponse
            if (finalResponseText.contains("[MODIFIED_SIGNATURES]")) {
                val signatures = finalResponseText.substringAfter("[MODIFIED_SIGNATURES]").trim()
                if (signatures.isNotBlank()) {
                    contextChain.add("### `${target.path}` 변경사항\n$signatures")
                }
            } else {
                logger.warn("[MODIFIED_SIGNATURES] 블록이 생성되지 않음: ${target.path}")
            }
        }
        
        onChunk("\n\n✅ **모든 파일의 자동 코드 수정 제안이 완료되었습니다.**\n수정된 코드를 확인하시고 Apply 버튼을 눌러 적용해주세요.")
    }
}
