package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.FileMatch
import net.ib.ixpert.ops.wuwagent.service.FileSearchService
import net.ib.ixpert.ops.wuwagent.service.SearchResult

/**
 * 프로젝트 내 텍스트/키워드 검색(/find)을 전담하는 Agent.
 * FileSearchService.searchInFiles로 파일 내용을 검색한 뒤,
 * 구조화된 결과를 LLM에 전달하여 자연어 요약/설명을 생성합니다.
 */
class FindAgent : BaseAgent() {

    override fun execute(
        context: AgentContext,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        val keyword = context.payloadText.trim()
        if (keyword.isBlank()) {
            onError("[알림] 검색어를 입력해주세요.")
            return
        }

        if (DumbService.isDumb(context.project)) {
            DumbService.getInstance(context.project).runWhenSmart {
                executeSearchAndLlm(context, keyword, onSuccess, onChunk, onError)
            }
            return
        }

        executeSearchAndLlm(context, keyword, onSuccess, onChunk, onError)
    }

    private fun executeSearchAndLlm(
        context: AgentContext,
        keyword: String,
        onSuccess: (String) -> Unit,
        onChunk: ((String) -> Unit)?,
        onError: (String) -> Unit
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            context.project, "코드 검색 중...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val searchResult = FileSearchService.searchInFiles(context.project, keyword, indicator)
                if (searchResult.matches.isEmpty()) {
                    onSuccess("검색 결과가 없습니다.")
                    return
                }

                indicator.checkCanceled()

                val promptVars = mapOf(
                    "SEARCH_KEYWORD" to keyword,
                    "SEARCH_RESULTS" to formatSearchResults(searchResult),
                    "RESULT_SUMMARY" to formatSummary(searchResult)
                )

                val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance()
                val isAndroid = settings.state.frameworkType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANDROID
                val findPromptFile = if (isAndroid) "find_android_prompt.txt" else "find_prompt.txt"
                var systemPrompt = PromptManager.loadPromptWithVars(findPromptFile, promptVars)

                // [Phase 1b] 메타그래프 컨텍스트 자동 주입
                val contextAssembler = context.project.getService(
                    net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.ContextAssembler::class.java
                )
                val graphContext = contextAssembler.assemble(context, keyword)
                if (graphContext.isNotBlank()) {
                    systemPrompt = "$graphContext\n\n$systemPrompt"
                }

                val userMessage = "\"$keyword\" 검색 결과를 바탕으로 설명해주세요."

                callLlmStreamAsync(context.project, "iXpert AI Assistant: Finding in Project", systemPrompt, userMessage, onSuccess, onChunk, onError)
            }
        })
    }

    // ── 검색 결과 → 프롬프트 변수 포맷팅 ────────────────────────────────

    private fun formatSummary(result: SearchResult): String {
        val base = "${result.totalFiles}개 파일, ${result.totalMatches}개 라인 매칭"
        if (!result.truncated) return base

        val reasons = mutableListOf<String>()
        if (result.truncatedByFileLimit) reasons.add("매칭 파일이 많아 일부 파일은 검색되지 않음")
        if (result.truncatedByLineLimit) reasons.add("매칭 라인이 많아 일부 라인은 표시되지 않음")
        return "$base (${reasons.joinToString(", ")})"
    }

    private fun formatSearchResults(result: SearchResult): String {
        return result.matches.joinToString("\n\n") { fileMatch -> formatFileMatch(fileMatch) }
    }

    private fun formatFileMatch(fileMatch: FileMatch): String {
        val lineBlocks = fileMatch.lines.joinToString("\n") { lineMatch ->
            val contextText = lineMatch.context.joinToString("\n") { "    $it" }
            "- L${lineMatch.lineNumber}: ${lineMatch.text.trim()}\n  컨텍스트:\n$contextText"
        }
        return "### 파일: ${fileMatch.filePath}\n$lineBlocks"
    }
}
