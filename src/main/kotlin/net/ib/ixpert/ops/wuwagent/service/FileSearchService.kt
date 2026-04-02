package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * 프로젝트 내 파일명을 기준으로 파일을 검색하는 서비스입니다.
 *
 * - IntelliJ 파일명 인덱스를 사용해 전체 파일 트리를 직접 순회하지 않습니다.
 * - 키워드가 포함된 파일명만 추려 실제 파일 목록을 조회합니다.
 */
object FileSearchService {

    /**
     * [keyword]가 파일명에 포함된 프로젝트 내 파일을 반환합니다.
     */
    fun searchFiles(project: Project, keyword: String): List<VirtualFile> {
        if (project.isDisposed) return emptyList()

        val normalizedKeyword = keyword.trim()
        if (normalizedKeyword.isEmpty()) return emptyList()

        return DumbService.getInstance(project).runReadActionInSmartMode<List<VirtualFile>> {
            val projectScope = GlobalSearchScope.projectScope(project)

            FilenameIndex.getAllFilenames(project)
                .asSequence()
                .filter { it.contains(normalizedKeyword, ignoreCase = true) }
                .flatMap { fileName ->
                    FilenameIndex.getVirtualFilesByName(project, fileName, projectScope).asSequence()
                }
                .filterNot { it.isDirectory }
                .distinctBy { it.path }
                .toList()
        }
    }

    /**
     * [searchFiles] 결과를 경로 문자열 리스트로 변환해 반환합니다.
     */
    fun searchFilePaths(project: Project, keyword: String): List<String> =
        searchFiles(project, keyword).map(VirtualFile::getPath)

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
