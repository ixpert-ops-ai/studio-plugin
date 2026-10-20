package net.ib.ixpert.ops.wuwagent.agent.eval

import org.junit.Assert.assertEquals
import org.junit.Test

/** required GT 네 갈래(유지 / Stage 3 제거 / 후보 밖(제외) / 후보 밖(손실))와 후보 복원, 합계 = gtTotal. */
class GtSplitTest {

    private fun gt(name: String) = EvalGt(name = name)
    private val matches = { g: EvalGt, p: String -> p.contains(g.name ?: "", ignoreCase = true) }

    private val stage3 = """
        ## 요구사항
        무언가를 한다

        ## 후보 파일 목록
        - [1] src/a/AService.java
        --- 정보 ---
        - Score: 80
        -------------
        - [2] src/b/BMapper.xml
        --- 정보 ---
        - Score: 60
        -------------
        - [3] src/c/CNew.java
        --- 정보 ---
        - (신규 제안)
        -------------
    """.trimIndent()

    @Test
    fun candidatePathsAreRestoredAsAUnionOfAllStage3Calls() {
        val second = "- [1] src/d/DDto.java\n--- 정보 ---\nx\n-------------"
        assertEquals(
            setOf("src/a/AService.java", "src/b/BMapper.xml", "src/c/CNew.java", "src/d/DDto.java"),
            GtSplit.candidatePaths(listOf(stage3, second))
        )
    }

    @Test
    fun splitsIntoFourWaysAndTheSumEqualsTheRequiredTotal() {
        val required = listOf(gt("AService.java"), gt("BMapper.xml"), gt("EExcluded.java"), gt("FLost.java"), gt("GKept.java"))
        val kept = listOf(required[4])
        val s = GtSplit.classify(required, kept, true, GtSplit.candidatePaths(listOf(stage3)), listOf("src/e/EExcluded.java"), matches)
        assertEquals(listOf("GKept.java"), s.kept)
        assertEquals(listOf("AService.java", "BMapper.xml"), s.stage3Removed)
        assertEquals(listOf("EExcluded.java"), s.outsideExcluded)
        assertEquals(listOf("FLost.java"), s.outsideLost)
        assertEquals(emptyList<String>(), s.unobserved)
        assertEquals(required.size, s.total())
    }

    @Test
    fun withoutStage3ObservationTheRestIsUnobservedNotRemovedOrLost() {
        val required = listOf(gt("AService.java"), gt("EExcluded.java"), gt("GKept.java"))
        val s = GtSplit.classify(required, listOf(required[2]), false, emptySet(), listOf("src/e/EExcluded.java"), matches)
        assertEquals(listOf("GKept.java"), s.kept)
        assertEquals(emptyList<String>(), s.stage3Removed)
        assertEquals(listOf("EExcluded.java"), s.outsideExcluded)
        assertEquals(emptyList<String>(), s.outsideLost)
        assertEquals(listOf("AService.java"), s.unobserved)
        assertEquals(required.size, s.total())
    }
}
