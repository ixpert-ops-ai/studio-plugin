package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.agent.ResponseWritePath.Judgement
import net.ib.ixpert.ops.wuwagent.agent.ResponseWritePath.StatementInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-84 4-(a): 결과 단계 사유 검사(ReasonGuard). LLM 없이 결정적으로 n/n이다.
 * 전/후 문장은 11-03 17:46 IDE 결과 표의 실제 사유(SurveyDaoImpl, SurveyDao 행)를 그대로 쓴다.
 */
class ReasonGuardB84Test {
    private val proc = StatementInfo("sql_survey.xml", "updateResult", "update", null, false, "sp_eb_stat_daily_mt_insert")
    private val sel = StatementInfo("sql_survey.xml", "selectResultList", "select", null, true, null)
    private val company = StatementInfo("sql_survey.xml", "updateCompany", "update", "ISSW_COMPANY", true, null)
    private val aggregate = StatementInfo("sql_survey.xml", "updateSurveyStat", "update", "ISSW_SURVEY_R", false, null)

    /** 응답 테이블 ISSW_SURVEY_R은 확인되지만 값을 받아 쓰는 문이 읽은 매퍼에 없는 판정(survey_admin과 같은 상황). */
    private val noWrite = Judgement(
        setOf("ISSW_SURVEY_R"), emptyList(), emptyList(), 1,
        mapOf("updateResult" to proc, "selectResultList" to sel, "updateCompany" to company, "updateSurveyStat" to aggregate)
    )

    private val mark = "(읽은 범위의 매퍼 1개에 응답 테이블(ISSW_SURVEY_R)로 값을 쓰는 쿼리 없음, 저장 주체 미확인)"

    private fun row(reason: String, role: PathRoles.Role? = null, j: Judgement? = noWrite) = ReasonGuard.annotateRow(reason, ReasonGuard.Context(j, role))
    private fun text(t: String, j: Judgement? = noWrite) = ReasonGuard.annotateText(t, ReasonGuard.Context(j))

    // 17:46 IDE 결과 표의 SurveyDaoImpl 행: 규칙 판정(응답 쓰기 쿼리 없음)과 어긋나는 "조회/저장" 단정에 판정이 붙는다. 선택 행이어도 추가·수정 말은 없어 G3는 붙지 않는다.
    @Test
    fun t1_daoImplRow_storeAssertionGetsTheJudgementMark() {
        val reason = "이메일 컬럼 조회/저장 SQL 호출"
        assertEquals("$reason $mark", row(reason, PathRoles.Role.OPTIONAL))
    }

    // 17:46 IDE 결과 표의 SurveyDao 행: 선택(경로 통과)인데 "시그니처 추가"를 말하므로 규칙 판정 내용(PathRoles.Role.verdict, 수정 불필요)만 덧붙는다.
    @Test
    fun t2_daoRow_changeWordOnAnOptionalRowGetsTheRoleMark() {
        val reason = "이메일 관련 메서드 시그니처 추가"
        assertEquals("$reason (규칙 판정: 수정 불필요)", row(reason, PathRoles.Role.OPTIONAL))
        // 덧붙이는 판정 내용은 PathRoles에서 온다(고정 문자열이 아니다)
        assertEquals("$reason (규칙 판정: ${PathRoles.Role.OPTIONAL.verdict})", row(reason, PathRoles.Role.OPTIONAL))
        // 필수 행이나 판정 없는 행에는 붙지 않는다
        assertEquals(reason, row(reason, PathRoles.Role.REQUIRED))
        assertEquals(reason, row(reason, null))
    }

    // G1: 쓰기처럼 보이는 이름이 읽은 매퍼에서는 프로시저 호출이면, 그 사실이 이름 뒤에 붙는다(삭제 없음).
    @Test
    fun t3_writeLookingNameGetsTheActualStatement() {
        val reason = "응답 테이블의 selectResultList/updateResult 등 이메일 컬럼 접근 SQL을 처리하는 리포지토리"
        val expected = "응답 테이블의 selectResultList/updateResult(실제: 프로시저 sp_eb_stat_daily_mt_insert 호출, 응답 테이블(ISSW_SURVEY_R) 쓰기 아님) 등 이메일 컬럼 접근 SQL을 처리하는 리포지토리"
        assertEquals(expected, row(reason))
        // 다른 테이블을 대상으로 하는 쓰기, 파라미터 없는 집계도 각자의 사실이 붙는다
        assertTrue(row("updateCompany 호출").contains("(실제: ISSW_COMPANY 대상, 응답 테이블(ISSW_SURVEY_R) 쓰기 아님)"))
        assertTrue(row("updateSurveyStat 호출").contains("(실제: 응답 테이블 대상이나 값을 받지 않는 문(집계), 응답 저장 아님)"))
        // 읽기 이름(select 접두)은 건드리지 않는다
        assertEquals("selectResultList 호출", row("selectResultList 호출"))
    }

