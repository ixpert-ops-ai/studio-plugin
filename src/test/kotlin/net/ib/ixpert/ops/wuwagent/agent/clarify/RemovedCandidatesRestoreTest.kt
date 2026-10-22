package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.AnalysisSessionMemory
import net.ib.ixpert.ops.wuwagent.agent.RemovedCandidate
import net.ib.ixpert.ops.wuwagent.agent.RemovedSource
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisResult
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatChoice
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolCall
import net.ib.ixpert.ops.wuwagent.model.ToolCallFunction
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import net.ib.ixpert.ops.wuwagent.agent.TargetFileValidator
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AdaptiveFileDiscovery
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * B-55 안 1의 A: 최종 목록에서 빠진 후보(removedCandidates)와 되살리기(restore), 같은 세션의 pinnedFiles.
 * Stage 3는 후보 경로를 받아 응답을 만드는 모의 검증기이고, LLM 호출 수를 센다.
 */
class RemovedCandidatesRestoreTest {

    private class StubLlm(private val stage3: (List<String>) -> String) : LLMClient {
        var calls = 0
        val stage3Candidates = mutableListOf<List<String>>()
        val stage3UserContents = mutableListOf<String>()

        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            calls++
            return OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", "{}"), done = true)
        }

        override fun chatWithTools(
            systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?
        ): ChatCompletionResponse? {
            calls++
            if (!systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.")) return null
            val user = messages.mapNotNull { it.content }.joinToString("\n")
            val paths = Regex("""(?m)^- \[(\d+)] (\S+)""").findAll(user).map { it.groupValues[2] }.toList()
            stage3Candidates.add(paths)
            stage3UserContents.add(user)
            val call = ToolCall("1", "function", ToolCallFunction("submit_verification", stage3(paths)))
            return ChatCompletionResponse("x", listOf(ChatChoice(0, ChatMessage("assistant", null, toolCalls = listOf(call)), "tool_calls")))
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private val names = listOf("FooDto", "ReportDao", "SurveyServiceImpl", "ZetaService", "TargetService")
    private fun pathOf(n: String) = "com/example/$n.java"

    private fun graph() = ProjectGraph(
        generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root",
        files = names.associate {
            pathOf(it) to FileNode(path = pathOf(it), packageName = "com.example", className = it, fileType = SpringFileType.SERVICE, layer = ArchitectureLayer.SERVICE)
        },
        relationships = emptyList(), statistics = GraphStatistics()
    )

    private val req = "FooDto ReportDao SurveyServiceImpl ZetaService TargetService 수정"

    private fun intent(excluded: List<String> = emptyList()) =
        ClarifyIntent(originalRequirement = req, refinedRequirement = req, excludedFiles = excluded, graphHash = "h")

    private fun analyze(llm: StubLlm, excluded: List<String> = emptyList(), root: File? = null, requirement: String = req): RequirementAnalysisResult =
        kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(llm).analyze(
                primaryReq = requirement, secondaryReq = "", projectGraph = graph(),
                clarifyIntent = intent(excluded).copy(originalRequirement = requirement, refinedRequirement = requirement), projectRoot = root
            )
        }

    /** 첫 후보만 required, 둘째는 removedFiles, 셋째는 required지만 사유가 짧아 코드가 제거, 나머지는 required. */
    private fun mixedStage3(paths: List<String>): String {
        val required = paths.indices.filter { it != 1 }.joinToString(",") { i ->
            """{"n":${i + 1},"reason":"${if (i == 2) "ab" else "수정이 필요한 구체적인 사유"}"}"""
        }
        return """{"requiredFiles":[$required],"removedFiles":[2],"reasoning":"1) 테스트"}"""
    }

    private fun removeAllButFirst(paths: List<String>): String =
        """{"requiredFiles":[{"n":1,"reason":"수정이 필요한 구체적인 사유"}],"removedFiles":[${(2..paths.size).joinToString(",")}],"reasoning":"1) 테스트"}"""

    @Before @After
    fun clean() {
        AnalysisSessionMemory.reset()
        RequirementAnalysisPipeline.lastResult = null
    }

    // R1 모델이 removedFiles에 넣은 후보는 STAGE3_EXPLICIT, REQUIRED로 적었지만 사유가 짧아 제거된 후보는 STAGE3_IMPLICIT(사유 포함).
    @Test
    fun r1_implicitDropIsListedAsStage3Implicit() {
        val llm = StubLlm(::mixedStage3)
        val result = analyze(llm)
        val cands = llm.stage3Candidates.single()
        assertTrue("후보가 3개 이상이어야 한다: $cands", cands.size >= 3)
        val removed = result.removedCandidates!!
        assertEquals(RemovedSource.STAGE3_EXPLICIT, removed.single { it.path == cands[1] }.source)
        val implicit = removed.single { it.path == cands[2] }
        assertEquals(RemovedSource.STAGE3_IMPLICIT, implicit.source)
        assertEquals(3, implicit.stageRank)
        assertNotNull(implicit.reason)
        assertEquals(2, removed.size)
        assertTrue(result.targetFiles.none { it.path == cands[1] || it.path == cands[2] })
    }

    // R2 사용자 제외 파일(후보에 있던 것과 없던 것)은 USER_EXCLUDED로 목록 맨 앞에 온다. 나머지는 stageRank 순.
    @Test
    fun r2_userExcludedFilesComeFirstInTheList() {
        val first = analyze(StubLlm(::mixedStage3))
        val inCandidates = first.removedCandidates!!.first { it.source == RemovedSource.STAGE3_EXPLICIT }.path
        AnalysisSessionMemory.reset()
        val result = analyze(StubLlm(::mixedStage3), excluded = listOf(inCandidates, "com/example/NotInGraph.java"))
        val removed = result.removedCandidates!!
        val userExcluded = removed.takeWhile { it.source == RemovedSource.USER_EXCLUDED }
        assertEquals(setOf(inCandidates, "com/example/NotInGraph.java"), userExcluded.map { it.path }.toSet())
        assertNull("후보에 없던 제외 파일은 순위가 없다", userExcluded.single { it.path == "com/example/NotInGraph.java" }.stageRank)
        assertEquals("순위가 있는 제외 파일이 앞이다", inCandidates, userExcluded.first().path)
        val rest = removed.drop(userExcluded.size)
        assertTrue(rest.none { it.source == RemovedSource.USER_EXCLUDED })
        assertEquals(rest.mapNotNull { it.stageRank }, rest.mapNotNull { it.stageRank }.sorted())
    }

    // R3 restore는 LLM을 부르지 않고 lastResult(/implement 입력)에 파일을 더하며, 목록에 없는 경로는 무시하고 알린다.
    @Test
    fun r3_restoreAddsToLastResultWithoutAnyLlmCall() {
        val llm = StubLlm(::mixedStage3)
        analyze(llm)
        val removed = RequirementAnalysisPipeline.lastResult!!.removedCandidates!!
        val before = llm.calls
        val outcome = RequirementAnalysisPipeline.restore(removed.map { it.path } + "com/example/Unknown.java")
        assertEquals(before, llm.calls)
        assertEquals(removed.map { it.path }, outcome.restored)
        assertEquals(listOf("com/example/Unknown.java"), outcome.ignored)
        val last = RequirementAnalysisPipeline.lastResult!!
        assertTrue(removed.all { r -> last.targetFiles.any { it.path == r.path } })
        assertEquals((1..last.targetFiles.size).toList(), last.targetFiles.map { it.order })
        assertEquals(emptyList<RemovedCandidate>(), last.removedCandidates)
    }

    // R4 되살린 파일은 같은 세션의 다음 분석에서 Stage 3가 제거하려 해도 유지되고, Stage 3 후보로 다시 보내지 않는다.
    @Test
    fun r4_restoredFileSurvivesTheNextAnalysisInTheSameSession() {
        analyze(StubLlm(::mixedStage3))
        val explicit = RequirementAnalysisPipeline.lastResult!!.removedCandidates!!.first { it.source == RemovedSource.STAGE3_EXPLICIT }.path
        RequirementAnalysisPipeline.restore(listOf(explicit))
        val llm = StubLlm(::removeAllButFirst)
        val next = analyze(llm)
        assertFalse("pinned 파일은 Stage 3 후보로 보내지 않는다", llm.stage3Candidates.single().contains(explicit))
        assertTrue(next.targetFiles.any { it.path == explicit })
        assertTrue(next.removedCandidates!!.none { it.path == explicit })
    }

    // R5 USER_EXCLUDED를 되살리면 다음 분석에서 사전 필터에 걸리지 않는다. 디스크의 저장 파일은 restore로 바뀌지 않는다.
    @Test
    fun r5_restoringAUserExcludedFileSkipsThePreFilterAndLeavesDiskUntouched() {
        val root = Files.createTempDirectory("restore-test").toFile()
        try {
            val target = pathOf("ZetaService")
            val first = analyze(StubLlm(::removeAllButFirst), excluded = listOf(target), root = root)
            assertTrue(first.targetFiles.none { it.path == target })
            assertEquals(RemovedSource.USER_EXCLUDED, first.removedCandidates!!.first().source)
            fun snapshot() = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readText() }
            val disk = snapshot()
            val intentBefore = intent(listOf(target))
            RequirementAnalysisPipeline.restore(listOf(target))
            assertEquals("restore는 디스크를 바꾸지 않는다", disk, snapshot())
            assertEquals("clarifyIntent의 제외 목록은 그대로다", listOf(target), intentBefore.excludedFiles)
            assertTrue(RequirementAnalysisPipeline.lastResult!!.targetFiles.any { it.path == target })
            val next = analyze(StubLlm(::removeAllButFirst), excluded = listOf(target), root = root)
            assertTrue("다음 분석의 최종 목록에 있다", next.targetFiles.any { it.path == target })
            assertTrue(next.removedCandidates!!.none { it.path == target })
        } finally {
            root.deleteRecursively()
        }
    }

