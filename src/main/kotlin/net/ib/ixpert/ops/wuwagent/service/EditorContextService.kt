package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable

/**
 * IDE 플랫폼 기능(문서 추출, 프로젝트/파일 정보 탐색 등)을 전담하는 서비스입니다.
 */
object EditorContextService {
    /** 에디터 코드 추출 결과 — 코드 본문과 추출 범위(선택 영역/전체 파일) */
    data class CodeExtractionResult(val code: String, val isSelection: Boolean)

    /**
     * 에디터에서 선택된 텍스트를 추출하거나,
     * 선택된 영역이 없으면 문서 전체 텍스트를 반환합니다.
     * 주의: 백그라운드 스레드에서 접근 시 예외가 발생하므로 ReadAction으로 감싸야 합니다.
     */
    fun extractCode(editor: Editor, project: Project): String =
        extractCodeWithScope(editor, project).code

    /**
     * 코드와 함께 "선택 영역인지 여부"를 함께 반환합니다.
     * Diff 기반 Apply를 위해 추가되었습니다.
     */
    /**
     * 현재 에디터에서 열린 파일의 이름을 반환합니다.
     * 파일을 찾을 수 없는 경우 빈 문자열을 반환합니다.
     */
    fun extractFileName(editor: Editor): String {
        return ApplicationManager.getApplication().runReadAction(Computable {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                .getFile(editor.document)?.name ?: ""
        })
    }

    fun extractCodeWithScope(editor: Editor, @Suppress("UNUSED_PARAMETER") project: Project): CodeExtractionResult {
        return ApplicationManager.getApplication().runReadAction(Computable {
            val selectionModel = editor.selectionModel
            val selectedText = selectionModel.selectedText

            if (!selectedText.isNullOrBlank()) {
                CodeExtractionResult(selectedText, isSelection = true)
            } else {
                CodeExtractionResult(editor.document.text, isSelection = false)
            }
        })
    }

    /**
     * 파일의 언어 ID를 반환합니다 (예: "JAVA", "kotlin").
     */
    fun extractLanguageId(editor: Editor, project: Project): String {
        return ApplicationManager.getApplication().runReadAction(Computable {
            val psiFile = com.intellij.psi.PsiDocumentManager.getInstance(project)
                .getPsiFile(editor.document)
            psiFile?.language?.id ?: "Unknown"
        })
    }

    /**
     * PSI 파일 객체를 반환합니다.
     */
    fun extractPsiFile(editor: Editor, project: Project): com.intellij.psi.PsiFile? {
        return ApplicationManager.getApplication().runReadAction(Computable {
            com.intellij.psi.PsiDocumentManager.getInstance(project)
                .getPsiFile(editor.document)
        })
    }

    /**
     * Document 객체를 반환합니다.
     */
    fun extractDocument(editor: Editor): com.intellij.openapi.editor.Document {
        return editor.document
    }

    /**
     * 선택 영역의 시작/끝 줄 번호를 반환합니다. 선택이 없으면 null.
     */
    fun extractLineRange(editor: Editor): Pair<Int, Int>? {
        return ApplicationManager.getApplication().runReadAction(Computable {
            val selectionModel = editor.selectionModel
            if (selectionModel.hasSelection()) {
                val startLine = editor.document.getLineNumber(selectionModel.selectionStart) + 1
                val endLine = editor.document.getLineNumber(selectionModel.selectionEnd) + 1
                Pair(startLine, endLine)
            } else {
                null
            }
        })
    }

    /**
     * 커서 위치의 PsiElement를 반환합니다.
     */
    fun extractElementAtCaret(editor: Editor, project: Project): com.intellij.psi.PsiElement? {
        return ApplicationManager.getApplication().runReadAction(Computable {
            val psiFile = com.intellij.psi.PsiDocumentManager.getInstance(project)
                .getPsiFile(editor.document) ?: return@Computable null
            val offset = editor.caretModel.offset
            psiFile.findElementAt(offset)
        })
    }
}
