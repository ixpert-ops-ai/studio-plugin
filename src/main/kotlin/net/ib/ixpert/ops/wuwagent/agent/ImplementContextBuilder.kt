package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

data class FileReference(val filePath: String, val content: String)

enum class ImplementFileType {
    JAVA_LIKE, VIEW_MARKUP, MYBATIS_XML, JAVASCRIPT, VUE_SFC, UNKNOWN
}

object ImplementContextBuilder {

    private val REQUIREMENT_TYPE_KEYWORDS = mapOf(
        "FILE_DOWNLOAD" to listOf("csv", "엑셀", "excel", "다운로드", "download", "export", "내보내기", "pdf"),
        "FILE_UPLOAD" to listOf("업로드", "upload", "첨부", "import", "가져오기"),
        "BATCH" to listOf("일괄", "batch", "대량", "bulk")
    )

    private val REFERENCE_SCAN_KEYWORDS = mapOf(
        "FILE_DOWNLOAD" to listOf(
            "response.getOutputStream",
            "Content-Disposition",
            "response.setContentType",
            "attachment;",
            "PrintWriter",
            "OutputStreamWriter"
        ),
        "FILE_UPLOAD" to listOf(
            "MultipartFile",
            "transferTo",
            "getOriginalFilename"
        ),
        "BATCH" to listOf(
            "forEach",
            "批量",
            "bulkInsert",
            "batchUpdate"
        )
    )

    fun detectRequirementType(requirement: String): String? {
        val lowerReq = requirement.lowercase()
        return REQUIREMENT_TYPE_KEYWORDS.entries.firstOrNull { (_, keywords) ->
            keywords.any { lowerReq.contains(it) }
        }?.key
    }

    fun findSimilarImplementation(
        graph: ProjectGraph,
        requirementType: String,
        targetFiles: List<TargetFileSpec>,
        sourceRoot: Path
    ): List<FileReference> {
        val scanKeywords = REFERENCE_SCAN_KEYWORDS[requirementType] ?: return emptyList()
        val targetPaths = targetFiles.map { it.path }

        return graph.files.values
            .filter { file -> !targetPaths.contains(file.path) }
            .mapNotNull { file ->
                var content: String? = null
                val localFile = sourceRoot.resolve(file.path)
                if (localFile.toFile().exists()) {
                    content = localFile.toFile().readText()
                }
                
                if (content.isNullOrBlank()) return@mapNotNull null
                
                val matchCount = scanKeywords.count { keyword -> content!!.contains(keyword) }
                if (matchCount >= 2) {
                    Pair(file, content)
                } else null
            }
            .sortedByDescending { (file, _) ->
                when {
                    file.className.endsWith("Controller") -> 3
                    file.className.endsWith("Service") || file.className.endsWith("ServiceImpl") -> 2
                    else -> 1
                }
            }
            .take(2)
            .map { (file, content) ->
                val lines = content.lines()
                val finalContent = if (lines.size > 200) {
                    // Extract relevant chunks
                    val relevantLines = mutableListOf<String>()
                    var i = 0
                    while (i < lines.size) {
                        val currentLine = lines[i]
                        if (scanKeywords.any { currentLine.contains(it) }) {
                            val start = maxOf(0, i - 15)
                            val end = minOf(lines.size - 1, i + 30)
                            if (relevantLines.isNotEmpty()) relevantLines.add("... (중략) ...")
                            relevantLines.addAll(lines.subList(start, end + 1))
                            i = end + 1
                        } else {
                            i++
                        }
                    }
                    if (relevantLines.isNotEmpty()) relevantLines.joinToString("\n") else extractClassSkeleton(content!!)
                } else {
                    content!!
                }
                FileReference(file.path, finalContent)
            }
    }


    private fun parseTargetHints(description: String): List<String> {
        val koreanRegex = Regex("[가-힣]+")
        val idRegex = Regex("[a-zA-Z0-9_\\-]+")
        val hints = mutableListOf<String>()
        hints.addAll(koreanRegex.findAll(description).map { it.value })
        hints.addAll(idRegex.findAll(description).map { it.value }.filter { it.length > 3 })
        return hints.distinct()
    }

    private fun parseTargetQueryIds(description: String): List<String> {
        val idRegex = Regex("[a-zA-Z0-9_]+")
        return idRegex.findAll(description).map { it.value }.filter { it.length > 3 }.distinct().toList()
    }