    // R6 새 세션(요구사항이 바뀌거나 reset)에서는 pinned가 비어 있다.
    @Test
    fun r6_newSessionStartsWithoutPinnedFiles() {
        analyze(StubLlm(::mixedStage3))
        val explicit = RequirementAnalysisPipeline.lastResult!!.removedCandidates!!.first().path
        RequirementAnalysisPipeline.restore(listOf(explicit))
        assertEquals(setOf(explicit), AnalysisSessionMemory.pinnedPaths())
        analyze(StubLlm(::mixedStage3), requirement = "$req 다른 요구사항")
        assertEquals(emptySet<String>(), AnalysisSessionMemory.pinnedPaths())
        RequirementAnalysisPipeline.restore(listOf(explicit))
        AnalysisSessionMemory.reset()
        assertEquals(emptySet<String>(), AnalysisSessionMemory.pinnedPaths())
    }

    // R7 removedCandidates 필드가 없는 옛 결과(JSON)를 읽어도 실패하지 않고, 되살리기는 모든 경로를 무시한다.
    @Test
    fun r7_oldResultWithoutTheFieldStillWorks() {
        val old = """{"summary":"s","targetFiles":[{"order":1,"path":"a.java","type":"MODIFY","description":"d"}],"warnings":"","rawResponse":"r","suggestedNewFiles":[]}"""
        val parsed = com.google.gson.Gson().fromJson(old, RequirementAnalysisResult::class.java)
        assertNull(parsed.removedCandidates)
        RequirementAnalysisPipeline.lastResult = parsed
        val outcome = RequirementAnalysisPipeline.restore(listOf("a.java", "b.java"))
        assertEquals(emptyList<String>(), outcome.restored)
        assertEquals(listOf("a.java", "b.java"), outcome.ignored)
        assertEquals(1, RequirementAnalysisPipeline.lastResult!!.targetFiles.size)
        assertEquals(null, RequirementAnalysisResult("s", emptyList(), "", "r").removedCandidates)
    }

