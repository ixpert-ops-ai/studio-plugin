package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

/**
 * 도메인 사전: 한글 → 영어 변환을 위한 내장 사전 + 그래프 학습 기반 사전.
 *
 * 두 가지 소스에서 사전을 구축합니다:
 * 1. **내장 사전**: CRUD/계층/공통 용어 (정적)
 * 2. **그래프 학습**: ProjectGraph의 localName, ServiceEndpoint, DemMethodInfo에서 한글↔영어 쌍 자동 수집
 */
class DomainDictionary private constructor(
    private val entries: Map<String, Set<String>>
) {
    /**
     * 한글 키에 대응하는 영어 토큰 집합을 반환합니다.
     * 정확 매칭 + 부분 매칭(한글 키가 입력에 포함되거나 입력이 키에 포함)을 모두 시도합니다.
     */
    fun translate(koreanNoun: String): Set<String> {
        // 1. 정확 매칭
        val exact = entries[koreanNoun]
        if (exact != null) return exact

        // 2. 부분 매칭: 사전 키가 입력에 포함되거나 입력이 키에 포함
        val partial = mutableSetOf<String>()
        for ((key, values) in entries) {
            if (koreanNoun.contains(key) || key.contains(koreanNoun)) {
                partial.addAll(values)
            }
        }
        return partial
    }

    /**
     * CamelCase 문자열을 소문자 토큰으로 분해합니다.
     * 예: "MemberCardLimitService" → ["member", "card", "limit", "service"]
     */
    companion object {
        /**
         * ProjectGraph로부터 도메인 사전을 생성합니다.
         * 내장 사전 + 그래프의 한글 메타데이터에서 학습한 항목을 합산합니다.
         */
        fun load(graph: ProjectGraph): DomainDictionary {
            val learned = mutableMapOf<String, MutableSet<String>>()

            graph.files.values.forEach { node ->
                // FileNode.localName 학습
                node.localName?.let { korean ->
                    if (korean.isNotBlank()) {
                        val tokens = tokenizeCamelCase(node.className)
                        learned.getOrPut(korean) { mutableSetOf() }.addAll(tokens)
                    }
                }

                // ServiceEndpoint.localName 학습
                node.serviceEndpoints?.forEach { ep ->
                    ep.localName?.let { korean ->
                        if (korean.isNotBlank()) {
                            val tokens = tokenizeCamelCase(ep.methodName) + tokenizeCamelCase(ep.serviceId)
                            learned.getOrPut(korean) { mutableSetOf() }.addAll(tokens)
                        }
                    }
                }

                // DemMethodInfo.localName 학습
                node.demMethods?.forEach { dem ->
                    dem.localName?.let { korean ->
                        if (korean.isNotBlank()) {
                            val tokens = tokenizeCamelCase(dem.methodName)
                            learned.getOrPut(korean) { mutableSetOf() }.addAll(tokens)
                        }
                    }
                }
            }

            // 내장 사전 + 학습 사전 합산
            val merged = mutableMapOf<String, MutableSet<String>>()
            BUILTIN_TERMS.forEach { (k, v) -> merged.getOrPut(k) { mutableSetOf() }.add(v) }
            learned.forEach { (k, v) -> merged.getOrPut(k) { mutableSetOf() }.addAll(v) }

            return DomainDictionary(merged.mapValues { it.value.toSet() })
        }

        /**
         * CamelCase 또는 ALL_CAPS 문자열을 소문자 토큰으로 분해합니다.
         */
        fun tokenizeCamelCase(identifier: String): List<String> {
            if (identifier.isBlank()) return emptyList()
            // ALL_CAPS_STYLE → 언더스코어 분리
            if (identifier.contains('_')) {
                return identifier.split('_')
                    .filter { it.length >= 2 }
                    .map { it.lowercase() }
            }
            // CamelCase → 분리
            return Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
                .split(identifier)
                .filter { it.length >= 2 }
                .map { it.lowercase() }
        }

        /**
         * 내장 한글→영어 용어 사전.
         * 기존 RelevanceFilter.COMMON_TERMS를 확장한 버전입니다.
         */
        private val BUILTIN_TERMS = mapOf(
            // CRUD 용어
            "등록" to "register",
            "수정" to "update",
            "삭제" to "delete",
            "조회" to "search",
            "목록" to "list",
            "상세" to "detail",
            "추가" to "add",
            "변경" to "change",
            "저장" to "save",
            "처리" to "process",
            "검색" to "search",
            "전송" to "send",
            "취소" to "cancel",
            "승인" to "approve",
            "반려" to "reject",
            // 인증/보안
            "로그인" to "login",
            "로그아웃" to "logout",
            "인증" to "auth",
            "권한" to "permission",
            // 파일 관련
            "다운로드" to "download",
            "업로드" to "upload",
            // 상태
            "오류" to "error",
            "실패" to "fail",
            "성공" to "success",
            "에러" to "error",
            // 계층 용어
            "서비스" to "service",
            "컨트롤러" to "controller",
            "레포지토리" to "repository",
            "저장소" to "repository",
            "엔티티" to "entity",
            "설정" to "config",
            "유틸" to "util",
            // 도메인 일반
            "회원" to "member",
            "사용자" to "user",
            "고객" to "customer",
            "카드" to "card",
            "계좌" to "account",
            "거래" to "transaction",
            "결제" to "payment",
            "주문" to "order",
            "상품" to "product",
            "메뉴" to "menu",
            "코드" to "code",
            "한도" to "limit",
            "정보" to "info",
            "관리" to "manage",
            "통계" to "statistics",
            "이력" to "history",
            "알림" to "notification",
            "배치" to "batch"
        )
    }
}
