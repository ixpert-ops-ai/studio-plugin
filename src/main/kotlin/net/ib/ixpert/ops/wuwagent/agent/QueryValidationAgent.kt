package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import net.ib.ixpert.ops.wuwagent.prompt.PromptManager
import net.ib.ixpert.ops.wuwagent.service.EditorContextService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * SQL / Query 유효성 검증 Agent.
 * 실행 전 스키마 정보(테이블/인덱스)를 입력받는 다이얼로그를 표시한다.
 */
class QueryValidationAgent : BaseAgent() {

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
        val code = EditorContextService.extractCode(editor, context.project)
        if (code.isBlank()) {
            onError("[알림] 분석할 코드를 도출하지 못했습니다.")
            return
        }

        // AtomicReference 사용: null = 취소됨, "" = 건너뛰기, 나머지 = 입력된 스키마
        val schemaRef = AtomicReference<String?>(null)

        ApplicationManager.getApplication().invokeAndWait {
            val dialog = SchemaInputDialog(context.project)
            when {
                !dialog.showAndGet() -> schemaRef.set(null)   // Cancel / X → 취소
                dialog.isSkipped()   -> schemaRef.set("")     // 건너뛰기 → 스키마 없이 진행
                else                 -> schemaRef.set(dialog.getSchema())
            }
        }

        val schemaInfo = schemaRef.get() ?: run {
            onError("분석이 취소되었습니다.")
            return
        }

        logger.info("QueryValidationAgent: schemaInfo='$schemaInfo'")

        val userMessage = buildString {
            append(code)
            if (schemaInfo.isNotBlank()) append("\n\n[테이블 / 인덱스 스키마]\n$schemaInfo")
        }

        // OllamaClient 스트리밍 완료 시 done 청크의 message.content가 빈 문자열로 반환되는 문제 대응.
        val accumulated = StringBuilder()
        val wrappedOnChunk: ((String) -> Unit)? = onChunk?.let { forwardChunk ->
            { chunk: String ->
                accumulated.append(chunk)
                forwardChunk(chunk)
            }
        }
        val wrappedOnSuccess: (String) -> Unit = { resultText ->
            val finalContent = if (resultText.isBlank() && accumulated.isNotEmpty()) {
                accumulated.toString()
            } else {
                resultText
            }
            onSuccess(finalContent)
        }

        callLlmStreamAsync(
            context.project,
            "iXpert AI Assistant: Validating Query",
            PromptManager.loadPrompt("query_validation_prompt.txt"),
            userMessage,
            wrappedOnSuccess,
            wrappedOnChunk,
            onError
        )
    }
}

/**
 * 쿼리 분석 전 스키마 정보를 입력받는 다이얼로그.
 */
private class SchemaInputDialog(project: Project) : DialogWrapper(project) {

    private val textArea = JBTextArea(10, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text =
            "예) CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100));\nCREATE INDEX idx_name ON users(name);"
    }
    private var skipped = false

    init {
        title = "쿼리 분석 - 스키마 정보 입력"
        setOKButtonText("분석 시작")
        setCancelButtonText("취소")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.add(
            JBLabel("분석에 사용할 테이블, 인덱스 정보를 입력해주세요. (없으면 건너뛰기)"),
            BorderLayout.NORTH
        )
        val scroll = JBScrollPane(textArea).apply {
            preferredSize = Dimension(600, 200)
        }
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    override fun createLeftSideActions(): Array<Action> {
        val skipAction = object : DialogWrapperAction("건너뛰기") {
            override fun doAction(e: ActionEvent?) {
                skipped = true
                close(OK_EXIT_CODE)
            }
        }
        return arrayOf(skipAction)
    }

    fun isSkipped(): Boolean = skipped
    fun getSchema(): String = textArea.text.trim()
}