    // R8 같은 요구사항 문자열로 restore한 뒤 새 /clarify 세션을 시작하면(라우터가 reset을 부른다) pinned가 비어 있고, 다음 분석에서 되살린 파일은 Stage 3가 다시 판정한다.
    @Test
    fun r8_newClarifySessionWithTheSameRequirementClearsPinned() {
        analyze(StubLlm(::mixedStage3))
        val explicit = RequirementAnalysisPipeline.lastResult!!.removedCandidates!!.first { it.source == RemovedSource.STAGE3_EXPLICIT }.path
        RequirementAnalysisPipeline.restore(listOf(explicit))
        assertEquals(setOf(explicit), AnalysisSessionMemory.pinnedPaths())
        // /clarify 분기는 새 세션을 시작할 때(saveSession 전에) reset을 부른다
        val router = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText()
        val clarifyBranch = router.substringAfter("\"/clarify\" -> {").substringBefore("\"/analyze\" -> {")
        assertTrue("/clarify 분기에 reset 호출이 있어야 한다", clarifyBranch.contains("AnalysisSessionMemory.reset()"))
        assertTrue("reset은 saveSession보다 먼저다", clarifyBranch.indexOf("AnalysisSessionMemory.reset()") < clarifyBranch.indexOf("saveSession("))
        AnalysisSessionMemory.reset()   // 라우터가 하는 일
        assertEquals(emptySet<String>(), AnalysisSessionMemory.pinnedPaths())
        val llm = StubLlm(::removeAllButFirst)
        val next = analyze(llm)   // 같은 요구사항 문자열
        assertTrue("pinned가 없으므로 되살렸던 파일도 Stage 3 후보로 다시 간다", llm.stage3Candidates.single().contains(explicit))
        assertTrue(next.targetFiles.none { it.path == explicit })
    }

