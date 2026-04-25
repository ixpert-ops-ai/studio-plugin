package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import java.io.File

/**
 * 소스 파일 분석 결과를 Markdown 파일로 저장하는 전담 서비스.
 * Agent에서 호출되며, 파일 I/O만 수행하고 LLM 호출은 하지 않는다.
 */
object MarkdownFileService {
    private val logger = Logger.getInstance(MarkdownFileService::class.java)

    /** 기본 저장 디렉토리명 */
    private const val DOCS_DIR = "docs"

    /** 분석 대상 소스 파일 확장자 */
    private val SUPPORTED_EXTENSIONS = setOf(
        "kt", "java", "ts", "tsx", "js", "jsx", "vue",
        "html", "css", "py", "go", "sql", "xml", "jsp"
    )

    /** 탐색에서 제외할 디렉토리명 */
    private val EXCLUDED_DIRS = setOf(
        "build", "out", "target", "dist", ".gradle", ".idea",
        "node_modules", ".git", "__pycache__", ".next", "docs"
    )

    /**
     * 디렉토리를 재귀 탐색하여 분석 대상 소스 파일 목록을 수집한다.
     * build/, node_modules/ 등 빌드 산출물 디렉토리는 자동 제외.
     *
     * @param directory 탐색 시작 디렉토리
     * @return 분석 대상 VirtualFile 목록
     */
    fun collectSourceFiles(directory: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()

        VfsUtilCore.visitChildrenRecursively(directory, object : VirtualFileVisitor<Void>() {
            override fun visitFile(file: VirtualFile): Boolean {
                // 제외 디렉토리 하위는 탐색 건너뛰기
                if (file.isDirectory && file.name in EXCLUDED_DIRS) {
                    return false
                }
                // 소스 파일만 수집
                if (!file.isDirectory && file.extension?.lowercase() in SUPPORTED_EXTENSIONS) {
                    result.add(file)
                }
                return true
            }
        })

        logger.info("MarkdownFileService: ${directory.path} 에서 ${result.size}개 소스 파일 수집")
        return result.sortedBy { it.path }
    }

    /**
     * VirtualFile의 텍스트 내용을 ReadAction 안에서 안전하게 읽어 반환한다.
     */
    fun readFileContent(file: VirtualFile): String {
        if (file.isDirectory || !file.isValid) return ""
        return try {
            ApplicationManager.getApplication().runReadAction(Computable {
                VfsUtilCore.loadText(file)
            })
        } catch (e: Exception) {
            logger.warn("MarkdownFileService: 파일 읽기 실패 → ${file.path}", e)
            ""
        }
    }

    /**
     * 파일 확장자에서 언어 ID를 추론한다.
     */
    fun inferLanguageId(file: VirtualFile): String {
        return when (file.extension?.lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "JAVA"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "js" -> "javascript"
            "jsx" -> "javascript"
            "vue" -> "vue"
            "html" -> "html"
            "css" -> "css"
            "py" -> "python"
            "go" -> "go"
            "sql" -> "sql"
            "xml" -> "xml"
            else -> "unknown"
        }
    }

    /**
     * 분석 결과를 프로젝트 docs/ 디렉토리에 {소스파일명}.md 로 저장한다.
     *
     * @param project        현재 프로젝트
     * @param sourceFileName 원본 소스 파일명 (예: ExplainAgent.kt)
     * @param content        저장할 Markdown 내용
     * @return 저장된 파일의 절대 경로
     */
    fun saveAnalysisDoc(project: Project, sourceFileName: String, content: String): String {
        val basePath = project.basePath
            ?: throw IllegalStateException("프로젝트 경로를 찾을 수 없습니다.")

        val docsDir = File(basePath, DOCS_DIR)
        if (!docsDir.exists()) {
            docsDir.mkdirs()
            logger.info("MarkdownFileService: docs 디렉토리 생성 → ${docsDir.absolutePath}")
        }

        val mdFileName = "${sourceFileName}.md"
        val mdFile = File(docsDir, mdFileName)
        mdFile.writeText(content, Charsets.UTF_8)

        // VFS 동기화 — IDE 프로젝트 트리에 즉시 반영
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(mdFile)

        logger.info("MarkdownFileService: 분석 문서 저장 완료 → ${mdFile.absolutePath} (${content.length}자)")
        return mdFile.absolutePath
    }

    /**
     * 저장된 MD 파일을 VirtualFile로 반환한다. (에디터에서 열기 위해 사용)
     */
    fun findSavedFile(project: Project, sourceFileName: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        val mdFile = File(basePath, "$DOCS_DIR/${sourceFileName}.md")
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(mdFile)
    }
}
