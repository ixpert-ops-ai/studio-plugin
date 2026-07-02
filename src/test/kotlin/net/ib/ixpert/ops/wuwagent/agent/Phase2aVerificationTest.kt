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

}
