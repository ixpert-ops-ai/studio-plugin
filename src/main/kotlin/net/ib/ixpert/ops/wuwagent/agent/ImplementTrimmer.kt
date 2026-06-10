package net.ib.ixpert.ops.wuwagent.agent

object ImplementTrimmer {

    fun trimViewMarkup(content: String, targetHints: List<String>, maxLines: Int = 300): String {
        val lines = content.lines()
        if (lines.size <= maxLines) return content

        val result = StringBuilder()
        var skippedCount = 0

        for (i in lines.indices) {
            val line = lines[i]
            // If the line contains any of the target hints, or we are near the top, we keep it.
            // A simple implementation: keep the first 100 lines, and any lines near target hints.
            // For now, if no target hints are found in the block, we truncate at maxLines.
            if (i < maxLines) {
                result.appendLine(line)
            } else {
                val hasHint = targetHints.any { hint -> line.contains(hint, ignoreCase = true) }
                if (hasHint) {
                    if (skippedCount > 0) {
                        result.appendLine("<!-- ... 이하 ${skippedCount}줄 생략 ... -->")
                        skippedCount = 0
                    }
                    result.appendLine(line)
                } else {
                    skippedCount++
                }
            }
        }
        
        if (skippedCount > 0) {
            result.appendLine("<!-- ... 하단 ${skippedCount}줄 생략 ... -->")
        }

        return result.toString()
    }

    fun trimMybatisXml(content: String, targetQueryIds: List<String>): String {
        // Find basic block limits
        val lines = content.lines()
        val result = StringBuilder()
        
        var inTargetQuery = false
        var inNonTargetQuery = false
        var currentQueryId = ""
        var skippedCount = 0
        var queryCount = 0

        for (line in lines) {
            val lowerLine = line.lowercase()
            if (lowerLine.contains("<select") || lowerLine.contains("<insert") || lowerLine.contains("<update") || lowerLine.contains("<delete")) {
                val idMatch = Regex("""id\s*=\s*["']([^"']+)["']""").find(line)
                if (idMatch != null) {
                    currentQueryId = idMatch.groupValues[1]
                    val isTarget = targetQueryIds.contains(currentQueryId) || (targetQueryIds.isEmpty() && queryCount < 3)
                    if (isTarget) {
                        inTargetQuery = true
                        inNonTargetQuery = false
                        result.appendLine(line)
                        if (targetQueryIds.isEmpty()) queryCount++
                    } else {
                        inTargetQuery = false
                        inNonTargetQuery = true
                        result.appendLine("    <!-- <${line.substringAfter("<").substringBefore(" ")} id=\"$currentQueryId\" ...> 생략 -->")
                        skippedCount = 0
                    }
                } else {
                    result.appendLine(line)
                }
                continue
            }
            
            if (lowerLine.contains("</select>") || lowerLine.contains("</insert>") || lowerLine.contains("</update>") || lowerLine.contains("</delete>")) {
                if (inTargetQuery) {
                    result.appendLine(line)
                }
                inTargetQuery = false
                inNonTargetQuery = false
                continue
            }
            
            if (inTargetQuery) {
                result.appendLine(line)
            } else if (inNonTargetQuery) {
                skippedCount++
            } else {
                result.appendLine(line)
            }
        }
        return result.toString()
    }

    fun trimJavaScript(content: String, maxLines: Int = 300): String {
        val lines = content.lines()
        if (lines.size <= maxLines) return content
        
        return lines.take(maxLines).joinToString("\n") + "\n// ... 이하 ${lines.size - maxLines}줄 하단 생략 ..."
    }

    fun trimJavaLike(source: String): String {
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
    }

    fun trimVueSfc(content: String, targetHints: List<String>): String {
        val result = StringBuilder()
        
        var inStyle = false
        var inTemplate = false
        var inScript = false
        
        val templateLines = mutableListOf<String>()
        val scriptLines = mutableListOf<String>()
        
        val styleStartRegex = Regex("""<style[^>]*>""")
        val scriptStartRegex = Regex("""<script[^>]*>""")
        val templateStartRegex = Regex("""<template[^>]*>""")
        
        var skippedStyleLines = 0
        var i = 0
        val lines = content.lines()
        
        while (i < lines.size) {
            val line = lines[i]
            
            if (!inStyle && !inTemplate && !inScript) {
                if (styleStartRegex.containsMatchIn(line)) {
                    val hasHint = targetHints.any { hint -> line.contains(hint, ignoreCase = true) }
                    inStyle = true
                    skippedStyleLines = 1
                    if (line.contains("</style>")) {
                        inStyle = false
                        result.appendLine("<!-- style 블록 생략 ($skippedStyleLines 줄) -->")
                    }
                } else if (templateStartRegex.containsMatchIn(line)) {
                    inTemplate = true
                    templateLines.add(line)
                    if (line.contains("</template>")) {
                        inTemplate = false
                        val trimmedTemplate = trimViewMarkup(templateLines.joinToString("\n"), targetHints, maxLines = 150)
                        result.append(trimmedTemplate)
                        if (!trimmedTemplate.endsWith("\n")) result.appendLine()
                        templateLines.clear()
                    }
                } else if (scriptStartRegex.containsMatchIn(line)) {
                    inScript = true
                    scriptLines.add(line)
                    if (line.contains("</script>")) {
                        inScript = false
                        val trimmedScript = trimJavaScript(scriptLines.joinToString("\n"), maxLines = 200)
                        result.append(trimmedScript)
                        if (!trimmedScript.endsWith("\n")) result.appendLine()
                        scriptLines.clear()
                    }
                } else {
                    result.appendLine(line)
                }
            } else if (inStyle) {
                skippedStyleLines++
                if (line.contains("</style>")) {
                    inStyle = false
                    result.appendLine("<!-- style 블록 생략 ($skippedStyleLines 줄) -->")
                }
            } else if (inTemplate) {
                templateLines.add(line)
                if (line.contains("</template>")) {
                    inTemplate = false
                    val trimmedTemplate = trimViewMarkup(templateLines.joinToString("\n"), targetHints, maxLines = 150)
                    result.append(trimmedTemplate)
                    if (!trimmedTemplate.endsWith("\n")) result.appendLine()
                    templateLines.clear()
                }
            } else if (inScript) {
                scriptLines.add(line)
                if (line.contains("</script>")) {
                    inScript = false
                    val trimmedScript = trimJavaScript(scriptLines.joinToString("\n"), maxLines = 200)
                    result.append(trimmedScript)
                    if (!trimmedScript.endsWith("\n")) result.appendLine()
                    scriptLines.clear()
                }
            }
            i++
        }
        
        if (templateLines.isNotEmpty()) {
            val trimmedTemplate = trimViewMarkup(templateLines.joinToString("\n"), targetHints, maxLines = 150)
            result.append(trimmedTemplate)
            if (!trimmedTemplate.endsWith("\n")) result.appendLine()
        }
        if (scriptLines.isNotEmpty()) {
            val trimmedScript = trimJavaScript(scriptLines.joinToString("\n"), maxLines = 200)
            result.append(trimmedScript)
            if (!trimmedScript.endsWith("\n")) result.appendLine()
        }
        
        return result.toString()
    }
}
