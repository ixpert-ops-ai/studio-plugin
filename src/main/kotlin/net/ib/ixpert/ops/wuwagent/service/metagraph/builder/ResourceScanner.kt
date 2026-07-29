package net.ib.ixpert.ops.wuwagent.service.metagraph.builder

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceType
import java.io.File
import java.nio.file.Path

class ResourceScanner(private val projectRoot: Path) {

    // 스캔할 확장자 (화이트리스트)
    private val targetExtensions = setOf(
        "xml", "js", "ts", "tsx", "jsp", "html", "vue",
        "properties", "yml", "yaml", "sql"
    )

    // 제외할 파일 패턴 (라이브러리 파일)
    private val excludeFilePatterns = setOf(
        Regex(""".*\.min\.js$"""),
        Regex(""".*\.bundle\.js$"""),
        Regex(""".*jquery.*\.js$""", RegexOption.IGNORE_CASE),
        Regex(""".*bootstrap.*""", RegexOption.IGNORE_CASE),
        Regex(""".*polyfill.*""", RegexOption.IGNORE_CASE),
        Regex(""".*\.d\.ts$"""),
        Regex(""".*pom\.xml$"""),
        Regex(""".*web\.xml$""")
    )

    // 크기 제한
    private val maxFileSizeBytes = 500_000L  // 500KB
    
    // Circuit Breaker 상한선
    private val maxHintsPerFile = 100
    private val maxHintsPerCategory = 20

    fun scan(): List<ResourceNode> {
        return projectRoot.toFile().walkTopDown()
            .onEnter { dir -> !ScanExclusionUtil.shouldExclude(dir.name, dir.path) }
            .filter { file ->
                file.isFile
                && file.extension in targetExtensions
                && file.length() < maxFileSizeBytes
                && isNotExcludedFile(file)
            }
            .mapNotNull { file -> scanFile(file) }
            .toList()
    }

    private fun isNotExcludedFile(file: File): Boolean {
        return excludeFilePatterns.none { it.matches(file.name) }
    }

    private fun scanFile(file: File): ResourceNode? {
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }

        if (content.length > maxFileSizeBytes) return null

        val type = classifyType(file, content)
        if (type == ResourceType.UNKNOWN) return null

        val patterns = ScanProfiles.getProfileForExtension(file.extension)
        if (patterns.isEmpty()) return null

        val hints = mutableMapOf<String, MutableList<String>>()
        var totalHintCount = 0

        patterns.forEach { pattern ->
            if (totalHintCount >= maxHintsPerFile) return@forEach

            pattern.regex.findAll(content).forEach inner@{ match ->
                if (totalHintCount >= maxHintsPerFile) return@inner

                val value = match.groupValues[pattern.groupIndex].trim()
                if (value.isEmpty() || value.length > 200) return@inner

                val categoryList = hints.getOrPut(pattern.category) { mutableListOf() }
                if (categoryList.size >= maxHintsPerCategory) return@inner

                categoryList.add(value)
                totalHintCount++
            }
        }

        // 힌트가 하나도 없으면 노드 미생성 (단, CONFIG 파일은 힌트 없어도 생성될 수 있으나 여기선 힌트 필수)
        if (hints.isEmpty() && type != ResourceType.CONFIG) return null

        // P6: Frontend Resource Deep Parsing 연동
        if (type == ResourceType.SCRIPT || type == ResourceType.VIEW) {
            val extensionLower = file.extension.lowercase()
            if (extensionLower == "vue" || extensionLower == "js" || extensionLower == "ts" || extensionLower == "tsx") {
                val deepMetadata = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.FrontendDeepParser.parse(content)
                deepMetadata.forEach { (key, list) ->
                    val categoryList = hints.getOrPut(key) { mutableListOf() }
                    categoryList.addAll(list)
                }
            }
        }

