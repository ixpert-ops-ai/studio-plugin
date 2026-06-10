package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

object ContextBuilderUtil {

    fun buildFileContext(
        path: String,
        graph: ProjectGraph,
        mdRoot: Path,
        sections: List<Int> = listOf(1, 5)
    ): String {
        val fileName = extractFileName(path)
        val mdFile = mdRoot.resolve("$fileName.md")
        val node = graph.files[path]
        
        if (mdFile.exists()) {
            return extractMdSections(mdFile, sections, node)
        }
        
        val logger = com.intellij.openapi.diagnostic.Logger.getInstance(ContextBuilderUtil::class.java)
        logger.warn("MD 파일 없음: ${mdFile.toAbsolutePath()} – graph 정보만 제공")
        
        if (node == null) return "정보 없음\n⚠️ 분석 문서 미생성 – /explain 실행 권장"
        return buildGraphContext(node)
    }

    private fun extractFileName(path: String): String {
        return path.substringAfterLast("/")
    }

    private fun extractMdSections(mdFile: Path, sections: List<Int>, node: FileNode?): String {
        val content = mdFile.readText()
        val lines = content.lines()
        val result = StringBuilder()
        
        var currentSection = 0
        var linesInSection = 0
        for (line in lines) {
            if (line.startsWith("## ")) {
                currentSection++
                linesInSection = 0
            }
            if (currentSection in sections) {
                if (currentSection == 3 && linesInSection > 10) {
                    if (linesInSection == 11) {
                        val methods = node?.methodNames ?: emptyList()
                        val getterSetterCount = methods.count { it.startsWith("get") || it.startsWith("set") || it.startsWith("is") }
                        val ratio = if (methods.isNotEmpty()) getterSetterCount.toDouble() / methods.size else 0.0
                        
                        if (ratio >= 0.7) {
                            result.appendLine("... (이하 생략 - 메서드 다수 존재, 주로 getter/setter로 추정)")
                        } else {
                            result.appendLine("... (이하 생략 - 메서드 다수 존재)")
                        }
                    }
                } else {
                    result.appendLine(line)
                }
                linesInSection++
            }
        }
        return result.toString().trim()
    }

    private fun summarizeMethods(methods: List<String>): String {
        if (methods.size <= 20) return "methods: ${methods.joinToString(", ")}"
        
        val getterSetterCount = methods.count { 
            it.startsWith("get") || it.startsWith("set") || it.startsWith("is") 
        }
        val ratio = getterSetterCount.toDouble() / methods.size
        
        return if (ratio > 0.7) {
            "methods: ${methods.size}개 (주로 getter/setter, 비즈니스 메서드 ${methods.size - getterSetterCount}개)"
        } else {
            val keyMethods = methods.filter { !it.startsWith("get") && !it.startsWith("set") && !it.startsWith("is") }
            "methods: ${keyMethods.joinToString(", ")} 외 getter/setter ${getterSetterCount}개"
        }
    }

    fun buildGraphContext(node: FileNode): String {
        return buildString {
            appendLine("- fileType: ${node.fileType}, layer: ${node.layer}")
            appendLine("- className: ${node.className}")
            node.superClass?.let { appendLine("- superClass: $it") }
            appendLine("- ${summarizeMethods(node.methodNames)}")
            appendLine("- injections: ${node.injections.joinToString(", ").ifEmpty { "없음" }}")
            appendLine("- dependedBy: ${node.dependedBy.joinToString(", ").ifEmpty { "없음" }}")
            if (node.koreanComments.isNotEmpty()) {
                appendLine("- koreanComments:")
                node.koreanComments.take(5).forEach { appendLine("  - $it") }
            }
        }
    }
}
