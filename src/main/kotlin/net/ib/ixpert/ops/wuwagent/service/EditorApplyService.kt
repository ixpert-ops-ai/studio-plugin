package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * LLM이 제안한 코드를 에디터에 실제로 적용하는 서비스.
 *
 * 우선순위:
 * 1. 선택된 텍스트 영역이 있으면 그 영역을 대체
 * 2. 선택 영역이 없으면 커서 위치에 삽입
 */
object EditorApplyService {
    private val logger = Logger.getInstance(EditorApplyService::class.java)

    /**
     * @param project  현재 프로젝트
     * @param code     에디터에 적용할 코드 문자열
     * @return         성공 여부를 나타내는 메시지
     */
    fun apply(project: Project, code: String): String {
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            val editor: Editor? = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor == null) {
                result = "[오류] 활성화된 에디터가 없어 코드를 적용할 수 없습니다."
                return@invokeAndWait
            }

            WriteCommandAction.runWriteCommandAction(project, "WuwAgent: Apply Code", null, {
                val document = editor.document
                val selectionModel = editor.selectionModel

                if (selectionModel.hasSelection()) {
                    logger.info("EditorApplyService: 선택 영역 교체 적용")
                    document.replaceString(
                        selectionModel.selectionStart,
                        selectionModel.selectionEnd,
                        code
                    )
                } else {
                    logger.info("EditorApplyService: 커서 위치에 삽입")
                    document.insertString(editor.caretModel.offset, code)
                }
                result = "코드가 에디터에 성공적으로 적용되었습니다. ✅"
            })
        }
        return result
    }

    /**
     * LLM 응답에서 첫 번째 코드 블록(``` ... ```)을 추출합니다.
     * 코드 블록이 없으면 전체 텍스트를 그대로 반환합니다.
     */
    fun extractCodeBlock(content: String): String {
        val regex = Regex("```(?:\\w+)?\\n?([\\s\\S]*?)```")
        val match = regex.find(content)
        return match?.groupValues?.get(1)?.trim() ?: content.trim()
    }
}