    /**
     * MODIFY(수정) 파일용 컨텍스트 구성
     * graph 노드 + MD 섹션 1(목적), 3(메서드 테이블), 5(의존성) 로딩 + 원본 소스
     */
    fun buildModifyContext(
        spec: TargetFileSpec, 
        graph: ProjectGraph, 
        mdRoot: Path, 
        sourceRoot: Path,
        similarRefs: List<FileReference>
    ): String {
        val md = ContextBuilderUtil.buildFileContext(spec.path, graph, mdRoot, listOf(1, 3, 5))
        val sourceFile = sourceRoot.resolve(spec.path)
        val sourceContent = if (sourceFile.exists()) sourceFile.readText() else ""
        val targetHints = parseTargetHints(spec.description)
        val targetQueryIds = parseTargetQueryIds(spec.description)
        
        return buildContextWithSource(md, sourceContent, graph, similarRefs, spec.path, targetHints, targetQueryIds)
    }

    /**
     * CREATE(신규) 파일용 컨텍스트 구성
     */
    fun buildCreateContext(
        spec: TargetFileSpec,
        graph: ProjectGraph,
        mdRoot: Path,
        sourceRoot: Path,
        allTargetFiles: List<TargetFileSpec>,
        similarRefs: List<FileReference>
    ): String {
        val path = spec.path
        val targetHints = parseTargetHints(spec.description)
        val targetQueryIds = parseTargetQueryIds(spec.description)
        val sb = StringBuilder()
        
        // 프레임워크 힌트 추가
        val fwType = graph.resolveFrameworkType()
        when (fwType) {
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_JPA -> {
                sb.appendLine("## 프레임워크 힌트")
                sb.appendLine("이 프로젝트는 Spring Boot + JPA 기반입니다. JPA Entity 생성 시 @Entity, @Table 등 매핑 어노테이션을 잊지 마세요.")
                sb.appendLine("- Controller가 Service의 반환값을 그대로 ResponseEntity로 감싸서 전달만 하는 경우,")
                sb.appendLine("  Service나 DTO 변경만으로 API 응답이 바뀌므로 Controller는 [변경 없음]으로 처리하세요.")
                sb.appendLine("- Controller에 새 엔드포인트를 추가하거나 파라미터를 변경하는 경우에만 [수정]하세요.")
                sb.appendLine()
            }
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_MYBATIS,
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_MVC_MYBATIS -> {
                sb.appendLine("## 프레임워크 힌트")
                sb.appendLine("이 프로젝트는 MyBatis 기반입니다. Mapper 인터페이스와 XML 작성 규칙을 준수하세요.")
                if (fwType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_MVC_MYBATIS) {
                    sb.appendLine("주의: 이 프로젝트는 Service Interface + ServiceImpl 쌍을 사용합니다. 새 메서드는 반드시 Interface에 먼저 선언하고 Impl에 구현하세요.")
                }
                sb.appendLine()
            }
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANYFRAME_AP -> {
                sb.appendLine("## 프레임워크 힌트")
                sb.appendLine("이 프로젝트는 Anyframe 기반입니다. DQM/DEM 패턴과 Layered SVO/BVO/DVO 전략을 사용합니다.")
                sb.appendLine("주의: 이 프로젝트는 Interface + Impl 쌍을 엄격히 준수합니다. 새 메서드는 반드시 Interface에 먼저 선언하고 Impl에 구현하세요.")
                sb.appendLine()
            }
            else -> {}
        }

        sb.appendLine("이 파일은 신규로 생성(CREATE)되는 파일입니다.")
        sb.appendLine("신규 파일은 다음의 기존 컴포넌트들을 참조하거나, 다음의 작업과 연관되어 있습니다.")
        
        // 형제 CREATE 파일 및 MODIFY 파일 정보 요약
        sb.appendLine("\n[함께 작업 중인 전체 파일 목록 및 요약]")
        for (spec in allTargetFiles) {
            if (spec.path == path) continue
            sb.appendLine("- ${spec.type} ${spec.path}: ${spec.description}")
        }
        
        // 동일 패키지에 기존 파일이 있다면 패키지 컨벤션 참고용으로 제공
        val packageName = path.substringBeforeLast("/").replace("/", ".")
        val siblingNodes = graph.files.values.filter { it.packageName == packageName }.take(3)
        if (siblingNodes.isNotEmpty()) {
            sb.appendLine("\n[참고: 동일 패키지($packageName) 내 기존 파일 정보]")
            for (sibling in siblingNodes) {
                sb.appendLine(ContextBuilderUtil.buildGraphContext(sibling))
            }
        }
        
        if (similarRefs.isNotEmpty()) {
            sb.appendLine("\n## 참조: 프로젝트 내 유사 구현 사례")
            sb.appendLine("아래 코드는 이 프로젝트에서 이미 구현된 유사 기능입니다.")
            sb.appendLine("동일한 패턴(반환 타입, 헤더 설정, 스트림 처리 방식 등)을 따라 구현하세요.\n")
            for (ref in similarRefs) {
                sb.appendLine("### ${ref.filePath}")
                sb.appendLine("```java")
                sb.appendLine(ref.content)
                sb.appendLine("```\n")
            }
        }
        
        return sb.toString().trim()
    }
    
