package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-49 아키텍처 유형별 평가 SR(12개)이 docs/eval_sr_drafts.md와 같은지 고정한다.
 * 합계: SR 12, required 27, mustNotAppear 4, uncertain("?") 10, holdout 4(SR-03, 06, 09, 10).
 */
class EvalScenariosArchTest {

    private fun parse(res: String): List<EvalScenario> =
        Gson().fromJson(javaClass.getResource(res)!!.readText(Charsets.UTF_8), EvalScenarioFile::class.java).scenarios!!

    private val arch = parse("/eval/arch_scenarios.json")

    @Test
    fun totalsMatchTheDraftDocument() {
        assertEquals(12, arch.size)
        assertEquals(27, arch.sumOf { s -> s.gtOrEmpty().count { it.countsForRecall() } })
        assertEquals(4, arch.sumOf { it.mustNotAppearOrEmpty().size })
        assertEquals(10, arch.sumOf { s -> s.gtOrEmpty().count { it.isUncertain() } })
        assertEquals(listOf("SR-03", "SR-06", "SR-09", "SR-10"), arch.filter { it.isHoldout() }.map { it.id })
        assertTrue("모든 정답 행이 그래프에 있음", arch.all { s -> (s.gtOrEmpty() + s.mustNotAppearOrEmpty()).all { it.isInGraph() } })
        assertTrue(arch.all { it.group == "arch" && it.gtMatch == "harness-contains" })
    }

    @Test
    fun eachScenarioMatchesItsBlockInTheDocument() {
        val doc = File("docs/eval_sr_drafts.md").readText(Charsets.UTF_8).lines()
        val starts = doc.indices.filter { doc[it].startsWith("## SR-") }
        assertEquals(12, starts.size)
        for ((k, st) in starts.withIndex()) {
            val block = doc.subList(st, if (k + 1 < starts.size) starts[k + 1] else doc.size)
            val id = block.first().substringAfter("## ").substringBefore(" ·")
            val sc = arch.first { it.id == id }
            assertEquals("$id SR 원문", block.first { it.startsWith("**SR 원문:**") }.removePrefix("**SR 원문:**").trim(), sc.sr)
            val rows = block.filter { it.startsWith("|") && !it.startsWith("|---") && !it.startsWith("| 파일명") }
                .map { l -> l.split("|").map { it.trim() }.drop(1).dropLast(1) }
                .filter { it.size >= 3 && it[1] in listOf("required", "reference", "mustNotAppear") }
            assertEquals("$id required", rows.count { it[1] == "required" }, sc.gtOrEmpty().count { !it.isReference() })
            assertEquals("$id reference", rows.count { it[1] == "reference" }, sc.gtOrEmpty().count { it.isReference() })
            assertEquals("$id mustNotAppear", rows.count { it[1] == "mustNotAppear" }, sc.mustNotAppearOrEmpty().size)
            assertEquals("$id uncertain", rows.count { it[0].startsWith("?") || it[2].startsWith("?") }, sc.gtOrEmpty().count { it.isUncertain() })
            assertEquals("$id holdout", block.first().contains("**holdout**") && !block.first().contains("holdout 아님"), sc.isHoldout())
            val script = block.first { it.startsWith("- **대본") }.substringAfter(":**").trim().removeSurrounding("\"")
            assertEquals("$id 대본", listOf(script), sc.scriptOrEmpty())
        }
    }

    @Test
    fun typesAreSetForNewScenariosAndArchTypeForExistingOnes() {
        assertTrue(arch.all { it.archType != null && it.srType != null })
        assertEquals(setOf("SPRING_MVC_MYBATIS", "ANYFRAME_AP", "SPRING_BOOT_JPA"), arch.map { it.archType }.toSet())
        assertEquals(setOf("기존 기능 수정", "기존 패턴 신규 추가", "제외 지시", "모호"), arch.map { it.srType }.toSet())
        val existing = parse("/eval/scenarios.json")
        assertTrue("기존 시나리오 archType", existing.all { it.archType != null })
        // 사용자 확정(2026-10-20): D4와 D2는 판단 여지가 있어 결정을 그대로 고정한다
        assertEquals(
            mapOf(
                "D1" to "기존 패턴 신규 추가", "D2" to "기존 기능 수정", "D3" to "기존 패턴 신규 추가", "D4" to "모호",
                "D5" to "제외 지시", "D5E" to "제외 지시", "D5N" to "제외 지시", "S1" to "기존 기능 수정", "S2" to "기존 패턴 신규 추가", "S3" to "제외 지시"
            ),
            existing.associate { it.id to it.srType }
        )
        assertEquals("SPRING_BOOT_JPA", existing.first { it.id == "S1" }.archType)
        assertEquals("ANYFRAME_AP", existing.first { it.id == "D2" }.archType)
    }

