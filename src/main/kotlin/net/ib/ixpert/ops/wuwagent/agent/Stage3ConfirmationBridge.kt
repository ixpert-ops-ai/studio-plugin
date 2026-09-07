package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Stage 2 Trimming 이후, Stage 3 LLM Verification 직전에 사용자에게 
 * 후보 파일 목록을 확인받고 제외/확정할 수 있도록 중개하는 브릿지 인터페이스
 */
fun interface Stage3ConfirmationBridge {
    /**
     * @param candidates Trimming된 대상 파일 후보 목록
     * @return 사용자가 명시적으로 선택한 파일 경로 목록 (null: 건너뛰기, 타임아웃, Headless 등)
     */
    fun requestConfirmation(candidates: List<TargetFileSpec>): List<String>?

    /**
     * 확인 카드를 UI에서 숨김 처리
     */
    fun hideConfirmation() {}
}

/**
 * IntelliJ JCEF Webview UI와 통신하는 Stage 3 Confirmation Bridge 구현체
 */
class JcefStage3ConfirmationBridge(
    private val project: Project?,
    private val timeoutSeconds: Long = 300 // 5분 타임아웃 (L1 Clarification과 동일)
) : Stage3ConfirmationBridge {

    companion object {
        private val pendingConfirmations = ConcurrentHashMap<String, CompletableFuture<List<String>?>>()

        fun completeConfirmation(projectId: String, selectedPaths: List<String>?) {
            val future = pendingConfirmations.remove(projectId)
            future?.complete(selectedPaths)
        }

        fun cancelConfirmation(projectId: String) {
            val future = pendingConfirmations.remove(projectId)
            future?.complete(null)
        }
    }

    override fun requestConfirmation(candidates: List<TargetFileSpec>): List<String>? {
        if (project == null) return null
        val projectId = project.locationHash

        val future = CompletableFuture<List<String>?>()
        pendingConfirmations[projectId] = future

        ApplicationManager.getApplication().invokeLater {
            try {
                val bridge = net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                val payload = mapOf(
                    "projectId" to projectId,
                    "candidates" to candidates.map {
                        mapOf(
                            "order" to it.order,
                            "path" to it.path,
                            "type" to it.type,
                            "description" to it.description
                        )
                    },
                    "message" to "Stage 3 최종 LLM 검증 전 후보 파일 목록입니다. 분석에 포함할 파일을 선택해 주세요."
                )
                val payloadStr = Gson().toJson(payload)
                bridge.sendMessage("stage3Confirmation/request", payloadStr, "system")
            } catch (e: Exception) {
                pendingConfirmations.remove(projectId)
                future.complete(null)
            }
        }

        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pendingConfirmations.remove(projectId)
            null
        }
    }

    override fun hideConfirmation() {
        if (project == null) return
        ApplicationManager.getApplication().invokeLater {
            try {
                val bridge = net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                bridge.sendMessage("stage3Confirmation/hide", "", "system")
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
