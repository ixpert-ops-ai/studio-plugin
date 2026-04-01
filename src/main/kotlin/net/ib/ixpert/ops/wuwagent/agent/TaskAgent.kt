package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import net.ib.ixpert.ops.wuwagent.client.OllamaClient

/**
 * 상위 오케스트레이터 Agent.
 *
 * 1. [IntentAnalyzer]로 사용자 의도 → [TaskPipeline] 결정
 * 2. 파이프라인의 각 [TaskPipeline.AgentStep]을 단일 백그라운드 태스크 안에서 순차 실행
 * 3. 각 Step 완료 시 [onStep] 콜백으로 중간 결과를 UI에 즉시 전달
 * 4. 전체 완료 후 [WuwAgent.execute]의 onSuccess 호출
 *
 * @param onStep (stepLabel, content, isApplyable) — Step별 중간 결과 콜백
 */
class TaskAgent(
    private val onStep: (stepLabel: String, content: String, isApplyable: Boolean) -> Unit
) : WuwAgent {

    private val logger = Logger.getInstance(TaskAgent::class.java)
    private val client = OllamaClient()

    override fun execute(context: AgentContext, onSuccess: (String) -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            context.project, "WuwAgent: Task 분석 중", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                // ── Step 0: 의도 분석 ──────────────────────────
                logger.info("TaskAgent: 의도 분석 시작 → '${context.payloadText}'")
                indicator.text = "사용자 의도 분석 중..."
                val pipeline = IntentAnalyzer.analyze(context.payloadText, client)
                logger.info("TaskAgent: Pipeline 결정 → ${pipeline::class.simpleName}")

                // ── Step 1~N: Pipeline 순차 실행 ──────────────
                pipeline.steps.forEachIndexed { idx, step ->
                    logger.info("TaskAgent: [${idx + 1}/${pipeline.steps.size}] ${step.label} 시작")
                    indicator.text = "${step.label} 실행 중..."

                    val result = step.executeSync(context, client)
                    logger.info("TaskAgent: [${idx + 1}/${pipeline.steps.size}] ${step.label} 완료")

                    // 중간 결과를 즉시 UI로 전달
                    onStep(step.label, result, step.isApplyable)
                }

                // ── 완료 신호 ──────────────────────────────────
                onSuccess("__task_done__")
            }
        })
    }
}
