import os

file_path = r"C:\Workspace\DEV-ASSISTANT\IDE-PLUGIN\intelliJ\ai-assistant-plugin\src\main\kotlin\net\ib\ixpert\ops\wuwagent\agent\ImplementContextBuilder.kt"
with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# Replace extractClassSkeleton definition
old_extract = """    private fun extractClassSkeleton(source: String): String {
        var depth = 0
        val result = StringBuilder()
        for (line in source.lines()) {
            val openCount = line.count { it == '{' }
            val closeCount = line.count { it == '}' }
            
            if (depth == 1 && openCount > 0) {
                if (openCount == closeCount) {
                    result.appendLine(line) // 한 줄짜리 메서드는 보존
                } else {
                    result.appendLine(line.substringBefore('{') + "{ /* 본문 생략 */ }")
                    depth += openCount - closeCount
                    continue
                }
            } else if (depth <= 1) {
                result.appendLine(line)
            }
            
            depth += openCount - closeCount
        }
        return result.toString()
    }"""

new_extract = """    @Deprecated("Use ImplementTrimmer.trimJavaLike instead")
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
            "js" -> ImplementFileType.JAVASCRIPT
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
            ImplementFileType.UNKNOWN -> (length / 2.5).toInt()
        }
    }"""
content = content.replace(old_extract, new_extract)

enum_def = """
enum class ImplementFileType {
    JAVA_LIKE, VIEW_MARKUP, MYBATIS_XML, JAVASCRIPT, UNKNOWN
}

object ImplementContextBuilder {"""
content = content.replace("object ImplementContextBuilder {", enum_def)

# Modify buildModifyContext signature and body
old_buildContextWithSource_sig = "private fun buildContextWithSource(md: String, source: String, graph: ProjectGraph, similarRefs: List<FileReference>): String {"
new_buildContextWithSource_sig = "private fun buildContextWithSource(md: String, source: String, graph: ProjectGraph, similarRefs: List<FileReference>, filePath: String, targetHints: List<String> = emptyList(), targetQueryIds: List<String> = emptyList()): String {"
content = content.replace(old_buildContextWithSource_sig, new_buildContextWithSource_sig)

old_buildContextWithSource_call = "return buildContextWithSource(md, sourceContent, graph, similarRefs)"
new_buildContextWithSource_call = "return buildContextWithSource(md, sourceContent, graph, similarRefs, path)"
content = content.replace(old_buildContextWithSource_call, new_buildContextWithSource_call)

# Update buildContextWithSource implementation
old_source_trim = """        if (source.isNotBlank()) {
            val trimmedSource = if (source.lines().size > 150) {
                extractClassSkeleton(source)
            } else {
                source
            }
            sb.appendLine("## 원본 소스")
            sb.appendLine("```java")
            sb.appendLine(trimmedSource)
            sb.appendLine("```")
        }"""
new_source_trim = """        if (source.isNotBlank()) {
            val fileType = determineFileType(filePath, source)
            val trimmedSource = when (fileType) {
                ImplementFileType.JAVA_LIKE -> ImplementTrimmer.trimJavaLike(source)
                ImplementFileType.VIEW_MARKUP -> ImplementTrimmer.trimViewMarkup(source, targetHints)
                ImplementFileType.MYBATIS_XML -> ImplementTrimmer.trimMybatisXml(source, targetQueryIds)
                ImplementFileType.JAVASCRIPT -> ImplementTrimmer.trimJavaScript(source)
                ImplementFileType.UNKNOWN -> source
            }
            val langTag = when (fileType) {
                ImplementFileType.JAVA_LIKE -> if (filePath.endsWith(".kt")) "kotlin" else "java"
                ImplementFileType.VIEW_MARKUP -> "html"
                ImplementFileType.MYBATIS_XML -> "xml"
                ImplementFileType.JAVASCRIPT -> "javascript"
                ImplementFileType.UNKNOWN -> ""
            }
            sb.appendLine("## 원본 소스")
            sb.appendLine("```$langTag")
            sb.appendLine(trimmedSource)
            sb.appendLine("```")
        }"""
content = content.replace(old_source_trim, new_source_trim)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)
print("Updated ImplementContextBuilder.kt")
