package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Stage 0 Live E2E 스냅샷 테스트 (survey_admin 실물 메타그래프 대상).
 * 4대 병렬 신호 (구조 식별자, Mapper, View-Script, 형제 유추) 및 신뢰도 버킷팅 실측 검증.
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
            localDomainOverrides = mapOf("설문" to setOf("survey", "poll")),
            maxBridgeDegree = 15,
            maxExternalShared = 3
        )
        val engine = Stage0ClarificationEngine(scanner, graph)

        val originalReq = "설문 발송 채널에 브랜드메시지 추가"
        val turn0 = engine.initSession(originalReq)

        // 1. 고신뢰 (HIGH_CONFIDENCE) 후보군 실측 검증 (프론트/매퍼/구조식별자)
        val highConfidenceItems = turn0.state.items.filter { it.confidence == ConfidenceBucket.HIGH_CONFIDENCE }
        val highPaths = highConfidenceItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        
        assertTrue("survey_list.jsp는 HIGH_CONFIDENCE여야 함", highPaths.any { it.contains("survey_list.jsp") })
        assertTrue("survey.list.js는 HIGH_CONFIDENCE여야 함", highPaths.any { it.contains("survey.list.js") })
        assertTrue("survey_write.jsp는 HIGH_CONFIDENCE여야 함", highPaths.any { it.contains("survey_write.jsp") })
        assertFalse("무관 파일(login.js)은 0건 차단되어야 함", highPaths.any { it.contains("login.js") || it.contains("authority.manage.js") })

        // 2. 형제 유추 (LOW_CONFIDENCE) 후보군 실측 검증:
        // 특정 파일명 단순 매칭에만 기대지 않고, 위상/속성 기반 술어(Predicate)로 검증:
        // (1) 외부 도메인(seed 패키지 외) 노드들이며 시드 도메인 토큰("survey")을 이름에 포함하지 않음
        // (2) 출처 신호가 SIBLING_ANALOGY로 엄격 격리됨
        // (3) HIGH_CONFIDENCE 버킷에 혼입되지 않고 LOW_CONFIDENCE에만 격리 보존됨
        val lowConfidenceItems = turn0.state.items.filter { it.confidence == ConfidenceBucket.LOW_CONFIDENCE }
        val lowExistingRefs = lowConfidenceItems.mapNotNull { it.hint as? LinkHint.ExistingRef }
        val lowPaths = lowExistingRefs.map { it.filePath }

        assertTrue("LOW_CONFIDENCE 회수 항목은 1건 이상이어야 함", lowPaths.isNotEmpty())
        assertTrue(
            "LOW_CONFIDENCE 회수 항목은 시드 도메인 Service/DAO(/survey/service/, /survey/dao/)가 아니어야 함",
            lowPaths.none { it.contains("/survey/service/") || it.contains("/survey/dao/") }
        )
        assertTrue(
            "LOW_CONFIDENCE 회수 항목에 외부 배치(/batch/) 노드가 반드시 포함되어야 함",
            lowPaths.any { it.contains("/batch/") }
        )

        // 위상 술어 2: 모든 저신뢰 항목은 SIBLING_ANALOGY 출처 신호를 단독/우선 보유해야 함
        assertTrue(
            "모든 저신뢰 파일 항목은 SIBLING_ANALOGY 신호를 포함해야 함",
            lowConfidenceItems.filter { it.hint is LinkHint.ExistingRef }.all { 
                it.provenanceSignals.contains(ProvenanceSignal.SIBLING_ANALOGY) 
            }
        )

        // 위상 술어 3: HIGH_CONFIDENCE에는 외부 배치/API 노드가 전혀 유입되지 않아야 함 (버킷 격리 불변식)
        assertTrue(
            "HIGH_CONFIDENCE 버킷에는 외부 도메인(/batch/, /api/) 노드가 없어야 함",
            highPaths.none { it.contains("/batch/") || it.contains("/api/") }
        )

        // 위상 술어 4: 저신뢰 회수 노드들의 패키지 응집도 및 상호 연결성 검증
        // 1) 저신뢰 회수 노드 중 동일 외부 패키지(/batch/)를 공유하는 응집 서브그래프 크기가 3개 이상이어야 함
        val batchClusterPaths = lowPaths.filter { it.contains("/batch/") }
        assertTrue("동일 외부 패키지 응집 클러스터는 3개 이상의 노드로 구성되어야 함", batchClusterPaths.size >= 3)

        // 2) 해당 클러스터 내부 노드들이 상호 간에 직접 연결(INJECTS/CALLS)된 연결 그래프를 형성해야 함
        val internalEdges = graph.relationships.filter { rel ->
            batchClusterPaths.contains(rel.source) && batchClusterPaths.contains(rel.target)
        }
        assertTrue("클러스터 내부 노드 간 최소 2개 이상의 유효 위상 엣지(INJECTS/CALLS)로 직결되어야 함", internalEdges.size >= 2)

        // 3. Category A 구조 슬롯 제안 검증 (익명 구조 슬롯 1층 원칙 & 오탐 리프 노드 배제)
        val structuralSlotItem = lowConfidenceItems.find { it.hint is LinkHint.NewCreation && it.structuralSlotProposal != null }
        assertNotNull("Category A 신규 생성 슬롯 제안이 생성되어야 함", structuralSlotItem)
        val slotProposal = structuralSlotItem!!.structuralSlotProposal!!
        assertEquals("슬롯 타입은 COHESIVE_SIBLING_CLUSTER 여야 함", "COHESIVE_SIBLING_CLUSTER", slotProposal.slotType)
        assertTrue("슬롯 템플릿 컴포넌트가 2개 이상 포함되어야 함", slotProposal.templateComponents.size >= 2)
        assertFalse("1층 구조 제안은 환각된 구체 파일명(Bizgo 등)을 생성하지 않아야 함", 
            structuralSlotItem.statement.contains("Bizgo")
        )
        // 오탐 리프 노드(IbCenterApiService 등 외부 패키지)가 템플릿 컴포넌트 구조 근거에 혼입되지 않고 코어 클러스터로만 정제되었는지 검증
        assertTrue(
            "슬롯 템플릿 컴포넌트는 외부 리프 노드(IbCenterApiService 등)를 배제하고 코어 응집 노드로만 구성되어야 함",
            slotProposal.templateComponents.none { it.contains("IbCenter") || it.contains("ApiService") }
        )

        // 4. 개방형 질문 트리거 확인
        assertNotNull("개방형 질문이 트리거되어야 함", turn0.openQuestion)

        // 5. 턴 진행 및 동결 보호 검증
        val surveyListJspItem = turn0.state.items.find { (it.hint as? LinkHint.ExistingRef)?.filePath?.contains("survey_list.jsp") == true }
        val updates = if (surveyListJspItem != null) mapOf(surveyListJspItem.id to Verdict.CONFIRMED) else emptyMap()

        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                verdictUpdates = updates,
                userStatement = "Bizgo REST API를 통해 브랜드메시지를 연동합니다."
            )
        )

        // 6. 전이 계약 검증
        val contract = engine.transitionToStage1(turn1.state)
        assertTrue(contract.trustedExistingRefs.any { it.filePath.contains("survey_list.jsp") })
        assertTrue(contract.enrichedRequirementText.contains("Bizgo REST API"))
    }
}