        // 테이블 매칭 후처리 (탐욕적 매칭 보완)
        if (hints.containsKey("table_name")) {
            val initialTables = hints["table_name"] ?: emptyList()
            hints["table_name"] = extractAdditionalTables(content, initialTables).toMutableList()
        }

        val relativePath = projectRoot.relativize(file.toPath()).toString().replace("\\", "/")

        return ResourceNode(
            path = relativePath,
            type = type,
            layer = classifyLayer(type, relativePath),
            linkedTo = emptyList(),
            linkType = "",
            metadata = hints.mapValues { it.value.distinct().take(maxHintsPerCategory) }
        )
    }

    private fun classifyType(file: File, content: String): ResourceType {
        return when (file.extension.lowercase()) {
            "xml" -> {
                when {
                    file.name == "AndroidManifest.xml" -> ResourceType.CONFIG
                    content.contains("<mapper") && content.contains("namespace") -> ResourceType.MYBATIS_MAPPER
                    content.contains("<queryService") -> ResourceType.MYBATIS_MAPPER // Anyframe
                    content.contains("<beans") || content.contains("<bean ") -> ResourceType.SPRING_XML
                    content.contains("<configuration") && content.contains("<mappers") -> ResourceType.MYBATIS_CONFIG
                    content.contains("<configuration") && content.contains("<appender") -> ResourceType.CONFIG
                    else -> ResourceType.UNKNOWN
                }
            }
            "js", "ts", "tsx" -> {
                val hasApiCall = content.contains("$.ajax") || content.contains("fetch(") || content.contains("axios") || content.contains("queryService.")
                val hasDomManipulation = content.contains("getElementById") || content.contains("$(\"#") || content.contains("document.querySelector")
                if (hasApiCall || hasDomManipulation) ResourceType.SCRIPT else ResourceType.UNKNOWN
            }
            "jsp", "html", "vue" -> ResourceType.VIEW
            "properties", "yml", "yaml" -> ResourceType.CONFIG
            "sql" -> ResourceType.SQL_MIGRATION
            else -> ResourceType.UNKNOWN
        }
    }

    private fun classifyLayer(type: ResourceType, path: String): String {
        return when (type) {
            ResourceType.MYBATIS_MAPPER -> "DATA_ACCESS"
            ResourceType.CONFIG -> "INFRASTRUCTURE"
            ResourceType.SQL_MIGRATION -> "DATA_ACCESS"
            ResourceType.SCRIPT, ResourceType.VIEW -> "PRESENTATION"
            ResourceType.SPRING_XML -> when {
                path.contains("security") -> "INFRASTRUCTURE"
                path.contains("context") -> "INFRASTRUCTURE"
                else -> "INFRASTRUCTURE"
            }
            else -> "UNKNOWN"
        }
    }

    private fun extractAdditionalTables(content: String, initialTables: List<String>): List<String> {
        val fromBlocks = Regex("""(?:FROM|JOIN)\s+(.+?)(?:WHERE|ORDER|GROUP|HAVING|LIMIT|;|<)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(content)
            .map { it.groupValues[1] }

        val tablePattern = Regex("""[A-Z][A-Z0-9_]{2,}""")
        val allTables = mutableSetOf<String>()
        allTables.addAll(initialTables)

        fromBlocks.forEach { block ->
            tablePattern.findAll(block).forEach { match ->
                val candidate = match.value
                if (candidate.uppercase() !in SQL_RESERVED_WORDS) {
                    allTables.add(candidate)
                }
            }
        }
        return allTables.toList()
    }

    private val SQL_RESERVED_WORDS = setOf(
        "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN",
        "EXISTS", "BETWEEN", "LIKE", "NULL", "ORDER", "GROUP",
        "HAVING", "LIMIT", "OFFSET", "INNER", "OUTER", "LEFT",
        "RIGHT", "CROSS", "ASC", "DESC", "INSERT", "INTO",
        "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "ALTER",
        "DROP", "TABLE", "INDEX", "VARCHAR", "INTEGER", "DATE"
    )
}
