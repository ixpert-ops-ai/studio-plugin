package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.RetentionAudit
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0TransitionContract
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.UnresolvedItem
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.UnresolvedKind
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 커밋 2c: Stage 3 입력(본문 + 미확정 섹션 + 빠진 식별자 섹션)과 `analyze()` 첫머리 fail-fast 검증 (B-36).
 *
 * - [순수 함수] 테스트는 `AnalyzeSections.buildStage3Base`만 호출한다.
 * - [파이프라인 기록] 테스트는 `analyze()`를 실제로 호출하고 Stage 3 검증기에 간 user 내용을 기록해,
 *   `## 요구사항\n`부터 `\n\n## 후보 파일 목록` 직전까지를 잘라 기대 문자열 전체와 `assertEquals`로 비교한다(출현 횟수는 보조).
 * - 손으로 만든 입력(`fixtureInput`)의 `effectiveRequirement`는 2b 조립식
 *   (`missing`이 비면 `rawRefined`, 아니면 `"$rawRefined\n" + missing.joinToString("") { "\n$it" }`)으로 계산한다.
 * - 인텐트 픽스처 값은 하네스 프로브 출력(`[2c-probe: …]`)에서 가져왔다.
 */
class AnalyzeSectionsTest {

    // ───────────────────────── 픽스처 ─────────────────────────

    private val SURVEY_LLMON_RAW = "설문 발송 채널에 브랜드메시지 추가 및 Bizgo 연동 API 신규 개발"
    private val APC_DEM_RAW = "앱카드 및 모니모페이 최근이력 1건 조회 기능 개발"
    private val APC_TRANSIT_RAW = "교통카드 발급업체 변경 후, 이전 모바일교통카드 이용회원 체크 및 재발급 안내를 위한 신규서비스 개발 건. " +
        "교통카드 구 발급정보 조회 신규서비스 개발 (SAPACMM0802S01 기존서비스 참고). " +
        "APCMMTrcdIsInfSVO 수정, APCMMTrcdIsSVC.java 신규서비스 추가, " +
        "aCMBTBAPC024DEM.selTrcdIsInf 참조하여 앱카드회원ID 및 모니모페이회원ID 최근이력 1건 조회"
    private val SURVEY_LLMOFF_RAW = "설문 발송 채널에 브랜드메시지 추가"

    private fun u(id: String, kind: UnresolvedKind) =
        UnresolvedItem(id, kind, null, HintSource.USER_UTTERED, 1, null, null)

    private val surveyLlmOnUnresolved = listOf(
        u("BizgoApiService", UnresolvedKind.NEW_CREATION),
        u("BrandMessageTemplateBatchRunner", UnresolvedKind.NEW_CREATION),
        u("BrandMessageTemplateBatchJob", UnresolvedKind.NEW_CREATION),
        u("BrandMessageTemplateBatchRepository", UnresolvedKind.NEW_CREATION),
        u("BrandMessageTmplDto", UnresolvedKind.NEW_CREATION)
    )
    private val surveyLlmOnMissing = listOf(
        "BizgoApiService", "BrandMessageTemplateBatchJob", "BrandMessageTemplateBatchRepository",
        "BrandMessageTemplateBatchRunner", "BrandMessageTmplDto"
    )
    private val apcTransitUnresolved = listOf(
        u("APCMMTrcdIsSVC.java", UnresolvedKind.EXISTING_REF),
        u("APCMMTrcdIsInfSVO", UnresolvedKind.EXISTING_REF),
        u("aCMBTBAPC024DEM", UnresolvedKind.EXISTING_REF),
        u("SAPACMM0802S01", UnresolvedKind.NEW_CREATION)
    )

