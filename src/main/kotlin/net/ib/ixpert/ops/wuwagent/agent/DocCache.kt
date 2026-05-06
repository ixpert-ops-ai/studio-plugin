package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.yaml.snakeyaml.Yaml
import java.io.File

data class DocSummary(
    val file: String,
    val language: String,
    val type: String,
    val dependencies: List<String>,
    val methods: List<String>,
    val purpose: String,
    val flowSummary: String,
    val constants: Map<String, String>
)

@Service(Service.Level.PROJECT)
class DocCache(project: Project) {

    private val docDir = File(project.basePath, "docs")
    private val logger = Logger.getInstance(DocCache::class.java)
    private val cache: Map<String, DocSummary> by lazy { loadAll() }

    fun get(filePath: String): DocSummary? {
        val fileName = java.io.File(filePath).name
        return cache[fileName]
    }

    /**
     * 파일명으로 문서를 검색합니다 (Fuzzy 매칭용)
     */
    fun findByFileName(fileName: String): DocSummary? {
        return cache.entries.firstOrNull { (key, _) ->
            key.substringAfterLast("/") == fileName
        }?.value
    }

    fun has(filePath: String): Boolean = get(filePath) != null

    fun getAll(): Map<String, DocSummary> = cache

    fun getByType(type: String): List<DocSummary> =
        cache.values.filter { it.type.equals(type, ignoreCase = true) }

    fun getRelatedDocs(filePaths: List<String>): List<Pair<String, DocSummary>> =
        filePaths.mapNotNull { path -> get(path)?.let { path to it } }

    private fun loadAll(): Map<String, DocSummary> {
        if (!docDir.exists() || !docDir.isDirectory) {
            logger.warn("📂 /docs 디렉토리가 존재하지 않습니다: ${docDir.absolutePath}")
            return emptyMap()
        }

        val yaml = Yaml()
        val result = docDir.walkTopDown()
            .filter { it.extension == "md" }
            .mapNotNull { f ->
                try {
                    parseDocFile(f, yaml)
                } catch (e: Exception) {
            logger.warn("⚠️ /docs 파싱 실패: ${f.name} - ${e.message}")
                    null
                }
            }
            .associateBy { it.file }

        logger.warn("📄 DocCache 로드 완료: ${result.size}개 문서")
        return result
    }

    private fun parseDocFile(file: File, yaml: Yaml): DocSummary? {
        val content = file.readText()
        val frontMatter = extractYamlFrontMatter(content) ?: return null
        val parsed = try {
            yaml.load<Map<String, Any>>(frontMatter)
        } catch (e: Exception) {
            logger.warn("Yaml 파싱 실패 (${file.name}): ${e.message}")
            null
        } ?: return null

        return DocSummary(
            file = parsed["file"] as? String ?: return null,
            language = parsed["language"] as? String ?: "unknown",
            type = parsed["type"] as? String ?: "unknown",
            dependencies = (parsed["dependencies"] as? List<*>)
                ?.map { it.toString() } ?: emptyList(),
            methods = extractMethods(content),
            purpose = extractSection(content, "## 1."),
            flowSummary = extractSection(content, "### 4-1."),
            constants = extractConstants(content)
        )
    }

    private fun extractYamlFrontMatter(content: String): String? {
        val regex = Regex("---\\s*\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL)
        return regex.find(content)?.groupValues?.get(1)
    }

    private fun extractMethods(content: String): List<String> {
        val section = extractSection(content, "## 3.")
        val methodRegex = Regex("`([^`]+\\([^)]*\\))`")
        return methodRegex.findAll(section).map { it.groupValues[1] }.toList()
    }

    private fun extractSection(content: String, heading: String): String {
        val startIdx = content.indexOf(heading)
        if (startIdx == -1) return ""
        val afterHeading = content.substring(startIdx)
        // 다음 ##으로 시작하는 섹션 또는 끝까지
        val nextSection = Regex("\\n## (?!#)").find(afterHeading, heading.length)
        return if (nextSection == null) {
            afterHeading.trim()
        } else {
            afterHeading.substring(0, nextSection.range.first).trim()
        }
    }

    private fun extractConstants(content: String): Map<String, String> {
        val section = extractSection(content, "## 6.")
        val rowRegex = Regex("\\|\\s*`([^`]+)`\\s*\\|\\s*([^|]+)\\|\\s*([^|]+)\\|")
        return rowRegex.findAll(section).associate {
            it.groupValues[1].trim() to it.groupValues[3].trim()
        }
    }
}
