package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 사용자 질문과 현재 에디터 상태를 기반으로 타겟 클래스명을 추출합니다.
 */
object TargetExtractor {

    // 한글 조사 제거용 정규식 (단어 끝에 붙은 조사)
    private val PARTICLE_REGEX = Regex("(을|를|의|에서|에|이|가|은|는|와|과|로|으로)\$")

    /**
     * 타겟 클래스명을 추출합니다.
     * 전략 1: 현재 열린 에디터 파일 우선
     * 전략 2: 질문 내에서 클래스명 매칭 (한국어 조사 제거 포함)
     *
     * @return 매칭된 타겟 클래스명(Simple Name) 목록
     */
    fun extractTargets(project: Project, question: String?, graph: ProjectGraph): List<String> {
        val targets = mutableSetOf<String>()

        // 전략 1: 현재 에디터에 열린 파일 확인
        val fileEditorManager = FileEditorManager.getInstance(project)
        val selectedEditor = fileEditorManager.selectedTextEditor
        if (selectedEditor != null) {
            val className = ReadAction.compute<String?, Throwable> {
                val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(selectedEditor.document)
                if (psiFile is PsiJavaFile) {
                    psiFile.classes.firstOrNull()?.name
                } else null
            }
            if (className != null && graph.files.values.any { it.className == className }) {
                targets.add(className)
            }
        }

        // 전략 2: 질문 텍스트 내에서 매칭
        if (!question.isNullOrBlank()) {
            val graphClassNames = graph.files.values.map { it.className }.toSet()
            targets.addAll(extractFromQuestion(question, graphClassNames))
        }

        return targets.toList()
    }

    /**
     * 전략 2: 사용자 질문 문자열에서 한국어 조사를 제거하고 클래스명을 추출합니다.
     * (PSI 접근 없이 단위 테스트가 가능하도록 분리)
     */
    internal fun extractFromQuestion(question: String, classNames: Set<String>): List<String> {
        val targets = mutableSetOf<String>()
        val tokens = question.split(Regex("\\s+"))

        for (token in tokens) {
            // 특수문자 제거 (콤마, 마침표, 따옴표 등)
            var cleanToken = token.replace(Regex("[.,\"'!?()<>{}\\[\\]]"), "")
            // 한글 조사 제거
            cleanToken = cleanToken.replace(PARTICLE_REGEX, "")

            if (cleanToken.isNotBlank() && classNames.contains(cleanToken)) {
                targets.add(cleanToken)
            }
        }
        return targets.toList()
    }
}
