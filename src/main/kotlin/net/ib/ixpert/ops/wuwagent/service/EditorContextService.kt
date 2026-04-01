package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * IDE 플랫폼 기능(문서 추출, 프로젝트/파일 정보 탐색 등)을 전담하는 서비스입니다.
 */
object EditorContextService {
    /**
     * 에디터에서 선택된 텍스트를 추출하거나, 
     * 선택된 영역이 없으면 문서 전체 텍스트를 반환합니다.
     */
    fun extractCode(editor: Editor, project: Project): String {
        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText
        
        return if (!selectedText.isNullOrBlank()) {
            selectedText
        } else {
            editor.document.text
        }
    }
}