    private fun loadPromptTemplate(fileType: ImplementFileType): String {
        val fileName = when (fileType) {
            ImplementFileType.JAVA_LIKE -> "implement_java.txt"
            ImplementFileType.VIEW_MARKUP -> "implement_jsp.txt"
            ImplementFileType.MYBATIS_XML -> "implement_xml.txt"
            ImplementFileType.JAVASCRIPT -> "implement_js.txt"
            ImplementFileType.VUE_SFC -> "implement_vue.txt"
            ImplementFileType.UNKNOWN -> "implement_java.txt"
        }
        val resourceStream = this::class.java.classLoader.getResourceAsStream("prompts/$fileName")
            ?: return ""
        return resourceStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun buildContextWithSource(md: String, source: String, graph: ProjectGraph, similarRefs: List<FileReference>, filePath: String, targetHints: List<String> = emptyList(), targetQueryIds: List<String> = emptyList()): String {
        val fileType = determineFileType(filePath, source)
        var template = loadPromptTemplate(fileType)

        val sb = StringBuilder()
        
        // 프레임워크 힌트 조립
        val fwSb = StringBuilder()
        val fwType = graph.resolveFrameworkType()
        when (fwType) {
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_JPA -> {
                fwSb.appendLine("## 프레임워크 힌트")
                fwSb.appendLine("이 프로젝트는 Spring Boot + JPA 기반입니다. JPA Entity 변경 시 @Column, @Table 등 매핑 어노테이션을 잊지 마세요.")
                fwSb.appendLine("- Controller가 Service의 반환값을 그대로 ResponseEntity로 감싸서 전달만 하는 경우,")
                fwSb.appendLine("  Service나 DTO 변경만으로 API 응답이 바뀌므로 Controller는 [변경 없음]으로 처리하세요.")
                fwSb.appendLine("- Controller에 새 엔드포인트를 추가하거나 파라미터를 변경하는 경우에만 [수정]하세요.")
            }
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_MYBATIS,
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_MVC_MYBATIS -> {
                fwSb.appendLine("## 프레임워크 힌트")
                fwSb.appendLine("이 프로젝트는 MyBatis 기반입니다. SQL과 파라미터 바인딩을 신중하게 확인하세요.")
                if (fwType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_MVC_MYBATIS) {
                    fwSb.appendLine("주의: 이 프로젝트는 Service Interface + ServiceImpl 쌍을 사용합니다. 새 메서드는 반드시 Interface에 먼저 선언하고 Impl에 구현하세요.")
                }
            }
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANYFRAME_AP -> {
                fwSb.appendLine("## 프레임워크 힌트")
                fwSb.appendLine("이 프로젝트는 Anyframe 기반입니다.")
                fwSb.appendLine("주의: 이 프로젝트는 Interface + Impl 쌍을 엄격히 준수합니다. 새 메서드는 반드시 Interface에 먼저 선언하고 Impl에 구현하세요.")
            }
            else -> {}
        }
        
        val mdSb = StringBuilder()
        if (md.isNotBlank()) {
            mdSb.appendLine("## 분석 문서 요약")
            mdSb.appendLine(md)
        }

        val sourceSb = StringBuilder()
        if (source.isNotBlank()) {
            val trimmedSource = when (fileType) {
                ImplementFileType.JAVA_LIKE -> ImplementTrimmer.trimJavaLike(source)
                ImplementFileType.VIEW_MARKUP -> ImplementTrimmer.trimViewMarkup(source, targetHints)
                ImplementFileType.MYBATIS_XML -> ImplementTrimmer.trimMybatisXml(source, targetQueryIds)
                ImplementFileType.JAVASCRIPT -> ImplementTrimmer.trimJavaScript(source)
                ImplementFileType.VUE_SFC -> ImplementTrimmer.trimVueSfc(source, targetHints)
                ImplementFileType.UNKNOWN -> source
            }
            val langTag = when (fileType) {
                ImplementFileType.JAVA_LIKE -> if (filePath.endsWith(".kt")) "kotlin" else "java"
                ImplementFileType.VIEW_MARKUP -> "html"
                ImplementFileType.MYBATIS_XML -> "xml"
                ImplementFileType.JAVASCRIPT -> "javascript"
                ImplementFileType.VUE_SFC -> "vue"
                ImplementFileType.UNKNOWN -> ""
            }
            sourceSb.appendLine("## 원본 소스")
            sourceSb.appendLine("```$langTag")
            sourceSb.appendLine(trimmedSource)
            sourceSb.appendLine("```")
        }

        val refSb = StringBuilder()
        if (similarRefs.isNotEmpty()) {
            refSb.appendLine("## 참조: 프로젝트 내 유사 구현 사례")
            refSb.appendLine("아래 코드는 이 프로젝트에서 이미 구현된 유사 기능입니다.")
            refSb.appendLine("동일한 패턴(반환 타입, 헤더 설정, 스트림 처리 방식 등)을 따라 구현하세요.\n")
            for (ref in similarRefs) {
                refSb.appendLine("### ${ref.filePath}")
                val refLangTag = if (ref.filePath.endsWith(".xml")) "xml" else "java"
                refSb.appendLine("```$refLangTag")
                refSb.appendLine(ref.content)
                refSb.appendLine("```\n")
            }
        }

        // Apply template or just append
        if (template.isNotBlank()) {
            // Replace placeholders
            // But we didn't add the placeholders in the prompt yet except {{COMMON_RULES}}, let's just append
            template = template.replace("{{COMMON_RULES}}", "")
            sb.appendLine(template)
            sb.appendLine()
        }

        if (fwSb.isNotBlank()) sb.appendLine(fwSb.toString())
        if (mdSb.isNotBlank()) sb.appendLine(mdSb.toString())
        if (sourceSb.isNotBlank()) sb.appendLine(sourceSb.toString())
        if (refSb.isNotBlank()) sb.appendLine(refSb.toString())

        return sb.toString().trim()
    }
    
    @Deprecated("Use ImplementTrimmer.trimJavaLike instead")
    private fun extractClassSkeleton(source: String): String {
        return ImplementTrimmer.trimJavaLike(source)
    }

    fun determineFileType(filePath: String, content: String): ImplementFileType {
        val ext = filePath.substringAfterLast('.').lowercase()
        return when (ext) {
            "java", "kt" -> ImplementFileType.JAVA_LIKE
            "jsp", "html", "ftl" -> ImplementFileType.VIEW_MARKUP
            "xml" -> {
                if (content.contains("<mapper") || content.contains("<!DOCTYPE mapper"))
                    ImplementFileType.MYBATIS_XML
                else
                    ImplementFileType.UNKNOWN
            }
            "js", "ts" -> ImplementFileType.JAVASCRIPT
            "vue" -> ImplementFileType.VUE_SFC
            else -> ImplementFileType.UNKNOWN
        }
    }

    fun estimateTokenCount(text: String, fileType: ImplementFileType): Int {
        val length = text.length
        return when (fileType) {
            ImplementFileType.JAVA_LIKE -> (length / 3.5).toInt()
            ImplementFileType.VIEW_MARKUP -> (length / 2.0).toInt()
            ImplementFileType.MYBATIS_XML -> (length / 2.5).toInt()
            ImplementFileType.JAVASCRIPT -> (length / 3.0).toInt()
            ImplementFileType.VUE_SFC -> (length / 2.5).toInt()
            ImplementFileType.UNKNOWN -> (length / 3.5).toInt()
        }
    }
}
