package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import java.io.File

/**
 * 실사용 평가 세트(B-39)의 시나리오 정의. 정의는 JSON 파일(`/eval/scenarios.json`, 선택 `/eval/real_scenarios.json`)에 둔다.
 * Gson은 생성자 기본값을 적용하지 않으므로 선택 필드는 널 허용으로 두고 접근자로 기본값을 준다.
 */
data class EvalGt(
    val path: String? = null,   // gtMatch == "path": 프로젝트 루트 기준 파일 경로
    val name: String? = null,   // gtMatch == "harness-contains": 하네스 규칙(경로가 이름을 대소문자 무시하고 포함)
    val reason: String? = null, // 고른 근거 한 줄
    val role: String? = null,   // required(고칠 파일, 기본) | reference(읽기만 하는 파일)
    val inGraph: Boolean? = null, // false면 그래프에 없는 파일(그래프 누락). Recall 분모에서 뺀다. 기본 true
    val uncertain: Boolean? = null // true면 정답 확신이 낮은 행(검토 문서에서 "?"가 붙은 행). Recall을 uncertain 제외 기준으로도 낸다
) {
    fun isUncertain(): Boolean = uncertain == true
    fun label(): String = path ?: name ?: "?"
    fun isReference(): Boolean = role == "reference"
    fun isInGraph(): Boolean = inGraph != false
    /** Recall 분모: required이면서 그래프에 있는 항목 */
    fun countsForRecall(): Boolean = !isReference() && isInGraph()
}

data class EvalExtra(
    val path: String? = null,
    val reason: String? = null,
    val inGraph: Boolean? = null
)

/** 대본이 끝난 뒤 남은 엔진 질문에 질문 문구가 포함되면 대체 답변 대신 이 답을 쓴다(테스트 전용 대본 확장). */
data class EvalAnswerRule(
    val questionContains: String? = null,
    val answer: String? = null
)

data class EvalScenario(
    val id: String,
    val group: String,                    // dev | synthetic | real
    val graphKey: String,
    val sr: String,
    val script: List<String>? = null,     // 사용자 발화 대본(2턴 이내)
    val gtMatch: String? = null,          // path | harness-contains
    val gt: List<EvalGt>? = null,
    val gtStatus: String? = null,         // draft | confirmed
    val onlyType: String? = null,         // 하네스 규칙 유지용: 이 type의 targetFiles만 GT 판정에 쓴다(예: MODIFY)
    val expectExcluded: List<String>? = null, // 제외될 것으로 기대하는 파일 경로(없으면 비움)
    val mustNotAppear: List<EvalGt>? = null,  // 사용자 지시로 최종 목록에 나타나면 안 되는 파일(gtMatch 규칙으로 판정). 나타난 수를 보고서에 기록한다
    val extraCandidates: List<EvalExtra>? = null,
    val note: String? = null,
    val answerRules: List<EvalAnswerRule>? = null,
    val archType: String? = null,         // SPRING_MVC_MYBATIS | ANYFRAME_AP | SPRING_BOOT_JPA (미측정: SPRING_BOOT_MYBATIS, SPRING_BOOT_JDBC, CUSTOM)
    val srType: String? = null,           // 기존 기능 수정 | 기존 패턴 신규 추가 | 제외 지시 | 모호. 값이 불확실한 기존 시나리오는 비워 둔다
    val holdout: Boolean? = null,         // true면 개선 판단에 쓰지 않고 -Deval.includeHoldout=true일 때만 실행한다
    val expectedQuestions: String? = null // 모호한 SR에서 기대하는 질문(보고서에 나란히 인쇄만 하고 자동 판정하지 않는다)
) {
    fun isHoldout(): Boolean = holdout == true
    fun scriptOrEmpty(): List<String> = script ?: emptyList()
    fun gtOrEmpty(): List<EvalGt> = gt ?: emptyList()
    fun matchMode(): String = gtMatch ?: "path"
    fun status(): String = gtStatus ?: "draft"
    fun expectExcludedOrEmpty(): List<String> = expectExcluded ?: emptyList()
    fun mustNotAppearOrEmpty(): List<EvalGt> = mustNotAppear ?: emptyList()
    fun extrasOrEmpty(): List<EvalExtra> = extraCandidates ?: emptyList()
    fun rulesOrEmpty(): List<EvalAnswerRule> = answerRules ?: emptyList()
}

data class EvalScenarioFile(val scenarios: List<EvalScenario>? = null)

object EvalScenarios {
    private val gson = Gson()

    /** `-Deval.graphs=<graphs.json>`(키 → 경로 맵). 없으면 현재 쓰는 네 경로. */
    fun graphPaths(): Map<String, String> {
        val file = System.getProperty("eval.graphs")
        if (!file.isNullOrBlank()) {
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(File(file).readText(Charsets.UTF_8), Map::class.java) as Map<String, String>
        }
        return mapOf(
            "survey_admin" to "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json",
            "apc" to "C:/Workspace/graph/project-graph-a/project-graph.json",
            "ism" to "C:/Workspace/graph/project-graph-i/project-graph.json",
            "member_market" to "C:/Workspace/member-market/.meta/project-graph.json"
        )
    }

    private fun readResource(path: String): String? =
        EvalScenarios::class.java.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

    private fun parse(text: String?): List<EvalScenario> =
        if (text.isNullOrBlank()) emptyList() else gson.fromJson(text, EvalScenarioFile::class.java).scenarios ?: emptyList()

    /** holdout 시나리오는 `-Deval.includeHoldout=true`일 때만 실행한다(개선 판단 근거로 쓰지 않는다). */
    fun includeHoldout(): Boolean = System.getProperty("eval.includeHoldout") == "true"

    /** 개발용·합성은 `/eval/scenarios.json`, 아키텍처 유형별 SR은 `/eval/arch_scenarios.json`, 실제 업무 SR은 `-Deval.real=<path>` 또는 `/eval/real_scenarios.json`(기본은 빈 목록). */
    fun loadAll(): List<EvalScenario> {
        val base = parse(readResource("/eval/scenarios.json")) + parse(readResource("/eval/arch_scenarios.json"))
        val realFile = System.getProperty("eval.real")
        val real = if (!realFile.isNullOrBlank()) parse(File(realFile).readText(Charsets.UTF_8)) else parse(readResource("/eval/real_scenarios.json"))
        val all = (base + real.map { it.copy(group = "real") }).filter { includeHoldout() || !it.isHoldout() }
        val only = System.getProperty("eval.only")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        // group == probe(확인용)는 기준선에 섞이지 않도록 eval.only로 지정했을 때만 돈다.
        val selected = if (only.isNullOrEmpty()) all.filter { it.group != "probe" } else all.filter { it.id in only }
        // -Deval.first=SR-07,SR-11: 지정한 시나리오를 앞으로 옮기고 나머지는 원래 순서를 유지한다(목록 구성은 바꾸지 않는다).
        val first = System.getProperty("eval.first")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (first.isEmpty()) return selected
        return first.mapNotNull { id -> selected.firstOrNull { it.id == id } } + selected.filter { it.id !in first }
    }
}
