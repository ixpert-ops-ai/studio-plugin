package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.GraphStatistics
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-54: 파일명 제외 규칙(결정적, LLM 없음)의 절 나누기, "만" 처리, 부정형, 판단 불가 안내를 문장 단위로 고정한다.
 * 그래프는 member_market의 클래스명을 본뜬 작은 그래프다(실제 그래프 파일에 의존하지 않는다).
 */
class ExclusionFileNameRuleTest {

    private class Fake : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("발화 번역 전문가") ->
                    """{"action": "ADD_CONSTRAINT", "constraintKind": "SCOPE_LIMIT", "constraintValue": "수정 범위 한정", "message": "제약 조건으로 기록했습니다."}"""
                systemPrompt.contains("ConstraintKind") -> "[]"
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> "정제된 요구사항"
                else -> "추가로 수정할 대상이 있으신가요?"
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private val names = listOf("AdminService", "OrganizationService", "AdminController", "OrganizationRepository", "AdminOrganizationResponse", "SurveyServiceImpl")

    private fun pathOf(name: String) = "src/$name.java"

    private fun graph(): ProjectGraph {
        val list = names.map {
            FileNode(path = pathOf(it), packageName = "p", className = it, fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS, koreanComments = listOf("테스트 $it"))
        }
        return ProjectGraph(
            generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = list.associateBy { it.path }, relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics()
        )
    }

    private fun engine(): Stage0ClarificationEngine {
        val g = graph()
        return Stage0ClarificationEngine(Stage0GraphScanner(g, minSpecificityScore = 1.0, proposalBudget = 10), g, llmClient = Fake())
    }

    private fun excludedAfter(statement: String): Pair<List<String>, Stage0ClarificationEngine.Stage0TurnResult> {
        val e = engine()
        val t0 = e.initSession("조직 목록에는 운영 중인 조직만 보이게 해 주세요")
        val t1 = e.processUtterance(t0.state, statement)
        return e.exclusionView(t1.state).excludedFiles.map { it.substringAfterLast("/").removeSuffix(".java") }.sorted() to t1
    }

    // 문장 -> 제외되어야 할 파일(클래스명). 기대값은 보고서의 합성 문장 표와 같다.
    private val cases = listOf(
        "AdminService는 빼고, OrganizationService는 수정해 주세요" to listOf("AdminService"),
        "AdminService는 빼고 OrganizationService는 수정해 주세요" to listOf("AdminService"),
        "AdminService, OrganizationService는 수정하고 AdminController는 빼 주세요" to listOf("AdminController"),
        "AdminService만 빼 주세요" to listOf("AdminService"),
        "AdminService.java 말고 나머지는 다 봐 주세요" to listOf("AdminService"),
        "OrganizationRepository는 그대로 두고 OrganizationService만 수정" to listOf("OrganizationRepository"),
        "AdminService 건드리지 마세요. OrganizationService는 수정" to listOf("AdminService"),
        "AdminService랑 AdminController는 빼 주세요" to listOf("AdminController", "AdminService"),
        "AdminService는 빼지 말고 같이 봐 주세요" to emptyList(),
        "AdminService는 수정하고 AdminController는 그대로 두세요" to listOf("AdminController"),
        "알림톡 배치는 건드리지 말고 SurveyServiceImpl만 수정" to emptyList(),
        "OrganizationService만 수정하고 나머지는 그대로" to emptyList(),
        // SR-05X 발화 2 원문
        "AdminService.java, AdminOrganizationResponse.java는 제외해 주세요" to listOf("AdminOrganizationResponse", "AdminService")
    )

    // 모든 문장을 돌려 한 번에 보고한다(하나가 어긋나도 나머지 결과가 보이게).
    @Test
    fun i1_sentenceTableMatchesExpectedExclusions() {
        val mismatches = cases.mapNotNull { (sentence, expected) ->
            val actual = excludedAfter(sentence).first
            if (actual == expected.sorted()) null else "\"$sentence\" 기대 $expected 실제 $actual"
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    // I2 판단 불가: 한 절에 제외 표현과 수정 동사가 있고 이름이 2개 이상이면 제외하지 않고 한 줄만 안내한다(확인 질문 없음).
    @Test
    fun i2_ambiguousClauseIsNotExcludedAndGetsOneNoticeLine() {
        val (excluded, t1) = excludedAfter("AdminService는 빼 주시고 OrganizationService는 수정해 주세요")
        assertEquals(emptyList<String>(), excluded)
        val echo = t1.echoBackMessage
        assertNotNull(echo)
        assertTrue(echo!!, echo.contains("AdminService.java, OrganizationService.java은(는) 제외 요청인지 분명하지 않아 제외하지 않았습니다."))
        assertFalse(echo, echo.contains("제약 조건으로 기록했습니다"))
        assertFalse((t1.openQuestion ?: "").contains("제외할까요?"))
    }

    // I3 이름이 하나뿐이면 수정 동사가 같은 절에 있어도 판단 불가가 아니다.
    @Test
    fun i3_singleNameWithModifyVerbIsStillExcluded() {
        assertEquals(listOf("AdminService"), excludedAfter("AdminService는 빼 주시고 수정은 나머지에서 해 주세요").first)
    }
}
