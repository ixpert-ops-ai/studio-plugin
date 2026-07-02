package net.ib.ixpert.ops.wuwagent.agent.stage0

import java.util.UUID

class Stage0Pipeline(
    private val metaGraphProvider: () -> String
) {
    private var currentSession: Stage0Session? = null

    fun startAnalysis(input: SrInput): Stage0Output {
        val quality = SrQualityAnalyzer.analyze(input)
        val archDecisions = ArchitectureDecisionExtractor.extract(input)
        val classification = SrClassifier.classify(input, metaGraphProvider())
        val deficiencies = DeficiencyDetector.detect(input, quality, classification, archDecisions)

        val analysis = SrAnalysis(
            srInput = input,
            classification = classification,
            qualityIndicators = quality,
            deficiencies = deficiencies,
            architectureDecisions = archDecisions
        )

        val questions = AdaptiveQuestionGenerator.generate(analysis, metaGraphProvider())
        
        val sessionId = UUID.randomUUID().toString()
        currentSession = Stage0Session(
            sessionId = sessionId,
            input = input,
            analysis = analysis,
            questions = questions
        )

        val initialGate = Stage0Gate.evaluate(analysis, questions, emptyMap())
        
        return Stage0Output(sessionId, initialGate, analysis)
    }

    fun submitAnswers(answers: Map<Stage0Question, String>): GateEvaluation {
        val session = currentSession ?: throw IllegalStateException("진행 중인 Stage 0 세션이 없습니다.")
        session.answers.putAll(answers)

        return Stage0Gate.evaluate(session.analysis, session.questions, session.answers)
    }

    fun skipQuestions(): GateEvaluation {
        val session = currentSession ?: throw IllegalStateException("진행 중인 Stage 0 세션이 없습니다.")
        return Stage0Gate.evaluate(session.analysis, session.questions, session.answers)
    }
}
