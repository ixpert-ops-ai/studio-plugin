package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService

/**
 * 코드 설명(Explain) 오케스트레이션을 관장하는 Agent 컴포넌트입니다.
 * UI나 외부 브릿지 통신에 대한 모든 참조를 제거하고, 오직 로직 흐름과 결과 리턴(Callback)만 다룹니다.
 */
class ExplainAgent(private val project: Project, private val editor: Editor) {
    private val logger = Logger.getInstance(ExplainAgent::class.java)
    private val ollamaClient = OllamaClient()

    fun execute(onSuccess: (String) -> Unit) {
        // 1. Service: 코드 영역 획득
        val codeToExplain = EditorContextService.extractCode(editor, project)
        if (codeToExplain.isBlank()) {
            onSuccess("분석할 코드를 확인하지 못했습니다.")
            return
        }

        // 백그라운드 스레드에서 LLM 비동기 연동 진행
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "WuwAgent: Explaining Code", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Ollama 서버에 코드 분석(Explain) 요청 진행 중..."

                // 2. PromptManager: 프롬프트 세팅
                val systemPrompt = PromptManager.loadPrompt("explain_prompt.txt")

                // 3. Client: HTTP 요청
                val response = ollamaClient.callChatApi(systemPrompt, codeToExplain)

                val resultText = response?.message?.content ?: "서버에서 응답을 파싱하는데 실패했습니다."

                // 4. 의존성 격리: 성공 결과를 콜백으로만 돌려준다.
                onSuccess(resultText)
            }
        })
    }
}
