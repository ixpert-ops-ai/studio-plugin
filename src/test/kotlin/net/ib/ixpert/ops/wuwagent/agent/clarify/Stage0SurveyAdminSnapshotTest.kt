package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Stage 0 Live E2E 스냅샷 테스트 (survey_admin 실물 메타그래프 대상).
 */
class Stage0SurveyAdminSnapshotTest {

    private fun loadSurveyAdminGraph(): ProjectGraph? {
        val path = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        if (!path.exists()) return null
        return Gson().fromJson(path.readText(), ProjectGraph::class.java)
    }

    @Test
    fun testStage0OnSurveyAdmin() {
        val graph = loadSurveyAdminGraph() ?: return
        val scanner = Stage0GraphScanner(
            graph = graph,
            minSpecificityScore = 1.0,
            proposalBudget = 10,
            localDomainOverrides = mapOf("설문" to setOf("survey", "poll"))
        )
        val engine = Stage0ClarificationEngine(scanner, graph)

        val originalReq = "설문 발송 채널에 브랜드메시지 추가"
        val turn0 = engine.initSession(originalReq)

        // 1. 후보군 실측 검증
        val candidatePaths = turn0.state.items.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertTrue("survey_list.jsp should be discovered via test_send_type inputField!", candidatePaths.any { it.contains("survey_list.jsp") })
        assertTrue("survey.list.js should be discovered via View-Script URL pairing!", candidatePaths.any { it.contains("survey.list.js") })
        assertTrue("survey_write.jsp should be discovered!", candidatePaths.any { it.contains("survey_write.jsp") })
        assertFalse("Unrelated noise (login.js, authority.manage.js) must be filtered out!", candidatePaths.any { it.contains("login.js") || it.contains("authority.manage.js") })

        // 2. 개방형 질문 트리거 확인
        assertNotNull("Open question should be triggered for external API gap!", turn0.openQuestion)

        // 3. 턴 진행 및 동결 보호 검증
        val surveyListJspItem = turn0.state.items.find { (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("survey_list.jsp") == true }
        val updates = if (surveyListJspItem != null) mapOf(surveyListJspItem.id to Verdict.CONFIRMED) else emptyMap()

        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                verdictUpdates = updates,
                userStatement = "Bizgo REST API를 통해 브랜드메시지를 연동합니다."
            )
        )

        // 4. 전이 계약 검증
        val contract = engine.transitionToStage1(turn1.state)
        assertTrue(contract.trustedExistingRefs.any { it.filePath.contains("survey_list.jsp") })
        assertEquals(1, contract.newCreations.size)
        assertTrue(contract.enrichedRequirementText.contains("Bizgo REST API"))
    }
}
