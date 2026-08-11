package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore

import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper

/**
 * [FileSearchService.searchInFiles] 결과 전체.
 *
 * @param totalFiles           매칭된 파일 수
 * @param totalMatches         매칭된 라인 총 개수
 * @param truncated            아래 두 상한 중 하나라도 걸려 결과가 잘렸는지 여부
 * @param truncatedByFileLimit 파일 상한(MAX_MATCHED_FILES) 도달로 일부 파일을 아예 탐색하지 못했는지 여부
 * @param truncatedByLineLimit 라인 상한(파일당 MAX_MATCHES_PER_FILE 또는 전체 MAX_TOTAL_MATCHES) 도달로
 *                              탐색된 파일 내에서도 일부 라인이 누락됐는지 여부
 */
data class SearchResult(
    val totalFiles: Int,
    val totalMatches: Int,
    val truncated: Boolean,
    val truncatedByFileLimit: Boolean,
    val truncatedByLineLimit: Boolean,
    val matches: List<FileMatch>
)

/** 파일 하나에 대한 매칭 결과. */
data class FileMatch(
    val filePath: String,
    val fileName: String,
    val lines: List<LineMatch>
)

/**
 * 매칭된 라인 하나.
 *
 * @param lineNumber 1-based 라인 번호
 * @param text       매칭된 라인 원문
 * @param context    앞뒤 2줄을 포함한 컨텍스트 라인 목록 (매칭 라인 포함)
 */
data class LineMatch(
    val lineNumber: Int,
    val text: String,
    val context: List<String>
)

/**
 * 프로젝트 내 파일명을 기준으로 파일을 검색하는 서비스입니다.
 *
 * - IntelliJ 파일명 인덱스를 사용해 전체 파일 트리를 직접 순회하지 않습니다.
 * - 키워드가 포함된 파일명만 추려 실제 파일 목록을 조회합니다.
 */
object FileSearchService {

    private val CONTENT_SEARCH_EXTENSIONS = setOf("kt", "java", "xml", "gradle", "kts")
    private const val MAX_MATCHED_FILES = 30
    private const val MAX_TOTAL_MATCHES = 150
    private const val MAX_MATCHES_PER_FILE = 10
    private const val CONTEXT_LINE_RADIUS = 2

    /**
     * [keyword]가 파일명에 포함된 프로젝트 내 파일을 반환합니다.
     */
    fun searchFiles(project: Project, keyword: String): List<VirtualFile> {
        if (project.isDisposed) return emptyList()

        val normalizedKeyword = keyword.trim()
        if (normalizedKeyword.isEmpty()) return emptyList()

        return DumbService.getInstance(project).runReadActionInSmartMode<List<VirtualFile>> {
            val projectScope = GlobalSearchScope.projectScope(project)
            val result = mutableListOf<VirtualFile>()

            FilenameIndex.getAllFilenames(project)
                .filter { it.contains(normalizedKeyword, ignoreCase = true) }
                .forEach { fileName ->
                    FilenameIndex.processFilesByName(fileName, false, projectScope) { file ->
                        if (!file.isDirectory) result.add(file)
                        true
                    }
                }

            result.distinctBy { it.path }
        }
    }

    /**
     * [searchFiles] 결과를 경로 문자열 리스트로 변환해 반환합니다.
     */
    fun searchFilePaths(project: Project, keyword: String): List<String> =
        searchFiles(project, keyword).map(VirtualFile::getPath)

