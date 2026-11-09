package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision
import net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedRequirementConfirmationBridge
import net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedRequirementGate
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ConstraintKind
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.FieldFact
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.IntentConstraint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-84 결과 화면(LLM·IDE 없음): (c) 표 표시 순서, (d) "구현 전 결정할 것" 블록, (f) 읽은 매퍼 파일 이름.
 */
class ResultScreenB84Test {
    // ── (c) 표 표시 순서: 11-03 17:46 IDE 결과 표의 실제 행으로 전/후를 고정한다 ──
    private data class Row(val path: String, val role: PathRoles.Role?)

    private val before = listOf(
        Row("src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java", PathRoles.Role.OPTIONAL),
        Row("src/main/java/net/infobank/iss/survey/dao/SurveyDao.java", PathRoles.Role.OPTIONAL),
        Row("src/main/java/net/infobank/iss/sql/mysql/sql_survey.xml", PathRoles.Role.REQUIRED),
        Row("src/main/java/net/infobank/iss/survey/dto/SurveyDto.java", PathRoles.Role.REQUIRED),
        Row("src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java", PathRoles.Role.REQUIRED)
    )

    private fun sorted(rows: List<Row>) = ResultTableOrder.sort(rows, { it.path }, { it.role })

    @Test
    fun c1_requiredFirstThenServiceDaoDtoMapper_stableInsideACell() {
        val names = sorted(before).map { it.path.substringAfterLast('/') }
        // 전: DaoImpl(선택), Dao(선택), sql_survey.xml(필수), SurveyDto(필수), SurveyServiceImpl(필수)
        // 후: 필수(service → dto → mapper) → 선택(같은 dao 칸이라 기존 순서 DaoImpl, Dao 유지)
        assertEquals(listOf("SurveyServiceImpl.java", "SurveyDto.java", "sql_survey.xml", "SurveyDaoImpl.java", "SurveyDao.java"), names)
    }

    @Test
    fun c2_theMiddleGroupHasNoJudgementAndLayersFollowTheOrder() {
        val rows = listOf(
            Row("a/web/IpsController.java", null),
            Row("a/sql/sql_x.xml", null),
            Row("a/dto/XDto.java", null),
            Row("a/dao/XDao.java", null),
            Row("a/service/XService.java", null),
            Row("a/web/x.jsp", PathRoles.Role.REQUIRED),
            Row("a/dao/YDao.java", PathRoles.Role.OPTIONAL)
        )
        assertEquals(
            listOf("x.jsp", "XService.java", "XDao.java", "XDto.java", "sql_x.xml", "IpsController.java", "YDao.java"),
            sorted(rows).map { it.path.substringAfterLast('/') }
        )
    }

    @Test
    fun c3_sortingTwiceAndOnAnAlreadyOrderedListChangesNothing() {
        val once = sorted(before)
        assertEquals(once, sorted(once))
        assertEquals(emptyList<Row>(), sorted(emptyList()))
    }

    // ── (f) 읽은 매퍼 파일 이름 ──
    private fun judgement(files: List<String>) = ResponseWritePath.Judgement(setOf("ISSW_SURVEY_R"), emptyList(), emptyList(), files.size, emptyMap(), files)

    @Test
    fun f1_theMapperFilesAreNamedAndLongListsAreShortened() {
        assertEquals(
            "읽은 매퍼 1개(sql_survey.xml)에서 응답 테이블(ISSW_SURVEY_R)에 값을 받아 쓰는 INSERT/UPDATE는 발견되지 않았습니다. 이 프로젝트 밖의 다른 시스템이 응답을 저장할 수 있습니다.",
            ResponseWritePath.notFoundSentence(judgement(listOf("sql_survey.xml")))
        )
        assertTrue(ResponseWritePath.notFoundSentence(judgement(listOf("a.xml", "b.xml", "c.xml")))!!.startsWith("읽은 매퍼 3개(a.xml, b.xml, c.xml)에서"))
        assertTrue(ResponseWritePath.notFoundSentence(judgement(listOf("a.xml", "b.xml", "c.xml", "d.xml", "e.xml")))!!.startsWith("읽은 매퍼 5개(a.xml, b.xml, c.xml 외 2개)에서"))
        // 파일 이름을 모르는 판정은 이전 문구
        val old = ResponseWritePath.Judgement(setOf("ISSW_SURVEY_R"), emptyList(), emptyList(), 2)
        assertTrue(ResponseWritePath.notFoundSentence(old)!!.startsWith("읽은 범위의 매퍼(2개)에서"))
    }

