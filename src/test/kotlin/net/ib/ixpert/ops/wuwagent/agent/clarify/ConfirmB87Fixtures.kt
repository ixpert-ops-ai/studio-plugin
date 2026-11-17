package net.ib.ixpert.ops.wuwagent.agent.clarify

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
import java.util.Collections

/**
 * B-87 (가) 시험 공용 재료: 호출을 종류별로 기록하는 모의 LLM, 합성 그래프, 소스 확인 질문이 열린 상태. LLM 서버·IDE 없음.
 * 정제문 응답은 입력(사용자 프롬프트)을 그대로 감싸서 돌려주므로, 응답이 같으면 정제문 입력이 같았다는 뜻이다.
 */
internal object ConfirmB87Fixtures {
    const val LABEL1 = "응답자 이메일"
    const val LABEL2 = "고객 전화번호"
    const val ALIM_PATH = "net/infobank/iss/batch/AlimtalkBatchJob.java"
    const val EXCLUDE_JSON = """[{"kind": "EXCLUDE_COMPONENT", "value": "알림톡 배치 제외", "evidence": "알림톡 배치"}]"""

    class RecordingLlm(@Volatile var extractJson: String = "[]") : LLMClient {
        /** (종류, 사용자 프롬프트). 종류: refine, extract, translate, summary, other */
        val calls: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())

        fun count(kind: String): Int = calls.count { it.first == kind }
        fun inputs(kind: String): List<String> = calls.filter { it.first == kind }.map { it.second }

        override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
            val kind = when {
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> "refine"
                systemPrompt.contains("ConstraintKind") -> "extract"
                systemPrompt.contains("발화 번역 전문가") -> "translate"
                systemPrompt.contains("시스템 분석 어시스턴트") -> "summary"
                else -> "other"
            }
            calls.add(kind to userCode)
            val content = when (kind) {
                "refine" -> "REFINED[$userCode]"
                "extract" -> extractJson
                else -> "{}"
            }
            return OllamaChatResponse(model = "fake", createdAt = "", message = OllamaMessage("assistant", content), done = true)
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    fun graph(): ProjectGraph {
        val files = mapOf(
            ALIM_PATH to FileNode(
                path = ALIM_PATH, packageName = "net.infobank.iss.batch", className = "AlimtalkBatchJob", localName = "알림톡 배치 작업",
                koreanComments = listOf("알림톡 발송 배치"), fileType = SpringFileType.SERVICE, layer = ArchitectureLayer.SERVICE
            ),
            "net/infobank/iss/batch/BrandmessageBatchJob.java" to FileNode(
                path = "net/infobank/iss/batch/BrandmessageBatchJob.java", packageName = "net.infobank.iss.batch", className = "BrandmessageBatchJob",
                localName = "브랜드메시지 배치 작업", koreanComments = listOf("브랜드메시지 발송 배치"), fileType = SpringFileType.SERVICE, layer = ArchitectureLayer.SERVICE
            )
        )
        return ProjectGraph(
            generatedAt = java.time.Instant.now().toString(), projectRoot = "/test/root", frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files, relationships = emptyList(), resourceNodes = emptyList(), statistics = GraphStatistics()
        )
    }

    fun engine(llm: LLMClient, deferTaskSummary: Boolean = true): Stage0ClarificationEngine {
        val g = graph()
        return Stage0ClarificationEngine(Stage0GraphScanner(g), g, llm, deferTaskSummary = deferTaskSummary)
    }

    fun fact(label: String, question: String, writeNotice: String? = null) = FieldFact(
        label, found = false, question = question, writeNotice = writeNotice,
        writeTables = if (writeNotice != null) listOf("ISSW_SURVEY_R") else emptyList(),
        mappersRead = if (writeNotice != null) 1 else 0, mapperFiles = if (writeNotice != null) listOf("sql_survey.xml") else emptyList()
    )

    /** 소스 확인 질문이 열린 상태(항목 개수만큼). 답은 아직 없다. */
    fun withOpenFields(state: Stage0State, count: Int): Stage0State {
        val facts = listOf(
            fact(LABEL1, "$LABEL1 은(는) 어디서 가져올까요?", writeNotice = "읽은 범위에서 발견되지 않음"),
            fact(LABEL2, "$LABEL2 은(는) 어디서 가져올까요?")
        ).take(count)
        return state.copy(fieldFacts = facts)
    }

    /** 제외 제안(예/아니요 질문)이 열린 상태로 만든다: "알림톡 배치는 건드리지 마"를 보낸 직후. 만든 뒤 추출 응답은 빈 목록으로 되돌린다. */
    fun withOpenProposal(engine: Stage0ClarificationEngine, llm: RecordingLlm, state: Stage0State): Stage0State {
        llm.extractJson = EXCLUDE_JSON
        val turn = engine.processTurn(state, Stage0ClarificationEngine.UserInput(userStatement = "알림톡 배치는 건드리지 마"))
        llm.extractJson = "[]"
        return turn.state
    }
}
