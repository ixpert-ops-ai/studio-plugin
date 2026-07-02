package net.ib.ixpert.ops.wuwagent.service.metagraph.trimmer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

object ContextTrimmer {
    
    /**
     * 파일 내용을 받아 불필요한 부분을 생략 처리하여 반환합니다.
     * @param filePath 파일 경로
     * @param content 원본 파일 내용
     * @param isSeed 이 파일이 Seed 파일(Hop 0)인지 여부
     */
    fun trimFile(filePath: String, content: String, isSeed: Boolean): String {
        // Seed 파일은 원칙적으로 원본을 100% 보존합니다.
        if (isSeed) return content

        if (filePath.endsWith(".java") || filePath.endsWith(".kt")) {
            return extractSkeleton(content)
        }
        
        return content
    }

    /**
     * 메서드 바디를 생략하고 인터페이스(Skeleton)만 추출하는 경량 파서
     */
    private fun extractSkeleton(content: String): String {
        val result = StringBuilder()
        var braceDepth = 0
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        var skipMode = false
        var i = 0

        while (i < content.length) {
            val c = content[i]
            val nextC = if (i + 1 < content.length) content[i + 1] else '\u0000'

            if (!inString && !inChar && !inBlockComment && c == '/' && nextC == '/') {
                inLineComment = true
                if (!skipMode) { result.append(c); result.append(nextC) }
                i += 2
                continue
            }
            if (!inString && !inChar && !inLineComment && c == '/' && nextC == '*') {
                inBlockComment = true
                if (!skipMode) { result.append(c); result.append(nextC) }
                i += 2
                continue
            }
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                if (!skipMode) result.append(c)
                i++
                continue
            }
            if (inBlockComment) {
                if (c == '*' && nextC == '/') {
                    inBlockComment = false
                    if (!skipMode) { result.append(c); result.append(nextC) }
                    i += 2
                } else {
                    if (!skipMode) result.append(c)
                    i++
                }
                continue
            }

            if (!inChar && c == '"') {
                val isRawStringBoundary = !inString && nextC == '"' && i + 2 < content.length && content[i+2] == '"'
                if (isRawStringBoundary) {
                    inString = true
                    if (!skipMode) result.append("\"\"\"")
                    i += 3
                    while (i < content.length) {
                        if (content[i] == '"' && i + 2 < content.length && content[i+1] == '"' && content[i+2] == '"') {
                            if (!skipMode) result.append("\"\"\"")
                            i += 3
                            inString = false
                            break
                        }
                        if (!skipMode) result.append(content[i])
                        i++
                    }
                    continue
                } else {
                    var escapes = 0
                    var j = i - 1
                    while (j >= 0 && content[j] == '\\') { escapes++; j-- }
                    if (escapes % 2 == 0) inString = !inString
                }
            }

            if (!inString && c == '\'') {
                var escapes = 0
                var j = i - 1
                while (j >= 0 && content[j] == '\\') { escapes++; j-- }
                if (escapes % 2 == 0) inChar = !inChar
            }

            if (!inString && !inChar) {
                if (c == '{') {
                    braceDepth++
                    if (braceDepth == 2) {
                        skipMode = true
                        result.append("{ /* implementation omitted */")
                    } else if (!skipMode) {
                        result.append(c)
                    }
                } else if (c == '}') {
                    if (braceDepth == 2) {
                        skipMode = false
                        result.append(" }")
                    } else if (!skipMode) {
                        result.append(c)
                    }
                    braceDepth--
                } else {
                    if (!skipMode) result.append(c)
                }
            } else {
                if (!skipMode) result.append(c)
            }
            i++
        }
        return result.toString()
    }
}
