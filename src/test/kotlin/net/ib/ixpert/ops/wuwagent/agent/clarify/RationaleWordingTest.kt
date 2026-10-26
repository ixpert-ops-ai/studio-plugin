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
import java.io.File

/**
 * 작업 요약 프롬프트: 근거 문구를 사용자 문구로 바꿔 넣고(RationaleWording), 지시 2번을 지어내기 금지 문장으로 교체했다.
 * 후보 선정 규칙과 저장된 근거(anchorRationale)는 그대로다. 실제 모델 호출은 하지 않는다.
 */
class RationaleWordingTest {

    // ───────────── 변환 함수 ─────────────

    @Test
    fun w1_구조_식별자_공유는_같은_화면_변수_사용으로_바뀐다() {
        assertEquals("같은 화면 변수(surveyId, qstnNo) 사용", RationaleWording.toUserWording("구조 식별자(surveyId, qstnNo) 공유"))
    }

    @Test
    fun w2_View_Script_페어는_같은_화면의_스크립트로_바뀐다() {
        assertEquals(
            "[survey.jsp]와 동일 URL(/survey/view) 공유하는 같은 화면의 스크립트",
            RationaleWording.toUserWording("[survey.jsp]와 동일 URL(/survey/view) 공유하는 View-Script 페어")
        )
    }

    @Test
    fun w3_CALLS_연결은_호출_관계로_바뀐다() {
        assertEquals("[SurveyServiceImpl.java]로부터 호출 관계", RationaleWording.toUserWording("[SurveyServiceImpl.java]로부터 CALLS 연결"))
        assertEquals("[SurveyDao.java]의 호출 관계", RationaleWording.toUserWording("[SurveyDao.java]의 CALLS 연결"))
    }

    @Test
    fun w4_요구사항_토큰_일치는_이름이_요구사항_단어와_비슷함으로_바뀐다() {
        assertEquals("이름이 요구사항 단어(edit, modify)와 비슷함", RationaleWording.toUserWording("클래스명/엔드포인트가 요구사항 토큰(edit, modify)과 일치"))
        assertEquals("구조 아님 + 이름이 요구사항 단어(survey)와 비슷함", RationaleWording.toUserWording("구조 아님 + 요구사항 토큰(survey) 일치"))
    }

    @Test
    fun w5_등록되지_않은_문구는_원문을_유지한다() {
        for (raw in listOf("설문 핵심 서비스", "[A.java]로부터 INJECTS 연결", "신규 제안", "", "근거 1")) {
            assertEquals(raw, RationaleWording.toUserWording(raw))
        }
    }

    @Test
    fun w6_한_문장에_여러_문구가_있어도_모두_바뀐다() {
        val raw = "구조 식별자(a) 공유 + [B.java]로부터 CALLS 연결"
        assertEquals("같은 화면 변수(a) 사용 + [B.java]로부터 호출 관계", RationaleWording.toUserWording(raw))
    }

    // ───────────── 실제 프롬프트 ─────────────

    private class Recorder : LLMClient {
        val systems = ArrayList<String>()
        val users = ArrayList<String>()
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            systems.add(systemPrompt); users.add(userCode)
            return OllamaChatResponse(model = "rec", createdAt = "", message = OllamaMessage("assistant", "요약"), done = true)
        }
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun item(i: Int, rationale: String) = RequirementItem(
        id = "ref:src/p/Cls$i.java", statement = "설명 $i", source = HintSource.SYSTEM_UNCONFIRMED,
        hint = LinkHint.ExistingRef("src/p/Cls$i.java", emptyList()), anchorRationale = rationale, verdict = Verdict.PENDING
    )

