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
 * 3. 각 Step 시작 시 [onStepStart] 콜백으로 즉각 UI 노출
 * 4. 각 Step 완료 시 [onStep] 콜백으로 결과 UI 전달
 * 5. [TaskCancellationToken]을 통해 외부에서 취소 가능
 */
class TaskAgent(
    private val onStep: (stepLabel: String, result: TaskPipeline.StepResult, isApplyable: Boolean) -> Unit,
    private val onStepStart: (stepLabel: String) -> Unit = {}
) : WuwAgent {

    private val logger = Logger.getInstance(TaskAgent::class.java)
    private val client = OllamaClient()

    override fun execute(context: AgentContext, onSuccess: (String) -> Unit) {
        TaskCancellationToken.reset()   // 이전 취소 상태 초기화

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            context.project, "WuwAgent: Task 실행 중", true   // canBeCancelled=true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                // 현재 스레드를 토큰에 등록 (cancel() 시 인터럽트)
                TaskCancellationToken.backgroundThread = Thread.currentThread()

                // ── Step 0: 의도 분석 ──────────────────────────
                logger.info("TaskAgent: 의도 분석 시작 → '${context.payloadText}'")
                indicator.text = "사용자 의도 분석 중..."

                if (TaskCancellationToken.isCancelled.get()) { onSuccess("__cancelled__"); return }

                val pipeline = IntentAnalyzer.analyze(context.payloadText, client)
                logger.info("TaskAgent: Pipeline 결정 → ${pipeline::class.simpleName}")

                // ── Step 1~N: Pipeline 순차 실행 ──────────────
                for ((idx, step) in pipeline.steps.withIndex()) {
                    // 취소 체크 (Step 시작 전)
                    if (TaskCancellationToken.isCancelled.get() || indicator.isCanceled) {
                        logger.info("TaskAgent: 취소됨 — ${step.label} 건너뜀")
                        onSuccess("__cancelled__")
                        return
                    }

                    logger.info("TaskAgent: [${idx + 1}/${pipeline.steps.size}] ${step.label} 시작")
                    indicator.text = "${step.label} 실행 중..."
                    onStepStart(step.label)

                    try {
                        val result = step.executeSync(context, client)

                        // executeSync 완료 후에도 취소 여부 재확인
                        if (TaskCancellationToken.isCancelled.get()) {
                            logger.info("TaskAgent: 취소됨 — ${step.label} 결과 버림")
                            onSuccess("__cancelled__")
                            return
                        }

                        logger.info("TaskAgent: [${idx + 1}/${pipeline.steps.size}] ${step.label} 완료 (success=${result.isSuccess})")
                        onStep(step.label, result, step.isApplyable)

                        // ❌ 에러 발생 시 파이프라인 즉시 중단
                        if (!result.isSuccess) {
                            logger.warn("TaskAgent: ${step.label} 실패 → 파이프라인 중단")
                            onSuccess("__task_done__")
                            return
                        }

                    } catch (e: InterruptedException) {
                        logger.info("TaskAgent: 스레드 인터럽트 → 취소됨")
                        Thread.currentThread().interrupt()
                        onSuccess("__cancelled__")
                        return
                    } catch (e: Exception) {
                        if (TaskCancellationToken.isCancelled.get()) {
                            logger.info("TaskAgent: 취소 중 예외 → 정상 취소로 처리")
                            onSuccess("__cancelled__")
                            return
                        }
                        logger.error("TaskAgent: ${step.label} 실행 중 예외", e)
                        onStep(step.label, TaskPipeline.StepResult("", "", "[오류] ${e.message}", "", isSuccess = false), false)
                        onSuccess("__task_done__")
                        return
                    }
                }

                onSuccess("__task_done__")
            }
        })
    }
}