    @Test
    fun f2_judgeRecordsTheFileNamesInReadOrder() {
        val lines = listOf("<select id=\"s\" resultType=\"X\">", "SELECT B.ANSWER_1 FROM ISSW_SURVEY_R B", "</select>")
        val j = ResponseWritePath.judge(linkedMapOf("sql_survey.xml" to lines, "sql_other.xml" to emptyList()))
        assertEquals(listOf("sql_survey.xml", "sql_other.xml"), j.mapperFiles)
    }

    // ── (d) "구현 전 결정할 것" 블록 ──
    private val label = "응답자 이메일"
    private val newAnswer = "응답자 이메일은 아직 수집하지 않아. 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘."
    private val decision = "응답 테이블(ISSW_SURVEY_R)에 값을 쓰는 쿼리가 읽은 매퍼 1개에 없습니다. 응답자 이메일 저장을 이 프로젝트에서 구현할지, 응답을 저장하는 다른 시스템과 연계할지 정해야 합니다."

    private fun finding(answer: String?, j: ResponseWritePath.Judgement? = judgement(listOf("sql_survey.xml"))) = ClaimedFieldCheck.Finding(
        noun = "이메일", subject = "응답자", filesRead = 24, otherCites = emptyList(), otherTables = emptyList(), seedNames = listOf("SurveyServiceImpl"), answer = answer
    ).withWriteJudgement(j)

    private val noController = listOf("a/service/SurveyServiceImpl.java", "a/dto/SurveyDto.java")

    @Test
    fun d1_blockOrderIsDecisionThenControllerThenDdl() {
        val lines = ClaimedFieldCheck.decisionBlock(listOf(finding(newAnswer)), noController, "IpsController")
        assertEquals(3, lines.size)
        assertEquals(decision, lines[0])
        assertTrue(lines[1], lines[1].contains("분석 대상에 없습니다") && lines[1].contains("이 분석은 파일을 추가하지 않았습니다") && lines[1].contains("@IpsController"))
        assertTrue(lines[2], lines[2].contains("ALTER TABLE") && lines[2].contains("DDL"))
    }

    @Test
    fun d2_eachLineKeepsItsOwnCondition() {
        // 요청 진입점이 이미 최종 목록에 있으면 컨트롤러 줄이 없다
        val withController = ClaimedFieldCheck.decisionBlock(listOf(finding(newAnswer)), noController + "a/web/IpsController.java", null)
        assertEquals(2, withController.size)
        assertEquals(decision, withController[0])
        assertTrue(withController[1].contains("ALTER TABLE"))
        // 쓰기 쿼리가 있다는 판정이거나 판정이 없으면 저장 주체 결정 줄이 없다(컨트롤러·DDL 줄은 그대로)
        val noJudgement = ClaimedFieldCheck.decisionBlock(listOf(finding(newAnswer, j = null)), noController, null)
        assertEquals(2, noJudgement.size)
        assertFalse(noJudgement.any { it.contains("정해야 합니다") })
        val withWriter = ResponseWritePath.Judgement(setOf("ISSW_SURVEY_R"), listOf("sql_survey.xml:saveAnswer"), emptyList(), 1, emptyMap(), listOf("sql_survey.xml"))
        assertEquals(2, ClaimedFieldCheck.decisionBlock(listOf(finding(newAnswer, j = withWriter)), noController, null).size)
        // 출처가 정해지지 않은 항목(답 없음, 모름)이나 새 데이터가 아닌 답은 블록이 없다
        assertEquals(emptyList<String>(), ClaimedFieldCheck.decisionBlock(listOf(finding(null)), noController, null))
        assertEquals(emptyList<String>(), ClaimedFieldCheck.decisionBlock(listOf(finding("잘 모르겠음")), noController, null))
        assertEquals(emptyList<String>(), ClaimedFieldCheck.decisionBlock(listOf(finding("다른 테이블에서 조인해서 가져와")), noController, null))
    }

