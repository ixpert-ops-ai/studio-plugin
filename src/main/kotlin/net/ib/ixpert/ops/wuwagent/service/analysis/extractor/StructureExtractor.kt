package net.ib.ixpert.ops.wuwagent.service.analysis.extractor

import net.ib.ixpert.ops.wuwagent.service.analysis.model.ExtractedStructure

/**
 * 구조 추출기 공통 인터페이스.
 * PSI, Tree-sitter, 정규식 등 모든 추출 방식이 이 인터페이스를 구현합니다.
 */
interface StructureExtractor {

    /**
     * 해당 언어를 이 추출기가 처리할 수 있는지 확인
     */
    fun supports(languageId: String): Boolean

    /**
     * 코드에서 구조 정보를 추출하여 통일 포맷으로 반환
     */
    fun extract(code: String, languageId: String): ExtractedStructure
}
