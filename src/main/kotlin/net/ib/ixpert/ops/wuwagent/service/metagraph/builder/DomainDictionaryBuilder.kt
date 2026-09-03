package net.ib.ixpert.ops.wuwagent.service.metagraph.builder

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable
import java.io.File

/**
 * 그래프 기반 자동 도메인 사전 빌더 (DomainDictionaryBuilder).
 *
 * 4대 설계 원칙:
 * 1. 빌드 시점 1회 동결 (Freeze-at-Build-time) -> .meta/dictionary.json 저장
 * 2. 소스 1~3 (프로젝트 내부 증거) 최우선 신뢰: localName, koreanComments, apiEndpoints, demMethods
 * 3. 정적 시드 용어집 (Seed Glossary) 안전망 병합
 * 4. 도메인 적격성(Domain-worthiness) 필터 + LLM SKIP 위임 (노이즈 원천 차단)
 * 5. LLM-Free 코어 아키텍처: build()는 LLM 없이도 100% 자립 완결 동작, enrichWithLlm()은 선택적 향상.
 */
class DomainDictionaryBuilder {

    private val logger = Logger.getInstance(DomainDictionaryBuilder::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    companion object {
        // 구조적 계층/아키텍처 불용어
        val STRUCTURAL_STOPWORDS = setOf(
            "response", "request", "service", "impl", "vo", "dto", "controller", "repository",
            "mapper", "dao", "biz", "svo", "bvo", "dvo", "dem", "dqm", "util", "helper",
            "config", "entity", "model", "api", "app", "bo", "src", "main", "java", "com",
            "samsungcardmall", "sc", "chn", "aps", "apc", "net", "infobank", "iss", "membermarket",
            "get", "set", "save", "update", "delete", "select", "insert", "count", "list", "req", "res",
            "json", "body", "data", "info", "view", "page", "param", "result", "type", "flag"
        )

        // 한국어 일반 행위/구조 불용어
        val KOREAN_STOPWORDS = setOf(
            "검사", "등록", "유효성", "조회", "수정", "삭제", "목록", "상세", "추가", "변경",
            "저장", "처리", "검색", "전송", "취소", "오류", "실패", "성공", "에러", "여부",
            "결과", "코드", "상태", "일시", "시간", "번호", "내용", "설정", "권한", "화면", "이력",
            "관련", "요청", "응답", "서비스", "컨트롤러", "매퍼", "다오", "엔티티"
        )

        // 정적 시드 용어집 (Seed Glossary - 원칙 3)
        val SEED_GLOSSARY = mapOf(
            "care" to "케어",
            "member" to "회원",
            "user" to "사용자",
            "customer" to "고객",
            "product" to "상품",
            "order" to "주문",
            "payment" to "결제",
            "card" to "카드",
            "point" to "포인트",
            "coupon" to "쿠폰",
            "survey" to "설문",
            "traffic" to "교통",
            "transport" to "교통카드"
        )

        /**
         * CamelCase 및 SnakeCase 문자열을 도메인 토큰으로 분해합니다.
         */
        fun tokenizeCamelCase(str: String?): List<String> {
            if (str.isNullOrBlank()) return emptyList()
            if (str.contains('_')) {
                return str.split('_').filter { it.length >= 2 }.map { it.lowercase() }
            }
            return str.replace(Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])"), " ")
                .lowercase()
                .split(Regex("[^a-z0-9]"))
                .filter { it.length >= 2 }
        }
    }

