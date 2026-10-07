package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.time.Instant

class Stage0EngineRoutedBaselineHarnessTest {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    private fun loadGraph(pathStr: String): ProjectGraph? {
        val file = File(pathStr)
        if (!file.exists()) return null
        return Gson().fromJson(file.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    /**
     * 엄격한 프롬프트 매칭 Mock LLM:
     * - 정의된 안정적 표지 문자열에 부합하지 않는 프롬프트 인입 시 error("unmatched prompt") 즉시 발생 (침묵 방지)
     */
    private class StrictClarifyMockLlm(
        private val refinementResponse: String,
        private val constraintsResponse: String = "[]",
        private val openQuestionResponse: String = "추가적으로 수정 또는 연동할 대상이 있으신가요?"
    ) : LLMClient {
        val callCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

        private fun recordCall(branch: String) {
            callCounts.compute(branch) { _, count -> (count ?: 0) + 1 }
        }

        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse {
            val content = when {
                // 표지 1: 요구사항 정제 전문가 프롬프트 (Stage0ClarificationEngine.kt:1078)
                systemPrompt.contains("소프트웨어 요구사항 정제 전문가") -> {
                    recordCall("Clarify_Refinement")
                    refinementResponse
                }
                // 표지 2: 제약조건 분류 전문가 프롬프트 (Stage0ClarificationEngine.kt:1133)
                systemPrompt.contains("ConstraintKind") -> {
                    recordCall("Clarify_Constraints")
                    constraintsResponse
                }
                // 표지 3: 개방형 질문 생성 프롬프트 (Stage0ClarificationEngine.kt:728)
                systemPrompt.contains("개방형 질문") -> {
                    recordCall("Clarify_OpenQuestion")
                    openQuestionResponse
                }
                else -> error("unmatched prompt in StrictClarifyMockLlm.chat: [systemPrompt=$systemPrompt]")
            }
            return OllamaChatResponse(
                model = "strict-mock-llm",
                createdAt = "",
                message = OllamaMessage("assistant", content),
                done = true
            )
        }

        override fun chatWithTools(
            systemPrompt: String,
            messages: List<net.ib.ixpert.ops.wuwagent.model.ChatMessage>,
            maxTokens: Int?,
            tools: List<net.ib.ixpert.ops.wuwagent.model.ToolDefinition>?,
            toolChoice: Any?,
            temperature: Double?
        ): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
            error("StrictClarifyMockLlm does not expect chatWithTools: [systemPrompt=$systemPrompt]")
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    /**
     * Pipeline Discovery 및 Verifier용 엄격한 Mock LLM:
     * - NewFileDetector, Stage 3 Verifier, AgenticSeedSelector의 정확한 시스템 프롬프트 상수 문자열 매칭
     * - 매칭되지 않는 프롬프트 인입 시 error("unmatched prompt in StrictPipelineLlm")로 즉시 실패.
     */
    private class StrictPipelineLlm : LLMClient {
        val callCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

        private fun recordCall(branch: String) {
            callCounts.compute(branch) { _, count -> (count ?: 0) + 1 }
        }

        override fun chat(
            systemPrompt: String,
            userCode: String,
            maxTokens: Int?,
            onChunk: ((String) -> Unit)?
        ): OllamaChatResponse {
            val response = when {
                // 표지 A: NewFileDetector 프롬프트 (NewFileDetector.kt:88)
                systemPrompt.contains("당신은 프로젝트 아키텍트입니다.") -> {
                    recordCall("BranchA_NewFileDetector_chat")
                    "[]"
                }
                // 표지 B: Stage 3 LLM Verification 프롬프트 (FileRelevanceVerifier.kt:49)
                systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.") -> {
                    recordCall("BranchB_Stage3Verifier_chat")
                    "{}"
                }
                // 표지 C: AgenticSeedSelector 시스템 프롬프트 (AgenticSeedSelector.kt:31)
                systemPrompt.contains("당신은 시스템의 소스코드 및 메타그래프를 능동적으로 탐색하는 전문 AI 분석 에이전트입니다.") -> {
                    recordCall("BranchC_AgenticSeedSelector_chat")
                    "{}"
                }
                else -> error("unmatched prompt in StrictPipelineLlm.chat: [systemPrompt=$systemPrompt]")
            }
            return OllamaChatResponse(
                model = "strict-pipeline-llm",
                createdAt = "",
                message = OllamaMessage("assistant", response),
                done = true
            )
        }

        override fun chatWithTools(
            systemPrompt: String,
            messages: List<net.ib.ixpert.ops.wuwagent.model.ChatMessage>,
            maxTokens: Int?,
            tools: List<net.ib.ixpert.ops.wuwagent.model.ToolDefinition>?,
            toolChoice: Any?,
            temperature: Double?
        ): net.ib.ixpert.ops.wuwagent.model.ChatCompletionResponse? {
            when {
                // 표지 A: NewFileDetector 프롬프트 (NewFileDetector.kt:88)
                systemPrompt.contains("당신은 프로젝트 아키텍트입니다.") -> {
                    recordCall("BranchA_NewFileDetector_chatWithTools")
                    return null
                }
                // 표지 B: Stage 3 LLM Verification 프롬프트 (FileRelevanceVerifier.kt:49)
                systemPrompt.contains("당신은 코드 변경 범위 검증자입니다.") -> {
                    recordCall("BranchB_Stage3Verifier_chatWithTools")
                    return null
                }
                // 표지 C: AgenticSeedSelector 시스템 프롬프트 (AgenticSeedSelector.kt:31)
                systemPrompt.contains("당신은 시스템의 소스코드 및 메타그래프를 능동적으로 탐색하는 전문 AI 분석 에이전트입니다.") -> {
                    recordCall("BranchC_AgenticSeedSelector_chatWithTools")
                    return null
                }
                else -> error("unmatched prompt in StrictPipelineLlm.chatWithTools: [systemPrompt=$systemPrompt]")
            }
        }

        override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
    }

    /**
     * RelevanceScorer의 키워드 추출 로직과 동일한 헬퍼 (테스트용 키워드 차집합 진단)
     */
    private fun extractScorerKeywords(text: String, graph: ProjectGraphQueryable): Set<String> {
        val stopWords = setOf(
            "controller", "service", "repository", "entity", "dto", "vo", "request", "response",
            "mapper", "view", "page", "screen", "api", "impl", "config", "exception", "handler",
            "util", "action", "svc", "svo", "dvo", "dao", "bo",
            "화면", "컨트롤러", "서비스", "레파지토리", "저장소", "엔티티", "디티오", "매퍼",
            "액션", "페이지", "에이피아이", "구현체", "인터페이스"
        )
        val crudVerbs = setOf("등록", "조회", "수정", "추가", "삭제", "변경", "목록", "상세")
        val directEnglish = Regex("[a-zA-Z0-9]{3,}").findAll(text)
            .map { it.value }
            .filter { it.any { c -> c.isLetter() } }
            .toMutableList()
        directEnglish.removeAll { stopWords.contains(it.lowercase()) }

        val dictionary = DomainDictionary.load(graph)
        val words = text.split(Regex("\\s+"))
        val nouns = mutableListOf<String>()
        val translatedEnglish = mutableListOf<String>()

        for (word in words) {
            val cleanWord = word.replace(Regex("[^가-힣a-zA-Z0-9]"), "")
            if (cleanWord.length < 2) continue
            if (stopWords.contains(cleanWord.lowercase())) continue

            if (!cleanWord.endsWith("한다") && !cleanWord.endsWith("해라") && !cleanWord.endsWith("추가") && !cleanWord.endsWith("수정") && !cleanWord.endsWith("삭제")) {
                nouns.add(cleanWord.replace("을", "").replace("를", "").replace("이", "").replace("가", "").replace("은", "").replace("는", ""))
            }

            val translated = dictionary.translate(cleanWord).filterNot { stopWords.contains(it.lowercase()) }
            if (!crudVerbs.any { cleanWord.contains(it) }) {
                translatedEnglish.addAll(translated)
            }
        }
        return (directEnglish + nouns + translatedEnglish).map { it.lowercase() }.toSet()
    }

    private fun printClassificationAndDiff(
        scenarioName: String,
        intent: ClarifyIntent,
        state: Stage0State,
        graph: ProjectGraphQueryable
    ) {
        val missingBeforeFix = intent.retentionAudit?.missingBeforeFix ?: emptyList()
        val rawRefined = intent.retentionAudit?.rawRefinedRequirement ?: intent.refinedRequirement

        println("[$scenarioName] refinedRequirement=\"${intent.refinedRequirement}\"")
        println("[$scenarioName] rawRefinedRequirement=\"$rawRefined\"")

        if (missingBeforeFix.isEmpty()) {
            println("[$scenarioName] tagged=(none)")
        } else {
            for (tagged in missingBeforeFix) {
                val isUnresolved = intent.unresolvedItems.any { it.identifier.equals(tagged, ignoreCase = true) }
                val isExcluded = intent.excludedFiles.any { it.contains(tagged, ignoreCase = true) }
                val isAnchor = intent.anchorTokens.any { it.equals(tagged, ignoreCase = true) }

                val item = state.items.find {
                    it.utteredIdentifier?.equals(tagged, ignoreCase = true) == true ||
                    (it.hint as? LinkHint.ExistingRef)?.filePath?.contains(tagged, ignoreCase = true) == true ||
                    (it.hint is LinkHint.NewCreation && it.statement.contains(tagged, ignoreCase = true))
                }
                val sourceVerdict = if (item != null) "${item.source}/${item.verdict}" else "ABSENT"
                println("[$scenarioName] tagged=$tagged -> unresolved=$isUnresolved, excluded=$isExcluded, anchor=$isAnchor, source=$sourceVerdict")
            }
        }

        // 키워드 차집합 진단
        val taggedSrText = "${intent.refinedRequirement}\n"
        val rawPlusMissingText = "$rawRefined ${missingBeforeFix.joinToString(" ")}\n"
        val kwTagged = extractScorerKeywords(taggedSrText, graph)
        val kwRawPlusMissing = extractScorerKeywords(rawPlusMissingText, graph)

        val diffTaggedOnly = kwTagged - kwRawPlusMissing
        val diffRawPlusMissingOnly = kwRawPlusMissing - kwTagged
        println("[$scenarioName] keyword_diff -> tagged_only=$diffTaggedOnly, raw_plus_missing_only=$diffRawPlusMissingOnly")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 1. GT 기준값 측정 (Top-N GT 생존 및 순위/점수 전체 목록)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun testMeasureBaselineTopNGtSurvival_SurveyAdmin_LlmOff() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("survey_admin 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = null)

        val sr = "설문 발송 채널에 브랜드메시지 추가"
        val turn0 = engine.initSession(sr)
        val intent = engine.buildClarifyIntent(turn0.state)

        val resolved = AnalyzeInputResolver.resolve(
            rawInput = intent.refinedRequirement,
            inMemoryIntent = intent
        )

        val pipelineLlm = StrictPipelineLlm()
        val pipeline = RequirementAnalysisPipeline(pipelineLlm)
        val result = pipeline.analyze(
            primaryReq = resolved.effectiveRequirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = resolved.effectiveIntent
        )

        val gtFiles = listOf(
            "survey_list.jsp", "survey_write.jsp", "survey.list.js", "survey.write.js",
            "SurveyServiceImpl.java", "sql_survey.xml", "AlimtalkChnlDto.java", "AlimtalkTmplDto.java"
        )

        val finalPaths = result.targetFiles.filter { it.type == "MODIFY" }.map { it.path }
        val survivedGt = gtFiles.filter { gt -> finalPaths.any { it.contains(gt, ignoreCase = true) } }

        println("[GT Baseline: survey_admin (Intent=LlmOff)] RefinedReq: \"${intent.refinedRequirement}\"")
        println("[GT Baseline: survey_admin (Intent=LlmOff)] Top-30 TargetFiles Count: ${result.targetFiles.size}")
        println("[GT Baseline: survey_admin (Intent=LlmOff)] GT Survived: ${survivedGt.size}/${gtFiles.size} (${survivedGt.joinToString(", ")})")
        val missing = gtFiles - survivedGt.toSet()
        if (missing.isNotEmpty()) {
            println("[GT Baseline: survey_admin (Intent=LlmOff)] GT Missing: $missing")
        }
        println("[GT Baseline: survey_admin (Intent=LlmOff)] Top-30 List:")
        result.targetFiles.forEachIndexed { idx, sf ->
            val isGt = gtFiles.any { sf.path.contains(it, ignoreCase = true) }
            val tag = if (isGt) "★[GT]" else "  [FP]"
            println("  $tag #${idx + 1}. [${sf.type}] ${sf.path} (${sf.description})")
        }
        println("[MockTelemetry: SurveyAdmin_LlmOff] BranchCalls=${pipelineLlm.callCounts}, unmatched=0")
    }

    @Test
    fun testMeasureBaselineTopNGtSurvival_SurveyAdmin_LlmOn() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("survey_admin 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlm = StrictClarifyMockLlm(
            refinementResponse = "설문 발송 채널에 브랜드메시지 추가 및 Bizgo 연동 API 신규 개발"
        )
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = mockLlm)

        val turn0 = engine.initSession("설문 발송 채널에 브랜드메시지 추가")
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "외부 Bizgo 연동 API(BizgoApiService)를 신규 생성하고, 알림톡 배치 구조와 동일하게 브랜드메시지 배치 3종(BrandMessageTemplateBatchRunner, BrandMessageTemplateBatchJob, BrandMessageTemplateBatchRepository) 및 DTO(BrandMessageTmplDto)를 신규 개발합니다."
            )
        )
        val intent = engine.buildClarifyIntent(turn1.state)

