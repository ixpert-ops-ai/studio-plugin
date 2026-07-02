package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable
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
     * 사전에 등록된 모든 키(한글/복합어 등) 목록을 반환합니다.
     */
    val keys: Set<String> get() = entries.keys

    /**
     * 정확 매칭 + 부분 매칭(한글 키가 입력에 포함되거나 입력이 키에 포함)을 모두 시도합니다.
     */
    fun translate(koreanNoun: String): Set<String> {
        // 1. 정확 매칭
        val exact = entries[koreanNoun]
        if (exact != null) return exact
        // 1글자 입력인 경우 부분 매칭을 시도하지 않고 정확 매칭 결과만 반환 (과매칭 방지)
        if (koreanNoun.length <= 1) return exact ?: emptySet()

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
        fun load(graph: ProjectGraphQueryable): DomainDictionary {
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

            if (graph.projectRoot != null) {
                try {
                    val dictFile = File(graph.projectRoot!!, ".meta/dictionary.json")
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
            // CRUD & Action 용어
            "등록" to setOf("register", "write", "regist", "create", "insert", "add", "new", "save"),
            "조회" to setOf("search", "list", "view", "detail", "read", "select", "get", "find", "lookup"),
            "수정" to setOf("update", "edit", "modify", "change", "alter", "patch"),
            "삭제" to setOf("delete", "remove", "del", "drop", "destroy", "erase"),
            "목록" to setOf("list", "index", "main", "all", "catalog"),
            "상세" to setOf("detail", "info", "view", "show", "single", "item"),
            "설정" to setOf("config", "setting", "preference", "option", "setup"),
            "승인" to setOf("approve", "confirm", "accept", "grant", "permit"),
            "반려" to setOf("reject", "deny", "refuse", "decline", "return"),
            "추가" to setOf("add", "append", "insert", "new", "create"),
            "변경" to setOf("change", "modify", "update", "edit"),
            "저장" to setOf("save", "store", "persist"),
            "처리" to setOf("process", "handle", "deal", "execute"),
            "검색" to setOf("search", "find", "query", "look"),
            "전송" to setOf("send", "transmit", "transfer", "submit"),
            "취소" to setOf("cancel", "abort", "revoke", "undo"),
            
            // 인증/보안
            "로그인" to setOf("login", "signin", "auth", "authenticate", "logon"),
            "로그아웃" to setOf("logout", "signout", "logoff"),
            "인증" to setOf("auth", "authentication", "cert", "verify"),
            "권한" to setOf("permission", "auth", "authorization", "role", "grant", "privilege"),
            
            // 파일 관련
            "다운로드" to setOf("download", "down"),
            "업로드" to setOf("upload", "up"),
            
            // 상태
            "오류" to setOf("error", "err", "exception", "fault"),
            "실패" to setOf("fail", "failure", "abort"),
            "성공" to setOf("success", "ok", "done", "complete"),
            "에러" to setOf("error", "err", "exception", "fault"),
            
            // 계층 용어
            "서비스" to setOf("service", "svc", "biz"),
            "컨트롤러" to setOf("controller", "ctrl", "api"),
            "레포지토리" to setOf("repository", "repo", "dao"),
            "저장소" to setOf("repository", "repo", "dao"),
            "엔티티" to setOf("entity", "domain", "model"),
            "유틸" to setOf("util", "helper", "common"),
            
            // 도메인 일반
            "회원" to setOf("member", "user", "account"),
            "사용자" to setOf("user", "member", "account", "client"),
            "고객" to setOf("customer", "client", "user"),
            "카드" to setOf("card"),
            "계좌" to setOf("account"),
            "거래" to setOf("transaction", "tx", "deal", "trade"),
            "결제" to setOf("payment", "pay", "checkout", "billing"),
            "주문" to setOf("order", "purchase"),
            "상품" to setOf("product", "item", "goods"),
            "메뉴" to setOf("menu", "nav", "navigation"),
            "코드" to setOf("code", "cd"),
            "한도" to setOf("limit", "max", "cap"),
            "정보" to setOf("info", "information", "data"),
            "관리" to setOf("manage", "management", "mgr", "admin"),
            
            // 화면/UI 용어
            "마이페이지" to setOf("mypage", "profile"),
            "대시보드" to setOf("dashboard", "dash", "main"),
            "홈" to setOf("home", "index"),
            "채팅" to setOf("chat", "message", "room"),
            "화면" to setOf("view", "page", "screen"),
            "통계" to setOf("statistics", "stat", "stats", "chart", "report"),
            "이력" to setOf("history", "hist", "log", "trace"),
            "알림" to setOf("notification", "noti", "alert", "notice", "message"),
            "배치" to setOf("batch", "job", "schedule", "cron")
        )
    }
}
