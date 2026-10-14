package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.RetentionAudit
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
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.MethodSignature
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.Relationship
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.RelationshipType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 커밋 2d: `missingBeforeFix`에서 사용자가 제외한 파일(`excludedFiles`)의 식별자를 차감하는 `AnalyzeInputResolver.resolve` 검증 (B-36).
 *
 * - D*P는 [순수 함수]: `AnalyzeInputResolver.resolve`(와 `AnalyzeSections.buildStage3Base`)만 호출한다.
 * - D*R, D0R은 [파이프라인 기록]: `analyze()`를 실제로 호출하고 Stage 3 user 내용 구간 또는 시드 프롬프트를 기록해 비교한다.
 *   호출 형식은 `primaryReq = resolved.effectiveRequirement, clarifyIntent = resolved.effectiveIntent, analyzeInput = resolved`.
 * - 일치 규칙: (1) 확장자 포함 파일명 일치(모든 확장자), (2) 제외 파일이 java/kt일 때만 기본 이름 일치. 둘 다 대소문자 무시.
 * - 제외 식별자가 본문에 남는 D10P를 뺀 모든 픽스처의 본문(rawRefined)에는 제외 파일의 기본 이름을 넣지 않는다(본문 변이 N10이 다른 테스트를 건드리지 않게).
 * - `effectiveRequirement` 기대값은 2b 조립식(`missing`이 비면 `rawRefined`, 아니면 `"$rawRefined\n" + missing.joinToString("") { "\n$it" }`)으로 계산한다.
 */
class ExcludedIdentifierSubtractionTest {

    // ───────────────────────── 픽스처와 도구 ─────────────────────────

    private fun u(id: String, kind: UnresolvedKind, filePath: String? = null) =
        UnresolvedItem(id, kind, filePath, HintSource.USER_UTTERED, 1, null, null)

    private fun intentFixture(
        raw: String,
        missing: List<String> = emptyList(),
        unresolved: List<UnresolvedItem> = emptyList(),
        excluded: List<String> = emptyList(),
        original: String = raw
    ): ClarifyIntent {
        val tagged = if (missing.isEmpty()) raw else "$raw (명시된 식별자: ${missing.joinToString(", ")})"
        return ClarifyIntent(
            originalRequirement = original,
            refinedRequirement = tagged,
            excludedFiles = excluded,
            graphHash = "dummyHash",
            retentionAudit = RetentionAudit(rawRefinedRequirement = raw, missingBeforeFix = missing),
            unresolvedItems = unresolved
        )
    }

    private fun resolve(intent: ClarifyIntent) =
        AnalyzeInputResolver.resolve(rawInput = intent.refinedRequirement, inMemoryIntent = intent)

    private fun expectedEffective(raw: String, missing: List<String>): String =
        if (missing.isEmpty()) raw else "$raw\n" + missing.joinToString("") { "\n$it" }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun assertBytesEqual(message: String, expected: String, actual: String) {
        assertEquals(message, expected, actual)
        assertArrayEquals(message, bytes(expected), bytes(actual))
    }

    private fun stage3Base(resolved: ResolvedAnalyzeInput): String =
        AnalyzeSections.buildStage3Base(resolved, "", null, fallbackBase = "<사용되면 안 되는 fallback>")

    // 파이프라인 기록용 mock: Stage 3 user 내용과 시드 프롬프트를 기록하고, 표지 외 프롬프트는 즉시 실패
    private class RecordingLlm : LLMClient {
        val stage3UserContents = mutableListOf<String>()
        val seedPrompts = mutableListOf<String>()
        val unmatched = mutableListOf<String>()

