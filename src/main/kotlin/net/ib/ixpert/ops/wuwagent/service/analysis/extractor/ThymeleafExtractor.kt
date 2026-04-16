// analysis/extractor/ThymeleafExtractor.kt
package net.ib.ixpert.ops.wuwagent.service.analysis.extractor

import net.ib.ixpert.ops.wuwagent.service.analysis.model.*

/**
 * Thymeleaf 템플릿 전용 구조 추출기.
 * HTML 파일 내 th:* 속성을 분석하여 서버 바인딩 정보를 추출합니다.
 */
object ThymeleafExtractor {

    /**
     * HTML 코드가 Thymeleaf 템플릿인지 감지
     */
    fun isThymeleaf(code: String, languageId: String): Boolean {
        if (languageId.lowercase() !in listOf("html", "thymeleaf")) return false
        return code.contains("th:") || code.contains("xmlns:th")
    }

    /**
     * Thymeleaf 구조 정보 추출
     */
    fun extract(code: String): ThymeleafStructure {
        val bindings = mutableListOf<ThymeleafBinding>()
        val conditionals = mutableListOf<ThymeleafBinding>()
        val iterations = mutableListOf<ThymeleafBinding>()
        val formBindings = mutableListOf<ThymeleafBinding>()
        val fragments = mutableListOf<FragmentInfo>()
        val includes = mutableListOf<FragmentRef>()

        val thAttrPattern = Regex("""(th:\w+(?:-\w+)?)\s*=\s*"([^"]*)"""")
        val fragmentDefPattern = Regex("""th:fragment\s*=\s*"(\w+)(?:\(([^)]*)\))?"""")
        val fragmentRefPattern = Regex("""th:(?:replace|insert)\s*=\s*"~?\{?([^:}]+)::([^}"(]+)""")

        code.lines().forEachIndexed { index, line ->
            val lineNum = index + 1
            val tagMatch = Regex("""<(\w+)""").find(line)
            val tagContext = tagMatch?.groupValues?.get(1) ?: "unknown"

            // Fragment 정의
            fragmentDefPattern.find(line)?.let { match ->
                fragments.add(FragmentInfo(
                    name = match.groupValues[1],
                    parameters = match.groupValues[2]
                        .split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    line = lineNum
                ))
            }

            // Fragment 참조
            fragmentRefPattern.find(line)?.let { match ->
                includes.add(FragmentRef(
                    templateName = match.groupValues[1].trim(),
                    fragmentName = match.groupValues[2].trim(),
                    line = lineNum
                ))
            }

            // 모든 th:* 속성
            thAttrPattern.findAll(line).forEach { match ->
                val binding = ThymeleafBinding(
                    attribute = match.groupValues[1],
                    expression = match.groupValues[2],
                    line = lineNum,
                    tagContext = "<$tagContext>"
                )

                when {
                    binding.attribute in listOf("th:if", "th:unless", "th:switch", "th:case") ->
                        conditionals.add(binding)
                    binding.attribute == "th:each" ->
                        iterations.add(binding)
                    binding.attribute in listOf("th:action", "th:field", "th:object") ->
                        formBindings.add(binding)
                    binding.attribute != "th:fragment" ->
                        bindings.add(binding)
                }
            }
        }

        return ThymeleafStructure(
            fragments = fragments,
            bindings = bindings,
            conditionals = conditionals,
            iterations = iterations,
            formBindings = formBindings,
            includes = includes
        )
    }
}

