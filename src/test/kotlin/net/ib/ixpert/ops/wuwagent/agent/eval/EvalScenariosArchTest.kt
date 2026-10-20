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
        assertTrue("기존 시나리오 srType은 임의로 정하지 않는다", existing.all { it.srType == null })
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
    fun graphPathsCoverEveryGraphKeyUsed() {
        val keys = (arch + parse("/eval/scenarios.json")).map { it.graphKey }.toSet()
        assertTrue(EvalScenarios.graphPaths().keys.containsAll(keys))
        assertEquals("C:/Workspace/graph/project-graph-i/project-graph.json", EvalScenarios.graphPaths()["ism"])
        assertEquals("C:/Workspace/graph/project-graph-a/project-graph.json", EvalScenarios.graphPaths()["apc"])
    }
}
