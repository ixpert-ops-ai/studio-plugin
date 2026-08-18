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
                
                // 원본 코드와 현재 에디터 코드가 다르면 즉시 중단하지 않고, 사용자에게 의사 확인
                if (currentTextToCompare.trim() != originalCode.trim()) {
                    val userResponse = com.intellij.openapi.ui.Messages.showYesNoDialog(
                        project,
                        "현재 코드가 변경되었습니다. 전체 내용을 덮어쓰시겠습니까?",
                        "iXpert AI Assistant 원본 불일치 경고",
                        com.intellij.openapi.ui.Messages.getWarningIcon()
                    )
                    
                    if (userResponse != com.intellij.openapi.ui.Messages.YES) {
                        result = "[취소] 에디터의 코드가 변경되어 사용자가 덮어쓰기를 취소했습니다."
                        return@invokeAndWait
                    }
                    logger.info("EditorApplyService: 원본 코드 불일치 경고가 발생했으나, 사용자가 강제 덮어쓰기(Override)를 선택했습니다.")
                }
            }

            WriteCommandAction.runWriteCommandAction(project, "iXpert AI Assistant: Apply Code", null, {
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
     * LLM의 Search/Replace 포맷 응답을 원본 코드에 적용하여 최종 코드를 반환합니다.
     *
     * 포맷:
     * ```
     * <<<<<<< SEARCH
     * (원본과 정확히 일치하는 코드)
     * =======
     * (대체할 코드)
     * >>>>>>> REPLACE
     * ```
     *
     * @return 병합된 전체 코드, 또는 파싱/매칭 실패 시 null (fallback 유도)
     */
    fun applySearchReplace(original: String, llmResponse: String): String? {
        val pattern = Regex(
            """<<<<<<< SEARCH\n(.*?)\n=======\n(.*?)\n>>>>>>> REPLACE""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val blocks = pattern.findAll(llmResponse).toList()
        if (blocks.isEmpty()) return null

        var result = original
        for (block in blocks) {
            val search  = block.groupValues[1]
            val replace = block.groupValues[2]
            if (!result.contains(search)) {
                logger.warn("applySearchReplace: SEARCH 블록 매칭 실패 → fallback")
                return null
            }
            result = result.replaceFirst(search, replace)
        }
        logger.info("applySearchReplace: ${blocks.size}개 블록 적용 완료 (결과 길이=${result.length})")
        return result
    }

    /**
     * LLM 응답에서 코드 블록(``` ... ```)을 추출합니다.
     *
     * - 코드 블록이 여러 개일 경우 **가장 긴 블록**을 반환합니다.
     *   (LLM이 짧은 예시 블록과 전체 코드 블록을 함께 반환할 때 전체 코드를 선택하기 위함)
     * - 닫는 펜스가 없는 경우(응답이 토큰 한도 등으로 잘린 경우)에도
     *   전체 텍스트를 반환하되, 첫/마지막 줄에 남은 여는 펜스는 별도로 제거합니다.
     * - 코드 블록이 아예 없으면 전체 텍스트를 그대로 반환합니다.
     */
    fun extractCodeBlock(content: String): String {
        val trimmedContent = content.trim()
        val regex = Regex("```(?:\\w+)?\\n?([\\s\\S]*?)```")
        val matches = regex.findAll(trimmedContent).toList()

        logger.info("extractCodeBlock: 코드블록 ${matches.size}개 발견, 응답 전체 길이=${trimmedContent.length}")
        matches.forEachIndexed { i, m ->
            logger.info("extractCodeBlock: 블록[$i] 길이=${m.groupValues[1].length}")
        }

        val extracted = if (matches.isEmpty()) {
            // 닫는 펜스가 없어 정규식이 매칭되지 않은 경우(응답 잘림 등) → 원문을 그대로 후보로 사용
            // 첫/마지막 줄에 남은 펜스는 stripFenceLines()에서 별도로 제거됨
            logger.info("extractCodeBlock: 완결된 코드블록 없음 → 전체 텍스트를 펜스 제거 후 반환")
            trimmedContent
        } else {
            // 가장 긴 블록 선택 (전체 파일일 가능성이 가장 높음)
            val largest = matches.maxByOrNull { it.groupValues[1].length }!!
            largest.groupValues[1].trim()
        }

        val stripped = stripFenceLines(extracted)
        logger.info("extractCodeBlock: 최종 선택 (길이=${stripped.length}) 앞 300자: ${stripped.take(300)}")
        return stripped
    }

    /**
     * 여는 코드 펜스(```)는 있는데 닫는 펜스가 없는지 확인합니다.
     * true면 응답이 토큰 한도 등으로 중간에 잘렸을 가능성이 높다는 신호로 씁니다.
     */
    fun hasUnclosedCodeFence(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.contains("```")) return false
        val regex = Regex("```(?:\\w+)?\\n?([\\s\\S]*?)```")
        return regex.findAll(trimmed).toList().isEmpty()
    }

    /**
     * 코드 블록의 첫 줄/마지막 줄에 남은 ``` 펜스만 제거합니다.
     * (``` 또는 ```언어명 형태 모두 대상. 코드 중간에 있는 ```는 건드리지 않음)
     */
    private fun stripFenceLines(code: String): String {
        var lines = code.trim().lines()
        if (lines.firstOrNull()?.trimStart()?.startsWith("```") == true) {
            lines = lines.drop(1)
        }
        if (lines.lastOrNull()?.trim() == "```") {
            lines = lines.dropLast(1)
        }
        return lines.joinToString("\n")
    }
}
