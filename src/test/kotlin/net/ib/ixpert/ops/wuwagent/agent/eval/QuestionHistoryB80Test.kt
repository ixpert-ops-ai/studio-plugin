package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/** B-80 11b: Stage 0 질문(소스 확인 질문)이 사용자의 답 앞에 어시스턴트 발화로 대화 기록에 남는다. 로컬 소스가 없으면 건너뛴다. */
class QuestionHistoryB80Test {
    private val surveyRoot = "C:/Workspace/HC_card_survey_admin/survey_admin"
    private val sr = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"

    private fun engine(): Stage0ClarificationEngine {
        val f = File("$surveyRoot/.meta/project-graph.json")
        Assume.assumeTrue(f.isFile)
        val g = Gson().fromJson(f.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        return Stage0ClarificationEngine(Stage0GraphScanner(g), g, null, deferTaskSummary = true, projectRoot = File(surveyRoot))
    }

    @Test
    fun 답하면_열려_있던_질문이_어시스턴트_발화로_답_앞에_남는다() {
        val eng = engine()
        val first = eng.initSession(sr)
        val question = first.openQuestion
        assertNotNull(question)
        val answer = "다른 테이블에서 조인해서 가져오세요"
        val turn = eng.processUtterance(first.state, answer)
        val history = turn.state.dialogueHistory
        val qIdx = history.indexOfFirst { it.role == "assistant" && it.content == question }
        val aIdx = history.indexOfFirst { it.role == "user" && it.content == answer }
        assertTrue("질문이 기록에 있다: $history", qIdx >= 0)
        assertTrue("질문이 답 앞에 있다: $history", aIdx > qIdx)
    }

    @Test
    fun 같은_질문은_기록에_한_번만_남는다() {
        val eng = engine()
        val first = eng.initSession(sr)
        val question = first.openQuestion!!
        // 답이 아닌 발화(제외 발화)는 질문을 닫지 않으므로 같은 질문이 다시 열려 있다
        val t1 = eng.processUtterance(first.state, "UserDto는 건드리지 마")
        val t2 = eng.processUtterance(t1.state, "SurveyDto는 건드리지 마")
        assertEquals(1, t2.state.dialogueHistory.count { it.role == "assistant" && it.content == question })
    }

    @Test
    fun 질문이_없는_시나리오는_기록이_달라지지_않는다() {
        val eng = engine()
        val first = eng.initSession("관리자 계정 목록 조회에서 이메일과 전화번호를 보여 준다")
        assertEquals(null, first.state.fieldFacts.firstOrNull { it.question != null })
        val turn = eng.processUtterance(first.state, "분석 시작해줘")
        assertTrue(turn.state.dialogueHistory.none { it.content.contains("찾지 못했습니다") })
    }
}
