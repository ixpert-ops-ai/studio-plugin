package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import net.ib.ixpert.ops.wuwagent.prompt.ImpactFormatter
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import net.ib.ixpert.ops.wuwagent.service.analysis.ImpactAnalyzer

/**
 * 코드 변경 영향 범위를 분석하는 Agent.
 * PSI 기반 ImpactAnalyzer로 호출 트리를 추출한 후,
 * 구조화된 결과를 LLM에 전달하여 자연어 분석을 수행합니다.
 */
class ImpactAgent : BaseAgent() {

    private val log = Logger.getInstance(ImpactAgent::class.java)

    override fun execute(
        context: AgentContext,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        val editor = context.editor ?: run {
            onError("[상태 이상] 에디터 컨텍스트가 주어지지 않았습니다.")
            return
        }

        val project = context.project

        // 1. 커서 위치에서 분석 대상 요소 찾기
        val rawElement = EditorContextService.extractElementAtCaret(editor, project)
        if (rawElement == null) {
            onError("[알림] 영향도를 분석할 대상을 찾을 수 없습니다.\n커서를 메서드, 클래스 또는 필드 위에 위치시켜주세요.")
            return
        }

        val targetElement = findBestTargetElement(rawElement)
        if (targetElement == null) {
            onError("[알림] 커서 위치에서 분석 가능한 메서드, 클래스 또는 필드를 찾을 수 없습니다.\n메서드명, 클래스명 또는 필드명 위에 커서를 놓고 다시 시도해 주세요.")
            return
        }

        val codeResult = EditorContextService.extractCodeWithScope(editor, project)
        val languageId = EditorContextService.extractLanguageId(editor, project)
        val codeToAnalyze = if (codeResult.isSelection && codeResult.code.isNotBlank()) {
            codeResult.code
        } else {
            targetElement.text
        }

        // 2. 단일 ProgressManager 태스크 안에서 ImpactAnalyzer(동기) → LLM(스트리밍) 직렬 실행
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "iXpert AI Assistant: 영향도 분석 중...", true) {
            override fun run(indicator: ProgressIndicator) {
                TaskCancellationToken.reset()
                TaskCancellationToken.backgroundThread = Thread.currentThread()
                TaskCancellationToken.activeMessageId = "msg_${System.currentTimeMillis()}"

                indicator.isIndeterminate = false
                indicator.text = "참조 및 호출 계층 분석 중..."

                // Step 1: PSI 기반 영향도 분석 (동기)
                val analysisResult = try {
                    ImpactAnalyzer.analyzeSync(project, targetElement)
                } catch (e: Exception) {
                    log.error("영향도 분석 실패", e)
                    onError("[오류] 영향도 분석 중 오류가 발생했습니다: ${e.message}")
                    return
                }

                if (TaskCancellationToken.isCancelled.get()) {
                    onError("__cancelled__")
                    return
                }

                log.info(
                    "영향도 분석 완료: target=${analysisResult.targetSignature}, " +
                    "direct=${analysisResult.statistics.directCallerCount}, " +
                    "total=${analysisResult.statistics.totalAffected}"
                )

                indicator.text = "LLM 응답 대기 중..."
                indicator.isIndeterminate = true

                // Step 2: 프롬프트 생성
                val vars = ImpactFormatter.toPromptVariables(analysisResult, codeToAnalyze, languageId)
                val isAndroid = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance().state.frameworkType ==
                    net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANDROID
                val impactPromptFile = if (isAndroid) "impact_android_prompt.txt" else "impact_prompt.txt"
                val systemPrompt = PromptManager.loadPromptWithVars(impactPromptFile, vars)
                val summaryHeader = ImpactFormatter.buildSummaryHeader(analysisResult)
                val userMessage = "$summaryHeader\n\n위 분석 결과를 바탕으로 상세한 영향도 분석을 수행해 주세요."

                // Step 3: 사용자 중심의 분석 범위 배너 생성
                val total = analysisResult.statistics.totalAffected
                val type = analysisResult.targetType
                val typeIcon = when (type.lowercase()) {
                    "method", "constructor" -> "🔹"
                    "class", "interface", "enum", "annotation" -> "📦"
                    "field" -> "📝"
                    else -> "🔍"
                }

                val banner = if (codeResult.isSelection && codeResult.startLine != null && codeResult.endLine != null) {
                    val fileName = EditorContextService.extractFileName(editor)
                    "### 🔍 코드 변경 영향도 리포트\n" +
                    "**분석 범위:** `$fileName` (Line ${codeResult.startLine} ~ ${codeResult.endLine})\n" +
                    "**파급 규모:** 총 **${total}개** 요소에 파급 효과 예상\n\n---\n"
                } else {
                    "### 🔍 코드 변경 영향도 리포트\n" +
                    "**분석 대상:** $typeIcon [$type] `${analysisResult.targetLocation}`\n" +
                    "**식별자:** `${analysisResult.targetSignature}`\n" +
                    "**파급 규모:** 총 **${total}개** 요소에 파급 효과 예상\n\n---\n"
                }

                // Step 4: LLM 스트리밍 호출 (배너 유지 로직 포함)
                var isFirstChunk = true
                var finalContentWithBanner = ""

                val wrappedOnChunk: (String) -> Unit = { chunk ->
                    if (isFirstChunk) {
                        val firstChunkWithBanner = banner + chunk
                        finalContentWithBanner = firstChunkWithBanner
                        onChunk?.invoke(firstChunkWithBanner)
                        isFirstChunk = false
                    } else {
                        finalContentWithBanner += chunk
                        onChunk?.invoke(chunk)
                    }
                }

                val response = ollamaClient.chat(systemPrompt, userMessage, onChunk = wrappedOnChunk)

                if (TaskCancellationToken.isCancelled.get()) {
                    onError("__cancelled__")
                    return
                }

                val resultText = response?.message?.content ?: "[오류 발생] 알 수 없는 데이터 널 응답"
                if (resultText.startsWith("[Error]")) {
                    onError(resultText)
                } else {
                    // LLM의 날것(resultText)이 아닌, 배너가 포함된 누적본을 최종 전달하여 덮어쓰기 방지
                    onSuccess(finalContentWithBanner)
                }
            }
        })
    }

    /**
     * 커서 위치의 토큰에서 분석 대상 선언 요소를 찾음.
     */
    private fun findBestTargetElement(rawElement: PsiElement): PsiElement? {
        var current: PsiElement? = rawElement
        while (current != null) {
            if (!current.isValid) { current = current.parent; continue }
            if (current is PsiField) return current
            if (current is PsiMethod) return current
            if (current is PsiClass && current !is PsiAnonymousClass) return current
            if (current is PsiNamedElement) {
                val cn = current.javaClass.simpleName
                when (cn) {
                    "KtProperty" -> return current
                    "KtNamedFunction" -> return current
                    "KtPropertyAccessor" -> return current
                    "KtClass", "KtObjectDeclaration", "KtClassOrObject" -> return current
                }
            }
            current = current.parent
        }
        return null
    }
}