    // 부정어가 언급 앞뒤 25자 안에 있으면 무변경(recount-b84.js와 같은 규칙)
    @Test
    fun t4_negationNearTheMentionLeavesTheTextAlone() {
        assertEquals("이 프로젝트에 응답 저장 로직은 없음", row("이 프로젝트에 응답 저장 로직은 없음"))
        assertEquals("updateResult는 응답을 저장하지 않는다", row("updateResult는 응답을 저장하지 않는다"))
        assertEquals("조회/저장 경로가 확인되지 않았습니다", row("조회/저장 경로가 확인되지 않았습니다"))
    }

    // 두 번 적용해도 같다(멱등)
    @Test
    fun t5_applyingTwiceChangesNothingMore() {
        val inputs = listOf(
            "이메일 컬럼 조회/저장 SQL 호출",
            "응답 테이블의 selectResultList/updateResult 등 이메일 컬럼 접근 SQL을 처리하는 리포지토리",
            "이메일 관련 메서드 시그니처 추가",
            "SurveyServiceImpl이 응답 저장 로직을 처리합니다. SurveyDto에 이메일 필드를 추가합니다.",
            "updateResult와 updateCompany가 응답 저장/조회를 담당"
        )
        for (r in inputs) {
            val once = row(r, PathRoles.Role.OPTIONAL)
            assertEquals(once, row(once, PathRoles.Role.OPTIONAL))
            val onceText = text(r)
            assertEquals(onceText, text(onceText))
        }
    }

    // 해당 없는 행은 무변경: 판정 없음, 응답 테이블을 모름, 응답 테이블에 값을 쓰는 문이 있음
    @Test
    fun t6_rowsThatDoNotApplyAreUnchanged() {
        val reason = "이메일 컬럼 조회/저장 SQL 호출. updateResult 호출"
        assertEquals(reason, row(reason, j = null))
        assertEquals(reason, row(reason, j = Judgement(emptySet(), emptyList(), emptyList(), 1, noWrite.statements)))
        val withWriter = Judgement(
            setOf("ISSW_SURVEY_R"), listOf("sql_survey.xml:saveAnswer"), emptyList(), 1,
            mapOf("saveAnswer" to StatementInfo("sql_survey.xml", "saveAnswer", "insert", "ISSW_SURVEY_R", true, null))
        )
        assertFalse(withWriter.noWritePath)
        val saving = "saveAnswer가 응답 저장 로직을 처리합니다"
        assertEquals(saving, row(saving, j = withWriter)) // 실제로 값을 받아 쓰는 문이고 쓰기 경로도 있으므로 표시 없음
        assertEquals("이메일 필드 추가", row("이메일 필드 추가"))
        assertEquals("", row(""))
    }

    // 읽지 않은 매퍼의 문은 표시하지 않는다(판정 정보에 없다)
    @Test
    fun t7_statementsOfUnreadMappersAreNotAnnotated() {
        val out = row("updateUnread가 호출됩니다")
        assertFalse(out, out.contains("(실제:"))
        assertEquals("updateUnread가 호출됩니다", out)
    }

    // 요약 문단: G1·G2만 문장 단위로. 단정 문장 뒤에만 판정이 붙고 G3(규칙 판정: …)는 없다.
    @Test
    fun t8_summaryParagraphIsAnnotatedPerSentence() {
        val paragraph = "SurveyServiceImpl이 응답 저장 로직을 처리합니다. SurveyDto에 이메일 필드를 추가합니다."
        assertEquals("SurveyServiceImpl이 응답 저장 로직을 처리합니다. $mark SurveyDto에 이메일 필드를 추가합니다.", text(paragraph))
        assertFalse(text(paragraph).contains("(규칙 판정:"))
        // 줄바꿈은 유지된다
        assertEquals("1. 응답 저장 로직을 처리합니다 $mark\n2. 필드 추가", text("1. 응답 저장 로직을 처리합니다\n2. 필드 추가"))
    }

    // 부정어 규칙은 scripts/recount-b84.js와 같은 값을 쓴다(상수 하나가 어긋나면 이 시험이 깨진다)
    @Test
    fun t9_negationRuleMatchesTheRecountScript() {
        val script = File("scripts/recount-b84.js").readText(Charsets.UTF_8)
        assertTrue("recount-b84.js의 neg가 ReasonGuard.NEGATION_PATTERN과 같아야 한다", script.contains("const neg = /${ReasonGuard.NEGATION_PATTERN}/;"))
        assertEquals(25, ReasonGuard.NEGATION_WINDOW)
        assertTrue("recount-b84.js의 부정문 판정 범위가 ${ReasonGuard.NEGATION_WINDOW}자여야 한다", script.contains("m.index - ${ReasonGuard.NEGATION_WINDOW}"))
    }

