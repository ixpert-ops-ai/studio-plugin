package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.OllamaClient
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService

class ExplainAgent(private val project: Project, private val editor: Editor) {
    private val logger = Logger.getInstance(ExplainAgent::class.java)
    private val ollamaClient = OllamaClient()

    fun execute() {
        // 1. Service 호출: 코드 영역 획득 (선택 영역 또는 파일 전체)
        val codeToExplain = EditorContextService.extractCode(editor, project)
        if (codeToExplain.isBlank()) {
            notifyUser("분석할 코드를 찾을 수 없습니다.")
            return
        }

        // 백그라운드 스레드로 실행 (ANR 방지)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "WuwAgent: Explaining Code", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Ollama (qwen3-coder:30b)에 코드 분석을 요청중입니다..."

                // 2. PromptManager 호출: 특화 프롬프트 로드
                val systemPrompt = PromptManager.loadPrompt("explain_prompt.txt")

                // 3. Client 호출: 외부 API 통신 (HTTP 직접 호출 안함)
                val response = ollamaClient.callChatApi(systemPrompt, codeToExplain)

                val resultText = response?.message?.content ?: "LLM 서버로부터 정상적인 응답을 받지 못했습니다."

                // 4. 이벤트 버스 / UI 렌더링 호출 (현재는 임시 알림으로 대체)
                ApplicationManager.getApplication().invokeLater {
                    notifyUser(resultText)
                }
            }
        })
    }

    private fun notifyUser(message: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("WhatUWantNotification")
        if (group != null) {
            group.createNotification("WhatUWant?", message, NotificationType.INFORMATION).notify(project)
        } else {
            logger.warn("Notification group not found. Fallback log: $message")
        }
    }
}
