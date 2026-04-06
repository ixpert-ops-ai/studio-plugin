package net.ib.ixpert.ops.wuwagent.service

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * IDE의 내장 Diff 기능을 호출하여 코드 비교창을 띄우는 서비스.
 */
object EditorDiffService {

    /**
     * @param project       현재 프로젝트
     * @param originalCode  이전 코드
     * @param modifiedCode  새 코드
     * @param title         Diff 창의 제목 (예: "MainActivity.kt (Diff)")
     */
    fun showDiff(project: Project, originalCode: String, modifiedCode: String, title: String = "Code Diff") {
        ApplicationManager.getApplication().invokeLater {
            val contentFactory = DiffContentFactory.getInstance()
            
            // 1. 원본 콘텐츠 생성
            val content1 = contentFactory.create(originalCode)
            // 2. 수정된 콘텐츠 생성
            val content2 = contentFactory.create(modifiedCode)

            // 3. Diff 리퀘스트 구성
            val request = SimpleDiffRequest(
                title,
                content1,
                content2,
                "Original",
                "Modified (AI Proposed)"
            )

            // 4. Diff 창 표시
            DiffManager.getInstance().showDiff(project, request)
        }
    }
}
