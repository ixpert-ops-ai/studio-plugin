package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.LinkHint
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.RequirementItem
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Verdict
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-61: 번역·요약 프롬프트의 후보 목록 상한(120). 120개 이하는 프롬프트가 기존과 바이트 단위로 같고,
 * 넘으면 앞 120개 + 항상 포함 항목만 기존 순서로 보이며 "외 N개 생략" 한 줄이 붙는다. 엔진 내부의 후보 집합(targetId 해석, EXCLUDE 검증)은 전체 기준이다.
 */
class CandidateCapTest {

    /** 호출된 프롬프트(시스템 + 사용자)를 기록하는 가짜 LLM. 번역 호출에는 translatorJson으로 답한다. */
    private class Recorder(private val translatorJson: String = """{"action": "UNKNOWN", "message": "m"}""") : LLMClient {
        val systems = mutableListOf<String>()
        val users = mutableListOf<String>()
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            systems.add(systemPrompt); users.add(userCode)
            val content = if (systemPrompt.contains("발화 번역 전문가")) translatorJson else "요약"
            return OllamaChatResponse(model = "rec", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun item(i: Int, verdict: Verdict = Verdict.PENDING, source: HintSource = HintSource.SYSTEM_UNCONFIRMED, className: String = "Cls$i") =
        RequirementItem(
            id = "ref:src/p/$className.java", statement = "설명 $i", source = source,
            hint = LinkHint.ExistingRef("src/p/$className.java", emptyList()), anchorRationale = "근거 $i", verdict = verdict
        )

    private fun items(n: Int) = (1..n).map { item(it) }

    private fun graph() = ProjectGraph(
        generatedAt = "t", projectRoot = "/r", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
        files = emptyMap(), relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics()
    )

    private fun engine(llm: LLMClient) = Stage0ClarificationEngine(Stage0GraphScanner(graph()), graph(), llmClient = llm)

    private fun state(items: List<RequirementItem>, original: String = "SR 원문", statements: List<String> = emptyList()) =
        Stage0State(originalRequirement = original, items = items, seedSet = emptySet(), userStatements = statements)

    private fun translatorBlock(prompt: String) = prompt.substringAfter("[현재 후보 목록]\n").lines().takeWhile { it.isNotBlank() }.map { it.trim() }
    private fun summaryBlock(prompt: String) = prompt.substringAfter("[현재 유효 분석 대상 컴포넌트 목록]\n").lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun oldTranslatorLines(list: List<RequirementItem>) = list.map { "- ID: ${it.id} | 파일: ${(it.hint as LinkHint.ExistingRef).filePath} | 설명: ${it.statement}" }
    private fun oldSummaryLines(list: List<RequirementItem>) = list.map { "- ${(it.hint as LinkHint.ExistingRef).filePath} (${it.anchorRationale})" }

    // C1 후보 120개 이하: 번역·요약 프롬프트의 후보 목록이 기존 형식 그대로이고 생략 줄이 없으며, 목록 밖 지시문은 후보 수와 무관하게 같다
    @Test
    fun c1_atOrBelowTheLimitThePromptsAreUnchanged() {
        val small = Recorder(); val big = Recorder()
        for ((n, rec) in listOf(5 to small, 120 to big)) {
            ClarifyUtteranceTranslator.translate("SurveyServiceImpl도 봐 주세요", items(n), rec, "SR 원문")
            engine(rec).buildTaskSummary(state(items(n)))
        }
        // 120개: 번역 줄 120개 = 기존 형식, 요약 줄 120개 = 기존 형식, 생략 줄 없음
        assertEquals(oldTranslatorLines(items(120)), translatorBlock(big.systems[0]))
        assertEquals(oldSummaryLines(items(120)), summaryBlock(big.users[1]))
        assertFalse(big.systems[0].contains("개 생략") || big.users[1].contains("개 생략"))
        // 후보 목록 부분을 뺀 지시문은 5개일 때와 120개일 때 한 글자도 다르지 않다
        fun withoutBlock(p: String, block: List<String>) = p.replace(block.joinToString("\n"), "<BLOCK>")
        val smallTr = withoutBlock(small.systems[0].lines().joinToString("\n") { it.trim() }, oldTranslatorLines(items(5)))
        val bigTr = withoutBlock(big.systems[0].lines().joinToString("\n") { it.trim() }, oldTranslatorLines(items(120)))
        assertEquals(smallTr, bigTr)
        assertEquals(small.users[1].substringBefore("[현재 유효 분석 대상 컴포넌트 목록]"), big.users[1].substringBefore("[현재 유효 분석 대상 컴포넌트 목록]"))
    }

    // C2 후보 625개: 표시 줄 수 = 120 + 상위 120 밖의 항상 포함 수, "외 N개 생략"의 N이 맞고 순서는 기존 순서
    @Test
    fun c2_over_the_limit_shows_the_top_120_plus_the_always_included_items() {
        val all = items(625).toMutableList()
        all[599] = item(600, verdict = Verdict.CONFIRMED)                                   // 600위 확정 항목
        all[600] = item(601, source = HintSource.PROPOSED_EXCLUSION)                        // 601위 제외 제안 항목
        all[499] = item(500, className = "MentionedService")                                 // 500위, 발화에 이름이 나온 항목
        val rec = Recorder()
        ClarifyUtteranceTranslator.translate("MentionedService는 건드리지 마세요", all, rec, "SR 원문")
        engine(rec).buildTaskSummary(state(all.toList(), "SR 원문", listOf("MentionedService는 건드리지 마세요")))
        val extra = listOf(499, 599, 600)
        val expected = all.filterIndexed { i, _ -> i < 120 || i in extra }
        val omitted = 625 - expected.size
        assertEquals(123, expected.size)
        assertEquals(oldTranslatorLines(expected) + "외 ${omitted}개 생략", translatorBlock(rec.systems[0]))
        assertEquals(502, omitted)
        // 요약은 REJECTED를 뺀 활성 항목 기준이고 같은 선택 규칙이다
        assertEquals(oldSummaryLines(expected) + "외 ${omitted}개 생략", summaryBlock(rec.users[1]))
    }

    // C3 후보 5,000개: 표시 줄 수가 120 + 항상 포함 수 이하(줄당 84.5토큰이면 약 10K, 16K 이하)
    @Test
    fun c3_withFiveThousandCandidatesTheListStaysBounded() {
        val all = items(5000).toMutableList()
        all[4000] = item(4001, verdict = Verdict.REJECTED)
        val sel = CandidateCap.select(all, listOf("발화", "SR"))
        assertEquals(121, sel.shown.size)
        assertEquals(5000 - 121, sel.omitted)
        assertTrue(sel.shown.size * 85 <= 16_000)
        // 요약은 REJECTED를 제외한 활성 항목만 보므로 그 항목은 요약에는 들어가지 않는다
        val rec = Recorder()
        engine(rec).buildTaskSummary(state(all.toList()))
        val block = summaryBlock(rec.users[0])
        assertEquals(120 + 1, block.size)
        assertEquals("외 ${4999 - 120}개 생략", block.last())
    }

    // C4 후보를 고르는 규칙 자체: 기존 순서 유지, 120개 이하면 같은 목록, 항상 포함 항목은 밀려나지 않는다
    @Test
    fun c4_selectionKeepsTheOrderAndNeverDropsAlwaysIncludedItems() {
        val all = items(300).toMutableList()
        all[250] = item(251, source = HintSource.USER_UTTERED)
        all[290] = item(291, verdict = Verdict.REJECTED)
        val sel = CandidateCap.select(all, emptyList())
        assertEquals((0 until 120).toList() + listOf(250, 290), sel.shown.map { all.indexOf(it) })
        assertEquals(300 - 122, sel.omitted)
        assertTrue(CandidateCap.select(items(120), emptyList()).shown.size == 120 && CandidateCap.select(items(120), emptyList()).omitted == 0)
    }

    // C5 targetId 해석과 EXCLUDE 검증은 625개 전체 집합 기준: 프롬프트에 안 보인 500위 항목도 EXCLUDE로 해석된다
    @Test
    fun c5_targetIdResolutionStillUsesTheFullSet() {
        val all = items(625)
        val hidden = all[499]
        val rec = Recorder("""{"action": "EXCLUDE", "targetId": "${hidden.id}", "message": "제외합니다."}""")
        val action = ClarifyUtteranceTranslator.translate("그 파일은 빼 주세요", all, rec, "SR 원문")
        assertFalse("500위 항목은 프롬프트에 없다", rec.systems[0].contains(hidden.id))
        assertEquals(UserActionKind.EXCLUDE, action.kind)
        assertEquals(hidden.id, action.targetId)
        // 목록에 없는 id는 기존처럼 후보 밖으로 강등된다
        val bogus = ClarifyUtteranceTranslator.translate("x", all, Recorder("""{"action": "EXCLUDE", "targetId": "ref:없는/파일.java", "message": "m"}"""), "SR")
        assertTrue(bogus.isNotInCandidates)
    }
}
