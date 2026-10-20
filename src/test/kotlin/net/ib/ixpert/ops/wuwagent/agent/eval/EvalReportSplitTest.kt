package net.ib.ixpert.ops.wuwagent.agent.eval

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 보고서 머리 합계 줄, 시나리오 표의 새 열, 유형별 합계, 턴별 질문 블록, 합계 불일치 표시가 나오는지 가짜 결과로 확인한다(LLM 호출 없음). */
class EvalReportSplitTest {

    private fun scenario(id: String, arch: String, sr: String?, holdout: Boolean = false) = EvalScenario(
        id = id, group = "arch", graphKey = "survey_admin", sr = "SR $id", script = listOf("답변"),
        gtMatch = "harness-contains", gt = listOf(EvalGt(name = "/A.java"), EvalGt(name = "/B.java", uncertain = true)),
        archType = arch, srType = sr, holdout = holdout, expectedQuestions = "무엇을 제한할까요?"
    )

    private fun run(id: String, kept: List<String>, removed: List<String>, excl: List<String>, lost: List<String>) = RunResult(
        scenarioId = id, run = 1, ok = true, error = null, totalMs = 1000, rawRefined = "r", refinedRequirement = "r",
        expectedIdentifiers = emptyList(), missingBeforeFix = emptyList(), subtractedIdentifiers = emptyList(), unresolved = emptyList(),
        delivered = emptyList(), stage3Calls = 1, stage3Observed = true, excludedFiles = emptyList(), expectedExcludedConfirmed = null,
        excludedInTargets = emptyList(), excludedNameInStage3Sections = emptyList(), excludedNameInStage3Body = emptyList(),
        turns = TurnStats(
            1, 1, 1, false, true, 0, fallbackAnswersUsed = 1, engineQuestionTexts = listOf("첫 질문"),
            utterances = listOf(UtteranceLog("발화1", "안내", "다음 질문", 0, 0, 1, 1, false, "{}"), UtteranceLog("대체", "안내", null, 0, 0, 1, 1, true, "{}")),
            initialQuestion = "첫 질문"
        ),
        targets = emptyList(), gtTotal = 2, gtHit30 = kept.size, gtHit10 = kept.size, gtMissing = emptyList(), referenceTotal = 0,
        referenceIn10 = emptyList(), referenceIn30 = emptyList(), graphMissingGt = emptyList(), fp30 = 0,
        llmClarify = LlmStats(0, 0, 0, 0, 0, 0, 0), llmAnalyze = LlmStats(0, 0, 0, 0, 0, 0, 0), callMs = emptyList(),
        gtKept = kept, gtStage3Removed = removed, gtOutsideExcluded = excl, gtOutsideLost = lost,
        gtTotalCertain = 1, gtHit30Certain = if ("/A.java" in kept) 1 else 0
    )

    @Test
    fun reportShowsTheFourWaySplitTypeSectionsAndTurnQuestions() {
        val summaries = listOf(
            ScenarioSummary(scenario("X1", "SPRING_MVC_MYBATIS", "기존 기능 수정"), listOf(run("X1", listOf("/A.java"), listOf("/B.java"), emptyList(), emptyList()))),
            ScenarioSummary(scenario("X2", "SPRING_BOOT_JPA", null), listOf(run("X2", emptyList(), emptyList(), listOf("/A.java"), listOf("/B.java")))),
            ScenarioSummary(scenario("X3", "ANYFRAME_AP", "모호"), listOf(run("X3", listOf("/A.java"), emptyList(), emptyList(), emptyList())))   // 합이 1이라 불일치
        )
        val dir = Files.createTempDirectory("evalreport").toFile()
        val (md, _) = EvalReport.write(dir, ServerCheck("s", "m", 200, emptyList(), true, ""), summaries, 1, 300, "m", 1000, emptyList())
        val text = md.readText(Charsets.UTF_8)
        assertTrue(text, text.contains("holdout 시나리오: 제외(기본"))
        assertTrue(text, text.contains("required GT 전체 합(진행 판단용, 시나리오 3개 x 실행): 6건 중 유지 2, Stage 3 제거 1, 후보 밖(제외 지시) 1, 후보 밖(Stage 1~2 손실) 1."))
        assertTrue(text, text.contains("**합계 불일치 1건**"))
        assertTrue(text, text.contains("Stage 3 제거가 나온 시나리오 1/3"))
        assertTrue(text, text.contains("- SPRING_BOOT_MYBATIS: 미측정 유형"))
        assertTrue(text, text.contains("- SPRING_BOOT_JDBC: 미측정 유형") && text.contains("- CUSTOM: 미측정 유형"))
        assertTrue(text, text.contains("| (미지정) | X2 |"))
        assertTrue(text, text.contains("초기 질문 \"첫 질문\""))
        assertTrue(text, text.contains("대체 답변 \"대체\" → 질문 \"(없음)\""))
        assertTrue(text, text.contains("기대 질문(참고, 자동 판정 안 함): 무엇을 제한할까요?"))
        assertTrue(text, text.contains("SPRING_MVC_MYBATIS / 기존 기능 수정"))
    }
}
