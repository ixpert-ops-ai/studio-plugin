package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.ClaimedFieldCheck
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-76 3d, 5: SR에서 항목을 뽑는 경고(ClaimedFieldCheck)와 이전 방식(요약의 "그대로 조회/전달" 문구 패턴)의 비교, 필드가 실제로 있는 시나리오 7개에서 오탐 점검.
 * 비교표는 build/b76_claim_compare.txt에 쓴다.
 */
class ClaimedFieldSamplesB76Test {

    private val surveyRoot = "C:/Workspace/HC_card_survey_admin/survey_admin"
    private val mmRoot = "C:/Workspace/member-market"

    /** B-75(2026-10-27) 이전 방식: 요약에 "그대로/기존 + 조회/전달/반환/select" 주장 문장이 있는가(비교용 복사). */
    private val oldClaim = Regex("(그대로|기존)[^.\\n]{0,40}(조회|전달|반환|select)|(조회|전달|반환)[^.\\n]{0,20}그대로", RegexOption.IGNORE_CASE)
    private fun oldRuleFires(summary: String) = summary.split(Regex("(?<=[.다요])\\s+|\\n")).any { oldClaim.containsMatchIn(it) }

    private fun load(path: String): ProjectGraph? {
        val f = File(path)
        return if (f.isFile) Gson().fromJson(f.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections() else null
    }

    private fun pathOf(g: ProjectGraph, cls: String) = g.files.values.firstOrNull { it.className == cls }?.path

    @Test
    fun s1_어제와_오늘_요약_모두에서_SR_기반_경고가_나오고_필드가_있는_시나리오_7개는_경고가_없다() {
        val sg = load("$surveyRoot/.meta/project-graph.json"); Assume.assumeNotNull(sg); sg!!
        val mg = load("$mmRoot/.meta/project-graph.json"); Assume.assumeNotNull(mg); mg!!
        val out = StringBuilder("구분|시나리오|SR(앞 40자)|이전 방식(요약 패턴)|새 방식(SR 항목 확인)|경고 내용 앞부분\n")

        // 경고가 나와야 하는 두 건: IDE 결과(요약과 SR 그대로)
        val seedsSurvey = listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto").map { pathOf(sg, it)!! }
        val yesterdaySr = "관리자 설문 상세 화면용 기존 설문조사 상세 조회 기능에서 응답자 이메일 정보를 앞 2자리와 도메인만 보이도록 마스킹하여 함께 반환하도록 백엔드를 수정한다."
        val yesterdaySummary = "1) 요구사항은 백엔드에서 이메일 마스킹 처리만 추가하는 것이므로, Service에서 기존 조회 결과를 가공하여 DTO에 마스킹된 이메일을 설정하는 방식으로 구현 가능합니다.\n2) DAO, Mapper, Controller, View, JS 등 다른 계층은 이메일 원본 데이터를 그대로 조회/전달하므로 수정 불필요하며, Address 관련 파일은 설문 상세 조회와 무관합니다."
        val todaySr = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요."
        val todaySummary = "1) 응답자 이메일은 기존 설문 응답 데이터에 이미 존재하므로 DAO/Service 인터페이스 시그니처 변경 없이 SurveyDto에 필드 추가와 MyBatis mapper SQL 컬럼 추가, ServiceImpl에서 DTO 매핑만 하면 구현 가능.\n2) AddressDao, UserServiceImpl, ReviewDto, SearchHistRDto는 설문 상세 조회 이메일 노출과 무관하여 제거."
        var positives = 0
        for ((label, sr, summary) in listOf(Triple("어제(2026-10-27) 요약", yesterdaySr, yesterdaySummary), Triple("오늘(2026-10-28) 요약", todaySr, todaySummary))) {
            val w = ClaimedFieldCheck.warnings(sr, sg, File(surveyRoot), seedsSurvey)
            if (w.size == 1) positives++
            out.appendLine("경고 기대|$label|${sr.take(40)}|${if (oldRuleFires(summary)) "경고" else "경고 없음"}|${if (w.isNotEmpty()) "경고" else "경고 없음"}|${w.firstOrNull()?.take(70) ?: "-"}")
        }

        // 필드가 실제로 있는 시나리오 7개: 경고가 없어야 한다
        val userSeeds = listOf("UserServiceImpl", "UserDto", "UserDaoImpl").map { pathOf(sg, it)!! }
        val memberSeeds = listOf("MemberResponse", "Member", "MemberController").mapNotNull { pathOf(mg, it) }
        Assume.assumeTrue(memberSeeds.size >= 2)
        val negatives = listOf(
            Triple("survey_admin 사용자(이메일)", "관리자 계정 이메일 표시를 확인한다", userSeeds to sg),
            Triple("survey_admin 사용자(전화번호)", "관리자 전화번호 입력 검증을 추가한다", userSeeds to sg),
            Triple("survey_admin 사용자(비밀번호)", "관리자 비밀번호 변경 안내 문구를 확인한다", userSeeds to sg),
            Triple("survey_admin 사용자(이메일+전화번호)", "관리자 계정 목록 조회에서 이메일과 전화번호를 보여 준다", userSeeds to sg),
            Triple("member_market 회원(이메일)", "회원 이메일 표시를 확인한다", memberSeeds to mg),
            Triple("member_market 회원(전화번호)", "회원 전화번호 수정 기능을 확인한다", memberSeeds to mg),
            Triple("member_market 회원(비밀번호)", "회원 비밀번호 변경 기능을 확인한다", memberSeeds to mg)
        )
        var falsePositives = 0
        for ((label, sr, sd) in negatives) {
            val (seeds, g) = sd
            val root = if (g === sg) surveyRoot else mmRoot
            val w = ClaimedFieldCheck.warnings(sr, g, File(root), seeds)
            if (w.isNotEmpty()) falsePositives++
            out.appendLine("오탐 점검|$label|${sr.take(40)}|-|${if (w.isNotEmpty()) "경고(오탐)" else "경고 없음"}|${w.firstOrNull()?.take(70) ?: "-"}")
        }
        out.appendLine("합계|경고 기대 2건 중 새 방식 경고 ${positives}건, 오탐 점검 ${negatives.size}건 중 오탐 ${falsePositives}건|||")
        File("build/b76_claim_compare.txt").writeText(out.toString(), Charsets.UTF_8)
        println(out)
        assertEquals("어제와 오늘 요약 모두 경고가 나와야 한다", 2, positives)
        assertEquals("필드가 실제로 있는 시나리오에서 오탐이 없어야 한다", 0, falsePositives)
    }

    @Test
    fun s2_조사는_받침에_맞다() {
        assertEquals("을", ClaimedFieldCheck.eulReul("응답자 이메일"))
        assertEquals("을", ClaimedFieldCheck.eulReul("전화번호 입력"))
        assertEquals("를", ClaimedFieldCheck.eulReul("비밀번호"))
        assertEquals("을", ClaimedFieldCheck.eulReul("카드번호 확인 문"))
        assertEquals("을(를)", ClaimedFieldCheck.eulReul("ABC"))
        assertEquals("을(를)", ClaimedFieldCheck.eulReul(""))
        assertTrue(ClaimedFieldCheck.implementCaution(listOf("응답자 이메일")) == "구현 전에 확인이 필요합니다: 응답자 이메일의 출처를 정해 주세요")
    }

    @Test
    fun s3_경고가_있으면_implement_안내_앞에_확인_요청_한_줄이_붙고_implement를_막지_않는다() {
        val router = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/action/WebviewActionRouter.kt").readText(Charsets.UTF_8)
        val caution = router.indexOf("ClaimedFieldCheck.implementCaution(result.confirmItems)")
        // B-78: 안내 문구가 ClaimedFieldCheck.implementHint로 옮겨 갔다(미해결 경고가 있으면 문구가 바뀐다). 순서(확인 요청 줄 → /implement 안내)는 그대로다.
        val implement = router.indexOf("ClaimedFieldCheck.implementHint(result.confirmItems)")
        assertTrue(caution in 0 until implement)
        assertTrue(router.substring(caution - 260, caution).contains("result.confirmItems.isNotEmpty()"))
    }
}