    /** 인텐트 픽스처: 정제문은 태그 없는 [raw]이고 감사 기록의 rawRefinedRequirement도 [raw]. */
    private fun intentFixture(
        original: String,
        raw: String,
        missing: List<String> = emptyList(),
        unresolved: List<UnresolvedItem> = emptyList(),
        audit: Boolean = true
    ): ClarifyIntent {
        val tagged = if (missing.isEmpty()) raw else "$raw (명시된 식별자: ${missing.joinToString(", ")})"
        return ClarifyIntent(
            originalRequirement = original,
            refinedRequirement = tagged,
            graphHash = "dummyHash",
            retentionAudit = if (audit) RetentionAudit(rawRefinedRequirement = raw, missingBeforeFix = missing) else null,
            unresolvedItems = unresolved
        )
    }

    private val surveyLlmOnIntent get() = intentFixture(SURVEY_LLMOFF_RAW, SURVEY_LLMON_RAW, surveyLlmOnMissing, surveyLlmOnUnresolved)
    private val apcDemLlmOnIntent get() = intentFixture("앱카드 최근이력", APC_DEM_RAW, listOf("aCMBTBAPC024DEM", "selTrcdIsInf"))
    private val apcTransitLlmOffIntent get() = intentFixture(APC_TRANSIT_RAW, APC_TRANSIT_RAW, emptyList(), apcTransitUnresolved)
    private val surveyLlmOffIntent get() = intentFixture(SURVEY_LLMOFF_RAW, SURVEY_LLMOFF_RAW)

    private val surveyLlmOnExpected = SURVEY_LLMON_RAW +
        "\n\n[미확정(확정 아님) 식별자]" +
        "\n- BizgoApiService (NEW_CREATION)" +
        "\n- BrandMessageTemplateBatchRunner (NEW_CREATION)" +
        "\n- BrandMessageTemplateBatchJob (NEW_CREATION)" +
        "\n- BrandMessageTemplateBatchRepository (NEW_CREATION)" +
        "\n- BrandMessageTmplDto (NEW_CREATION)"
    private val apcDemLlmOnExpected = APC_DEM_RAW +
        "\n\n[원문에 있었으나 정제문에서 빠진 식별자]" +
        "\n- aCMBTBAPC024DEM" +
        "\n- selTrcdIsInf"
    private val apcTransitLlmOffExpected = APC_TRANSIT_RAW +
        "\n\n[미확정(확정 아님) 식별자]" +
        "\n- APCMMTrcdIsSVC.java (EXISTING_REF)" +
        "\n- APCMMTrcdIsInfSVO (EXISTING_REF)" +
        "\n- aCMBTBAPC024DEM (EXISTING_REF)" +
        "\n- SAPACMM0802S01 (NEW_CREATION)"
    private val surveyLlmOffExpected = SURVEY_LLMOFF_RAW