    /**
     * [keyword]가 파일 내용에 포함된 프로젝트 내 파일을 검색합니다.
     * IntelliJ 단어 인덱스(PsiSearchHelper) 기반이라 프로젝트 전체를 직접 순회하지 않습니다.
     *
     * - 대상 확장자: kt, java, xml, gradle, kts
     * - 상한: 파일 30개 / 파일당 매칭 라인 10개 / 전체 매칭 라인 150개
     * - 전체 탐색 중단은 파일 상한(30개) 도달 시에만 발생합니다.
     *   라인 상한(파일당 10개, 전체 150개)에 걸리면 해당 파일의 라인 수집만 멈추고 탐색은 계속됩니다.
     */
    fun searchInFiles(project: Project, keyword: String): SearchResult {
        if (project.isDisposed) return SearchResult(0, 0, false, false, false, emptyList())

        val normalizedKeyword = keyword.trim()
        if (normalizedKeyword.isEmpty()) return SearchResult(0, 0, false, false, false, emptyList())

        return DumbService.getInstance(project).runReadActionInSmartMode<SearchResult> {
            val searchHelper = PsiSearchHelper.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)

            val fileMatches = mutableListOf<FileMatch>()
            var totalMatches = 0
            var truncatedByFileLimit = false
            var truncatedByLineLimit = false

            searchHelper.processAllFilesWithWord(normalizedKeyword, scope, { file ->
                val ext = file.name.substringAfterLast('.', "").lowercase()
                if (ext !in CONTENT_SEARCH_EXTENSIONS) return@processAllFilesWithWord true

                // 전체 라인 상한에 이미 도달했으면 파일 텍스트 스캔 자체를 건너뜀
                // (탐색은 계속하되, 더 담을 수 없는 파일의 불필요한 연산을 회피)
                if (totalMatches < MAX_TOTAL_MATCHES) {
                    val lines = file.text.lines()
                    val lineMatches = mutableListOf<LineMatch>()

                    for (i in lines.indices) {
                        if (lineMatches.size >= MAX_MATCHES_PER_FILE) {
                            truncatedByLineLimit = true
                            break
                        }
                        if (totalMatches + lineMatches.size >= MAX_TOTAL_MATCHES) {
                            truncatedByLineLimit = true
                            break
                        }
                        if (lines[i].contains(normalizedKeyword, ignoreCase = true)) {
                            val start = (i - CONTEXT_LINE_RADIUS).coerceAtLeast(0)
                            val end = (i + CONTEXT_LINE_RADIUS).coerceAtMost(lines.size - 1)
                            lineMatches.add(
                                LineMatch(
                                    lineNumber = i + 1,
                                    text = lines[i],
                                    context = lines.subList(start, end + 1)
                                )
                            )
                        }
                    }

                    // 라인이 0건인 파일은 결과에 넣지 않음
                    if (lineMatches.isNotEmpty()) {
                        totalMatches += lineMatches.size
                        fileMatches.add(
                            FileMatch(
                                filePath = file.virtualFile?.path ?: file.name,
                                fileName = file.name,
                                lines = lineMatches
                            )
                        )
                    }
                }

                // 전체 탐색 중단은 파일 상한만으로 결정
                if (fileMatches.size >= MAX_MATCHED_FILES) {
                    truncatedByFileLimit = true
                    return@processAllFilesWithWord false
                }
                true
            }, false)

            SearchResult(
                totalFiles = fileMatches.size,
                totalMatches = totalMatches,
                truncated = truncatedByFileLimit || truncatedByLineLimit,
                truncatedByFileLimit = truncatedByFileLimit,
                truncatedByLineLimit = truncatedByLineLimit,
                matches = fileMatches
            )
        }
    }

    /**
     * [VirtualFile]의 텍스트 내용을 읽어 반환합니다.
     * ReadAction 안에서 실행되므로 백그라운드 스레드에서 안전하게 호출할 수 있습니다.
     *
     * @return 파일 내용 문자열. 바이너리이거나 읽기 실패 시 빈 문자열.
     */
    fun readFileContent(file: VirtualFile): String {
        if (file.isDirectory || !file.isValid) return ""
        return try {
            ApplicationManager.getApplication().runReadAction(Computable {
                VfsUtilCore.loadText(file)
            })
        } catch (e: Exception) {
            ""
        }
    }

}
