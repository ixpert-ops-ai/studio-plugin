package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.ClaimedFieldCheck
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.FieldFact
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-84: 정제문 확인 카드의 "확인 필요" 한 문장. 정제문은 그대로 두고(문장 치환 없음) 조건이 맞을 때만 따로 한 문장을 보인다.
 * 조건: 사용자가 새 데이터로 답했고(isNewData) 그 항목에 쓰기 쿼리 없음 판정(writeNotice)이 있다. LLM·IDE 없음.
 */
class DecisionNoteB84Test {
    private val label = "응답자 이메일"
    private val newAnswer = "응답자 이메일은 아직 수집하지 않아. 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘."
    private val expected = "응답 테이블(ISSW_SURVEY_R)에 값을 쓰는 쿼리가 읽은 매퍼 1개에 없습니다. 응답자 이메일 저장을 이 프로젝트에서 구현할지, 응답을 저장하는 다른 시스템과 연계할지 정해야 합니다."

    private fun fact(writeNotice: String? = "읽은 범위에서 발견되지 않음", tables: List<String> = listOf("ISSW_SURVEY_R"), n: Int = 1, lbl: String = label) =
        FieldFact(lbl, found = false, question = "어디서 가져올까요?", writeNotice = writeNotice, writeTables = if (writeNotice != null) tables else emptyList(), mappersRead = n)

    // n1: 조건이 맞으면 판정의 테이블 이름과 매퍼 수로 만든 한 문장, 아니면 없다
    @Test
    fun n1_oneLineOnlyWhenTheConditionHolds() {
        assertEquals(listOf(expected), ClaimedFieldCheck.decisionNotes(listOf(fact()), mapOf(label to newAnswer)))
        // 테이블 이름과 매퍼 수는 판정에서 온다
        val other = ClaimedFieldCheck.decisionNote("고객 전화번호", listOf("T1", "T2"), 3)
        assertTrue(other, other.startsWith("응답 테이블(T1/T2)에 값을 쓰는 쿼리가 읽은 매퍼 3개에 없습니다. 고객 전화번호 저장을"))
        // 새 데이터가 아닌 답, 쓰기 쿼리 없음 판정이 없는 항목, 답이 없는 항목, 판정 재료가 없는 항목, 다른 항목의 답
        assertEquals(emptyList<String>(), ClaimedFieldCheck.decisionNotes(listOf(fact()), mapOf(label to "잘 모르겠음")))
        assertEquals(emptyList<String>(), ClaimedFieldCheck.decisionNotes(listOf(fact()), mapOf(label to "다른 테이블 조인해서 가져와")))
        assertEquals(emptyList<String>(), ClaimedFieldCheck.decisionNotes(listOf(fact(writeNotice = null)), mapOf(label to newAnswer)))
        assertEquals(emptyList<String>(), ClaimedFieldCheck.decisionNotes(listOf(fact()), emptyMap()))
        assertEquals(emptyList<String>(), ClaimedFieldCheck.decisionNotes(listOf(fact(tables = emptyList())), mapOf(label to newAnswer)))
        assertEquals(emptyList<String>(), ClaimedFieldCheck.decisionNotes(listOf(fact()), mapOf("다른 항목" to newAnswer)))
    }