    private fun summaryPrompts(rationales: List<String>): Pair<String, String> {
        val graph = ProjectGraph(
            generatedAt = "t", projectRoot = "/r", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = emptyMap(), relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics()
        )
        val rec = Recorder()
        val engine = Stage0ClarificationEngine(Stage0GraphScanner(graph), graph, llmClient = rec)
        engine.buildTaskSummary(Stage0State(originalRequirement = "SR 원문", items = rationales.mapIndexed { i, r -> item(i + 1, r) }, seedSet = emptySet()))
        val idx = rec.systems.indexOfFirst { it.contains("전체 작업의 핵심 목표와 각 컴포넌트의 변경 맥락") }
        return rec.systems[idx] to rec.users[idx]
    }

    private val RAW = listOf(
        "구조 식별자(surveyId) 공유",
        "[survey.jsp]와 동일 URL(/survey/view) 공유하는 View-Script 페어",
        "[SurveyServiceImpl.java]로부터 CALLS 연결",
        "클래스명/엔드포인트가 요구사항 토큰(edit, modify)과 일치"
    )

    @Test
    fun p1_요약_프롬프트에는_내부_용어가_없고_사용자_문구로_바뀐_근거가_들어_있다() {
        val (system, user) = summaryPrompts(RAW)
        for (term in listOf("구조 식별자", "View-Script", "CALLS")) {
            assertFalse("system에 '$term'", system.contains(term))
            assertFalse("user에 '$term'", user.contains(term))
        }
        assertTrue(user.contains("- src/p/Cls1.java (같은 화면 변수(surveyId) 사용)"))
        assertTrue(user.contains("같은 화면의 스크립트"))
        assertTrue(user.contains("호출 관계"))
        assertTrue(user.contains("이름이 요구사항 단어(edit, modify)와 비슷함"))
    }

    @Test
    fun p2_지시_2번은_근거에_없는_역할을_만들지_않도록_바뀌었다() {
        val (system, _) = summaryPrompts(listOf("근거 1"))
        assertTrue(system.contains("2. 근거 문구에 없는 역할·기능을 만들지 말 것."))
        assertTrue(system.contains("근거가 이름/토큰 일치뿐인 파일은 '이름이 비슷해 포함됨(확인 필요)'으로만 쓸 것."))
        assertTrue(system.contains("사용자 제약(범위·대상 화면)과 맞지 않는 파일을 '핵심 변경 대상'이라 부르지 말 것."))
        // 바뀐 지시가 있던 문장은 더 없다
        assertFalse(system.contains("어떤 역할을 하는지 사용자에게 친절하게 요약/설명하는 용도로만"))
        // 1번과 3번 지시는 그대로다
        assertTrue(system.contains("1. 주어진 후보 파일 목록을 절대로 재판단, 평가, 추가, 또는 삭제하지 마십시오."))
        assertTrue(system.contains("3. 불필요한 인사말"))
    }

    @Test
    fun p3_등록되지_않은_근거는_원문_그대로_프롬프트에_들어간다() {
        val (_, user) = summaryPrompts(listOf("설문 핵심 서비스"))
        assertTrue(user.contains("- src/p/Cls1.java (설문 핵심 서비스)"))
    }

    // ───────────── 화면 문구 ─────────────

    @Test
    fun u1_진행_문구에_메타그래프가_없다() {
        val router = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText(Charsets.UTF_8)
        val starts = Regex("""bridge\.sendMessage\("analyze_start", "([^"]*)"""").findAll(router).map { it.groupValues[1] }.toList()
        assertEquals(2, starts.size)
        starts.forEach { assertFalse(it, it.contains("메타그래프")) }
        assertTrue(starts.any { it.contains("프로젝트 코드 구조를 바탕으로 요구사항을 구체화하고 있습니다") })
        assertTrue(starts.any { it.contains("프로젝트 코드 구조를 바탕으로 영향받는 파일 분석을 시작합니다") })
    }

    @Test
    fun u2_입력칸_예시는_새_문구다() {
        val app = File("webview/src/App.tsx").readText(Charsets.UTF_8)
        assertTrue(app.contains("예: \"관리자 화면만 해당이에요\", \"이메일은 마스킹해서\", \"분석 시작해줘\""))
        assertFalse(app.contains("주소 매퍼 빼줘"))
    }
}
