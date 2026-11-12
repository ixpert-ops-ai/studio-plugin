package net.ib.ixpert.ops.wuwagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-85 후속 5: "구현"을 G3·G4의 수정 낱말에 더한다(ReasonGuard.IMPLEMENT_VERB). 사례는 11-12 13:30 IDE 결과(src/test/resources/b85/golden_1112_1330.md, 저장된 채팅의 원문):
 * 선택(경로 통과) 행 SurveyDaoImpl의 LLM 사유가 "이메일 컬럼 매핑 및 쿼리 구현"이라 추가·수정 낱말이 없어 "(규칙 판정: 수정 불필요)"가 빠졌다.
 * 이미 있는 것·하지 않겠다는 쓰임("구현체", "구현되", "구현 불필요", "구현하지 않")은 동사로 세지 않는다. 결정 블록(📌)과 ℹ️ 줄에는 G4를 쓰지 않는다. IDE·LLM·로컬 그래프 없음.
 */
class ImplementVerbB85Test {
    private val golden = File("src/test/resources/b85/golden_1112_1330.md").readText(Charsets.UTF_8).replace("\r\n", "\n")
    private val judgement = ResponseWritePath.Judgement(setOf("ISSW_SURVEY_R"), emptyList(), emptyList(), 1, emptyMap(), listOf("sql_survey.xml"))

    private class Row(val n: Int, val path: String, val role: PathRoles.Role, val shown: String, val raw: String)

    private val G2_MARK = " (읽은 범위의 매퍼 1개에 응답 테이블(ISSW_SURVEY_R)로 값을 쓰는 쿼리 없음, 저장 주체 미확인)"
    private val G3_MARK = " (규칙 판정: 수정 불필요)"

    /** 저장된 결과 표의 행: 화면에 보인 "LLM 사유:" 문구(shown)와, 거기서 판정 표시를 걷어 낸 LLM의 원래 사유(raw). */
    private val rows: List<Row> = golden.lines().filter { Regex("^\\| \\d+ \\|").containsMatchIn(it) }.map { line ->
        val c = line.split("|").map { it.trim() }
        val reason = c[4]
        val det = reason.substringBefore(" / LLM 사유: ")
        val shown = reason.substringAfter(" / LLM 사유: ")
        val raw = shown.replace(Regex(" \\(읽은 범위의 매퍼.*?저장 주체 미확인\\)"), "").replace(Regex(" \\(규칙 판정: [^)]*\\)"), "")
        Row(c[1].toInt(), c[2], if (det.startsWith("**선택")) PathRoles.Role.OPTIONAL else PathRoles.Role.REQUIRED, shown, raw)
    }

    private fun annotate(r: Row) = ReasonGuard.annotateRow(r.raw, ReasonGuard.Context(judgement, r.role))

    @Test
    fun a1_theRecordedRowsComeBackTheSameExceptTheSurveyDaoImplRowThatNowGetsTheMark() {
        assertEquals(5, rows.size)
        assertEquals(listOf(PathRoles.Role.REQUIRED, PathRoles.Role.REQUIRED, PathRoles.Role.REQUIRED, PathRoles.Role.OPTIONAL, PathRoles.Role.OPTIONAL), rows.map { it.role })
        // 판정 표시를 걷어 낸 원래 사유
        assertEquals("이메일 컬럼 매핑 및 쿼리 구현", rows[3].raw)
        assertEquals("이메일 저장/조회 메서드 시그니처 추가", rows[4].raw)
        for (r in rows) {
            if (r.n == 4) continue
            assertEquals("행 ${r.n} ${r.path.substringAfterLast('/')}: 바이트가 같아야 한다", r.shown, annotate(r))
        }
        // 11-12에는 없던 표시: SurveyDaoImpl 행("확인 / 선택(경로 통과)")의 사유가 "구현"만 말해도 이제 규칙 판정이 붙는다
        assertFalse("저장된 화면에는 표시가 없었다", rows[3].shown.contains("규칙 판정"))
        assertEquals(rows[3].shown + G3_MARK, annotate(rows[3]))
        // SurveyDao 행은 11-12에도 표시가 있었고 지금도 같다(저장 단정 표시 → 규칙 판정 순서)
        assertEquals(rows[4].raw + G2_MARK + G3_MARK, rows[4].shown)
    }

    @Test
    fun a2_theSummaryLinesOfTheRecordedResultAreUnchangedByG4() {
        val summary = golden.lines().filter { Regex("^\\d\\) ").containsMatchIn(it) }
        assertEquals(2, summary.size)
        val optional = rows.filter { it.role == PathRoles.Role.OPTIONAL }.map { it.path.substringAfterLast('/').substringBeforeLast('.') }
        assertEquals(listOf("SurveyDaoImpl", "SurveyDao"), optional)
        // 1번 문장은 선택 행 이름이 나오지만 수정 낱말이 없고("흐름이 완성됨"), 2번 문장의 이름은 선택 행이 아니다(SurveyService는 제외됨). 동사를 더해도 둘 다 그대로다.
        // "완성됨"(요약이 표의 '선택' 행을 완성된 흐름에 넣은 불일치)은 동사로 잡지 않는다: 보류 항목 (a)의 사례
        for (s in summary) {
            assertEquals(s, ReasonGuard.annotateText(s, ReasonGuard.Context(judgement, null, optional)))
            assertEquals(s, ReasonGuard.annotateText(s, ReasonGuard.Context(null, null, optional)))
        }
    }

