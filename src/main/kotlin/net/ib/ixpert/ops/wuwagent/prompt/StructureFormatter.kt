package net.ib.ixpert.ops.wuwagent.prompt

import net.ib.ixpert.ops.wuwagent.service.analysis.model.*

/**
 * ExtractedStructure → 프롬프트용 문자열 변환 유틸리티.
 * intellij-plugin의 PromptTemplates.formatStructureInfo() 등에서 이식.
 */
object StructureFormatter {

    /**
     * 클래스/함수 구조 요약 정보를 포맷팅합니다.
     */
    fun formatStructureInfo(structure: ExtractedStructure): String {
        if (!structure.hasStructure()) return ""

        val sb = StringBuilder()
        sb.appendLine("## 추출된 구조 정보")

        // 클래스 정보
        if (structure.classes.isNotEmpty()) {
            sb.appendLine("\n### 클래스/인터페이스")
            for (cls in structure.classes) {
                val annotations = if (cls.annotations.isNotEmpty()) {
                    cls.annotations.joinToString(", ") { "@$it" } + " "
                } else ""
                val hierarchy = buildString {
                    if (cls.superClass != null) append(" extends ${cls.superClass}")
                    if (cls.interfaces.isNotEmpty()) append(" implements ${cls.interfaces.joinToString(", ")}")
                }
                sb.appendLine("- ${annotations}${cls.kind.displayName} `${cls.name}`$hierarchy (line ${cls.line})")
            }
        }

        // 함수/메서드 요약
        if (structure.symbols.isNotEmpty()) {
            sb.appendLine("\n### 함수/메서드 목록 (${structure.symbols.size}개)")
            for (sym in structure.symbols) {
                val paramsStr = sym.params.joinToString(", ")
                val returnStr = sym.returnType?.let { ": $it" } ?: ""
                val modifiers = buildString {
                    if (sym.isExported) append("public ")
                    if (sym.isStatic) append("static ")
                    if (sym.isAsync) append("async ")
                }
                val annotations = if (sym.annotations.isNotEmpty()) {
                    sym.annotations.joinToString(" ") { "@$it" } + " "
                } else ""
                val parent = sym.parentClass?.let { "($it)" } ?: ""
                sb.appendLine("- ${annotations}${modifiers}${sym.kind.displayName} `${sym.name}($paramsStr)${returnStr}` $parent [${sym.startLine}-${sym.endLine}]")
            }
        }

        // Import 요약
        val externalDeps = structure.getExternalDependencies()
        if (externalDeps.isNotEmpty()) {
            sb.appendLine("\n### 주요 외부 의존성")
            for (dep in externalDeps.take(15)) {
                sb.appendLine("- `$dep`")
            }
            if (externalDeps.size > 15) {
                sb.appendLine("- ...외 ${externalDeps.size - 15}개")
            }
        }

        return sb.toString()
    }

