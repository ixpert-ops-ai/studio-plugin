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
요구사항 요약
설문 결과를 Excel 파일로 다운로드할 수 있는 기능을 추가해야 합니다. 이 기능은 Survey 관련 기능과 연동되어야 하며, Controller, Service, DAO 계층에서 필요한 수정이나 추가가 필요합니다.

수정 대상 파일
순서	파일 경로	유형	작업 내용
1	src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java	수정	설문 결과 데이터를 조회하는 메서드 추가 또는 수정
2	src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java	수정	설문 결과를 Excel로 변환하는 로직 추가

작업 시 주의사항
SurveyDaoImpl과 SurveyServiceImpl은 데이터베이스 연동 및 로직 변경으로 인해 ChangeRisk가 HIGH입니다. ⚠️
        """.trimIndent()
        
        // Just to instantiate the pipeline, we don't actually call the LLM in this test
        val pipeline = RequirementAnalysisPipeline(net.ib.ixpert.ops.wuwagent.client.OllamaClient())
        val result = pipeline.parseResponse(rawResponse)
        
        println("=== Verification 3: Parsing Fallback Output ===")
        println("Target Files size: ${result.targetFiles.size}")
        println("Raw Response: \n${result.rawResponse}")
        
        assertTrue(result.targetFiles.size > 0)
    }
}