    @Test
    fun d3_theResolvedLineNoLongerCarriesTheOtherSystemTailWhenTheBlockSaysIt() {
        val f = finding(newAnswer)
        val line = ClaimedFieldCheck.renderResolved(f)
        assertTrue(line, line.contains("새 컬럼 추가는 구현 범위에 포함됩니다."))
        assertFalse(line, line.contains("다만") || line.contains("INSERT/UPDATE") || line.contains("다른 시스템"))
        // 판정 재료(테이블 이름)가 없는 이전 형식의 항목은 이 줄에 이전 문구를 그대로 잇는다
        val legacy = ClaimedFieldCheck.Finding(noun = "이메일", subject = "응답자", filesRead = 24, otherCites = emptyList(), answer = newAnswer, writeNotice = "읽은 범위에서 발견되지 않았습니다.")
        assertTrue(ClaimedFieldCheck.renderResolved(legacy).contains("다만 읽은 범위에서 발견되지 않았습니다."))
    }

    private class Bridge(val decision: RefinedConfirmationDecision?) : RefinedRequirementConfirmationBridge {
        override fun requestConfirmation(originalRequirement: String, refinedRequirement: String): RefinedConfirmationDecision? = decision
    }

    // 5분 동안 카드에 응답하지 않아 자동 진행해도 결과에는 같은 문장이 나온다(문장은 카드 상태가 아니라 분석 인텐트의 답에서 정해진다)
    @Test
    fun d4_theSentenceReachesTheResultEvenWhenTheCardTimedOut() {
        val intent = ClarifyIntent(
            originalRequirement = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요",
            refinedRequirement = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고 마스킹하여 반환한다.",
            constraints = listOf(IntentConstraint(kind = ConstraintKind.OTHER, value = "$label 출처: $newAnswer", rawStatement = newAnswer, evidence = newAnswer)),
            graphHash = "h"
        )
        for (d in listOf(RefinedConfirmationDecision.TimedOut, null, RefinedConfirmationDecision.Proceed)) {
            val analyzed = ArrayList<ClarifyIntent>()
            RefinedRequirementGate.handoff(intent, Bridge(d), {}, { analyzed.add(it) }, {}, decisionNote = decision)
            assertEquals(1, analyzed.size)
            // 분석 단계는 인텐트의 제약에서 답을 읽고(RequirementAnalysisPipeline과 같은 함수), 읽은 매퍼 판정을 붙여 결과 블록을 만든다
            val answers = ClaimedFieldCheck.answersOf(analyzed.single().constraints.map { it.value })
            val f = ClaimedFieldCheck.Finding(noun = "이메일", subject = "응답자", filesRead = 24, otherCites = emptyList())
                .withAnswer(answers[label]).withWriteJudgement(judgement(listOf("sql_survey.xml")))
            assertEquals(decision, ClaimedFieldCheck.decisionBlock(listOf(f), noController, null).first())
        }
        // 카드와 결과는 같은 함수가 만든 같은 문장이다
        val fromCard = ClaimedFieldCheck.decisionNotes(
            listOf(FieldFact(label, found = false, writeNotice = "x", writeTables = listOf("ISSW_SURVEY_R"), mappersRead = 1)), mapOf(label to newAnswer)
        ).single()
        assertEquals(fromCard, decision)
    }
}
