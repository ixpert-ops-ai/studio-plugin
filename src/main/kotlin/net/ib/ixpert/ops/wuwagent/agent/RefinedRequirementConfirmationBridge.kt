package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision
import net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedRequirementConfirmationBridge
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 응답을 기다리는 확인 요청 표. 화면 응답(complete)과 대기(await)를 이어 준다. IDE 없이 테스트할 수 있게 따로 둔다. */
class RefinedConfirmationRegistry {
    private val pending = ConcurrentHashMap<String, CompletableFuture<RefinedConfirmationDecision?>>()

    fun register(key: String): CompletableFuture<RefinedConfirmationDecision?> {
        val future = CompletableFuture<RefinedConfirmationDecision?>()
        pending[key] = future
        return future
    }

    fun complete(key: String, decision: RefinedConfirmationDecision?) {
        pending.remove(key)?.complete(decision)
    }

    fun cancel(key: String) = complete(key, RefinedConfirmationDecision.Cancel)

    /** 시간 안에 응답이 없거나 대기가 깨지면 null(결정 없음)이다. */
    fun await(key: String, future: CompletableFuture<RefinedConfirmationDecision?>, timeoutSeconds: Long): RefinedConfirmationDecision? =
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pending.remove(key)
            null
        }

    fun forget(key: String) {
        pending.remove(key)
    }
}

/** IntelliJ JCEF 웹뷰와 통신하는 정제문 확인 브리지. Stage3ConfirmationBridge와 같은 요청·응답·대기 방식이다. */
class JcefRefinedRequirementConfirmationBridge(
    private val project: Project?,
    private val timeoutSeconds: Long = 300 // 5분, Stage 3 확인과 같다
) : RefinedRequirementConfirmationBridge {

    companion object {
        private val registry = RefinedConfirmationRegistry()

        /** "이대로 분석"(editedText=null) 또는 "수정한 내용으로 분석"(editedText=고친 문장). */
        fun completeConfirmation(projectId: String, editedText: String?) {
            registry.complete(
                projectId,
                if (editedText == null) RefinedConfirmationDecision.Proceed else RefinedConfirmationDecision.ProceedEdited(editedText)
            )
        }

        fun cancelConfirmation(projectId: String) = registry.cancel(projectId)
    }

    override fun requestConfirmation(originalRequirement: String, refinedRequirement: String): RefinedConfirmationDecision? {
        if (project == null) return null
        val projectId = project.locationHash
        val future = registry.register(projectId)

        ApplicationManager.getApplication().invokeLater {
            try {
                val bridge = net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                val payload = mapOf(
                    "projectId" to projectId,
                    "originalRequirement" to originalRequirement,
                    "refinedRequirement" to refinedRequirement,
                    "message" to "분석을 시작하기 전에 정제된 요구사항을 확인해 주세요. 그대로 진행하거나 고쳐서 진행할 수 있습니다."
                )
                bridge.sendMessage("refinedConfirmation/request", Gson().toJson(payload), "system")
            } catch (e: Exception) {
                registry.complete(projectId, null)
            }
        }
        return registry.await(projectId, future, timeoutSeconds)
    }

    override fun hideConfirmation() {
        if (project == null) return
        ApplicationManager.getApplication().invokeLater {
            try {
                net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project).sendMessage("refinedConfirmation/hide", "", "system")
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