    /** 손으로 만든 입력. rawRefined는 항상 명시하고, effectiveRequirement는 2b 조립식으로 계산한다. */
    private fun fixtureInput(
        rawRefined: String,
        missing: List<String> = emptyList(),
        unresolved: List<UnresolvedItem> = emptyList()
    ): ResolvedAnalyzeInput {
        val effective = if (missing.isEmpty()) rawRefined else "$rawRefined\n" + missing.joinToString("") { "\n$it" }
        if (missing.isNotEmpty()) {
            assertNotEquals("missing이 있는 픽스처는 effectiveRequirement가 rawRefined와 달라야 함(변이 감지 전제)", rawRefined, effective)
        }
        return ResolvedAnalyzeInput(
            effectiveRequirement = effective,
            effectiveIntent = null,
            rawRefined = rawRefined,
            missingIdentifiers = missing,
            unresolvedItems = unresolved,
            excludedFiles = emptyList()
        )
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun assertBytesEqual(message: String, expected: String, actual: String) {
        assertEquals(message, expected, actual)
        assertArrayEquals(message, bytes(expected), bytes(actual))
    }

    private fun pure(input: ResolvedAnalyzeInput, secondaryReq: String = "", enriched: String? = null): String =
        AnalyzeSections.buildStage3Base(input, secondaryReq, enriched, fallbackBase = "<사용되면 안 되는 fallback>")

    // ───────────────────────── 파이프라인 기록 도구 ─────────────────────────

    /** Stage 3(검증기) 호출의 user 내용을 기록하는 strict mock. 표지 외 프롬프트는 즉시 실패. */
    private class RecordingLlm : LLMClient {
        val stage3UserContents = mutableListOf<String>()
        val unmatched = mutableListOf<String>()

        private fun route(systemPrompt: String, userPrompts: List<String>) {
            when {
                systemPrompt.contains("당신은 프로젝트 아키텍트입니다.") -> Unit
                systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.") -> stage3UserContents.addAll(userPrompts)
                systemPrompt.contains("당신은 시스템의 소스코드 및 메타그래프를 능동적으로 탐색하는 전문 AI 분석 에이전트입니다.") -> Unit
                else -> {
                    unmatched.add(systemPrompt)
                    error("unmatched prompt in RecordingLlm: [systemPrompt=$systemPrompt]")
                }
            }
        }

        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            route(systemPrompt, listOf(userCode))
            return OllamaChatResponse(model = "recording-llm", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)
        }

        override fun chatWithTools(
            systemPrompt: String,
            messages: List<ChatMessage>,
            maxTokens: Int?,
            tools: List<ToolDefinition>?,
            toolChoice: Any?,
            temperature: Double?
        ): ChatCompletionResponse? {
            route(systemPrompt, messages.mapNotNull { it.content })
            return null
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun file(path: String, className: String) = path to FileNode(
        path = path,
        packageName = path.substringBeforeLast('/').replace('/', '.'),
        className = className,
        fileType = SpringFileType.SERVICE,
        layer = ArchitectureLayer.SERVICE
    )

    /**
     * 기록 테스트용 작은 그래프. discovery는 `primaryReq`/`secondaryReq` 텍스트만 보므로, 각 픽스처의 effectiveRequirement에 나오는
     * 식별자와 같은 클래스명을 둬서 Stage 3까지 후보가 남게 한다(각 테스트가 Stage 3 호출 1회를 단언).
     */
    private fun smallGraph() = ProjectGraph(
        generatedAt = java.time.Instant.now().toString(),
        projectRoot = "/test/root",
        files = mapOf(
            file("com/example/BizgoApiService.java", "BizgoApiService"),
            file("com/example/TargetService.java", "TargetService"),
            file("com/example/APCMMTrcdIsInfSVO.java", "APCMMTrcdIsInfSVO"),
            file("com/example/APCMMTrcdIsSVC.java", "APCMMTrcdIsSVC"),
            file("com/example/ACMBTBAPC024DEM.java", "aCMBTBAPC024DEM"),
            file("com/example/SelTrcdIsInf.java", "selTrcdIsInf")
        ),
        relationships = emptyList(),
        statistics = GraphStatistics()
    )

    /** `## 요구사항\n` ~ `\n\n## 후보 파일 목록` 구간을 잘라 돌려준다. */
    private fun segmentOf(userContent: String): String {
        val marker = "## 요구사항\n"
        val start = userContent.indexOf(marker)
        assertTrue("user 내용에 '## 요구사항' 헤더가 있어야 함: $userContent", start >= 0)
        val from = start + marker.length
        val end = userContent.indexOf("\n\n## 후보 파일 목록", from)
        assertTrue("user 내용에 '## 후보 파일 목록' 헤더가 있어야 함: $userContent", end >= 0)
        return userContent.substring(from, end)
    }

    /** analyze()를 실행하고 Stage 3 user 내용에서 잘라 낸 구간을 돌려준다. Stage 3 호출은 정확히 1회여야 한다. */
    private fun runAndRecord(
        primaryReq: String,
        clarifyIntent: ClarifyIntent?,
        analyzeInput: ResolvedAnalyzeInput?,
        secondaryReq: String = "",
        stage0Contract: Stage0TransitionContract? = null
    ): String = kotlinx.coroutines.runBlocking {
        val llm = RecordingLlm()
        RequirementAnalysisPipeline(llm).analyze(
            primaryReq = primaryReq,
            secondaryReq = secondaryReq,
            projectGraph = smallGraph(),
            stage0Contract = stage0Contract,
            clarifyIntent = clarifyIntent,
            analyzeInput = analyzeInput
        )
        assertTrue("strict mock에서 표지와 일치하지 않는 프롬프트가 없어야 함: ${llm.unmatched}", llm.unmatched.isEmpty())
        assertEquals("Stage 3 검증기 호출이 정확히 1회 기록되어야 함", 1, llm.stage3UserContents.size)
        segmentOf(llm.stage3UserContents.single())
    }

    private fun runResolved(intent: ClarifyIntent, rawInput: String = intent.refinedRequirement): String {
        val resolved = AnalyzeInputResolver.resolve(rawInput = rawInput, inMemoryIntent = intent)
        return runAndRecord(resolved.effectiveRequirement, resolved.effectiveIntent, resolved)
    }

    private fun occurrences(text: String, token: String): Int =
        Regex(Regex.escape(token), RegexOption.IGNORE_CASE).findAll(text).count()

    // ───────────────────────── 1. 전체 문자열 ─────────────────────────

    // 1P [순수 함수] 인텐트 픽스처 → resolve → buildStage3Base 전체 문자열
    @Test
    fun resolvedIntentFixturesProduceExpectedStage3BaseStrings() {
        val cases = listOf(
            "survey_admin LlmOn" to (surveyLlmOnIntent to surveyLlmOnExpected),
            "APC DEM Isolated LlmOn" to (apcDemLlmOnIntent to apcDemLlmOnExpected),
            "APC Transit Card LlmOff" to (apcTransitLlmOffIntent to apcTransitLlmOffExpected),
            "survey_admin LlmOff" to (surveyLlmOffIntent to surveyLlmOffExpected)
        )
        for ((name, pair) in cases) {
            val (intent, expected) = pair
            val resolved = AnalyzeInputResolver.resolve(rawInput = intent.refinedRequirement, inMemoryIntent = intent)
            assertBytesEqual(name, expected, pure(resolved))
        }
    }

    // 1R [파이프라인 기록] 같은 픽스처(survey_admin LlmOff 제외)가 analyze()를 거쳐 Stage 3 user 내용 구간에 그대로 들어간다
    @Test
    fun recordedStage3SegmentEqualsExpectedStringForResolvedFixtures() {
        assertBytesEqual("survey_admin LlmOn", surveyLlmOnExpected, runResolved(surveyLlmOnIntent))
        assertBytesEqual("APC DEM Isolated LlmOn", apcDemLlmOnExpected, runResolved(apcDemLlmOnIntent))
        assertBytesEqual("APC Transit Card LlmOff", apcTransitLlmOffExpected, runResolved(apcTransitLlmOffIntent))
        // survey_admin LlmOff는 한글뿐이라 이 그래프에서 후보가 없어 Stage 3이 호출되지 않는다. 섹션이 빈 경우의 기록 비교는 5R이 맡는다.
    }

    // ───────────────────────── 2. 대소문자 겹침 ─────────────────────────

    private val caseOverlapInput get() = fixtureInput(
        rawRefined = "본문 RAW",
        missing = listOf("aCMBTBAPC024DEM", "selTrcdIsInf"),
        unresolved = listOf(u("ACMBTBAPC024DEM", UnresolvedKind.EXISTING_REF))
    )
    private val caseOverlapExpected = "본문 RAW" +
        "\n\n[미확정(확정 아님) 식별자]" +
        "\n- ACMBTBAPC024DEM (EXISTING_REF)" +
        "\n\n[원문에 있었으나 정제문에서 빠진 식별자]" +
        "\n- selTrcdIsInf"

    // 2P [순수 함수] 두 섹션 합쳐 한 번, 표기는 unresolvedItems 철자
    @Test
    fun caseOverlapAppearsOnceAcrossSectionsWithUnresolvedSpelling() {
        assertBytesEqual("2P", caseOverlapExpected, pure(caseOverlapInput))
    }

    // 2R [파이프라인 기록] 같은 입력의 기록 구간 전체 비교 + 출현 횟수(보조)
    @Test
    fun recordedStage3SegmentKeepsCaseOverlapOnce() {
        val input = caseOverlapInput
        val segment = runAndRecord(input.effectiveRequirement, null, input)
        assertBytesEqual("2R", caseOverlapExpected, segment)
        assertEquals("aCMBTBAPC024DEM(대소문자 무시) 출현 횟수", 1, occurrences(segment, "aCMBTBAPC024DEM"))
    }

    // ───────────────────────── 3. 빠진 섹션 안 대소문자 중복 ─────────────────────────

    // 3 [순수 함수] 빠진 식별자 섹션 안에서 대소문자만 다른 중복이면 먼저 나온 철자만 남는다
    @Test
    fun missingSectionKeepsFirstSpellingOfCaseInsensitiveDuplicates() {
        assertBytesEqual(
            "AbcDto 먼저",
            "본문\n\n[원문에 있었으나 정제문에서 빠진 식별자]\n- AbcDto\n- Zeta",
            pure(fixtureInput(rawRefined = "본문", missing = listOf("AbcDto", "abcDto", "Zeta")))
        )
        assertBytesEqual(
            "abcDto 먼저",
            "본문\n\n[원문에 있었으나 정제문에서 빠진 식별자]\n- abcDto\n- Zeta",
            pure(fixtureInput(rawRefined = "본문", missing = listOf("abcDto", "AbcDto", "Zeta")))
        )
    }

    // ───────────────────────── 4. 본문에 있어도 섹션에 나옴 ─────────────────────────

    // 4 [순수 함수] APC Transit LlmOff: 식별자 4개가 모두 본문에 있어도 미확정 섹션에 순서대로 나온다
    @Test
    fun unresolvedIdentifiersAlreadyInBodyStillAppearInSection() {
        val resolved = AnalyzeInputResolver.resolve(rawInput = APC_TRANSIT_RAW, inMemoryIntent = apcTransitLlmOffIntent)
        apcTransitUnresolved.forEach {
            assertTrue("전제: ${it.identifier}가 본문에 이미 있어야 함", resolved.rawRefined.contains(it.identifier))
        }
        assertBytesEqual("4", apcTransitLlmOffExpected, pure(resolved))
    }

    // ───────────────────────── 5. 섹션이 비면 2c 전과 바이트 동일 ─────────────────────────

    private val noSectionCases: List<Pair<String, ClarifyIntent?>> get() = listOf(
        // A. 정제문 빈 값(감사 기록 있음, missing 없음): originalRequirement로 복원
        "A 정제문 빈 값" to intentFixture(original = "TargetService 원본 요구사항 본문", raw = "", missing = emptyList()),
        // B. retentionAudit == null, unresolved 없음
        "B retentionAudit == null" to intentFixture(original = "원본", raw = "TargetService 정제문 (명시된 식별자: Old)", audit = false),
        // C. 단독 /analyze(인텐트 null)
        "C 단독 /analyze" to null
    )

    // 5P [순수 함수] 세 경우: 섹션이 비면 buildStage3Base == resolved.effectiveRequirement
    @Test
    fun emptySectionsEqualEffectiveRequirementInThreeCases() {
        for ((name, intent) in noSectionCases) {
            val resolved = AnalyzeInputResolver.resolve(rawInput = "TargetService 단독 입력 본문", inMemoryIntent = intent)
            assertBytesEqual(name, resolved.effectiveRequirement, pure(resolved))
        }
    }

    // 5R [파이프라인 기록] 세 경우: analyzeInput=null 경로와 analyzeInput=resolved 경로의 기록 구간이 바이트 동일
    @Test
    fun recordedSegmentIsByteIdenticalWithAndWithoutAnalyzeInputInThreeCases() {
        for ((name, intent) in noSectionCases) {
            val resolved = AnalyzeInputResolver.resolve(rawInput = "TargetService 단독 입력 본문", inMemoryIntent = intent)
            val before = runAndRecord(resolved.effectiveRequirement, resolved.effectiveIntent, analyzeInput = null)
            val after = runAndRecord(resolved.effectiveRequirement, resolved.effectiveIntent, analyzeInput = resolved)
            assertBytesEqual("$name: analyzeInput 유무", before, after)
            assertBytesEqual("$name: 기대 문자열", resolved.effectiveRequirement, after)
        }
    }

    // ───────────────────────── 6. 중복 방지 ─────────────────────────

    // 6 [파이프라인 기록] survey_admin LlmOn: 식별자 줄이 본문에 들어가지 않고 섹션에만 한 번 나온다
    @Test
    fun recordedSegmentDoesNotDuplicateIdentifiersFromEffectiveRequirement() {
        val segment = runResolved(surveyLlmOnIntent)
        assertBytesEqual("6", surveyLlmOnExpected, segment)
        assertEquals("BizgoApiService 출현 횟수(보조)", 1, occurrences(segment, "BizgoApiService"))
        assertTrue("본문은 rawRefined로 시작", segment.startsWith("$SURVEY_LLMON_RAW\n\n[미확정(확정 아님) 식별자]\n"))
    }

    // ───────────────────────── 7. fail-fast ─────────────────────────

    private fun expectIllegalArgument(message: String, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("IllegalArgumentException이 기대됨: $message")
    }

    private fun analyzeOnly(primaryReq: String, clarifyIntent: ClarifyIntent?, analyzeInput: ResolvedAnalyzeInput?) =
        kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(RecordingLlm()).analyze(
                primaryReq = primaryReq,
                projectGraph = smallGraph(),
                clarifyIntent = clarifyIntent,
                analyzeInput = analyzeInput
            )
        }

    // 7i~7v [파이프라인] (LLM 호출 전 예외, 기록 불필요). 메서드를 나눠 변이마다 실패 부분을 구분한다.

    private fun failFastFixture(): Pair<ClarifyIntent, ResolvedAnalyzeInput> {
        val intent = surveyLlmOnIntent
        return intent to AnalyzeInputResolver.resolve(rawInput = intent.refinedRequirement, inMemoryIntent = intent)
    }

    // 7i primaryReq != analyzeInput.effectiveRequirement → 첫 번째 require
    @Test
    fun failFast7i_primaryReqDiffersFromEffectiveRequirement() {
        val (_, resolved) = failFastFixture()
        expectIllegalArgument("(i) primaryReq 불일치") {
            analyzeOnly("다른 primaryReq", resolved.effectiveIntent, resolved)
        }
    }

    // 7ii 같은 값이지만 다른 객체(copy) → 두 번째 require (===)
    @Test
    fun failFast7ii_equalButDifferentIntentObject() {
        val (intent, resolved) = failFastFixture()
        expectIllegalArgument("(ii) 인텐트 copy()") {
            analyzeOnly(resolved.effectiveRequirement, intent.copy(), resolved)
        }
    }

    // 7iii analyzeInput.effectiveIntent는 있는데 clarifyIntent == null → 두 번째 require
    @Test
    fun failFast7iii_clarifyIntentNullWhileInputHasIntent() {
        val (_, resolved) = failFastFixture()
        expectIllegalArgument("(iii) clarifyIntent null") {
            analyzeOnly(resolved.effectiveRequirement, null, resolved)
        }
    }

    // 7iv 둘 다 일치하면 정상 진행 (항상 통과해야 함)
    @Test
    fun failFast7iv_matchingInputProceeds() {
        val (_, resolved) = failFastFixture()
        assertNotNull(analyzeOnly(resolved.effectiveRequirement, resolved.effectiveIntent, resolved))
    }

    // 7v analyzeInput.effectiveIntent == null인데 clarifyIntent != null → 두 번째 require
    @Test
    fun failFast7v_inputHasNoIntentButClarifyIntentGiven() {
        val (intent, _) = failFastFixture()
        val standalone = AnalyzeInputResolver.resolve(rawInput = "단독 입력", inMemoryIntent = null)
        expectIllegalArgument("(v) analyzeInput.effectiveIntent null, clarifyIntent 있음") {
            analyzeOnly(standalone.effectiveRequirement, intent, standalone)
        }
    }

    // ───────────────────────── 8. retentionAudit == null + unresolvedItems ─────────────────────────

    // 8 [순수 함수] 감사 기록이 없어도 unresolvedItems가 있으면 미확정 섹션만 생긴다(의도된 동작, B-36)
    @Test
    fun unresolvedItemsWithoutRetentionAuditProduceOnlyUnresolvedSection() {
        val refined = "정제문 (명시된 식별자: OldTag)"
        val intent = intentFixture(
            original = "원본", raw = refined, unresolved = listOf(u("ZetaService", UnresolvedKind.NEW_CREATION)), audit = false
        )
        val resolved = AnalyzeInputResolver.resolve(rawInput = "무시", inMemoryIntent = intent)
        assertEquals(refined, resolved.rawRefined)
        assertTrue("전제: ZetaService가 본문에 없어야 함", !resolved.rawRefined.contains("ZetaService"))
        assertBytesEqual("8", "$refined\n\n[미확정(확정 아님) 식별자]\n- ZetaService (NEW_CREATION)", pure(resolved))
    }

    // ───────────────────────── 9. 본문 결합(secondaryReq, enrichedRequirementText) ─────────────────────────

    // 9R [파이프라인 기록] APC DEM LlmOn 픽스처: secondaryReq가 있으면 "\n" 결합, 공백뿐이면 무시
    @Test
    fun recordedSegmentCombinesSecondaryReqWithRawRefined() {
        val intent = apcDemLlmOnIntent
        val resolved = AnalyzeInputResolver.resolve(rawInput = intent.refinedRequirement, inMemoryIntent = intent)
        val sections = apcDemLlmOnExpected.removePrefix(APC_DEM_RAW)

        val withSecondary = runAndRecord(resolved.effectiveRequirement, resolved.effectiveIntent, resolved, secondaryReq = "보조 요구사항")
        assertBytesEqual("secondaryReq 있음", APC_DEM_RAW + "\n보조 요구사항" + sections, withSecondary)

        val blankSecondary = runAndRecord(resolved.effectiveRequirement, resolved.effectiveIntent, resolved, secondaryReq = "   ")
        assertBytesEqual("secondaryReq 공백", APC_DEM_RAW + sections, blankSecondary)
    }

    // 9S [순수 함수] enrichedRequirementText가 있으면(빈 문자열 제외 규칙 없음, B-24 7) 그 값이 본문이고 섹션이 뒤따른다
    @Test
    fun enrichedRequirementTextTakesPrecedenceOverRawRefinedAndSecondaryReq() {
        val resolved = AnalyzeInputResolver.resolve(rawInput = APC_DEM_RAW, inMemoryIntent = apcDemLlmOnIntent)
        val sections = apcDemLlmOnExpected.removePrefix(APC_DEM_RAW)
        assertBytesEqual("enriched 우선", "보강 본문$sections", pure(resolved, secondaryReq = "보조 요구사항", enriched = "보강 본문"))
        assertBytesEqual("input == null이면 fallback", "FALLBACK", AnalyzeSections.buildStage3Base(null, "x", "y", "FALLBACK"))
    }
}
