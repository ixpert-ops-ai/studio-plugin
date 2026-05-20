package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.agent.ImplementationPipeline
import net.ib.ixpert.ops.wuwagent.agent.ImplementationPipelineUtils
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import org.junit.Assert.*
import org.junit.Test

class ImplementationPipelineUtilsTest {

    // ═══════════════════════════════════════════════════════════════
    // Strategy Decision
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testStrategyDto() {
        val target = TargetFileSpec(1, "src/main/java/com/dto/SurveyDto.java", "modify", "Add field")
        assertEquals(
            ImplementationPipeline.EditStrategy.DTO_ONLY,
            ImplementationPipelineUtils.decideStrategy(target, 750)
        )
    }

    @Test
    fun testStrategyVo() {
        val target = TargetFileSpec(1, "src/main/java/com/vo/UserVo.java", "modify", "Add field")
        assertEquals(
            ImplementationPipeline.EditStrategy.DTO_ONLY,
            ImplementationPipelineUtils.decideStrategy(target, 200)
        )
    }

    @Test
    fun testStrategyCreate() {
        val target = TargetFileSpec(1, "src/main/java/com/service/NewService.java", "create", "New")
        assertEquals(
            ImplementationPipeline.EditStrategy.WHOLE,
            ImplementationPipelineUtils.decideStrategy(target, 0)
        )
    }

    @Test
    fun testStrategySmall() {
        val target = TargetFileSpec(1, "src/main/java/com/service/SmallService.java", "modify", "Modify")
        assertEquals(
            ImplementationPipeline.EditStrategy.WHOLE,
            ImplementationPipelineUtils.decideStrategy(target, 100)
        )
    }

    @Test
    fun testStrategyMedium() {
        val target = TargetFileSpec(1, "src/main/java/com/service/BigServiceImpl.java", "modify", "Add method")
        assertEquals(
            ImplementationPipeline.EditStrategy.ACTION_BASED,
            ImplementationPipelineUtils.decideStrategy(target, 300)
        )
    }

    @Test
    fun testStrategyInterface() {
        val target = TargetFileSpec(1, "src/main/java/com/dao/SurveyDao.java", "modify", "Add declaration")
        assertEquals(
            ImplementationPipeline.EditStrategy.ACTION_BASED,
            ImplementationPipelineUtils.decideStrategy(target, 50, originalSourceContainsInterface = true)
        )
    }

    @Test
    fun testStrategyBoundary150() {
        val target = TargetFileSpec(1, "src/main/java/com/service/Svc.java", "modify", "test")
        assertEquals(
            ImplementationPipeline.EditStrategy.WHOLE,
            ImplementationPipelineUtils.decideStrategy(target, 150)
        )
    }

    @Test
    fun testStrategyBoundary151() {
        val target = TargetFileSpec(1, "src/main/java/com/service/Svc.java", "modify", "test")
        assertEquals(
            ImplementationPipeline.EditStrategy.ACTION_BASED,
            ImplementationPipelineUtils.decideStrategy(target, 151)
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Layer Weight
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testLayerWeights() {
        assertEquals(1, ImplementationPipelineUtils.getLayerWeight("src/main/java/com/dao/UserDao.java"))
        assertEquals(1, ImplementationPipelineUtils.getLayerWeight("src/main/java/com/repository/UserRepo.java"))
        assertEquals(1, ImplementationPipelineUtils.getLayerWeight("src/main/java/com/entity/User.java"))
        assertEquals(2, ImplementationPipelineUtils.getLayerWeight("src/main/java/com/service/UserService.java"))
        assertEquals(3, ImplementationPipelineUtils.getLayerWeight("src/main/java/com/controller/UserCtrl.java"))
        assertEquals(4, ImplementationPipelineUtils.getLayerWeight("src/main/java/com/dto/UserDto.java"))
        assertEquals(5, ImplementationPipelineUtils.getLayerWeight("src/main/java/com/util/StringUtil.java"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Max Length
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testMaxLengthCore() {
        val result = ImplementationPipelineUtils.calculateWholeMaxLength("src/main/java/com/dao/X.java", 100)
        assertEquals((15000 * 1.3).toInt(), result)
    }

    // ═══════════════════════════════════════════════════════════════
    // Field Extraction
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testExtractFields() {
        val source = """
            public class SurveyDto {
                private String s_idx;
                private String s_code;
                private int count;
            }
        """.trimIndent()

        val fields = ImplementationPipelineUtils.extractFieldNames(source)
        assertEquals(3, fields.size)
        assertTrue(fields.containsAll(listOf("s_idx", "s_code", "count")))
    }

    // ═══════════════════════════════════════════════════════════════
    // Target Method Names
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testExtractMethodNames() {
        val methods = ImplementationPipelineUtils.extractTargetMethodNames(
            "CSV download survey results",
            "Add selectSurveyResultForCsv method implementation"
        )
        assertTrue(methods.any { it.contains("selectSurveyResultForCsv") })
    }

    // ═══════════════════════════════════════════════════════════════
    // Truncation
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testTruncationSmall() {
        val source = (1..100).joinToString("\n") { "// line $it" }
        val result = ImplementationPipelineUtils.truncateForLargeFile(source, listOf("test"), 25)
        assertEquals(source, result)
    }

    // ═══════════════════════════════════════════════════════════════
    // DTO Merge
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testDtoMergeAddField() {
        val original = """
            public class UserDto {
                private String name;
            }
        """.trimIndent()

        val snippet = """
            [MODIFIED_SIGNATURES]
            1. `String getCsvPath()`

            private String csvPath;

            public String getCsvPath() {
                return this.csvPath;
            }
        """.trimIndent()

        val result = ImplementationPipelineUtils.mergeDtoSnippet(original, snippet)
        assertTrue(result.contains("private String csvPath;"))
        assertTrue(result.contains("getCsvPath"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Post-Processing: Bypass Logic
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun testBypassRemoval() {
        val code = "if (ok) {\n    run();\n} else {\n    bypass();\n}"
        val result = ImplementationPipelineUtils.postProcessBypassLogic(code)
        assertFalse(result.contains("bypass"))
    }
}