        private fun route(systemPrompt: String, userPrompts: List<String>) {
            when {
                systemPrompt.contains("당신은 프로젝트 아키텍트입니다.") -> Unit
                systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.") -> stage3UserContents.addAll(userPrompts)
                systemPrompt.contains("당신은 시스템의 소스코드 및 메타그래프를 능동적으로 탐색하는 전문 AI 분석 에이전트입니다.") -> seedPrompts.addAll(userPrompts)
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

    /** 각 픽스처의 effectiveRequirement에 나오는 식별자와 같은 클래스명을 둬서 Stage 3까지 후보가 남게 하는 작은 그래프. */
    private fun smallGraph() = ProjectGraph(
        generatedAt = java.time.Instant.now().toString(),
        projectRoot = "/test/root",
        files = mapOf(
            file("com/example/FooDto.java", "FooDto"),
            file("com/example/ReportDao.java", "ReportDao"),
            file("com/example/SurveyServiceImpl.java", "SurveyServiceImpl"),
            file("com/example/ZetaService.java", "ZetaService"),
            file("com/example/TargetService.java", "TargetService")
        ),
        relationships = emptyList(),
        statistics = GraphStatistics()
    )

    private fun runAnalyze(resolved: ResolvedAnalyzeInput, graph: ProjectGraph = smallGraph()): RecordingLlm =
        kotlinx.coroutines.runBlocking {
            val llm = RecordingLlm()
            RequirementAnalysisPipeline(llm).analyze(
                primaryReq = resolved.effectiveRequirement,
                secondaryReq = "",
                projectGraph = graph,
                clarifyIntent = resolved.effectiveIntent,
                analyzeInput = resolved
            )
            assertTrue("strict mock에서 표지와 일치하지 않는 프롬프트가 없어야 함: ${llm.unmatched}", llm.unmatched.isEmpty())
            llm
        }

    /** `## 요구사항\n` ~ `\n\n## 후보 파일 목록` 구간. Stage 3 호출은 정확히 1회여야 한다. */
    private fun stage3Segment(llm: RecordingLlm): String {
        assertEquals("Stage 3 검증기 호출이 정확히 1회 기록되어야 함", 1, llm.stage3UserContents.size)
        val content = llm.stage3UserContents.single()
        val marker = "## 요구사항\n"
        val start = content.indexOf(marker)
        assertTrue("'## 요구사항' 헤더가 있어야 함: $content", start >= 0)
        val from = start + marker.length
        val end = content.indexOf("\n\n## 후보 파일 목록", from)
        assertTrue("'## 후보 파일 목록' 헤더가 있어야 함: $content", end >= 0)
        return content.substring(from, end)
    }

    /** 시드 프롬프트(`AgenticSeedSelector.kt:59`)에서 `요구사항(SR):\n` 뒤 구간을 자른다. 표지는 각각 정확히 한 번이어야 한다. */
    private fun seedSr(llm: RecordingLlm): String {
        val open = "요구사항(SR):\n"
        val close = "\n\n위 요구사항을 분석하여"
        val prompts = llm.seedPrompts.filter { open in it }
        assertEquals("시드 프롬프트는 정확히 1건", 1, prompts.size)
        val p = prompts.single()
        assertEquals("표지 '요구사항(SR):' 출현 횟수", 1, p.split(open).size - 1)
        assertEquals("표지 '위 요구사항을 분석하여' 출현 횟수", 1, p.split(close).size - 1)
        return p.substringAfter(open).substringBefore(close)
    }

    private fun occurrences(text: String, token: String): Int =
        Regex(Regex.escape(token), RegexOption.IGNORE_CASE).findAll(text).count()

    // ───────────────────────── D1: 차감과 순서 ─────────────────────────

    private val d1Raw = "리포트 기능 개발"
    private val d1Missing = listOf("FooDto", "ReportDao", "SurveyServiceImpl", "ZetaService")
    private val d1Excluded = listOf("com/example/ZetaService.java", "com/example/SurveyServiceImpl.java")  // missing과 반대 순서
    private val d1Intent get() = intentFixture(d1Raw, d1Missing, excluded = d1Excluded)

    // D1P [순수 함수] 차감되고, subtractedIdentifiers는 missingBeforeFix 순서를 유지한다
    @Test
    fun d1P_subtractsExcludedIdentifiersKeepingMissingOrder() {
        val resolved = resolve(d1Intent)
        assertEquals(listOf("FooDto", "ReportDao"), resolved.missingIdentifiers)
        assertEquals("제외 파일 순서가 아니라 missingBeforeFix 순서", listOf("SurveyServiceImpl", "ZetaService"), resolved.subtractedIdentifiers)
        assertBytesEqual("effectiveRequirement", "리포트 기능 개발\n\nFooDto\nReportDao", resolved.effectiveRequirement)
        assertEquals(d1Raw, resolved.rawRefined)
    }

    // D1R [파이프라인 기록] Stage 3 구간 전체 비교 + 차감된 식별자 출현 횟수 0(보조)
    @Test
    fun d1R_recordedStage3SegmentExcludesSubtractedIdentifiers() {
        val resolved = resolve(d1Intent)
        val segment = stage3Segment(runAnalyze(resolved))
        assertBytesEqual("D1R", "리포트 기능 개발\n\n[원문에 있었으나 정제문에서 빠진 식별자]\n- FooDto\n- ReportDao", segment)
        assertEquals("SurveyServiceImpl 출현 횟수(보조)", 0, occurrences(segment, "SurveyServiceImpl"))
        assertEquals("ZetaService 출현 횟수(보조)", 0, occurrences(segment, "ZetaService"))
    }

    // D3R [파이프라인 기록] 시드 프롬프트(요구사항(SR): 뒤 구간)에 차감된 식별자가 없고 남은 식별자는 있다
    @Test
    fun d3R_seedPromptSrSectionExcludesSubtractedIdentifiers() {
        val resolved = resolve(d1Intent)
        val sr = seedSr(runAnalyze(resolved))
        assertEquals(resolved.effectiveRequirement + "\n", sr)
        assertFalse(sr.contains("SurveyServiceImpl"))
        assertFalse(sr.contains("ZetaService"))
        assertTrue(sr.contains("FooDto"))
    }

    // D8P [순수 함수] effectiveIntent는 같은 객체이고 감사 기록은 바뀌지 않는다(차감이 생기는 D1 입력 사용)
    @Test
    fun d8P_effectiveIntentIsSameObjectAndAuditUnchanged() {
        val intent = d1Intent
        val resolved = resolve(intent)
        assertTrue("전제: 차감이 실제로 생겨야 함", resolved.subtractedIdentifiers.isNotEmpty())
        assertSame(intent, resolved.effectiveIntent)
        assertEquals(d1Missing, intent.retentionAudit!!.missingBeforeFix)
        assertEquals(d1Missing, resolved.effectiveIntent!!.retentionAudit!!.missingBeforeFix)
    }

    // ───────────────────────── D2: 일치 규칙(확장자) ─────────────────────────

    // D2aP [순수 함수] 확장자 포함 파일명 일치: .java와 .xml 모두
    @Test
    fun d2aP_fileNameFormMatchesForAnyExtension() {
        val java = resolve(intentFixture("조회 기능", listOf("APCMMTrcdIsSVC.java"), excluded = listOf("src/main/java/x/APCMMTrcdIsSVC.java")))
        assertEquals(emptyList<String>(), java.missingIdentifiers)
        assertEquals(listOf("APCMMTrcdIsSVC.java"), java.subtractedIdentifiers)

        val xml = resolve(intentFixture("조회 기능", listOf("ECMBTBISM006Mapper.xml"), excluded = listOf("src/main/resources/ECMBTBISM006Mapper.xml")))
        assertEquals(emptyList<String>(), xml.missingIdentifiers)
        assertEquals(listOf("ECMBTBISM006Mapper.xml"), xml.subtractedIdentifiers)
    }

    // D2bP [순수 함수] 확장자가 다른 파일은 차감하지 않는다
    @Test
    fun d2bP_differentExtensionDoesNotSubtract() {
        val resolved = resolve(intentFixture("조회 기능", listOf("Foo.xml"), excluded = listOf("com/example/Foo.java")))
        assertEquals(listOf("Foo.xml"), resolved.missingIdentifiers)
        assertEquals(emptyList<String>(), resolved.subtractedIdentifiers)
    }

    // D2cP [순수 함수] 제외 파일이 클래스 소스(java/kt)가 아니면 기본 이름으로 차감하지 않는다
    @Test
    fun d2cP_classNameDoesNotMatchNonClassSourceExtension() {
        val resolved = resolve(intentFixture("조회 기능", listOf("ECMBTBISM006Mapper"), excluded = listOf("src/main/resources/ECMBTBISM006Mapper.xml")))
        assertEquals(listOf("ECMBTBISM006Mapper"), resolved.missingIdentifiers)
        assertEquals(emptyList<String>(), resolved.subtractedIdentifiers)
    }

    // ───────────────────────── D3~D4: 대소문자, 메서드 토큰 ─────────────────────────

    // D3P [순수 함수] B-22: 대소문자를 무시하고 일치한다
    @Test
    fun d3P_matchIgnoresCase() {
        val resolved = resolve(intentFixture("조회 기능", listOf("aCMBTBAPC024DEM"), excluded = listOf("com/example/ACMBTBAPC024DEM.java")))
        assertEquals(emptyList<String>(), resolved.missingIdentifiers)
        assertEquals(listOf("aCMBTBAPC024DEM"), resolved.subtractedIdentifiers)
    }

    // D4P [순수 함수] 메서드 토큰은 소유 클래스가 제외돼도 차감하지 않는다
    @Test
    fun d4P_methodTokenIsNotSubtracted() {
        val resolved = resolve(intentFixture("조회 기능", listOf("selTrcdIsInf"), excluded = listOf("com/example/ACMBTBAPC024DEM.java")))
        assertEquals(listOf("selTrcdIsInf"), resolved.missingIdentifiers)
        assertEquals(emptyList<String>(), resolved.subtractedIdentifiers)
    }

    // ───────────────────────── D5: 전부 차감 ─────────────────────────

    // D5P [순수 함수] 전부 차감되면 effectiveRequirement는 rawRefined와 바이트 동일하고 Stage 3 본문도 rawRefined뿐이다
    @Test
    fun d5P_allSubtractedEqualsRawRefined() {
        val raw = "TargetService 기능 수정"
        val resolved = resolve(intentFixture(raw, listOf("SurveyServiceImpl"), excluded = listOf("com/example/SurveyServiceImpl.java")))
        assertEquals(emptyList<String>(), resolved.missingIdentifiers)
        assertEquals(listOf("SurveyServiceImpl"), resolved.subtractedIdentifiers)
        assertBytesEqual("effectiveRequirement", raw, resolved.effectiveRequirement)
        assertBytesEqual("Stage 3 본문", raw, stage3Base(resolved))
    }

    // D2R [파이프라인 기록] 전부 차감 → Stage 3 구간이 rawRefined와 바이트 동일
    @Test
    fun d2R_recordedSegmentEqualsRawRefinedWhenAllSubtracted() {
        val raw = "TargetService 기능 수정"
        val resolved = resolve(intentFixture(raw, listOf("SurveyServiceImpl"), excluded = listOf("com/example/SurveyServiceImpl.java")))
        assertBytesEqual("D2R", raw, stage3Segment(runAnalyze(resolved)))
    }

    // ───────────────────────── D6: 겹침 없으면 2c와 동일 ─────────────────────────

    // D6P [순수 함수] excluded가 비었거나 겹침이 없으면 2c의 4개 픽스처가 2b 조립식과 바이트 동일
    @Test
    fun d6P_noOverlapKeepsTwoCOutput() {
        val surveyMissing = listOf(
            "BizgoApiService", "BrandMessageTemplateBatchJob", "BrandMessageTemplateBatchRepository",
            "BrandMessageTemplateBatchRunner", "BrandMessageTmplDto"
        )
        val surveyUnresolved = surveyMissing.map { u(it, UnresolvedKind.NEW_CREATION) }
        val apcUnresolved = listOf(
            u("APCMMTrcdIsSVC.java", UnresolvedKind.EXISTING_REF),
            u("APCMMTrcdIsInfSVO", UnresolvedKind.EXISTING_REF),
            u("aCMBTBAPC024DEM", UnresolvedKind.EXISTING_REF),
            u("SAPACMM0802S01", UnresolvedKind.NEW_CREATION)
        )
        val fixtures = listOf(
            Triple("설문 발송 채널에 브랜드메시지 추가 및 Bizgo 연동 API 신규 개발", surveyMissing, surveyUnresolved),
            Triple("앱카드 및 모니모페이 최근이력 1건 조회 기능 개발", listOf("aCMBTBAPC024DEM", "selTrcdIsInf"), emptyList<UnresolvedItem>()),
            Triple("교통카드 구 발급정보 조회 (SAPACMM0802S01 기존서비스 참고). APCMMTrcdIsInfSVO 수정, APCMMTrcdIsSVC.java 추가, aCMBTBAPC024DEM.selTrcdIsInf 참조", emptyList<String>(), apcUnresolved),
            Triple("설문 발송 채널에 브랜드메시지 추가", emptyList<String>(), emptyList<UnresolvedItem>())
        )
        for (excluded in listOf(emptyList(), listOf("com/example/Other.java"))) {
            for ((raw, missing, unresolved) in fixtures) {
                val resolved = resolve(intentFixture(raw, missing, unresolved, excluded))
                val label = "excluded=$excluded raw=$raw"
                assertBytesEqual(label, expectedEffective(raw, missing), resolved.effectiveRequirement)
                assertEquals(label, missing, resolved.missingIdentifiers)
                assertEquals(label, emptyList<String>(), resolved.subtractedIdentifiers)
            }
        }
    }

    // ───────────────────────── D7, D9, D10 ─────────────────────────

    // D7P [순수 함수] unresolvedItems는 차감하지 않는다(filePath가 제외 경로와 같아도 그대로, B-38)
    @Test
    fun d7P_unresolvedItemsAreNotSubtracted() {
        val item = u("SurveyServiceImpl", UnresolvedKind.EXISTING_REF, filePath = "com/example/SurveyServiceImpl.java")
        val resolved = resolve(intentFixture("조회 기능", unresolved = listOf(item), excluded = listOf("com/example/SurveyServiceImpl.java")))
        assertEquals(listOf(item), resolved.unresolvedItems)
    }

    // D9P [순수 함수] 부분 문자열은 차감하지 않는다
    @Test
    fun d9P_substringDoesNotMatch() {
        val resolved = resolve(intentFixture("조회 기능", listOf("SurveyServiceImpl"), excluded = listOf("com/example/SurveyService.java")))
        assertEquals(listOf("SurveyServiceImpl"), resolved.missingIdentifiers)
        assertEquals(emptyList<String>(), resolved.subtractedIdentifiers)
    }

    // D10P [순수 함수] 범위 밖 고정: 정제문 본문에 남은 제외 식별자는 건드리지 않는다
    @Test
    fun d10P_bodyKeepsExcludedIdentifier() {
        val raw = "SurveyServiceImpl 수정 제외 및 신규 FooService 추가"
        val resolved = resolve(intentFixture(raw, emptyList(), excluded = listOf("com/example/SurveyServiceImpl.java")))
        assertBytesEqual("rawRefined", raw, resolved.rawRefined)
        assertBytesEqual("effectiveRequirement", raw, resolved.effectiveRequirement)
        assertEquals(emptyList<String>(), resolved.subtractedIdentifiers)
    }

    // ───────────────────────── D0R: 엔진 경로 ─────────────────────────

    /** 하네스 `StrictClarifyMockLlm`과 같은 표지. 정제 응답만 제외 식별자를 뺀 문장을 돌려준다. */
    private class EngineMockLlm(private val refinementResponse: String, private val constraintsResponse: String) : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> refinementResponse
                systemPrompt.contains("ConstraintKind") -> constraintsResponse
                systemPrompt.contains("개방형 질문") -> "추가적으로 수정 또는 연동할 대상이 있으신가요?"
                else -> error("unmatched prompt in EngineMockLlm.chat: [systemPrompt=$systemPrompt]")
            }
            return OllamaChatResponse(model = "engine-mock-llm", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }

        override fun chatWithTools(
            systemPrompt: String,
            messages: List<ChatMessage>,
            maxTokens: Int?,
            tools: List<ToolDefinition>?,
            toolChoice: Any?,
            temperature: Double?
        ): ChatCompletionResponse? = error("EngineMockLlm does not expect chatWithTools: [systemPrompt=$systemPrompt]")

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    // 4조건 테스트(testClassificationAndKeywordDiff_4Conditions)와 같은 그래프
    private fun conditionGraph(): ProjectGraph {
        val files = mapOf(
            "com/example/OrderDto.java" to FileNode(
                path = "com/example/OrderDto.java",
                packageName = "com.example",
                className = "OrderDto",
                fileType = SpringFileType.DTO,
                layer = ArchitectureLayer.PERSISTENCE,
                methods = listOf(MethodSignature("getPay_type", "String", emptyList()))
            ),
            "com/example/SurveyServiceImpl.java" to FileNode(
                path = "com/example/SurveyServiceImpl.java",
                packageName = "com.example",
                className = "SurveyServiceImpl",
                localName = "SurveyServiceImpl",
                koreanComments = listOf("SurveyServiceImpl 설문 서비스 구현체"),
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("executeSurvey", "void", emptyList()))
            ),
            "com/example/SurveyService.java" to FileNode(
                path = "com/example/SurveyService.java",
                packageName = "com.example",
                className = "SurveyService",
                localName = "SurveyServiceImpl",
                koreanComments = listOf("SurveyServiceImpl 설문 서비스 인터페이스"),
                fileType = SpringFileType.INTERFACE,
                layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("executeSurvey", "void", emptyList()))
            )
        )
        return ProjectGraph(
            generatedAt = java.time.Instant.now().toString(),
            projectRoot = "/test/root",
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files,
            relationships = listOf(
                Relationship(
                    source = "com/example/SurveyServiceImpl.java",
                    target = "com/example/SurveyService.java",
                    type = RelationshipType.IMPLEMENTS
                )
            ),
            resourceNodes = emptyList(),
            statistics = GraphStatistics()
        )
    }

