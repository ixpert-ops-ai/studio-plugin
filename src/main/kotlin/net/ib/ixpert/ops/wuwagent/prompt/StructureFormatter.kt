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
     * 핵심 메서드 본문을 포맷팅합니다 (토큰 절약을 위해 상위 N개만).
     */
    fun formatKeyCode(structure: ExtractedStructure, maxSymbols: Int = 5): String {
        val keySymbols = structure.getKeySymbols(maxSymbols)
        if (keySymbols.isEmpty()) {
            return if (structure.rawCode != null) {
                "## 소스 코드\n<ib_source>\n${escapeSourceTag(structure.rawCode)}\n</ib_source>"
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

    /**
     * 모든 구조 정보를 프롬프트 변수 맵으로 변환합니다.
     */
    fun toPromptVariables(
        structure: ExtractedStructure,
        language: String,
        fileName: String,
        isPartial: Boolean,
        startLine: Int? = null,
        endLine: Int? = null
    ): Map<String, String> {
        val langNames = mapOf(
            "java" to "Java", "JAVA" to "Java",
            "kotlin" to "Kotlin", "Kotlin" to "Kotlin",
            "javascript" to "JavaScript", "typescript" to "TypeScript",
            "html" to "HTML", "css" to "CSS",
            "python" to "Python", "sql" to "SQL",
            "xml" to "XML", "json" to "JSON", "yaml" to "YAML"
        )
        val langName = langNames[language] ?: language

        val locationInfo = if (isPartial && startLine != null && endLine != null) {
            "- 분석 범위: ${fileName}의 ${startLine}~${endLine}번째 줄 (부분 선택)"
        } else {
            "- 분석 범위: ${fileName} 전체"
        }

        val analysisMode = if (isPartial) "선택 영역 분석" else "파일 전체 분석"

        return mapOf(
            "LANGUAGE" to langName,
            "ANALYSIS_MODE" to analysisMode,
            "LOCATION_INFO" to locationInfo,
            "EXTRACTION_METHOD" to structure.extractionMethod.displayName,
            "STRUCTURE_INFO" to formatStructureInfo(structure),
            "THYMELEAF_INFO" to formatThymeleafInfo(structure),
            "KEY_CODE" to formatKeyCode(structure)
        )
    }

    private fun escapeSourceTag(code: String): String {
        return code.replace("</ib_source>", "<\\/ib_source>")
    }
}