    @Test
    fun a3_theDecisionBlockAndTheInfoLineAreNotGuardedByG4() {
        val info = golden.lines().single { it.startsWith("> ℹ") }
        val decision = golden.lines().filter { it.startsWith("> - ") }
        assertEquals(3, decision.size)
        // 저장된 화면의 두 곳에는 수정 낱말(추가·구현)이 있고 규칙 판정 표시는 없다
        assertTrue(info, info.contains("추가") && info.contains("구현"))
        assertTrue(decision.joinToString("\n"), decision.any { it.contains("추가") } && decision.any { it.contains("구현") })
        assertFalse(info.contains("규칙 판정"))
        assertTrue(decision.none { it.contains("규칙 판정") })
        // 대조: 같은 줄에 선택 행 이름과 수정 낱말을 더해 요약 검사(G4)에 넣으면 표시가 붙는다 → 표시가 없는 것은 그 줄이 G4를 거치지 않기 때문이다
        val optional = listOf("SurveyDaoImpl", "SurveyDao")
        assertTrue(ReasonGuard.annotateText("$info SurveyDao 쿼리를 구현한다.", ReasonGuard.Context(null, null, optional)).endsWith("(SurveyDao 규칙 판정: 수정 불필요)"))
        // 구조: ℹ️ 줄과 📌 줄은 guardedText를 거치지 않고 만들어진다(요약 문단만 거친다)
        val pipeline = File("src/main/kotlin/net/ib/ixpert/ops/wuwagent/agent/RequirementAnalysisPipeline.kt").readText(Charsets.UTF_8)
        val infoSrc = pipeline.lines().single { it.contains("claimLines.add(\"> ℹ") && it.contains("ClaimedFieldCheck.renderResolved(f)") }
        assertFalse(infoSrc, infoSrc.contains("guardedText"))
        val decisionSrc = pipeline.lines().single { it.contains("claimLines.add(\"> 📌 **구현 전 결정할 것**") }
        assertFalse(decisionSrc, decisionSrc.contains("guardedText"))
        assertTrue(pipeline.lines().single { it.contains("appendLine(guardedText(citation.text") }.isNotBlank())
    }

    @Test
    fun a4_implementIsAModifyVerbForRowsAndSummary() {
        val optionalRow = ReasonGuard.Context(null, PathRoles.Role.OPTIONAL)
        assertEquals("쿼리 구현$G3_MARK", ReasonGuard.annotateRow("쿼리 구현", optionalRow))
        assertEquals("메서드를 구현해야 한다$G3_MARK", ReasonGuard.annotateRow("메서드를 구현해야 한다", optionalRow))
        // 필수 행에는 G3를 쓰지 않는다
        assertEquals("쿼리 구현", ReasonGuard.annotateRow("쿼리 구현", ReasonGuard.Context(null, PathRoles.Role.REQUIRED)))
        // 요약(G4): 선택 행 이름 + 구현
        val ctx = ReasonGuard.Context(null, null, listOf("SurveyDaoImpl"))
        assertEquals("SurveyDaoImpl에서 쿼리를 구현한다. (SurveyDaoImpl 규칙 판정: 수정 불필요)", ReasonGuard.annotateText("SurveyDaoImpl에서 쿼리를 구현한다.", ctx))
    }

    // "구현 필요 없", "구현할 필요 없"은 낱말 자체에서 뺐다. 요약(G4)의 부정어 판정은 이름 앞뒤 25자라서, 이름과 먼 곳의 "구현 필요 없다"는 부정어 판정으로는 못 잡는다.
    @Test
    fun a6_noNeedToImplementIsNotAVerbEvenFarFromTheName() {
        val filler = "이 요청은 조회 결과를 그대로 돌려주는 경로라서 기존 코드가 이미 같은 값을 반환하고 있고 별도로 손볼 부분은 없으며"
        val ctx = ReasonGuard.Context(null, null, listOf("SurveyDaoImpl"))
        for (tail in listOf("구현 필요 없다", "구현할 필요 없다")) {
            val s = "SurveyDaoImpl은 $filler 새 쿼리를 $tail."
            // 이름과 부정어("없")가 25자보다 멀다(부정어 판정만으로는 표시가 붙는다는 전제)
            assertTrue(tail, s.indexOf("없다") - (s.indexOf("SurveyDaoImpl") + "SurveyDaoImpl".length) > 25)
            assertEquals(s, ReasonGuard.annotateText(s, ctx))
        }
    }

    @Test
    fun a5_negatedOrNonVerbUsesOfImplementChangeNothing() {
        val optionalRow = ReasonGuard.Context(null, PathRoles.Role.OPTIONAL)
        val ctx = ReasonGuard.Context(null, null, listOf("SurveyDaoImpl", "SurveyDao"))
        val same = listOf(
            "구현 불필요", "구현불필요", "구현하지 않는다", "구현하지 않아도 된다", "구현하지 말 것", "구현 안 함", "구현 안 해도 된다",
            "구현체를 그대로 쓴다", "이미 구현되어 있다", "구현된 메서드를 호출한다", "구현돼 있어 그대로 쓴다",
            "구현이 필요 없다" // 부정어(없)가 앞뒤 25자 안에 있다
        )
        for (s in same) {
            assertEquals("행: $s", s, ReasonGuard.annotateRow(s, optionalRow))
            assertEquals("요약: SurveyDaoImpl $s", "SurveyDaoImpl $s", ReasonGuard.annotateText("SurveyDaoImpl $s", ctx))
        }
        // 대조: 같은 자리에서 동사로 쓰이면 표시가 붙는다(위 시험이 낱말을 빼서 통과한 것이 아님을 보인다)
        assertTrue(ReasonGuard.annotateRow("쿼리 구현 필요", optionalRow).endsWith("(규칙 판정: 수정 불필요)"))
        // 이미 있는 판정 표시와 같이 있어도 두 번 적용해도 같다(멱등)
        val once = ReasonGuard.annotateRow("쿼리 구현", optionalRow)
        assertEquals(once, ReasonGuard.annotateRow(once, optionalRow))
    }
}
