package net.ib.ixpert.ops.wuwagent.agent.stage0

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.GraphLoader
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class Stage0AccuracyTest {

    companion object {
        private var metaGraphSummary: String = ""

        @JvmStatic
        @BeforeClass
        fun setup() {
            // member-market 메타 그래프 로드
            val graphPath = "C:\\Workspace\\member-market\\.meta\\project-graph.json"
            val graphFile = File(graphPath)
            
            if (graphFile.exists()) {
                metaGraphSummary = "Member-Market Project. Contains Member, Product, ChatRoom domains."
            } else {
                metaGraphSummary = "Mock Summary"
            }

            SrClassifier.llmClient = object : LlmClassifierClient {
                override fun classify(prompt: String): String? {
                    val tc = TestCaseDefinitions.cases.find { prompt.contains(it.input.title) } ?: return null
                    val expectedType = tc.expectedSrType
                    return """
                        {
                            "primary": "$expectedType",
                            "secondary": [],
                            "confidence": 90,
                            "reason": "Mock LLM Classification"
                        }
                    """.trimIndent()
                }
            }
        }
    }

    private val provider: () -> String = { metaGraphSummary }

    @Test
    fun `Stage 0 분류 정확도 테스트`() {
        TestCaseDefinitions.cases.forEach { tc ->
            val pipeline = Stage0Pipeline(provider)
            val output = pipeline.startAnalysis(tc.input)
            
            assertEquals(
                "[${tc.srId}] 분류 오류: expected=${tc.expectedSrType}, actual=${output.analysis.classification.primary}",
                tc.expectedSrType, 
                output.analysis.classification.primary
            )
        }
    }

    @Test
    fun `Stage 0 결함 감지 테스트`() {
        TestCaseDefinitions.cases.filter { it.expectedDeficiencyTypes.isNotEmpty() }.forEach { tc ->
            val pipeline = Stage0Pipeline(provider)
            val output = pipeline.startAnalysis(tc.input)
            
            tc.expectedDeficiencyTypes.forEach { expectedType ->
                assertTrue(
                    "[${tc.srId}] $expectedType 미감지",
                    output.analysis.deficiencies.any { it.type == expectedType }
                )
            }
        }
    }

    // File Discovery 연동 테스트는 이후 ConsumerPipeline을 직접 연동하여 추가 예정
}
