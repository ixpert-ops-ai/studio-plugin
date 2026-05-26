package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.io.File

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
            BUILTIN_TERMS.forEach { (k, v) -> merged.getOrPut(k) { mutableSetOf() }.addAll(v) }
            learned.forEach { (k, v) -> merged.getOrPut(k) { mutableSetOf() }.addAll(v) }

            try {
                val dictFile = File(graph.projectRoot, ".meta/dictionary.json")
                if (dictFile.exists()) {
                    val type = object : com.google.gson.reflect.TypeToken<Map<String, List<String>>>() {}.type
                    val externalDict: Map<String, List<String>> = com.google.gson.Gson().fromJson(dictFile.readText(Charsets.UTF_8), type)
                    externalDict.forEach { (k, v) ->
                        merged.getOrPut(k) { mutableSetOf() }.addAll(v)
                    }
                    com.intellij.openapi.diagnostic.Logger.getInstance(DomainDictionary::class.java).info("Loaded external dictionary from ${dictFile.absolutePath} with ${externalDict.size} entries.")
                }
            } catch (e: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(DomainDictionary::class.java).warn("Failed to load .meta/dictionary.json: ${e.message}")
            }

            return DomainDictionary(merged.mapValues { it.value.toSet() })
        }

        private val CAMEL_CASE_REGEX = Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

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
            return CAMEL_CASE_REGEX
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
            "등록" to setOf("register"),
            "수정" to setOf("update"),
            "삭제" to setOf("delete"),
            "조회" to setOf("search"),
            "목록" to setOf("list"),
            "상세" to setOf("detail"),
            "추가" to setOf("add"),
            "변경" to setOf("change"),
            "저장" to setOf("save"),
            "처리" to setOf("process"),
            "검색" to setOf("search"),
            "전송" to setOf("send"),
            "취소" to setOf("cancel"),
            "승인" to setOf("approve"),
            "반려" to setOf("reject"),
            // 인증/보안
            "로그인" to setOf("login"),
            "로그아웃" to setOf("logout"),
            "인증" to setOf("auth"),
            "권한" to setOf("permission"),
            // 파일 관련
            "다운로드" to setOf("download"),
            "업로드" to setOf("upload"),
            // 상태
            "오류" to setOf("error"),
            "실패" to setOf("fail"),
            "성공" to setOf("success"),
            "에러" to setOf("error"),
            // 계층 용어
            "서비스" to setOf("service"),
            "컨트롤러" to setOf("controller"),
            "레포지토리" to setOf("repository"),
            "저장소" to setOf("repository"),
            "엔티티" to setOf("entity"),
            "설정" to setOf("config"),
            "유틸" to setOf("util"),
            // 도메인 일반
            "회원" to setOf("member", "user"),
            "사용자" to setOf("user", "member"),
            "고객" to setOf("customer"),
            "카드" to setOf("card"),
            "계좌" to setOf("account"),
            "거래" to setOf("transaction"),
            "결제" to setOf("payment"),
            "주문" to setOf("order"),
            "상품" to setOf("product"),
            "메뉴" to setOf("menu"),
            "코드" to setOf("code"),
            "한도" to setOf("limit"),
            "정보" to setOf("info"),
            "관리" to setOf("manage"),
            "통계" to setOf("statistics"),
            "이력" to setOf("history"),
            "알림" to setOf("notification"),
            "배치" to setOf("batch")
        )
    }
}