    // R9 pinned와 사용자 제외가 없을 때 A는 기존 동작을 바꾸지 않는다: Stage 3 검증기가 받는 후보 목록과 요구사항 문장이 Stage 1-2 결과(correctedFiles)·요구사항과 같고,
    // 최종 목록·noticeLines·마크다운이 removedCandidates와 무관하다. 기대값은 파이프라인 줄을 쓰지 않고 같은 공개 함수(Stage 1 탐색, correctPaths, sortByDependency)로 따로 만든다.
    @Test
    fun r9_stage3InputAndOutputsAreUnchangedWhenNothingIsPinnedOrExcluded() {
        val llm = StubLlm(::mixedStage3)
        val chunks = StringBuilder()
        val result = kotlinx.coroutines.runBlocking {
            RequirementAnalysisPipeline(llm).analyze(primaryReq = req, secondaryReq = "", projectGraph = graph(), clarifyIntent = intent()) { chunks.append(it) }
        }
        // 기대 correctedFiles: 파이프라인 :154-234와 같은 공개 함수 호출(제외 없음)
        val g = graph()
        val discovery = AdaptiveFileDiscovery.filter(primaryReq = req, secondaryReq = "", graph = g, client = StubLlm(::mixedStage3), project = null, projectBasePath = null, enhancedRequirements = emptyList()) { }
        val specs = mutableListOf<TargetFileSpec>()
        discovery.relevantFiles.forEachIndexed { i, f -> specs.add(TargetFileSpec(i + 1, f.path, "MODIFY", "Score: ${f.score}, Via: ${f.discoveryReason}")) }
        discovery.suggestedNewFiles.forEach { f ->
            specs.add(TargetFileSpec(specs.size + 1, f.suggestedPath, "CREATE", "- suggestedFileType: ${f.suggestedFileType}\n- reason: ${f.reason}\n- referencePattern: ${f.referencePattern}"))
        }
        val expectedCorrected = TargetFileValidator.correctPaths(specs, g)
        val cands = expectedCorrected.map { it.path }
        assertTrue("후보가 3개 이상이어야 한다: $cands", cands.size >= 3)

        // (1) 검증기 입력: 후보 목록(번호와 순서)과 요구사항 문장
        assertEquals(cands, llm.stage3Candidates.single())
        val user = llm.stage3UserContents.single()
        val seg = user.substringAfter("## 요구사항\n").substringBefore("\n\n## 후보 파일 목록")
        assertEquals(req, seg)

        // (2) 최종 목록: 후보에서 모의 검증기가 뺀 둘(번호 2, 3)을 제외하고 sortByDependency한 것
        val keptSpecs = expectedCorrected.filterIndexed { i, _ -> i != 1 && i != 2 }.map { it.copy(description = "수정이 필요한 구체적인 사유") }
        assertEquals(TargetFileValidator.sortByDependency(keptSpecs, g), result.targetFiles)

        // (3) noticeLines와 마크다운: 제외 안내 줄과 파일 표가 기존 형식 그대로이고, 새 필드의 어휘가 나오지 않는다
        val md = chunks.toString()
        val names = listOf(cands[1], cands[2]).joinToString(", ") { it.substringAfterLast('/') }
        assertTrue(md, md.contains("- Stage 3에서 제외된 후보 2개: $names (사유: 제공하지 않음)"))
        result.targetFiles.forEach { assertTrue(md, md.contains("| ${it.order} | ${it.path} | 수정 | ${it.description} |")) }
        listOf("removedCandidates", "STAGE3_", "USER_EXCLUDED", "되살").forEach { assertFalse("마크다운에 \"$it\"가 나오면 안 된다", md.contains(it)) }
        assertTrue(md.contains("### 요구사항 분석 요약"))

        // 새 필드는 부가 정보일 뿐이고 같은 분석에서 목록은 (카드 후보 - 최종 유지)이다
        assertEquals(setOf(cands[1], cands[2]), result.removedCandidates!!.map { it.path }.toSet())
    }
}
