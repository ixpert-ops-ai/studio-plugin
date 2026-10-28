package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-75 5(실험 코드): 결과 요약이 "기존 조회/전달된다"고 주장하는 데이터 필드(이메일 등)가 후보 소스·SQL에 실제로 있는지 검사해
 * 없으면 "확인 안 됨" 경고를 만든다. 4292986 프로토타입의 근거 수집(SourceFactQuestionProbe)을 재사용한다. 운영 경로에는 연결하지 않는다(이 파일은 테스트 소스).
 */
object ClaimedFieldWarningProbe {
    /** B-76: 요약 문구는 보지 않고 SR의 데이터 항목을 소스로 확인한다(summary는 호출 호환용으로만 남겼다). */
    fun warnings(@Suppress("UNUSED_PARAMETER") summary: String, srText: String, graph: ProjectGraph, projectRoot: String, seedPaths: List<String>): List<String> =
        net.ib.ixpert.ops.wuwagent.agent.ClaimedFieldCheck.warnings(srText, graph, java.io.File(projectRoot), seedPaths)
}

class ClaimedFieldWarningProbeTest {

    private val surveyRoot = "C:/Workspace/HC_card_survey_admin/survey_admin"
    private val mmRoot = "C:/Workspace/member-market"
    private val srToday = "관리자 설문 상세 화면용 기존 설문조사 상세 조회 기능에서 응답자 이메일 정보를 앞 2자리와 도메인만 보이도록 마스킹하여 함께 반환하도록 백엔드를 수정한다."

    /** 오늘 IDE 결과 요약 2문장(저장된 채팅 chat_1793063859962.json의 "요구사항 분석 요약"). */
    private val summaryToday = "1) 요구사항은 백엔드에서 이메일 마스킹 처리만 추가하는 것이므로, Service에서 기존 조회 결과를 가공하여 DTO에 마스킹된 이메일을 설정하는 방식으로 구현 가능합니다.\n" +
        "2) DAO, Mapper, Controller, View, JS 등 다른 계층은 이메일 원본 데이터를 그대로 조회/전달하므로 수정 불필요하며, Address 관련 파일은 설문 상세 조회와 무관합니다."

    private fun load(path: String): ProjectGraph? {
        val f = File(path)
        return if (f.isFile) Gson().fromJson(f.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() else null
    }

    private fun pathOf(g: ProjectGraph, cls: String) = g.files.values.first { it.className == cls }.path

    @Test
    fun w1_오늘_결과_요약은_이메일_조회_코드를_찾지_못했다는_경고가_나온다() {
        val g = load("$surveyRoot/.meta/project-graph.json"); Assume.assumeNotNull(g); g!!
        val seeds = listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto").map { pathOf(g, it) }
        val w = ClaimedFieldWarningProbe.warnings(summaryToday, srToday, g, surveyRoot, seeds)
        println("[w1] $w")
        assertEquals(1, w.size)
        assertTrue(w[0], w[0].startsWith("확인 안 됨: 응답자 이메일을 조회하는 코드/SQL을 찾지 못했습니다"))
        assertTrue(w[0], w[0].contains("sql_survey.xml:670"))
    }

    @Test
    fun w2_이메일이_실제로_있는_시나리오에서는_경고가_없다_survey_admin_사용자_도메인() {
        val g = load("$surveyRoot/.meta/project-graph.json"); Assume.assumeNotNull(g); g!!
        val seeds = listOf("UserServiceImpl", "UserDto", "UserDaoImpl").map { pathOf(g, it) }
        val summary = "관리자 계정 이메일은 기존 조회에서 그대로 전달되므로 수정이 필요 없습니다."
        val w = ClaimedFieldWarningProbe.warnings(summary, "관리자 계정 이메일 표시 확인", g, surveyRoot, seeds)
        println("[w2] $w")
        assertTrue(w.toString(), w.isEmpty())
    }

    @Test
    fun w3_이메일이_실제로_있는_시나리오에서는_경고가_없다_member_market() {
        val g = load("$mmRoot/.meta/project-graph.json"); Assume.assumeNotNull(g); g!!
        val seeds = listOf("MemberResponse", "Member", "MemberController").mapNotNull { c -> g.files.values.firstOrNull { it.className == c }?.path }
        Assume.assumeTrue(seeds.size >= 2)
        val summary = "회원 이메일은 기존 조회 결과에서 그대로 전달되므로 수정이 필요 없습니다."
        val w = ClaimedFieldWarningProbe.warnings(summary, "회원 이메일 표시 확인", g, mmRoot, seeds)
        println("[w3] seeds=${seeds.size} $w")
        assertTrue(w.toString(), w.isEmpty())
    }

    @Test
    fun w4_SR에_데이터_명사가_없으면_요약과_무관하게_경고가_없다() {
        val g = load("$surveyRoot/.meta/project-graph.json"); Assume.assumeNotNull(g); g!!
        val seeds = listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto").map { pathOf(g, it) }
        // SR에 데이터 명사가 없으면 요약이 무엇을 말하든 경고가 없다
        assertTrue(ClaimedFieldWarningProbe.warnings(summaryToday, "설문 상세 조회 기능의 응답 건수 표시를 수정한다", g, surveyRoot, seeds).isEmpty())
        assertTrue(ClaimedFieldWarningProbe.warnings("", "설문 목록 정렬 기준을 바꾼다", g, surveyRoot, seeds).isEmpty())
    }
}
