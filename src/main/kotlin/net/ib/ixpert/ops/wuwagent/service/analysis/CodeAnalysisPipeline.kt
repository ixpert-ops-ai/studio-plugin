// analysis/CodeAnalysisPipeline.kt
package net.ib.ixpert.ops.wuwagent.service.analysis

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import net.ib.ixpert.ops.wuwagent.service.analysis.extractor.*
import net.ib.ixpert.ops.wuwagent.service.analysis.model.*

/**
 * 코드 분석 파이프라인 — 구조 추출부터 프롬프트 조립까지를 조율합니다.
 *
 * 흐름:
 * 1. 언어/환경에 따라 최적의 구조 추출기 선택
 * 2. 구조 추출 실행 (실패 시 자동 fallback)
 * 3. Thymeleaf 감지 및 추가 추출
 * 4. ExtractedStructure 반환 → PromptTemplates에서 프롬프트 조립
 */
class CodeAnalysisPipeline {

    private val log = Logger.getInstance(CodeAnalysisPipeline::class.java)

    data class AnalysisInput(
        val code: String,
        val languageId: String,
        val fileName: String,
        val document: Document,
        val psiFile: PsiFile?,
        val isPartial: Boolean,
        val startLine: Int?,
        val endLine: Int?
    )

    /**
     * 파이프라인 실행: 최적의 방법으로 구조를 추출하여 반환합니다.
     */
    fun extractStructure(input: AnalysisInput): ExtractedStructure {
        val langId = input.languageId.lowercase()

        // 1단계: 최적 추출기로 구조 추출 시도
        val baseStructure = tryExtractWithBestMethod(input, langId)

        // 2단계: Thymeleaf 감지 및 보강
        var enriched = enrichWithThymeleaf(input.code, langId, baseStructure)

        // 3단계: 부분 선택 시 구조 필터링 (선택 영역과 겹치는 심볼/클래스만 유지)
        if (input.isPartial && input.startLine != null && input.endLine != null) {
            enriched = filterStructureByRange(enriched, input.startLine, input.endLine)
        }

        log.info(
            "구조 추출 완료: method=${enriched.extractionMethod.displayName}, " +
            "symbols=${enriched.symbols.size}, classes=${enriched.classes.size}, " +
            "hasThymeleaf=${enriched.thymeleafStructure != null}"
        )

        return enriched
    }

    private fun filterStructureByRange(
        structure: ExtractedStructure,
        startLine: Int,
        endLine: Int
    ): ExtractedStructure {
        val filteredSymbols = structure.symbols.filter { sym ->
            // 교집합이 있는지 확인 (sym 영역이 선택 영역과 겹치는가)
            val symStart = sym.startLine
            val symEnd = sym.endLine
            !(symEnd < startLine || symStart > endLine)
        }

        val filteredClasses = structure.classes.filter { cls ->
            val clsLine = cls.line
            clsLine in startLine..endLine
        }
        
        // 원문은 선택된 코드 자체로 교체 (선택 부분 분석을 위해)
        return structure.copy(
            symbols = filteredSymbols,
            classes = filteredClasses,
            rawCode = null // rawCode는 StructureFormatter에서 선택코드가 주입되도록 변경할 예정이므로 일단 null
        )
    }

    /**
     * PSI → Tree-sitter → Regex → Raw 순서로 fallback하며 구조 추출을 시도합니다.
     */
    private fun tryExtractWithBestMethod(
        input: AnalysisInput,
        langId: String
    ): ExtractedStructure {
        // 1순위: PSI (Java, Kotlin 등 IntelliJ 완전 지원 언어)
        if (input.psiFile != null && PsiStructureExtractor.supports(langId)) {
            try {
                val extractor = PsiStructureExtractor(input.psiFile, input.document)
                val result = extractor.extract(input.code, langId)
                if (result.hasStructure()) {
                    log.info("PSI 추출 성공: ${result.symbols.size}개 심볼")
                    return result
                }
            } catch (e: Exception) {
                log.warn("PSI 추출 실패, fallback 시도: ${e.message}")
            }
        }

        // 2순위: Tree-sitter (JS, TS, Vue, Python 등 — 네이티브 라이브러리 필요)
        val treeSitterExtractor = TreeSitterExtractor()
        if (treeSitterExtractor.supports(langId)) {
            try {
                val result = treeSitterExtractor.extract(input.code, langId)
                if (result.hasStructure()) {
                    log.info("Tree-sitter 추출 성공: ${result.symbols.size}개 심볼")
                    return result
                }
            } catch (e: Exception) {
                log.warn("Tree-sitter 추출 실패, Regex fallback 시도: ${e.message}")
            }
        }

        // 3순위: 정규식 경량 추출 (Tree-sitter 없이도 동작)
        val regexExtractor = RegexExtractor()
        if (regexExtractor.supports(langId)) {
            try {
                val result = regexExtractor.extract(input.code, langId)
                if (result.hasStructure()) {
                    log.info("정규식 추출 성공: ${result.symbols.size}개 심볼")
                    return result
                }
            } catch (e: Exception) {
                log.warn("정규식 추출 실패: ${e.message}")
            }
        }

        // 4순위: 코드 원문 그대로 (구조 추출 없음)
        log.info("구조 추출 불가, 코드 원문 사용: language=$langId")
        return ExtractedStructure.rawOnly(input.code)
    }

    /**
     * Thymeleaf 감지 시 구조 정보 보강
     */
    private fun enrichWithThymeleaf(
        code: String,
        langId: String,
        base: ExtractedStructure
    ): ExtractedStructure {
        if (!ThymeleafExtractor.isThymeleaf(code, langId)) {
            return base
        }

        return try {
            val thStructure = ThymeleafExtractor.extract(code)
            if (thStructure.isEmpty()) {
                base
            } else {
                log.info("Thymeleaf 구조 추출: bindings=${thStructure.bindings.size}, " +
                         "conditionals=${thStructure.conditionals.size}")
                base.copy(thymeleafStructure = thStructure)
            }
        } catch (e: Exception) {
            log.warn("Thymeleaf 추출 실패: ${e.message}")
            base
        }
    }
}

