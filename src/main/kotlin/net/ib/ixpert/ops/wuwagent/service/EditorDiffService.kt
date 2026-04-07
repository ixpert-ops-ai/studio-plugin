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
     * @param project          현재 프로젝트
     * @param targetFile       원본 에디터의 실제 파일 (Left 측 매핑 및 쓰기 권한 확보용)
     * @param modifiedFullText 적용될 전체 코드 문자열 (Right 측 매핑용)
     * @param title            Diff 창의 제목
     */
    fun showDiff(project: Project, targetFile: com.intellij.openapi.vfs.VirtualFile, modifiedFullText: String, title: String = "Code Diff") {
        ApplicationManager.getApplication().invokeLater {
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(targetFile)
            if (document == null) return@invokeLater

            val originalText = document.text

            // 1. Merge 창 구성을 위한 3-Way 데이터 매핑
            // ByteArray/VirtualFile 기반은 열린 에디터(Document)와 쓰기 잠금 충돌이 발생해 단순 DiffViewer로 강등(Fallback)됩니다.
            // 반드시 메모리에 올라온 Document 객체 자체와 String 기반의 콘텐츠를 사용해야 "Merge Tool" 다이얼로그가 확보됩니다.
            val request = com.intellij.diff.DiffRequestFactory.getInstance().createMergeRequest(
                project,
                targetFile.fileType,
                document,
                listOf(originalText, originalText, modifiedFullText), // [Left, Base, Right] 순서
                title,
                listOf("Local (Current)", "Base", "AI Proposed"),
                com.intellij.util.Consumer<com.intellij.diff.merge.MergeResult> { result ->
                    // Merge Tool 종료 시 콜백. Document 직접 조작 모델은 여기서 자동 커밋을 돕습니다.
                }
            )

            // 2. 부분 적용이 가능한 Merge 다이얼로그 호출
            com.intellij.diff.DiffManager.getInstance().showMerge(project, request)
        }
    }
}