        val resolved = AnalyzeInputResolver.resolve(
            rawInput = intent.refinedRequirement,
            inMemoryIntent = intent
        )

        val pipelineLlm = StrictPipelineLlm()
        val pipeline = RequirementAnalysisPipeline(pipelineLlm)
        val result = pipeline.analyze(
            primaryReq = resolved.effectiveRequirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = resolved.effectiveIntent
        )

        val gtFiles = listOf(
            "survey_list.jsp", "survey_write.jsp", "survey.list.js", "survey.write.js",
            "SurveyServiceImpl.java", "sql_survey.xml", "AlimtalkChnlDto.java", "AlimtalkTmplDto.java"
        )

        val finalPaths = result.targetFiles.filter { it.type == "MODIFY" }.map { it.path }
        val survivedGt = gtFiles.filter { gt -> finalPaths.any { it.contains(gt, ignoreCase = true) } }

        println("[GT Baseline: survey_admin (Intent=LlmOn)] RefinedReq: \"${intent.refinedRequirement}\"")
        println("[GT Baseline: survey_admin (Intent=LlmOn)] Top-30 TargetFiles Count: ${result.targetFiles.size}")
        println("[GT Baseline: survey_admin (Intent=LlmOn)] GT Survived: ${survivedGt.size}/${gtFiles.size} (${survivedGt.joinToString(", ")})")
        val missing = gtFiles - survivedGt.toSet()
        if (missing.isNotEmpty()) {
            println("[GT Baseline: survey_admin (Intent=LlmOn)] GT Missing: $missing")
        }
        println("[GT Baseline: survey_admin (Intent=LlmOn)] Top-30 List:")
        result.targetFiles.forEachIndexed { idx, sf ->
            val isGt = gtFiles.any { sf.path.contains(it, ignoreCase = true) }
            val tag = if (isGt) "★[GT]" else "  [FP]"
            println("  $tag #${idx + 1}. [${sf.type}] ${sf.path} (${sf.description})")
        }
        println("[MockTelemetry: SurveyAdmin_LlmOn] ClarifyCalls=${mockLlm.callCounts}, PipelineCalls=${pipelineLlm.callCounts}, unmatched=0")
    }

    @Test
    fun testMeasureBaselineTopNGtSurvival_ApcTransitCard_LlmOff() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/graph/project-graph-a/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("apc 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = null)

        val sr = "교통카드 발급업체 변경 후, 이전 모바일교통카드 이용회원 체크 및 재발급 안내를 위한 신규서비스 개발 건. 교통카드 구 발급정보 조회 신규서비스 개발 (SAPACMM0802S01 기존서비스 참고). APCMMTrcdIsInfSVO 수정, APCMMTrcdIsSVC.java 신규서비스 추가, aCMBTBAPC024DEM.selTrcdIsInf 참조하여 앱카드회원ID 및 모니모페이회원ID 최근이력 1건 조회"
        val turn0 = engine.initSession(sr)
        val intent = engine.buildClarifyIntent(turn0.state)

        val resolved = AnalyzeInputResolver.resolve(
            rawInput = intent.refinedRequirement,
            inMemoryIntent = intent
        )

        val pipelineLlm = StrictPipelineLlm()
        val pipeline = RequirementAnalysisPipeline(pipelineLlm)
        val result = pipeline.analyze(
            primaryReq = resolved.effectiveRequirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = resolved.effectiveIntent
        )

        val gtFiles = listOf("APCMMTrcdIsInfSVO", "APCMMTrcdIsSVC", "APCMMTrcdIsSVCImpl", "APCMMTrcdIsBIZ", "ACMBTBAPC024DEM")
        val finalPaths = result.targetFiles.map { it.path }
        val survivedGt = gtFiles.filter { gt -> finalPaths.any { it.contains(gt, ignoreCase = true) } }

        println("[GT Baseline: APC Transit Card (Intent=LlmOff)] RefinedReq: \"${intent.refinedRequirement}\"")
        println("[GT Baseline: APC Transit Card (Intent=LlmOff)] Top-30 TargetFiles Count: ${result.targetFiles.size}")
        println("[GT Baseline: APC Transit Card (Intent=LlmOff)] GT Survived: ${survivedGt.size}/${gtFiles.size} (${survivedGt.joinToString(", ")})")
        val missing = gtFiles - survivedGt.toSet()
        if (missing.isNotEmpty()) {
            println("[GT Baseline: APC Transit Card (Intent=LlmOff)] GT Missing: $missing")
        }
        println("[GT Baseline: APC Transit Card (Intent=LlmOff)] Top-30 List:")
        result.targetFiles.forEachIndexed { idx, sf ->
            val isGt = gtFiles.any { sf.path.contains(it, ignoreCase = true) }
            val tag = if (isGt) "★[GT]" else "  [FP]"
            println("  $tag #${idx + 1}. [${sf.type}] ${sf.path} (${sf.description})")
        }
        println("[MockTelemetry: ApcTransitCard_LlmOff] BranchCalls=${pipelineLlm.callCounts}, unmatched=0")
    }

    @Test
    fun testMeasureBaselineTopNGtSurvival_Apc_DemIsolated_LlmOn() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/graph/project-graph-a/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("apc 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlm = StrictClarifyMockLlm(
            refinementResponse = "앱카드 및 모니모페이 최근이력 1건 조회 기능 개발"
        )
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = mockLlm)

        val sr = "aCMBTBAPC024DEM.selTrcdIsInf 참조하여 앱카드회원ID 및 모니모페이회원ID 최근이력 1건 조회"
        val turn0 = engine.initSession(sr)
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(userStatement = "aCMBTBAPC024DEM을 통해서 조회하는 로직을 추가해줘")
        )
        val intent = engine.buildClarifyIntent(turn1.state)

        val resolved = AnalyzeInputResolver.resolve(
            rawInput = intent.refinedRequirement,
            inMemoryIntent = intent
        )

        val pipelineLlm = StrictPipelineLlm()
        val pipeline = RequirementAnalysisPipeline(pipelineLlm)
        val result = pipeline.analyze(
            primaryReq = resolved.effectiveRequirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = resolved.effectiveIntent
        )

        val gtFiles = listOf("APCMMTrcdIsInfSVO", "APCMMTrcdIsSVC", "APCMMTrcdIsSVCImpl", "APCMMTrcdIsBIZ", "ACMBTBAPC024DEM")
        val finalPaths = result.targetFiles.map { it.path }
        val survivedGt = gtFiles.filter { gt -> finalPaths.any { it.contains(gt, ignoreCase = true) } }

        println("[GT Baseline: APC DEM Isolated (Intent=LlmOn)] RefinedReq: \"${intent.refinedRequirement}\"")
        println("[GT Baseline: APC DEM Isolated (Intent=LlmOn)] Top-30 TargetFiles Count: ${result.targetFiles.size}")
        println("[GT Baseline: APC DEM Isolated (Intent=LlmOn)] GT Survived: ${survivedGt.size}/${gtFiles.size} (${survivedGt.joinToString(", ")})")
        val missing = gtFiles - survivedGt.toSet()
        if (missing.isNotEmpty()) {
            println("[GT Baseline: APC DEM Isolated (Intent=LlmOn)] GT Missing: $missing")
        }
        println("[GT Baseline: APC DEM Isolated (Intent=LlmOn)] Top-30 List:")
        result.targetFiles.forEachIndexed { idx, sf ->
            val isGt = gtFiles.any { sf.path.contains(it, ignoreCase = true) }
            val tag = if (isGt) "★[GT]" else "  [FP]"
            println("  $tag #${idx + 1}. [${sf.type}] ${sf.path} (${sf.description})")
        }
        println("[MockTelemetry: Apc_DemIsolated_LlmOn] ClarifyCalls=${mockLlm.callCounts}, PipelineCalls=${pipelineLlm.callCounts}, unmatched=0")
    }

    @Test
    fun testMeasureBaselineTopNGtSurvival_IsmCore() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/graph/project-graph-i/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("ISM 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = null)

        val sr = "케어회원 관리 화면 조회"
        val turn0 = engine.initSession(sr)
        val intent = engine.buildClarifyIntent(turn0.state)

        val resolved = AnalyzeInputResolver.resolve(
            rawInput = intent.refinedRequirement,
            inMemoryIntent = intent
        )

        val pipelineLlm = StrictPipelineLlm()
        val pipeline = RequirementAnalysisPipeline(pipelineLlm)
        val result = pipeline.analyze(
            primaryReq = resolved.effectiveRequirement,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = resolved.effectiveIntent
        )

        val gtFiles = listOf("CareMemberMgmtController", "CareMemberMgmtServiceImpl", "ECMBTBISM006Mapper", "ECMBTBISM006Mapper.xml")
        val finalPaths = result.targetFiles.map { it.path }
        val survivedGt = gtFiles.filter { gt -> finalPaths.any { it.contains(gt, ignoreCase = true) } }

        println("[GT Baseline: ISM Core (Intent=LlmOff)] RefinedReq: \"${intent.refinedRequirement}\"")
        println("[GT Baseline: ISM Core (Intent=LlmOff)] Top-30 TargetFiles Count: ${result.targetFiles.size}")
        println("[GT Baseline: ISM Core (Intent=LlmOff)] GT Survived: ${survivedGt.size}/${gtFiles.size} (${survivedGt.joinToString(", ")})")
        val missing = gtFiles - survivedGt.toSet()
        if (missing.isNotEmpty()) {
            println("[GT Baseline: ISM Core (Intent=LlmOff)] GT Missing: $missing")
        }
        println("[GT Baseline: ISM Core (Intent=LlmOff)] Top-30 List:")
        result.targetFiles.forEachIndexed { idx, sf ->
            val isGt = gtFiles.any { sf.path.contains(it, ignoreCase = true) }
            val tag = if (isGt) "★[GT]" else "  [FP]"
            println("  $tag #${idx + 1}. [${sf.type}] ${sf.path} (${sf.description})")
        }
        println("[MockTelemetry: IsmCore_LlmOff] BranchCalls=${pipelineLlm.callCounts}, unmatched=0")
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 2. 분류 줄 및 키워드 차집합 출력
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun testClassificationAndKeywordDiff_SurveyAdmin_LlmOff() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("survey_admin 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = null)

        val sr = "외부 Bizgo 연동 API(BizgoApiService)를 신규 생성하고, 알림톡 배치 구조와 동일하게 브랜드메시지 배치 3종(BrandMessageTemplateBatchRunner, BrandMessageTemplateBatchJob, BrandMessageTemplateBatchRepository) 및 DTO(BrandMessageTmplDto)를 신규 개발합니다."
        val turn0 = engine.initSession(sr)
        val intent = engine.buildClarifyIntent(turn0.state)

        printClassificationAndDiff("SurveyAdmin_LlmOff", intent, turn0.state, graph)
    }

    @Test
    fun testClassificationAndKeywordDiff_SurveyAdmin_LlmOn() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("survey_admin 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlm = StrictClarifyMockLlm(
            refinementResponse = "설문 발송 채널에 브랜드메시지 추가 및 Bizgo 연동 API 신규 개발"
        )
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = mockLlm)

        val turn0 = engine.initSession("설문 발송 채널에 브랜드메시지 추가")
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "외부 Bizgo 연동 API(BizgoApiService)를 신규 생성하고, 알림톡 배치 구조와 동일하게 브랜드메시지 배치 3종(BrandMessageTemplateBatchRunner, BrandMessageTemplateBatchJob, BrandMessageTemplateBatchRepository) 및 DTO(BrandMessageTmplDto)를 신규 개발합니다."
            )
        )
        val intent = engine.buildClarifyIntent(turn1.state)

        printClassificationAndDiff("SurveyAdmin_LlmOn", intent, turn1.state, graph)
    }

    @Test
    fun testClassificationAndKeywordDiff_Scenario4_Apc() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/graph/project-graph-a/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("apc 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = null)

        val sr = "교통카드 발급업체 변경 후, 이전 모바일교통카드 이용회원 체크 및 재발급 안내를 위한 신규서비스 개발 건. 교통카드 구 발급정보 조회 신규서비스 개발 (SAPACMM0802S01 기존서비스 참고). APCMMTrcdIsInfSVO 수정, APCMMTrcdIsSVC.java 신규서비스 추가, aCMBTBAPC024DEM.selTrcdIsInf 참조하여 앱카드회원ID 및 모니모페이회원ID 최근이력 1건 조회"
        val turn0 = engine.initSession(sr)
        val intent = engine.buildClarifyIntent(turn0.state)

        printClassificationAndDiff("Scenario4_APC", intent, turn0.state, graph)
    }

    @Test
    fun testClassificationAndKeywordDiff_Apc_DemIsolatedSimulation_LlmOnMissingDem() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/graph/project-graph-a/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("apc 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        // LLM이 aCMBTBAPC024DEM 식별자를 누락하고 일반 문장으로만 정제한 상황을 모사
        val mockLlm = StrictClarifyMockLlm(
            refinementResponse = "앱카드 및 모니모페이 최근이력 1건 조회 기능 개발"
        )
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = mockLlm)

        val sr = "aCMBTBAPC024DEM.selTrcdIsInf 참조하여 앱카드회원ID 및 모니모페이회원ID 최근이력 1건 조회"
        val turn0 = engine.initSession(sr)
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(userStatement = "aCMBTBAPC024DEM을 통해서 조회하는 로직을 추가해줘")
        )
        val intent = engine.buildClarifyIntent(turn1.state)

        printClassificationAndDiff("Apc_DemIsolated_LlmOn", intent, turn1.state, graph)
    }

    @Test
    fun testClassificationAndKeywordDiff_4Conditions() = kotlinx.coroutines.runBlocking {
        val files = mapOf(
            "com/example/OrderDto.java" to FileNode(
                path = "com/example/OrderDto.java",
                packageName = "com.example",
                className = "OrderDto",
                fileType = SpringFileType.DTO,
                layer = ArchitectureLayer.PERSISTENCE,
                methods = listOf(MethodSignature("getPay_type", "String", emptyList()))
            ),
            "com/example/SurveyServiceImpl.java" to FileNode(
                path = "com/example/SurveyServiceImpl.java",
                packageName = "com.example",
                className = "SurveyServiceImpl",
                localName = "SurveyServiceImpl",
                koreanComments = listOf("SurveyServiceImpl 설문 서비스 구현체"),
                fileType = SpringFileType.SERVICE,
                layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("executeSurvey", "void", emptyList()))
            ),
            "com/example/SurveyService.java" to FileNode(
                path = "com/example/SurveyService.java",
                packageName = "com.example",
                className = "SurveyService",
                localName = "SurveyServiceImpl",
                koreanComments = listOf("SurveyServiceImpl 설문 서비스 인터페이스"),
                fileType = SpringFileType.INTERFACE,
                layer = ArchitectureLayer.BUSINESS,
                methods = listOf(MethodSignature("executeSurvey", "void", emptyList()))
            )
        )
        val rels = listOf(
            Relationship(
                source = "com/example/SurveyServiceImpl.java",
                target = "com/example/SurveyService.java",
                type = RelationshipType.IMPLEMENTS
            )
        )
        val graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = "/test/root",
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files,
            relationships = rels,
            resourceNodes = emptyList(),
            statistics = GraphStatistics()
        )

        val scanner = Stage0GraphScanner(graph, minSpecificityScore = 1.0, proposalBudget = 10)

        // 1) 조건 1: LLM-off
        val engine1 = Stage0ClarificationEngine(scanner, graph, llmClient = null)
        val turn0_1 = engine1.initSession("신규 서비스 FooService 개발")
        val intent1 = engine1.buildClarifyIntent(turn0_1.state)
        printClassificationAndDiff("Condition1_LlmOff", intent1, turn0_1.state, graph)

        // Mock for condition 2, 3, 4
        val exclusionMockLlm = StrictClarifyMockLlm(
            refinementResponse = "SurveyServiceImpl 수정 제외 및 신규 FooService 추가",
            constraintsResponse = """
                [
                  {
                    "kind": "EXCLUDE_COMPONENT",
                    "value": "SurveyServiceImpl 수정 제외",
                    "rawStatement": "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘",
                    "evidence": "SurveyServiceImpl은 건드리지 말고"
                  }
                ]
            """.trimIndent()
        )

        // 2) 조건 2: Proposed Exclusion
        val engine2 = Stage0ClarificationEngine(scanner, graph, llmClient = exclusionMockLlm)
        val turn0_2 = engine2.initSession("주문 기능 개발")
        val turn1_2 = engine2.processTurn(
            turn0_2.state,
            Stage0ClarificationEngine.UserInput(userStatement = "SurveyServiceImpl은 건드리지 말고 신규 FooService를 추가해줘")
        )
        val intent2 = engine2.buildClarifyIntent(turn1_2.state)
        printClassificationAndDiff("Condition2_ProposedExclusion", intent2, turn1_2.state, graph)

        // 3) 조건 3: Affirmative Yes
        val turn2_3 = engine2.processTurn(
            turn1_2.state,
            Stage0ClarificationEngine.UserInput(userStatement = "응")
        )
        val intent3 = engine2.buildClarifyIntent(turn2_3.state)
        printClassificationAndDiff("Condition3_AffirmativeYes", intent3, turn2_3.state, graph)

        // 4) 조건 4: Negative No
        val turn2_4 = engine2.processTurn(
            turn1_2.state,
            Stage0ClarificationEngine.UserInput(userStatement = "아니요")
        )
        val intent4 = engine2.buildClarifyIntent(turn2_4.state)
        printClassificationAndDiff("Condition4_NegativeNo", intent4, turn2_4.state, graph)
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 3. Commit 2b 시뮬레이션 및 점수 분해 진단 (C-11, C-12)
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun testSimulateCommit2b_SurveyAdmin_LlmOn() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("survey_admin 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlm = StrictClarifyMockLlm(
            refinementResponse = "설문 발송 채널에 브랜드메시지 추가 및 Bizgo 연동 API 신규 개발"
        )
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = mockLlm)

        val turn0 = engine.initSession("설문 발송 채널에 브랜드메시지 추가")
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(
                userStatement = "외부 Bizgo 연동 API(BizgoApiService)를 신규 생성하고, 알림톡 배치 구조와 동일하게 브랜드메시지 배치 3종(BrandMessageTemplateBatchRunner, BrandMessageTemplateBatchJob, BrandMessageTemplateBatchRepository) 및 DTO(BrandMessageTmplDto)를 신규 개발합니다."
            )
        )
        val intent = engine.buildClarifyIntent(turn1.state)

        // C-11 시뮬레이션 규칙:
        // raw = intent.retentionAudit?.rawRefinedRequirement ?: intent.refinedRequirement
        // srText = "$raw\n" + missingBeforeFix.joinToString("") { "\n$it" }
        val raw = intent.retentionAudit?.rawRefinedRequirement ?: intent.refinedRequirement
        val missingBeforeFix = intent.retentionAudit?.missingBeforeFix ?: emptyList()
        val simulatedSrText = "$raw\n" + missingBeforeFix.joinToString("") { "\n$it" }

        val pipelineLlm = StrictPipelineLlm()
        val pipeline = RequirementAnalysisPipeline(pipelineLlm)
        val result = pipeline.analyze(
            primaryReq = simulatedSrText,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = intent
        )

        val gtFiles = listOf(
            "survey_list.jsp", "survey_write.jsp", "survey.list.js", "survey.write.js",
            "SurveyServiceImpl.java", "sql_survey.xml", "AlimtalkChnlDto.java", "AlimtalkTmplDto.java"
        )

        val finalPaths = result.targetFiles.filter { it.type == "MODIFY" }.map { it.path }
        val survivedGt = gtFiles.filter { gt -> finalPaths.any { it.contains(gt, ignoreCase = true) } }

        println("[Simulated 2b: survey_admin (Intent=LlmOn)] SimulatedSrText:\n\"\"\"\n$simulatedSrText\n\"\"\"")
        println("[Simulated 2b: survey_admin (Intent=LlmOn)] Top-30 TargetFiles Count: ${result.targetFiles.size}")
        println("[Simulated 2b: survey_admin (Intent=LlmOn)] GT Survived: ${survivedGt.size}/${gtFiles.size} (${survivedGt.joinToString(", ")})")
        val missing = gtFiles - survivedGt.toSet()
        if (missing.isNotEmpty()) {
            println("[Simulated 2b: survey_admin (Intent=LlmOn)] GT Missing: $missing")
        }
        println("[Simulated 2b: survey_admin (Intent=LlmOn)] Top-30 List:")
        result.targetFiles.forEachIndexed { idx, sf ->
            val isGt = gtFiles.any { sf.path.contains(it, ignoreCase = true) }
            val tag = if (isGt) "★[GT]" else "  [FP]"
            println("  $tag #${idx + 1}. [${sf.type}] ${sf.path} (${sf.description})")
        }
        println("[MockTelemetry: SurveyAdmin_LlmOn_Simulated2b] ClarifyCalls=${mockLlm.callCounts}, PipelineCalls=${pipelineLlm.callCounts}, unmatched=0")
    }

    @Test
    fun testSimulateCommit2b_Apc_DemIsolated_LlmOn() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/graph/project-graph-a/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("apc 메타그래프 존재 시에만 실행", graph)

        val scanner = Stage0GraphScanner(graph!!, minSpecificityScore = 1.0, proposalBudget = 10)
        val mockLlm = StrictClarifyMockLlm(
            refinementResponse = "앱카드 및 모니모페이 최근이력 1건 조회 기능 개발"
        )
        val engine = Stage0ClarificationEngine(scanner, graph, llmClient = mockLlm)

        val sr = "aCMBTBAPC024DEM.selTrcdIsInf 참조하여 앱카드회원ID 및 모니모페이회원ID 최근이력 1건 조회"
        val turn0 = engine.initSession(sr)
        val turn1 = engine.processTurn(
            turn0.state,
            Stage0ClarificationEngine.UserInput(userStatement = "aCMBTBAPC024DEM을 통해서 조회하는 로직을 추가해줘")
        )
        val intent = engine.buildClarifyIntent(turn1.state)

        val raw = intent.retentionAudit?.rawRefinedRequirement ?: intent.refinedRequirement
        val missingBeforeFix = intent.retentionAudit?.missingBeforeFix ?: emptyList()
        val simulatedSrText = "$raw\n" + missingBeforeFix.joinToString("") { "\n$it" }

        val pipelineLlm = StrictPipelineLlm()
        val pipeline = RequirementAnalysisPipeline(pipelineLlm)
        val result = pipeline.analyze(
            primaryReq = simulatedSrText,
            secondaryReq = "",
            projectGraph = graph,
            clarifyIntent = intent
        )

        val gtFiles = listOf("APCMMTrcdIsInfSVO", "APCMMTrcdIsSVC", "APCMMTrcdIsSVCImpl", "APCMMTrcdIsBIZ", "ACMBTBAPC024DEM")
        val finalPaths = result.targetFiles.map { it.path }
        val survivedGt = gtFiles.filter { gt -> finalPaths.any { it.contains(gt, ignoreCase = true) } }

        println("[Simulated 2b: APC DEM Isolated (Intent=LlmOn)] SimulatedSrText:\n\"\"\"\n$simulatedSrText\n\"\"\"")
        println("[Simulated 2b: APC DEM Isolated (Intent=LlmOn)] Top-30 TargetFiles Count: ${result.targetFiles.size}")
        println("[Simulated 2b: APC DEM Isolated (Intent=LlmOn)] GT Survived: ${survivedGt.size}/${gtFiles.size} (${survivedGt.joinToString(", ")})")
        val missing = gtFiles - survivedGt.toSet()
        if (missing.isNotEmpty()) {
            println("[Simulated 2b: APC DEM Isolated (Intent=LlmOn)] GT Missing: $missing")
        }
        println("[Simulated 2b: APC DEM Isolated (Intent=LlmOn)] Top-30 List:")
        result.targetFiles.forEachIndexed { idx, sf ->
            val isGt = gtFiles.any { sf.path.contains(it, ignoreCase = true) }
            val tag = if (isGt) "★[GT]" else "  [FP]"
            println("  $tag #${idx + 1}. [${sf.type}] ${sf.path} (${sf.description})")
        }
        println("[MockTelemetry: Apc_DemIsolated_LlmOn_Simulated2b] ClarifyCalls=${mockLlm.callCounts}, PipelineCalls=${pipelineLlm.callCounts}, unmatched=0")
    }

    @Test
    fun testScoreBreakdown_SurveyServiceImpl() = kotlinx.coroutines.runBlocking {
        val graphPath = "C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json"
        val graph = loadGraph(graphPath)
        Assume.assumeNotNull("survey_admin 메타그래프 존재 시에만 실행", graph)

        val fileNode = graph!!.files.values.find { it.className == "SurveyServiceImpl" }
        assertNotNull("SurveyServiceImpl 노드 존재 확인", fileNode)

        println("=== C-12: SurveyServiceImpl Score Breakdown Diagnostic ===")
        val cases = listOf(
            "LlmOff" to "설문 발송 채널에 브랜드메시지 추가",
            "LlmOn (Tagged)" to "설문 발송 채널에 브랜드메시지 추가 및 Bizgo 연동 API 신규 개발 (명시된 식별자: BizgoApiService, BrandMessageTemplateBatchJob, BrandMessageTemplateBatchRepository, BrandMessageTemplateBatchRunner, BrandMessageTmplDto)",
            "Simulated2b" to "설문 발송 채널에 브랜드메시지 추가 및 Bizgo 연동 API 신규 개발\nBizgoApiService\nBrandMessageTemplateBatchJob\nBrandMessageTemplateBatchRepository\nBrandMessageTemplateBatchRunner\nBrandMessageTmplDto"
        )

        val domainExtractor = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor(graph.files)
        val config = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig(maxHop = 3)
        val expander = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander(graph, domainExtractor, config)

        for ((label, text) in cases) {
            println("[$label] InputText: \"$text\"")

            // 1. Selector 실행하여 실제 Seed 및 SeedResult 도출
            val selector = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector(
                llmClient = StrictPipelineLlm(),
                projectBasePath = null,
                clarificationBridge = null
            )
            val seedResult = selector.selectSeeds(text, graph)
            println("  - SeedClasses: ${seedResult.seedClasses}")
            println("  - LayerHint: ${seedResult.layerHint}")

            // 2. Expander 실행하여 실제 확장 그래프 획득
            val expandedFiles = expander.expand(seedResult, text)
            val step = expandedFiles[fileNode!!.path]
            println("  - ExpansionStep for SurveyServiceImpl: hop=${step?.hop}, via=${step?.via}, from=${step?.from}")

            // 3. RelevanceScorer 실제 호출 (minScore = 10으로 주어 탈락 노드도 점수 계산값 수집)
            val scorer = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer(graph, fileLimit = 100, minScore = 10)
            val scoredList = scorer.scoreAndFilter(text, expandedFiles, seedResult)
            val targetScored = scoredList.find { it.className == "SurveyServiceImpl" }

            println("  - RelevanceScorer.scoreAndFilter Actual Output:")
            if (targetScored != null) {
                println("    * Actual Total Score: ${targetScored.score}")
                println("    * Discovery Reason: ${targetScored.discoveryReason}")
                println("    * Hop Distance: ${targetScored.hopDistance}")
                println("    * Protected: ${targetScored.isProtected} (Reason: ${targetScored.protectionReason})")
            } else {
                println("    * SurveyServiceImpl NOT FOUND in scored list (Score < 10 or not expanded)")
            }

            // 4. 구성 요소별 정밀 분해 (RelevanceScorer 공식과 동일한 가중치 계산)
            val totalNodes = graph.files.size + graph.resourceNodes.size
            val maxIdf = Math.log(totalNodes.toDouble())
            fun getDf(token: String): Int {
                val lower = token.lowercase()
                val fm = graph.files.values.count { it.className.contains(lower, ignoreCase = true) }
                val rm = graph.resourceNodes.count { it.path.substringAfterLast("/").contains(lower, ignoreCase = true) }
                return fm + rm
            }
            fun getIdfWeight(token: String): Double {
                val df = maxOf(1, getDf(token))
                return Math.log(totalNodes.toDouble() / df) / maxIdf
            }

            val stopWords = setOf(
                "controller", "service", "repository", "entity", "dto", "vo", "request", "response",
                "mapper", "view", "page", "screen", "api", "impl", "config", "exception", "handler",
                "util", "action", "svc", "svo", "dvo", "dao", "bo",
                "화면", "컨트롤러", "서비스", "레파지토리", "저장소", "엔티티", "디티오", "매퍼",
                "액션", "페이지", "에이피아이", "구현체", "인터페이스"
            )
            val directEnglish = Regex("[a-zA-Z0-9]{3,}").findAll(text)
                .map { it.value }
                .filter { it.any { c -> c.isLetter() } }
                .filterNot { stopWords.contains(it.lowercase()) }
                .toList()

            val dict = DomainDictionary.load(graph)
            val words = text.split(Regex("\\s+"))
            val nouns = mutableListOf<String>()
            val verbs = mutableListOf<String>()
            val translatedEnglish = mutableListOf<String>()
            for (w in words) {
                val cw = w.replace(Regex("[^가-힣a-zA-Z0-9]"), "")
                if (cw.length < 2 || stopWords.contains(cw.lowercase())) continue
                if (cw.endsWith("한다") || cw.endsWith("해라") || cw.endsWith("추가") || cw.endsWith("수정") || cw.endsWith("삭제")) {
                    verbs.add(cw.replace("한다", "").replace("해라", ""))
                } else {
                    nouns.add(cw.replace("을", "").replace("를", "").replace("이", "").replace("가", "").replace("은", "").replace("는", ""))
                }
                translatedEnglish.addAll(dict.translate(cw).filterNot { stopWords.contains(it.lowercase()) })
            }

            val hopScore = when (step?.hop) {
                0 -> 40
                1 -> 30
                2 -> 15
                else -> 0
            }

            val directMatches = directEnglish.filter { eng -> fileNode.className.contains(eng, ignoreCase = true) }
            val transMatches = translatedEnglish.filter { eng -> fileNode.className.contains(eng, ignoreCase = true) }
            var nameMatchScore = 0.0
            if (directMatches.isNotEmpty()) {
                val maxToken = directMatches.maxByOrNull { getIdfWeight(it) }!!
                nameMatchScore = 50.0 * getIdfWeight(maxToken)
            } else if (transMatches.isNotEmpty()) {
                val maxToken = transMatches.maxByOrNull { getIdfWeight(it) }!!
                nameMatchScore = 30.0 * getIdfWeight(maxToken)
            }

            val layerAlignScore = if (seedResult.layerHint.any { layer -> fileNode.layer.name.contains(layer, ignoreCase = true) || fileNode.fileType.name.contains(layer, ignoreCase = true) }) 15 else 0

            var commentMatchScore = 0.0
            val matchedComment = fileNode.koreanComments.find { comment ->
                nouns.any { n -> comment.contains(n) } || verbs.any { v -> comment.contains(v) }
            }
            if (matchedComment != null) {
                commentMatchScore = 20.0
            }

            println("    * Score Components: HopScore=$hopScore + NameMatch=${String.format("%.1f", nameMatchScore)} + LayerAlign=$layerAlignScore + CommentMatch=${String.format("%.1f", commentMatchScore)} = ${(hopScore + nameMatchScore + layerAlignScore + commentMatchScore).toInt()}")
        }
    }
}