    // D0R [파이프라인 기록, 엔진 경로] Condition3 + 정제 mock이 SurveyServiceImpl을 뺀 정제문을 돌려줌
    @Test
    fun d0R_enginePathSubtractsExcludedIdentifierDroppedByRefinement() = kotlinx.coroutines.runBlocking {
        val graph = conditionGraph()
        val constraints = """
            [
              {
                "kind": "EXCLUDE_COMPONENT",
                "value": "SurveyServiceImpl 수정 제외",
                "rawStatement": "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘",
                "evidence": "SurveyServiceImpl은 건드리지 말고"
              }
            ]
        """.trimIndent()
        val engine = Stage0ClarificationEngine(
            Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10),
            graph,
            llmClient = EngineMockLlm(refinementResponse = "신규 FooService 추가", constraintsResponse = constraints)
        )
        val turn0 = engine.initSession("주문 기능 개발")
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(userStatement = "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘")
        )
        val turn2 = engine.processTurn(turn1.state, Stage0ClarificationEngine.UserInput(userStatement = "응"))
        val intent = engine.buildClarifyIntent(turn2.state)

        // 단언 1: 제외 확정 (실패하면 mock이나 픽스처를 고치지 말고 멈춰 보고)
        assertTrue("excludedFiles=${intent.excludedFiles}", intent.excludedFiles.contains("com/example/SurveyServiceImpl.java"))
        // 단언 2: 엔진이 만든 원본 intent의 감사 기록에 SurveyServiceImpl이 빠진 식별자로 있음
        assertTrue("missingBeforeFix=${intent.retentionAudit!!.missingBeforeFix}", intent.retentionAudit!!.missingBeforeFix.contains("SurveyServiceImpl"))

        // 단언 3: 차감 뒤 resolved에서 빠지고 subtractedIdentifiers에 기록됨
        val resolved = resolve(intent)
        assertFalse(resolved.missingIdentifiers.contains("SurveyServiceImpl"))
        assertTrue(resolved.subtractedIdentifiers.contains("SurveyServiceImpl"))

        // 단언 4: 시드 프롬프트의 요구사항(SR): 뒤 구간 (표지 정확히 한 번씩은 seedSr가 단언)
        val llm = runAnalyze(resolved, graph)
        val sr = seedSr(llm)
        assertEquals(resolved.effectiveRequirement + "\n", sr)
        assertEquals("신규 FooService 추가\n", sr)
        assertFalse(sr.contains("SurveyServiceImpl"))
    }

    // ───────────────────────── 커밋 4 불변식 (엔진 경로, D0R과 같은 대화) ─────────────────────────

    /** D0R과 같은 대화로 엔진이 만든 인텐트. 정제 mock은 '신규 FooService 추가'를 돌려주므로 SurveyServiceImpl이 정제문에서 빠진다. */
    private fun engineIntent(): ClarifyIntent {
        val graph = conditionGraph()
        val constraints = """
            [
              {
                "kind": "EXCLUDE_COMPONENT",
                "value": "SurveyServiceImpl 수정 제외",
                "rawStatement": "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘",
                "evidence": "SurveyServiceImpl은 건드리지 말고"
              }
            ]
        """.trimIndent()
        val engine = Stage0ClarificationEngine(
            Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10),
            graph,
            llmClient = EngineMockLlm(refinementResponse = "신규 FooService 추가", constraintsResponse = constraints)
        )
        val turn0 = engine.initSession("주문 기능 개발")
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(userStatement = "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘")
        )
        val turn2 = engine.processTurn(turn1.state, Stage0ClarificationEngine.UserInput(userStatement = "응"))
        return engine.buildClarifyIntent(turn2.state)
    }

    // I1R [엔진 경로] 정제문에서 빠진 식별자가 감사 기록의 missingBeforeFix에 채워진다 (커밋 4 전후 모두 통과)
    @Test
    fun i1R_enginePathFillsMissingBeforeFix() {
        val intent = engineIntent()
        assertEquals(listOf("SurveyServiceImpl"), intent.retentionAudit!!.missingBeforeFix)
    }

    // I2a [엔진 경로] 감사 기록의 raw 정제문은 리터럴과 같다 (커밋 4 전후 모두 통과, 두 필드에 같은 태그가 붙는 변이를 막는다)
    @Test
    fun i2a_rawRefinedRequirementIsLiteral() {
        val intent = engineIntent()
        assertEquals("신규 FooService 추가", intent.retentionAudit!!.rawRefinedRequirement)
    }

    // I2b [엔진 경로] 커밋 4 이후에만 통과: 정제문에 식별자 태그가 없고 refinedRequirement == 감사 기록의 raw
    @Test
    fun i2b_refinedEqualsRawWithoutTag() {
        val intent = engineIntent()
        assertEquals("신규 FooService 추가", intent.refinedRequirement)
        assertEquals(intent.retentionAudit!!.rawRefinedRequirement, intent.refinedRequirement)
        assertFalse(intent.refinedRequirement.contains("명시된 식별자"))
    }
}