    /**
     * 실제 survey_admin 매퍼(서버 없음, 로컬 그래프가 없으면 사유를 출력하고 skip): updateResult가 통계 프로시저 호출(sql_survey.xml:788-791)로 읽히고,
     * 17:46 IDE 결과 표의 두 사유가 실제 판정으로 표시된다.
     */
    @Test
    fun t11_realSurveyAdminMappers() {
        val root = File("C:/Workspace/HC_card_survey_admin/survey_admin")
        val graphFile = File(root, ".meta/project-graph.json")
        if (!graphFile.isFile) println("[t11] skip: 로컬 그래프 파일이 없습니다: ${graphFile.path}")
        org.junit.Assume.assumeTrue("로컬 그래프 파일 없음(${graphFile.path})", graphFile.isFile)
        val g = com.google.gson.Gson().fromJson(graphFile.readText(Charsets.UTF_8), net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph::class.java).normalizeLegacyCollections()
        val seeds = g.files.values.filter { it.className in setOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto") }.map { it.path }
        val j = ClaimedFieldCheck.writePath(g, root, seeds)!!
        assertTrue(j.noWritePath)
        val r = j.statements.getValue("updateResult")
        assertEquals("sp_eb_stat_daily_mt_insert", r.callsProcedure)
        assertNull(r.targetTable)
        val ctx = ReasonGuard.Context(j, PathRoles.Role.OPTIONAL)
        val daoImpl = ReasonGuard.annotateRow("이메일 컬럼 조회/저장 SQL 호출", ctx)
        assertTrue(daoImpl, daoImpl.startsWith("이메일 컬럼 조회/저장 SQL 호출 (읽은 범위의 매퍼 1개에 응답 테이블(ISSW_SURVEY_R)로 값을 쓰는 쿼리 없음"))
        assertEquals("이메일 관련 메서드 시그니처 추가 (규칙 판정: 수정 불필요)", ReasonGuard.annotateRow("이메일 관련 메서드 시그니처 추가", ctx))
        val explorerStyle = ReasonGuard.annotateRow("SurveyDaoImpl: 응답 테이블의 selectResultList/updateResult 등 이메일 컬럼 접근 SQL을 처리", ReasonGuard.Context(j))
        assertTrue(explorerStyle, explorerStyle.contains("updateResult(실제: 프로시저 sp_eb_stat_daily_mt_insert 호출, 응답 테이블(ISSW_SURVEY_R) 쓰기 아님)"))
    }

    // ResponseWritePath가 문 정보를 낸다: 종류, 대상 테이블, CALL, 파라미터, 주석 처리된 문 제외
    @Test
    fun t10_statementsAreParsedFromTheReadMappers() {
        val d = "$"
        val lines = """
            <select id="selectResultList" resultType="SurveyDto">
              SELECT B.ANSWER_1 FROM ${d}{web_db}.ISSW_SURVEY_R B WHERE B.S_CODE = #{s_code}
            </select>
            <update id="updateResult">
              /* updateResult */
              call sp_eb_stat_daily_mt_insert('0');
            </update>
            <!-- <update id="commented"> UPDATE ISSW_SURVEY_R SET X = #{x} </update> -->
            <insert id="insertLog"> INSERT INTO ISSW_LOG (A) VALUES (#{a}) </insert>
        """.trimIndent().lines()
        val j = ResponseWritePath.judge(mapOf("sql_survey.xml" to lines))
        assertEquals(setOf("selectResultList", "updateResult", "insertLog"), j.statements.keys)
        val r = j.statements.getValue("updateResult")
        assertEquals("update", r.kind); assertNull(r.targetTable); assertFalse(r.hasParams); assertEquals("sp_eb_stat_daily_mt_insert", r.callsProcedure)
        val l = j.statements.getValue("insertLog")
        assertEquals("ISSW_LOG", l.targetTable); assertTrue(l.hasParams); assertNull(l.callsProcedure)
        assertEquals("select", j.statements.getValue("selectResultList").kind)
        assertTrue(j.noWritePath)
        // 이 판정으로 17:46의 DaoImpl 행이 그대로 표시된다(end-to-end)
        assertTrue(ReasonGuard.annotateRow("이메일 컬럼 조회/저장 SQL 호출", ReasonGuard.Context(j)).endsWith("저장 주체 미확인)"))
    }
}