    @Test
    fun holdoutIsExcludedByDefaultAndIncludedOnRequest() {
        val keep = System.getProperty("eval.includeHoldout")
        val keepOnly = System.getProperty("eval.only")
        try {
            System.clearProperty("eval.only")
            System.clearProperty("eval.includeHoldout")
            val byDefault = EvalScenarios.loadAll().map { it.id }
            assertEquals(listOf("D1", "D2", "D3", "D4", "D5", "S1", "S2", "S3", "SR-01", "SR-02", "SR-04", "SR-05", "SR-07", "SR-08", "SR-11", "SR-12"), byDefault)
            assertFalse(byDefault.any { it in listOf("SR-03", "SR-06", "SR-09", "SR-10") })
            System.setProperty("eval.includeHoldout", "true")
            val withHoldout = EvalScenarios.loadAll().map { it.id }
            assertEquals(20, withHoldout.size)
            assertTrue(withHoldout.containsAll(listOf("SR-03", "SR-06", "SR-09", "SR-10")))
        } finally {
            if (keep == null) System.clearProperty("eval.includeHoldout") else System.setProperty("eval.includeHoldout", keep)
            if (keepOnly != null) System.setProperty("eval.only", keepOnly)
        }
    }

    @Test
    fun evalFirstMovesTheChosenScenariosToTheFrontAndKeepsTheRestInOrder() {
        val keepFirst = System.getProperty("eval.first")
        val keepOnly = System.getProperty("eval.only")
        val keepHoldout = System.getProperty("eval.includeHoldout")
        try {
            System.clearProperty("eval.only")
            System.clearProperty("eval.includeHoldout")
            System.clearProperty("eval.first")
            val base = EvalScenarios.loadAll().map { it.id }
            System.setProperty("eval.first", "SR-07,SR-11")
            val moved = EvalScenarios.loadAll().map { it.id }
            assertEquals(listOf("SR-07", "SR-11") + base.filter { it != "SR-07" && it != "SR-11" }, moved)
            assertEquals("목록 구성은 바뀌지 않는다", base.sorted(), moved.sorted())
            // 목록에 없는 ID(holdout 제외 상태의 SR-03)는 무시되고, 지정한 순서가 유지된다
            System.setProperty("eval.first", "SR-03,S1,D1")
            assertEquals(listOf("S1", "D1") + base.filter { it != "S1" && it != "D1" }, EvalScenarios.loadAll().map { it.id })
            // eval.only와 함께 쓰면 선택된 것 안에서만 앞으로 옮긴다
            System.setProperty("eval.only", "D1,D2,S1")
            System.setProperty("eval.first", "S1")
            assertEquals(listOf("S1", "D1", "D2"), EvalScenarios.loadAll().map { it.id })
        } finally {
            fun restore(k: String, v: String?) { if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
            restore("eval.first", keepFirst); restore("eval.only", keepOnly); restore("eval.includeHoldout", keepHoldout)
        }
    }

    @Test
    fun graphPathsCoverEveryGraphKeyUsed() {
        val keys = (arch + parse("/eval/scenarios.json")).map { it.graphKey }.toSet()
        assertTrue(EvalScenarios.graphPaths().keys.containsAll(keys))
        assertEquals("C:/Workspace/graph/project-graph-i/project-graph.json", EvalScenarios.graphPaths()["ism"])
        assertEquals("C:/Workspace/graph/project-graph-a/project-graph.json", EvalScenarios.graphPaths()["apc"])
    }
}