    /**
     * 프로젝트 그래프로부터 내부 증거(소스 1~3)와 시드 용어집을 결합하여 사전을 생성합니다.
     * LLM 없이 100% 결정적(Deterministic)으로 동작합니다.
     *
     * @param graph 프로젝트 메타그래프
     * @param saveToFile .meta/dictionary.json 저장 여부
     * @return 한글 표제어 -> 매핑된 영문 토큰 목록
     */
    fun build(graph: ProjectGraphQueryable, saveToFile: Boolean = false): Map<String, List<String>> {
        logger.info("Building domain dictionary from graph internal evidence (LLM-free core)...")

        val files = graph.files
        val internalEvidence = mutableMapOf<String, MutableMap<String, Int>>()

        // 1. 소스 1~3: 내부 증거 수집
        for ((_, node) in files) {
            val cTokens = tokenizeCamelCase(node.className).filter { it !in STRUCTURAL_STOPWORDS }

            // (1) koreanComments
            node.koreanComments?.forEach { comment ->
                val kWords = comment.split(Regex("[^가-힣]")).filter { it.length >= 2 && it !in KOREAN_STOPWORDS }
                cTokens.forEach { t ->
                    val kMap = internalEvidence.getOrPut(t) { mutableMapOf() }
                    kWords.forEach { kw -> kMap[kw] = (kMap[kw] ?: 0) + 1 }
                }
            }

            // (2) localName (클래스 한글명) - 높은 신뢰도 (가중치 5배)
            node.localName?.takeIf { it.isNotBlank() }?.let { lName ->
                val kWords = lName.split(Regex("[^가-힣]")).filter { it.length >= 2 && it !in KOREAN_STOPWORDS }
                cTokens.forEach { t ->
                    val kMap = internalEvidence.getOrPut(t) { mutableMapOf() }
                    kWords.forEach { kw -> kMap[kw] = (kMap[kw] ?: 0) + 5 }
                }
            }

            // (3) demMethods (Anyframe/MyBatis 한글 설명) - 높은 신뢰도 (가중치 5배)
            node.demMethods?.forEach { dm ->
                val mTokens = tokenizeCamelCase(dm.methodName).filter { it !in STRUCTURAL_STOPWORDS }
                dm.localName?.takeIf { it.isNotBlank() }?.let { lName ->
                    val kWords = lName.split(Regex("[^가-힣]")).filter { it.length >= 2 && it !in KOREAN_STOPWORDS }
                    mTokens.forEach { t ->
                        val kMap = internalEvidence.getOrPut(t) { mutableMapOf() }
                        kWords.forEach { kw -> kMap[kw] = (kMap[kw] ?: 0) + 5 }
                    }
                }
            }

            // (4) serviceEndpoints (Anyframe 서비스 한글 설명) - 높은 신뢰도 (가중치 5배)
            node.serviceEndpoints?.forEach { se ->
                val mTokens = (tokenizeCamelCase(se.methodName) + tokenizeCamelCase(se.serviceId)).filter { it !in STRUCTURAL_STOPWORDS }
                se.localName?.takeIf { it.isNotBlank() }?.let { lName ->
                    val kWords = lName.split(Regex("[^가-힣]")).filter { it.length >= 2 && it !in KOREAN_STOPWORDS }
                    mTokens.forEach { t ->
                        val kMap = internalEvidence.getOrPut(t) { mutableMapOf() }
                        kWords.forEach { kw -> kMap[kw] = (kMap[kw] ?: 0) + 5 }
                    }
                }
            }
        }

        // 2. 결과 사전 조립: 한글 표제어 -> 영문 토큰 목록
        val finalDict = mutableMapOf<String, MutableSet<String>>()

        // (A) 소스 1~3 내부 증거 기반 등록
        for ((engToken, kMap) in internalEvidence) {
            val topK = kMap.entries.sortedByDescending { it.value }.take(2)
            topK.forEach { (kWord, count) ->
                if (count >= 1 && kWord !in KOREAN_STOPWORDS) {
                    finalDict.getOrPut(kWord) { mutableSetOf() }.add(engToken)
                }
            }
        }

        // (B) 정적 시드 용어집 (Seed Glossary - 원칙 3) 병합
        for ((engToken, kWord) in SEED_GLOSSARY) {
            finalDict.getOrPut(kWord) { mutableSetOf() }.add(engToken)
        }

        val resultMap = finalDict.mapValues { it.value.toList().sorted() }

        // 3. .meta/dictionary.json 영속화 (옵션)
        if (saveToFile && graph.projectRoot != null) {
            saveDictionaryFile(graph.projectRoot!!, resultMap)
        }

        return resultMap
    }

