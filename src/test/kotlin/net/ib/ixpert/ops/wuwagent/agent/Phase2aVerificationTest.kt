package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.ProjectSummaryFormatter
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase2aVerificationTest {

    @Test
    fun testVerification1_ProjectSummaryFormatterOutput() {
        // 실제 프로젝트 그래프 읽기
        val file = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        if (!file.exists()) {
            println("No project-graph.json found. Skipping Verification 1.")
            return
        }

        val jsonContent = file.readText(Charsets.UTF_8)
        val gson = Gson()
        val parsedGraph = gson.fromJson(jsonContent, ProjectGraph::class.java)
        
        val summary = ProjectSummaryFormatter.format(parsedGraph)
        println("=== Verification 1: ProjectSummaryFormatter Output ===")
        println(summary)
        
        assertTrue(summary.contains("- 프레임워크: Spring Boot / 총 66개 파일"))
        assertTrue(summary.contains("| 클래스명 | 타입 | 계층 | 위험도 | 파일경로 |"))
        // Check if PERSISTENCE comes before CONTROLLER
        val persistenceIdx = summary.indexOf("PERSISTENCE")
        val presentationIdx = summary.indexOf("PRESENTATION")
        if (persistenceIdx != -1 && presentationIdx != -1) {
            assertTrue("PERSISTENCE should appear before PRESENTATION", persistenceIdx < presentationIdx)
        }
    }

    @Test
    fun testVerification3_ParsingFallback() {
        val rawResponse = """
            요구사항을 분석했습니다.
            ExcelExportUtil.java 파일을 새로 만들고, SurveyServiceImpl.java를 수정해야 합니다.
            마크다운 테이블 형식을 지키지 않고 이렇게 자유 텍스트로만 말할 수도 있습니다.
        """.trimIndent()
        
        // Just to instantiate the pipeline, we don't actually call the LLM in this test
        val pipeline = RequirementAnalysisPipeline(net.ib.ixpert.ops.wuwagent.client.OllamaClient())
        val result = pipeline.parseResponse(rawResponse)
        
        println("=== Verification 3: Parsing Fallback Output ===")
        println("Target Files size: ${result.targetFiles.size}")
        println("Raw Response: \n${result.rawResponse}")
        
        assertEquals(0, result.targetFiles.size)
        assertEquals(rawResponse, result.rawResponse)
    }
}
