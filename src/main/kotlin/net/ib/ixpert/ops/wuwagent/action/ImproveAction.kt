package net.ib.ixpert.ops.wuwagent.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.agent.TaskAgent
import net.ib.ixpert.ops.wuwagent.agent.TaskPipeline
import net.ib.ixpert.ops.wuwagent.service.EditorApplyService
import net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge
import com.intellij.openapi.diagnostic.Logger

/**
 * 에디터 우클릭 → "Improve Code" 액션.
 * TaskAgent 파이프라인을 통해 코드 개선(Analyze → Improve)을 실행합니다.
 */
class ImproveAction : AnAction() {
    private val logger = Logger.getInstance(ImproveAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val bridge = JcefBridge.getInstance(project)
        val messageId = "task_${System.currentTimeMillis()}"
        val fileName = editor.virtualFile?.name ?: ""
        val hasSelection = editor.selectionModel.hasSelection()
        // actionPerformed 시점에 선택 텍스트 즉시 캡처 (Step 진행 중 selection이 해제될 수 있음)
        val selectedText = if (hasSelection) editor.selectionModel.selectedText ?: "" else ""

        // 개선 요청 텍스트 (라우터가 intent 분석 후 ImprovePipeline으로 실행)
        val improveRequest = "/improve 선택된 코드를 분석하고 최선의 방법으로 개선해주세요."
        val context = AgentContext(project, editor, improveRequest)

        // 선택 영역 없이 전체 파일이 1500줄 이상인 경우 사전 안내 말풍선 출력
        if (!hasSelection && editor.document.lineCount >= 1500) {
            val warnMsgId = "${messageId}_warn"
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage("task_start", "", warnMsgId)
                bridge.sendMessage(
                    subType = "task_step",
                    content = "코드가 1500줄 이상입니다. 전체 개선 시 결과가 불완전할 수 있습니다.\n드래그로 범위를 지정하여 실행하는 것을 권장합니다.",
                    messageId = warnMsgId,
                    meta = mapOf("applyable" to "false", "isSuccess" to "true")
                )
            }
        }

        ApplicationManager.getApplication().invokeLater {
            bridge.sendMessage("task_start", "코드 개선을 시작합니다...", messageId)
        }

        val stepNotiIdx = intArrayOf(0)
        var analysisHeaderSent = false
        val onStepStart = { stepLabel: String, stepMsgId: String, isApplyable: Boolean ->
            logger.info("ImproveAction: Step 시작 → $stepLabel (stepMsgId=$stepMsgId, isApplyable=$isApplyable)")
            val notiId = "${messageId}_noti_${stepNotiIdx[0]}"
            stepNotiIdx[0]++
            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage("step_noti", stepLabel, notiId, mapOf("status" to "started"))
                if (stepMsgId == messageId) {
                    // Step 1: 기존 말풍선에 진행 상태 업데이트 + 파일명/범위 헤더 즉시 전송
                    bridge.sendMessage("task_progress", "$stepLabel LLM 응답 대기 중...", stepMsgId)
                    if (!analysisHeaderSent) {
                        analysisHeaderSent = true
                        val scopeText = if (hasSelection) "선택 영역" else "전체 파일"
                        bridge.sendMessageChunk(messageId, "### 분석 대상: `$fileName` ($scopeText)\n\n")
                    }
                } else if (!isApplyable && stepMsgId.endsWith("_s3")) {
                    // Step 3: 안정성 평가 - 새 말풍선, 파일 메타 없음
                    bridge.sendMessage("task_start", "$stepLabel LLM 응답 대기 중...", stepMsgId)
                } else if (!isApplyable) {
                    // Step 2: 텍스트 스트리밍 step - 새 말풍선 생성 + Diff 버튼용 메타 전달
                    bridge.sendMessage("task_start", "$stepLabel LLM 응답 대기 중...", stepMsgId,
                        mapOf(
                            "filePath"     to (editor.virtualFile?.path ?: ""),
                            "hasSelection" to hasSelection.toString(),
                            "selectedText" to selectedText
                        ))
                } else {
                    // Step 2+, 코드 카드 step: 진행 상태만 기존 말풍선에 업데이트
                    bridge.sendMessage("task_progress", "$stepLabel LLM 응답 대기 중...", messageId)
                }
            }
        }

        val stepNotiDoneIdx = intArrayOf(0)
        val onStep = { stepLabel: String, result: TaskPipeline.StepResult, _: Boolean, stepMsgId: String ->
            logger.info("ImproveAction: Step 완료 → $stepLabel (stepMsgId=$stepMsgId)")
            val notiId = "${messageId}_noti_${stepNotiDoneIdx[0]}"
            stepNotiDoneIdx[0]++
            // [이전 코드 백업 - 선택 케이스 SimpleDiff 2-way 방식, /viewDiffSimple 전송]
            // val meta = mapOf("stepLabel" to stepLabel, "applyable" to "false", "isSuccess" to result.isSuccess.toString())
            // bridge.sendMessage(subType = "task_step", content = result.llmResponse, messageId = stepMsgId, meta = meta)

            // 선택 케이스 Step2: 원본 풀코드에서 선택 범위를 개선 코드로 교체 → 3-way Diff용 modifiedFullCode 생성
            // Step3(안정성 평가)는 코드가 없으므로 제외
            val modifiedFullCode = if (hasSelection && stepMsgId != messageId && !stepMsgId.endsWith("_s3") && result.isSuccess && selectedText.isNotBlank()) {
                val fullCode = ApplicationManager.getApplication().runReadAction(
                    com.intellij.openapi.util.Computable { editor.document.text }
                )
                val improvedCode = EditorApplyService.extractCodeBlock(result.llmResponse).takeIf { it.isNotBlank() } ?: result.llmResponse
                fullCode.replaceFirst(selectedText, improvedCode)
            } else ""

            ApplicationManager.getApplication().invokeLater {
                bridge.sendMessage(
                    "step_noti", stepLabel, notiId,
                    mapOf("status" to if (result.isSuccess) "completed" else "failed")
                )
                val meta = mutableMapOf(
                    "stepLabel" to stepLabel,
                    "applyable" to "false",
                    "isSuccess" to result.isSuccess.toString()
                )
                if (modifiedFullCode.isNotBlank()) meta["modifiedFullCode"] = modifiedFullCode
                bridge.sendMessage(
                    subType = "task_step",
                    content = result.llmResponse,
                    messageId = stepMsgId,
                    meta = meta
                )
            }
        }

        val agent = TaskAgent(messageId, onStep, onStepStart)
        agent.execute(
            context,
            onSuccess = { _ ->
                ApplicationManager.getApplication().invokeLater {
                    bridge.sendMessage("task_success", "코드 개선이 완료되었습니다.", messageId)
                }
            },
            onChunk = { chunk ->
                ApplicationManager.getApplication().invokeLater {
                    bridge.sendMessageChunk(messageId, chunk)
                }
            },
            onError = { errorMsg ->
                ApplicationManager.getApplication().invokeLater {
                    bridge.sendMessage("error", errorMsg, messageId)
                }
            }
        )
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
}