    /**
     * 원칙 4: 고아(Orphan) 토큰을 선별하여 LLM 배치 번역으로 사전을 확장합니다 (선택적 향상 계층).
     */
    fun enrichWithLlm(
        baseDict: Map<String, List<String>>,
        graph: ProjectGraphQueryable,
        llmClient: LLMClient?,
        saveToFile: Boolean = false
    ): Map<String, List<String>> {
        if (llmClient == null) {
            logger.info("LlmClient is null, skipping orphan token LLM enrichment.")
            return baseDict
        }

        val files = graph.files
        val allEnglishTokens = mutableSetOf<String>()
        val tokenContext = mutableMapOf<String, MutableList<String>>()

        for ((_, node) in files) {
            val cTokens = tokenizeCamelCase(node.className).filter { it !in STRUCTURAL_STOPWORDS }
            cTokens.forEach { t ->
                allEnglishTokens.add(t)
                val ctx = tokenContext.getOrPut(t) { mutableListOf() }
                if (ctx.size < 3) ctx.add(node.className)
            }
            node.apiEndpoints?.forEach { ep ->
                val epTokens = ep.path.split(Regex("[^a-zA-Z0-9]")).flatMap { tokenizeCamelCase(it) }.filter { it !in STRUCTURAL_STOPWORDS }
                epTokens.forEach { t ->
                    allEnglishTokens.add(t)
                    val ctx = tokenContext.getOrPut(t) { mutableListOf() }
                    if (ctx.size < 3) ctx.add(ep.path)
                }
            }
        }

        val coveredEngTokens = baseDict.values.flatten().toSet()
        val orphanCandidates = allEnglishTokens.filter { it !in coveredEngTokens }

        val qualifiedOrphans = orphanCandidates.filter { token ->
            token.length >= 3 &&
            token !in STRUCTURAL_STOPWORDS &&
            !token.all { it.isDigit() }
        }

        logger.info("Qualified Orphans for LLM Batch: " + qualifiedOrphans.size + " (out of " + orphanCandidates.size + " candidates)")
        if (qualifiedOrphans.isEmpty()) return baseDict

        val enrichedDict = baseDict.mapValues { it.value.toMutableSet() }.toMutableMap()
        val batchSize = 50
        val chunks = qualifiedOrphans.chunked(batchSize)

        for ((idx, chunk) in chunks.withIndex()) {
            logger.info("Translating orphan batch " + (idx + 1) + "/" + chunks.size + " (" + chunk.size + " tokens)...")
            val promptBuilder = StringBuilder()
            promptBuilder.append("아래는 시스템 소스코드에서 추출된 영문 도메인 토큰과 사용 문맥입니다.\n")
            promptBuilder.append("각 영문 토큰에 대해 한국어 업무 도메인 명사(단어)를 매핑하세요.\n")
            promptBuilder.append("규칙:\n")
            promptBuilder.append("1. 업무 도메인 용어가 아니거나 기술적인 일반어, 불명확한 약어(예: asis, wrf, impl 등)인 경우 번역하지 말고 반드시 'SKIP'을 출력하세요.\n")
            promptBuilder.append("2. 반드시 '영문토큰: 한글도메인명사' 형식으로 한 줄에 하나씩만 출력하세요. (예: 'care: 케어', 'survey: 설문', 'dept: 부서')\n\n")

            chunk.forEach { token ->
                val ctx = tokenContext[token]?.take(2)?.joinToString(", ") ?: ""
                promptBuilder.append("- " + token + " (문맥: [" + ctx + "])\n")
            }

            val systemPrompt = "당신은 엔터프라이즈 시스템 도메인 용어 사전 구축 전문가입니다. 불필요한 설명 없이 오직 지정된 포맷으로만 답변하세요."
            try {
                val response = llmClient.chat(systemPrompt, promptBuilder.toString(), 1000, null)
                val content = response?.message?.content ?: ""
                content.lines().forEach { line ->
                    val trimmed = line.trim().removePrefix("-").trim()
                    if (trimmed.contains(":")) {
                        val parts = trimmed.split(":")
                        val eng = parts[0].trim().lowercase()
                        val kor = parts[1].trim()
                        if (kor.isNotBlank() && kor != "SKIP" && kor !in KOREAN_STOPWORDS && kor.length >= 2) {
                            enrichedDict.getOrPut(kor) { mutableSetOf() }.add(eng)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("LLM batch translation failed for chunk " + idx + ": " + e.message)
            }
        }

        val finalResult = enrichedDict.mapValues { it.value.toList().sorted() }
        if (saveToFile && graph.projectRoot != null) {
            saveDictionaryFile(graph.projectRoot!!, finalResult)
        }

        return finalResult
    }

    private fun saveDictionaryFile(projectRoot: String, dict: Map<String, List<String>>) {
        try {
            val metaDir = File(projectRoot, ".meta")
            if (!metaDir.exists()) metaDir.mkdirs()
            val dictFile = File(metaDir, "dictionary.json")
            dictFile.writeText(gson.toJson(dict), Charsets.UTF_8)
            logger.info("Saved domain dictionary (" + dict.size + " terms) to " + dictFile.absolutePath)
        } catch (e: Exception) {
            logger.error("Failed to save dictionary.json: " + e.message, e)
        }
    }
}