    /**
     * Thymeleaf 구조 정보를 포맷팅합니다.
     */
    fun formatThymeleafInfo(structure: ExtractedStructure): String {
        val thymeleaf = structure.thymeleafStructure ?: return ""
        if (thymeleaf.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("## Thymeleaf 바인딩 정보")

        if (thymeleaf.fragments.isNotEmpty()) {
            sb.appendLine("\n### Fragment 정의")
            for (frag in thymeleaf.fragments) {
                val params = if (frag.parameters.isNotEmpty()) "(${frag.parameters.joinToString(", ")})" else ""
                sb.appendLine("- `${frag.name}$params` (line ${frag.line})")
            }
        }

        if (thymeleaf.bindings.isNotEmpty()) {
            sb.appendLine("\n### 데이터 바인딩")
            for (binding in thymeleaf.bindings.take(20)) {
                sb.appendLine("- `${binding.attribute}=\"${binding.expression}\"` (line ${binding.line}, tag: ${binding.tagContext})")
            }
        }

        if (thymeleaf.conditionals.isNotEmpty()) {
            sb.appendLine("\n### 조건부 렌더링")
            for (cond in thymeleaf.conditionals) {
                sb.appendLine("- `${cond.attribute}=\"${cond.expression}\"` (line ${cond.line})")
            }
        }

        if (thymeleaf.iterations.isNotEmpty()) {
            sb.appendLine("\n### 반복 렌더링")
            for (iter in thymeleaf.iterations) {
                sb.appendLine("- `${iter.attribute}=\"${iter.expression}\"` (line ${iter.line})")
            }
        }

        if (thymeleaf.formBindings.isNotEmpty()) {
            sb.appendLine("\n### 폼 바인딩")
            for (form in thymeleaf.formBindings) {
                sb.appendLine("- `${form.attribute}=\"${form.expression}\"` (line ${form.line})")
            }
        }

        if (thymeleaf.includes.isNotEmpty()) {
            sb.appendLine("\n### Fragment 참조")
            for (inc in thymeleaf.includes) {
                sb.appendLine("- `${inc.templateName}::${inc.fragmentName}` (line ${inc.line})")
            }
        }

        return sb.toString()
    }

    /**
     * 핵심 메서드 본문을 포맷팅합니다.
     * 부분 선택인 경우(isPartial=true)에는 선택된 코드 자체를 출력합니다.
     */
    fun formatKeyCode(structure: ExtractedStructure, maxSymbols: Int = 5, isPartial: Boolean = false, partialCode: String? = null): String {
        if (isPartial && partialCode != null) {
            return "## 선택 영역 소스 코드\n<ib_source>\n${escapeSourceTag(partialCode)}\n</ib_source>"
        }

        val keySymbols = structure.getKeySymbols(maxSymbols)
        if (keySymbols.isEmpty()) {
            return if (structure.rawCode != null) {
                "## 전체 소스 코드\n<ib_source>\n${escapeSourceTag(structure.rawCode)}\n</ib_source>"
            } else ""
        }

        val sb = StringBuilder()
        sb.appendLine("## 핵심 메서드 본문 (토큰 최적화: 상위 ${keySymbols.size}개)")
        for (sym in keySymbols) {
            sb.appendLine("\n### ${sym.parentClass?.let { "$it." } ?: ""}${sym.name}")
            sb.appendLine("```")
            sb.appendLine(sym.bodyText.take(500))
            sb.appendLine("```")
        }

        return sb.toString()
    }

    fun toPromptVariables(
        fileName: String,
        language: String,
        structure: ExtractedStructure,
        isPartial: Boolean,
        startLine: Int? = null,
        endLine: Int? = null,
        partialCode: String? = null,
        includeFaq: Boolean = false
    ): Map<String, String> {
        val langNames = mapOf(
            "java" to "Java", "JAVA" to "Java",
            "kotlin" to "Kotlin", "Kotlin" to "Kotlin",
            "javascript" to "JavaScript", "typescript" to "TypeScript",
            "html" to "HTML", "css" to "CSS",
            "python" to "Python", "sql" to "SQL",
            "xml" to "XML", "json" to "JSON", "yaml" to "YAML",
            "jsp" to "JSP", "JSP" to "JSP"
        )
        val langName = langNames[language] ?: language

        val locationInfo = if (isPartial && startLine != null && endLine != null) {
            "- 분석 범위: ${fileName}의 ${startLine}~${endLine}번째 줄 (부분 선택)"
        } else {
            "- 분석 범위: ${fileName} 전체"
        }

        val analysisMode = if (isPartial) "선택 영역 분석" else "파일 전체 분석"

        val patternGuide = if (!isPartial) {
            "\n- 디자인 패턴(Singleton, Factory, Observer 등)이 명확히 관찰될 경우\n  패턴명과 구현 위치를 기술하세요."
        } else ""

        val nonCodeLanguages = setOf("json", "xml", "yaml", "css", "scss", "html", "markdown")
        val isNonCode = nonCodeLanguages.contains(language.lowercase()) ||
                nonCodeLanguages.any { langNames[it]?.equals(language, ignoreCase = true) == true }

        val nonCodeNotice = if (isNonCode) {
            "- 함수/메서드가 존재하지 않는 파일의 경우 이 섹션은 \"해당 없음\"으로 표기하세요.\n"
        } else ""

        val hasThymeleaf = structure.thymeleafStructure != null
        val thymeleafNotice = if (hasThymeleaf) {
            """- Thymeleaf 파일의 경우 위 "Thymeleaf 구조 정보"의 바인딩을 아래 형식으로 정리하세요.

| 분류 | 위치 | 표현식 | 역할 요약 |
| :--- | :--- | :--- | :--- |
| 조건부 렌더링 | L행번호 <태그> | th:if="표현식" | 1문장 요약 |
| 반복 | L행번호 <태그> | th:each="표현식" | 1문장 요약 |
| 데이터 바인딩 | L행번호 <태그> | th:text="표현식" | 1문장 요약 |
| 폼 처리 | L행번호 <태그> | th:action="표현식" | 1문장 요약 |
"""
        } else ""

        val functionGuide = if (!isPartial) {
            """${nonCodeNotice}위 "IDE 추출 구조 정보"에 나열된 함수/메서드 목록을 그대로 사용하세요.
목록을 새로 추출하거나 누락/추가하지 말고, 각 항목의 역할을 자연어로 설명하는 데 집중하세요.
분류 기준: [진입점, 핵심 로직, 외부 연동, 보조]

| 분류 | 함수/메서드명 | 역할 요약 |
| :--- | :--- | :--- |
| <분류명> | `식별자(파라미터)` | 1문장으로 요약된 기능 |
$thymeleafNotice"""
        } else {
            """${nonCodeNotice}- 코드에 등장하는 순서대로 추출하세요.
- 대상: function, method, 생성자/소멸자, 이벤트 핸들러, 로직이 있는 getter/setter.
- 제외: 로직 없는 단순 필드 반환/설정자, 프레임워크 자동 생성 코드.
- 분류 기준: [진입점, 핵심 로직, 외부 연동, 보조]

| 분류 | 함수/메서드명 | 역할 요약 |
| :--- | :--- | :--- |
| <분류명> | `식별자(파라미터)` | 1문장으로 요약된 기능 |"""
        }

        val faqSection = if (includeFaq) {
            """
            ## 7. 자주 묻는 질문 (FAQ)
            - 이 코드에 대해 개발자가 실무에서 물을 법한 질문 3~5개를 생성하세요.
            - 각 질문에 대해 코드 사실에만 기반하여 2~4문장으로 답변하세요.
            - 질문은 "~하려면?", "~는 어떻게 동작하나?", "~의 역할은?", "~를 호출하기 전에 필요한 것은?" 패턴으로 작성하세요.
            - 아래 형식으로 작성하세요
            ### Q. 질문 내용?
            답변 내용 (2~4문장)
            """.trimIndent()
        } else ""

        return mapOf(
            "LANGUAGE" to langName,
            "ANALYSIS_MODE" to analysisMode,
            "LOCATION_INFO" to locationInfo,
            "EXTRACTION_METHOD" to structure.extractionMethod.displayName,
            "STRUCTURE_INFO" to formatStructureInfo(structure),
            "THYMELEAF_INFO" to formatThymeleafInfo(structure),
            "KEY_CODE" to formatKeyCode(structure, isPartial = isPartial, partialCode = partialCode),
            "PATTERN_GUIDE" to patternGuide,
            "FUNCTION_GUIDE" to functionGuide,
            "FAQ_SECTION" to faqSection
        )
    }

    private fun escapeSourceTag(code: String): String {
        return code.replace("</ib_source>", "<\\/ib_source>")
    }
}
