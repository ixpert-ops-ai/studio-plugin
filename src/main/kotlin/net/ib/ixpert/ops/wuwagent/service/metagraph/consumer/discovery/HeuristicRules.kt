package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

/**
 * 탐색·점수 단계 휴리스틱의 오탐을 줄이는 규칙(B-75 3).
 * - [FrontendKeywordRule]: 화면 확장(frontendRelevant) 판정. "백엔드/서버/API"가 있으면 화면 쪽 확장을 하지 않고, "~화면용"은 화면 키워드로 세지 않는다.
 * - [GenericTranslationTokens]: 범용 번역 토큰(info, data, manage 등). 시드 근거에서 빼고 이름 일치 점수는 약한 일치(가중치 낮음)로만 쓴다.
 * - [NameWordMatch]: 리소스 파일명 일치를 부분 문자열이 아니라 단어 경계로 본다(manage가 manager를 맞히지 않게).
 */
object FrontendKeywordRule {
    private val BACKEND = Regex("백엔드|서버|\\bapi\\b|backend", RegexOption.IGNORE_CASE)

    /** "화면용", "화면에서 쓰는/사용하는"은 화면을 쓰는 곳을 가리킬 뿐 화면 코드를 고치라는 뜻이 아니다. */
    private val SCREEN_AS_AUDIENCE = Regex("화면\\s*(용|에서\\s*(쓰|사용|쓸|쓴|사용하는|사용할))")

    private val FRONT = listOf("어드민", "화면", "UI", "프론트", "vue", "jsp", "페이지", "웹", "frontend", "admin")

    /** 화면을 직접 고치라는 뜻이 분명한 표현(B-77): 화면에/화면도 + 추가·표시·반영 같은 동사, 화면 기술 이름, 버튼·레이아웃. "화면에서 ~"(화면이 출처인 말)과 "화면의 백엔드"는 아니다. */
    private val SCREEN_EDIT = Regex(
        "(화면|페이지|팝업|어드민|admin|UI)\\s*(에도|에|도|을|를)(?!서)[^.\\n]{0,20}(추가|표시|노출|반영|변경|수정|개선|출력)|\\bjsp\\b|\\bvue\\b|\\.js\\b|\\bhtml\\b|\\bcss\\b|프론트|frontend|버튼|레이아웃",
        RegexOption.IGNORE_CASE
    )

    /** "백엔드/서버"를 직접 말했는가(API는 화면이 부르는 경우도 많아 단독으로는 세지 않는다). */
    private val BACKEND_EXPLICIT = Regex("백엔드|서버|backend", RegexOption.IGNORE_CASE)

    fun mentionsBackend(srText: String): Boolean = BACKEND.containsMatchIn(srText)

    fun hasScreenEditIntent(srText: String): Boolean = SCREEN_EDIT.containsMatchIn(SCREEN_AS_AUDIENCE.replace(srText, " "))

    /**
     * 백엔드/서버만 말하고 화면 수정 의도가 없는가. 이때만 화면 쪽 확장(frontendRelevant)을 끈다(B-77: 전에는 API 한 단어만 있어도 껐고,
     * D1 같은 화면 파일이 필요한 요구사항에 API 문구가 붙으면 화면 파일 4개를 잃었다).
     */
    fun isBackendOnly(srText: String): Boolean = BACKEND_EXPLICIT.containsMatchIn(srText) && !hasScreenEditIntent(srText)

    fun isExplicitFrontend(srText: String): Boolean {
        if (mentionsBackend(srText) && !hasScreenEditIntent(srText)) return false
        val text = SCREEN_AS_AUDIENCE.replace(srText, " ")
        return FRONT.any { text.contains(it, ignoreCase = true) }
    }
}

object GenericTranslationTokens {
    private val TOKENS = setOf(
        "controller", "service", "impl", "dao", "mapper", "dto", "vo", "response", "request", "entity", "repository", "base", "util", "helper",
        "common", "config", "job", "batch", "list", "get", "set", "save", "update", "delete", "insert", "select", "find", "search", "read",
        "view", "page", "screen", "manage", "management", "mgr", "mgmt", "admin", "main", "detail", "info", "data", "handle", "process",
        "execute", "deal", "query", "information", "edit", "modify", "change", "add", "remove", "show", "display"
    )

    fun isGeneric(token: String): Boolean = token.lowercase() in TOKENS

    /** 도메인 이름이 아니라 동작을 가리키는 번역(추가 → add, 조회 → search). 이름 일치(약한 일치 포함)에 쓰지 않는다(B-78). */
    private val ACTION_ONLY = setOf("add", "search")

    fun isActionOnly(token: String): Boolean = token.lowercase() in ACTION_ONLY
}

object NameWordMatch {
    private val SPLIT = Regex("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])")

    /** 파일명을 영문·숫자 단어(카멜 경계 포함)로 쪼개 token과 같은 단어가 있으면 참. 확장자도 단어로 본다. */
    fun containsWord(fileName: String, token: String): Boolean {
        val t = token.lowercase()
        if (t.isBlank()) return false
        return fileName.split(SPLIT).any { it.isNotEmpty() && it.lowercase() == t }
    }
}