    private class Fake : LLMClient {
        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val content = when {
                systemPrompt.contains("ConstraintKind") -> "[]"
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고 상세 조회에서 마스킹하여 반환한다."
                else -> "{}"
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    private fun engine(): Stage0ClarificationEngine {
        val node = FileNode(path = "src/survey/SurveyServiceImpl.java", packageName = "p", className = "SurveyServiceImpl", fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS, koreanComments = listOf("설문 서비스"))
        val g = ProjectGraph(
            generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = mapOf(node.path to node), relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics()
        )
        return Stage0ClarificationEngine(Stage0GraphScanner(g, minSpecificityScore = 1.0, proposalBudget = 10), g, llmClient = Fake())
    }

    private fun state(facts: List<FieldFact>) = Stage0State(
        originalRequirement = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요",
        userStatements = listOf("$label 출처: $newAnswer"),
        fieldFacts = facts,
        fieldAnswers = mapOf(label to newAnswer)
    )

    // n2: 확정 인텐트의 정제문은 "확인 필요" 문장이 있든 없든 바이트까지 같고, 사용자가 고친 정제문으로 complete해도 문장은 그대로다
    @Test
    fun n2_theRefinedSentenceIsByteIdenticalWithOrWithoutTheNote() {
        val withNote = engine().buildClarifyIntentDeferred(state(listOf(fact())))
        val without = engine().buildClarifyIntentDeferred(state(listOf(fact(writeNotice = null))))
        assertEquals(expected, withNote.decisionNote)
        assertNull(without.decisionNote)
        assertEquals(without.early.intent.refinedRequirement, withNote.early.intent.refinedRequirement)
        assertEquals(without.early.intent.retentionAudit?.rawRefinedRequirement, withNote.early.intent.retentionAudit?.rawRefinedRequirement)
        // 정제문에는 "수집"이 그대로 남아 있다(문장 치환 없음: 사용자 요구를 옮긴 것이므로 지우지 않는다)
        assertTrue(withNote.early.intent.refinedRequirement, withNote.early.intent.refinedRequirement.contains("추가하여 수집하고"))
        // 사용자가 정제문을 고쳐 진행해도 문장은 카드 요청 때 이미 정해진 값이고 바뀌지 않는다
        val edited = withNote.early.intent.copy(refinedRequirement = "사용자가 고친 정제문")
        val done = withNote.complete(edited)
        assertEquals("사용자가 고친 정제문", done.intent.refinedRequirement)
        assertEquals(expected, withNote.decisionNote)
    }

    private class NoteBridge(val decision: RefinedConfirmationDecision?) : RefinedRequirementConfirmationBridge {
        var note: String? = "미호출"
        var shownRefined: String? = null
        var oldStyleCalls = 0
        override fun requestConfirmation(originalRequirement: String, refinedRequirement: String): RefinedConfirmationDecision? {
            oldStyleCalls++
            return decision
        }

        override fun requestConfirmation(originalRequirement: String, refinedRequirement: String, decisionNote: String?): RefinedConfirmationDecision? {
            note = decisionNote
            shownRefined = refinedRequirement
            return decision
        }
    }

    private fun intent() = ClarifyIntent(
        originalRequirement = "설문 응답 이메일 추가",
        refinedRequirement = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집한다.",
        graphHash = "h"
    )

    // n3: 게이트는 문장을 카드 요청에 그대로 넘기고(정제문은 문장이 붙지 않은 채로 보인다), 고쳐서 진행해도 같다. 문장이 없으면 null.
    @Test
    fun n3_theGatePassesTheNoteToTheCardAndKeepsTheRefinedTextClean() {
        for (decision in listOf(RefinedConfirmationDecision.Proceed, RefinedConfirmationDecision.ProceedEdited("사용자가 고친 정제문"))) {
            val bridge = NoteBridge(decision)
            val analyzed = ArrayList<ClarifyIntent>()
            RefinedRequirementGate.handoff(intent(), bridge, {}, { analyzed.add(it) }, {}, decisionNote = expected)
            assertEquals(expected, bridge.note)
            assertEquals(RefinedRequirementGate.shownRefined(intent()), bridge.shownRefined)
            assertTrue(bridge.shownRefined!!, !bridge.shownRefined!!.contains("정해야 합니다"))
            assertEquals(1, analyzed.size)
            assertTrue(analyzed.single().refinedRequirement.let { !it.contains("정해야 합니다") })
        }
        val none = NoteBridge(RefinedConfirmationDecision.Proceed)
        RefinedRequirementGate.handoff(intent(), none, {}, {}, {})
        assertNull(none.note)
        // 문장을 쓰지 않는 이전 방식(람다)의 브리지도 문장이 있어도 그대로 동작한다
        var lambdaCalls = 0
        val lambda = RefinedRequirementConfirmationBridge { _, _ -> lambdaCalls++; RefinedConfirmationDecision.Proceed }
        RefinedRequirementGate.handoff(intent(), lambda, {}, {}, {}, decisionNote = expected)
        assertEquals(1, lambdaCalls)
    }

    // n4: 같은 문장을 카드와 결과 화면이 쓸 수 있게 문장은 한 함수에서만 만든다
    @Test
    fun n4_theSentenceIsBuiltInOnePlaceOnly() {
        val count = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .sumOf { Regex("다른 시스템과 연계할지 정해야 합니다").findAll(it.readText(Charsets.UTF_8)).count() }
        assertEquals(1, count)
    }
}
