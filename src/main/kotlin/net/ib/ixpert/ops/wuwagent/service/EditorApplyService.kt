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
     * @param project       현재 프로젝트
     * @param code          에디터에 적용할 코드 문자열
     * @param scope         적용 대상 파일명 (Diff에서 포커싱 전환용)
     * @param originalCode  분석 당시 원본 코드 (위치/내용 불일치 방지용)
     * @return              성공 여부를 나타내는 메시지
     */
    fun apply(project: Project, code: String, scope: String = "", originalCode: String = ""): String {
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            val fileEditorManager = FileEditorManager.getInstance(project)
            var targetEditor: Editor? = fileEditorManager.selectedTextEditor

            // Diff 뷰어 등 메인 텍스트 에디터가 아닐 경우, 원본 코드로 자동 포커싱 시도
            if (targetEditor != null && targetEditor.editorKind != com.intellij.openapi.editor.EditorKind.MAIN_EDITOR) {
                if (scope.isNotBlank() && scope != "선택 영역" && scope != "전체 파일") {
                    val targetFile = fileEditorManager.openFiles.firstOrNull { it.name == scope }
                    if (targetFile != null) {
                        val openedEditors = fileEditorManager.openFile(targetFile, true)
                        if (openedEditors.isNotEmpty()) {
                            targetEditor = fileEditorManager.selectedTextEditor
                            logger.info("EditorApplyService: Diff 뷰어에서 원본 파일($scope)로 포커스 자동 전환 완료")
                        }
                    }
                }
            }

            // 포커스 전환 이후에도 유효한 텍스트 에디터가 아니라면 중단
            if (targetEditor == null || targetEditor.editorKind != com.intellij.openapi.editor.EditorKind.MAIN_EDITOR || !targetEditor.document.isWritable) {
                result = "[오류] 코드 에디터를 활성화한 후 다시 시도하세요"
                return@invokeAndWait
            }

            // Apply 전 검증 추가 (위치 mismatch 방지)
            if (originalCode.isNotBlank()) {
                val isSelection = targetEditor!!.selectionModel.hasSelection()
                val currentTextToCompare = if (isSelection) {
                    targetEditor!!.selectionModel.selectedText ?: ""
                } else {
                    targetEditor!!.document.text
                }
                
                // 원본 코드와 현재 에디터 코드가 다르면 중단
                if (currentTextToCompare.trim() != originalCode.trim()) {
                    result = "[오류] 에디터의 현재 코드가 분석 당시의 원본과 다릅니다. (수정 중복 및 덮어쓰기 방지)"
                    return@invokeAndWait
                }
            }

            WriteCommandAction.runWriteCommandAction(project, "WuwAgent: Apply Code", null, {
                val document = targetEditor!!.document
                val selectionModel = targetEditor!!.selectionModel

                if (selectionModel.hasSelection()) {
                    logger.info("EditorApplyService: 선택 영역 교체 적용")
                    document.replaceString(
                        selectionModel.selectionStart,
                        selectionModel.selectionEnd,
                        code
                    )
                } else {
                    logger.info("EditorApplyService: 전체 문서 내용 변경 적용")
                    document.replaceString(0, document.textLength, code)
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
