package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.ClaimedFieldCheck
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0ClarificationEngine
import net.ib.ixpert.ops.wuwagent.agent.clarify.Stage0GraphScanner
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ConstraintKind
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse
import net.ib.ixpert.ops.wuwagent.model.ChatMessage
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * B-77 1, 1-추가: /clarify 첫 요약 단계의 소스 확인(LLM 호출 없음) → Stage 0 질문 1개, 답의 제약 기록과 정제문 반영, 요약 프롬프트의 사실 줄.
 * 로컬 소스가 없으면 건너뛴다. 추가 소요 시간은 build/b77_stage0_field_timing.txt에 쓴다.
 */
class ClarifyFieldQuestionB77Test {

    private val surveyRoot = "C:/Workspace/HC_card_survey_admin/survey_admin"
    private val mmRoot = "C:/Workspace/member-market"

    private val srToday = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"
    private val srYesterday = "관리자 설문 상세 화면용 기존 설문조사 상세 조회 기능에서 응답자 이메일 정보를 앞 2자리와 도메인만 보이도록 마스킹하여 함께 반환하도록 백엔드를 수정한다."

    /** 호출된 프롬프트를 기록하는 시험용 클라이언트. 모든 응답은 같은 짧은 문장이다. */
    private class RecordingClient : LLMClient {
        val calls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse? {
            calls.add(systemPrompt to userCode)
            return OllamaChatResponse(model = "stub", createdAt = "", message = OllamaMessage("assistant", "요약 문장입니다."), done = true)
        }
        override fun chatWithTools(systemPrompt: String, messages: List<ChatMessage>, maxTokens: Int?, tools: List<ToolDefinition>?, toolChoice: Any?, temperature: Double?): ChatCompletionResponse? = null
        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun load(path: String): ProjectGraph? {
        val f = File(path)
        return if (f.isFile) Gson().fromJson(f.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() else null
    }

    private fun engine(g: ProjectGraph, root: String, client: LLMClient?) =
        Stage0ClarificationEngine(Stage0GraphScanner(g), g, client, deferTaskSummary = true, projectRoot = File(root))

    @Test
    fun r1_R_EMAIL에서_질문_1개와_근거_줄이_나오고_필드가_있는_7개_시나리오에서는_질문이_없다() {
        val sg = load("$surveyRoot/.meta/project-graph.json"); Assume.assumeNotNull(sg); sg!!
        val mg = load("$mmRoot/.meta/project-graph.json"); Assume.assumeNotNull(mg); mg!!
        val out = StringBuilder("구분|SR(앞 40자)|질문 수|질문 첫 줄|근거 줄\n")

        for (sr in listOf(srToday, srYesterday)) {
            val turn = engine(sg, surveyRoot, null).initSession(sr)
            val q = turn.openQuestion
            assertNotNull("R-EMAIL은 질문이 나와야 한다: ${sr.take(20)}", q)
            q!!
            assertEquals("질문은 1개", 1, Regex("어디서 가져올까요").findAll(q).count())
            assertTrue("항목 이름: $q", q.contains("응답자 이메일을 저장하거나 조회하는 코드를 찾지 못했습니다"))
            assertTrue("근거 줄(파일:줄): $q", Regex("sql_survey\\.xml:\\d+").containsMatchIn(q))
            assertTrue("근거 테이블: $q", q.contains("ISSW_ADMIN_USER"))
            assertTrue("정답 출처는 판정하지 않는다: $q", !q.contains("정답") && q.contains("어디서 가져올까요"))
            out.appendLine("R-EMAIL|${sr.take(40)}|1|${q.lineSequence().first()}|${q.lineSequence().drop(1).first()}")
        }

        val negatives = listOf(
            Triple("survey_admin 사용자(이메일)", "관리자 계정 이메일 표시를 확인한다", sg to surveyRoot),
            Triple("survey_admin 사용자(전화번호)", "관리자 전화번호 입력 검증을 추가한다", sg to surveyRoot),
            Triple("survey_admin 사용자(비밀번호)", "관리자 비밀번호 변경 안내 문구를 확인한다", sg to surveyRoot),
            Triple("survey_admin 사용자(이메일+전화번호)", "관리자 계정 목록 조회에서 이메일과 전화번호를 보여 준다", sg to surveyRoot),
            Triple("member_market 회원(이메일)", "회원 이메일 표시를 확인한다", mg to mmRoot),
            Triple("member_market 회원(전화번호)", "회원 전화번호 수정 기능을 확인한다", mg to mmRoot),
            Triple("member_market 회원(비밀번호)", "회원 비밀번호 변경 기능을 확인한다", mg to mmRoot)
        )
        var asked = 0
        for ((label, sr, gr) in negatives) {
            val (g, root) = gr
            val turn = engine(g, root, null).initSession(sr)
            val fq = turn.state.fieldFacts.count { it.question != null }
            if (fq > 0) asked++
            out.appendLine("필드 있음|$label ${sr.take(30)}|$fq|${turn.openQuestion?.lineSequence()?.first() ?: "-"}|-")
            assertEquals("필드가 있는 시나리오는 질문이 없어야 한다: $label", 0, fq)
            assertFalse("openQuestion에도 소스 확인 질문이 없어야 한다: $label", turn.openQuestion?.contains("찾지 못했습니다") == true)
        }
        out.appendLine("합계|R-EMAIL 2건 질문 2건|필드 있는 ${negatives.size}건 중 질문 ${asked}건||")
        File("build/b77_stage0_field_question.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }

    @Test
    fun r2_사용자_답은_제약으로_기록되어_정제문_프롬프트까지_전달된다() {
        val sg = load("$surveyRoot/.meta/project-graph.json"); Assume.assumeNotNull(sg); sg!!
        val client = RecordingClient()
        val eng = engine(sg, surveyRoot, client)
        val first = eng.initSession(srToday)
        assertNotNull(first.openQuestion)
        val callsBefore = client.calls.size

        val answer = "다른 테이블에서 조인해서 가져오세요"
        val turn = eng.processUtterance(first.state, answer)
        // 답은 번역기(LLM)를 거치지 않고 항목 이름과 함께 기록된다
        assertEquals("번역기 호출 없음", callsBefore, client.calls.size)
        assertEquals(answer, turn.state.fieldAnswers["응답자 이메일"])
        assertTrue(turn.state.userStatements.single().contains("응답자 이메일의 출처") && turn.state.userStatements.single().contains(answer))
        assertTrue("답한 뒤에는 같은 질문을 다시 내지 않는다", turn.openQuestion?.contains("찾지 못했습니다") != true)

        val build = eng.buildClarifyIntentChecked(turn.state)
        val refinePrompt = client.calls.map { it.second }.firstOrNull { it.contains("[사용자 추가 발화 및 답변]") }
        assertNotNull("정제문 프롬프트가 불려야 한다", refinePrompt)
        assertTrue("정제문 프롬프트에 답이 들어간다: $refinePrompt", refinePrompt!!.contains("응답자 이메일의 출처") && refinePrompt.contains(answer))
        val c = build.intent.constraints.firstOrNull { it.kind == ConstraintKind.OTHER && it.value.contains("응답자 이메일 출처") }
        assertNotNull("제약으로 기록: ${build.intent.constraints}", c)
        assertTrue(c!!.value.contains(answer))
        assertTrue(build.intent.userStatements.any { it.contains(answer) })
    }

    @Test
    fun r3_완료_발화와_제외_발화는_질문의_답으로_보지_않는다() {
        val sg = load("$surveyRoot/.meta/project-graph.json"); Assume.assumeNotNull(sg); sg!!
        val eng = engine(sg, surveyRoot, null)
        val first = eng.initSession(srToday)
        val done = eng.processUtterance(first.state, "분석 시작해줘")
        assertTrue("완료 발화는 답이 아니다", done.state.fieldAnswers.isEmpty())
        val excl = eng.processUtterance(first.state, "UserDto는 건드리지 마")
        assertTrue("제외 발화는 답이 아니다", excl.state.fieldAnswers.isEmpty())
        assertTrue("답이 없으면 질문이 남는다", excl.openQuestion?.contains("찾지 못했습니다") == true)
    }

    @Test
    fun r4_요약_프롬프트에_소스_확인_사실이_들어가고_항목이_없으면_프롬프트는_그대로다() {
        val sg = load("$surveyRoot/.meta/project-graph.json"); Assume.assumeNotNull(sg); sg!!
        val client = RecordingClient()
        val eng = engine(sg, surveyRoot, client)
        val st = eng.initSession(srToday).state
        eng.buildTaskSummary(st)
        val (sys, user) = client.calls.last()
        assertTrue("사실 구역: $user", user.contains("[소스 확인 결과(코드를 직접 읽어 확인한 사실)]"))
        assertTrue(user.contains("- 응답자 이메일: 후보 범위의 업무 코드/SQL") && user.contains("찾지 못함"))
        assertTrue("지시: $user", user.contains("어떤 후보 파일도 응답자 이메일을 담고 있다고 쓰지 말 것"))
        assertTrue("시스템 규칙", sys.contains("[소스 확인 결과]는 코드를 직접 읽어 확인한 사실이다"))

        val clean = RecordingClient()
        val eng2 = engine(sg, surveyRoot, clean)
        val st2 = eng2.initSession("설문 목록 화면의 정렬 기준을 변경한다").state
        eng2.buildTaskSummary(st2)
        val (sys2, user2) = clean.calls.last()
        assertFalse("항목이 없으면 사실 구역이 없다", user2.contains("[소스 확인 결과"))
        assertFalse(sys2.contains("[소스 확인 결과]"))
    }

    @Test
    fun r5_추가_소요_시간을_잰다_LLM_호출_없음() {
        val sg = load("$surveyRoot/.meta/project-graph.json"); Assume.assumeNotNull(sg); sg!!
        val mg = load("$mmRoot/.meta/project-graph.json"); Assume.assumeNotNull(mg); mg!!
        val client = RecordingClient()
        val out = StringBuilder("SR(앞 40자)|stage0Facts 5회 중앙값(ms)|최소|최대|LLM 호출 수\n")
        val cases = listOf(srToday to (sg to surveyRoot), srYesterday to (sg to surveyRoot), "관리자 계정 이메일 표시를 확인한다" to (sg to surveyRoot), "회원 이메일 표시를 확인한다" to (mg to mmRoot))
        for ((sr, gr) in cases) {
            val (g, root) = gr
            ClaimedFieldCheck.stage0Facts(sr, g, File(root)) // 예열
            val ms = (1..5).map { val t = System.nanoTime(); ClaimedFieldCheck.stage0Facts(sr, g, File(root)); (System.nanoTime() - t) / 1_000_000.0 }.sorted()
            out.appendLine("${sr.take(40)}|${"%.0f".format(ms[2])}|${"%.0f".format(ms.first())}|${"%.0f".format(ms.last())}|${client.calls.size}")
        }
        File("build/b77_stage0_field_timing.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
    }
}
