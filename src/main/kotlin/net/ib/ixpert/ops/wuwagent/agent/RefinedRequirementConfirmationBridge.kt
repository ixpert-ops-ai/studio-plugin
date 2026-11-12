package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedConfirmationDecision
import net.ib.ixpert.ops.wuwagent.agent.clarify.RefinedRequirementConfirmationBridge
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 응답을 기다리는 확인 요청 표. 화면 응답(complete)과 대기(await)를 이어 준다. IDE 없이 테스트할 수 있게 따로 둔다. */
class RefinedConfirmationRegistry {
    /** requestId: 이 확인을 만든 요청(백엔드 rid + messageId). 응답이 같은 요청의 것인지 가릴 때 쓴다(B-85 1 F). 시험에서 쓰는 이전 형식은 null. */
    private class Pending(val future: CompletableFuture<RefinedConfirmationDecision?>, val requestId: String?)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** 같은 키에 이미 기다리는 요청이 있으면 그 요청은 새 요청이 대신한 것으로 끝낸다(분석을 시작하지 않는다). */
    fun register(key: String, requestId: String? = null): CompletableFuture<RefinedConfirmationDecision?> {
        val future = CompletableFuture<RefinedConfirmationDecision?>()
        pending.put(key, Pending(future, requestId))?.future?.complete(RefinedConfirmationDecision.Superseded)
        return future
    }

    /** 기다리는 요청이 있으면 대신 처리됐다고 끝낸다(새 /clarify). 없으면 아무 일도 없다. */
    fun supersede(key: String) {
        pending.remove(key)?.future?.complete(RefinedConfirmationDecision.Superseded)
    }

    fun isPending(key: String): Boolean = pending.containsKey(key)

    /** 지금 기다리는 요청의 식별자(없으면 null). 오래된 응답을 무시할 때 로그에 남긴다. */
    fun pendingRequestId(key: String): String? = pending[key]?.requestId

    fun complete(key: String, decision: RefinedConfirmationDecision?) {
        pending.remove(key)?.future?.complete(decision)
    }

    /**
     * 응답이 지금 기다리는 요청의 것일 때만 처리한다(B-85 1 F). 기다리는 요청이 없거나 요청 식별자가 다르면(오래된 카드의 응답) 처리하지 않고 false를 돌려준다.
     * 기다리는 요청에 식별자가 없으면(이전 형식) 식별자를 따지지 않는다.
     */
    fun completeIfCurrent(key: String, requestId: String?, decision: RefinedConfirmationDecision?): Boolean {
        val current = pending[key] ?: return false
        if (current.requestId != null && current.requestId != requestId) return false
        if (!pending.remove(key, current)) return false
        current.future.complete(decision)
        return true
    }

    fun cancel(key: String) = complete(key, RefinedConfirmationDecision.Cancel)

    /**
     * 시간 안에 응답이 없으면 [RefinedConfirmationDecision.TimedOut], 대기가 깨지면 null(결정 없음)이다.
     * 정리할 때는 이 요청의 항목일 때만 지운다(그 사이 같은 키로 등록된 새 요청을 지우지 않는다).
     */
    fun await(key: String, future: CompletableFuture<RefinedConfirmationDecision?>, timeoutSeconds: Long): RefinedConfirmationDecision? =
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            removeIfSame(key, future)
            RefinedConfirmationDecision.TimedOut
        } catch (e: Exception) {
            removeIfSame(key, future)
            null
        }

    private fun removeIfSame(key: String, future: CompletableFuture<RefinedConfirmationDecision?>) {
        pending.computeIfPresent(key) { _, p -> if (p.future === future) null else p }
    }

    fun forget(key: String) {
        pending.remove(key)
    }
}

/** IntelliJ JCEF 웹뷰와 통신하는 정제문 확인 브리지. Stage3ConfirmationBridge와 같은 요청·응답·대기 방식이다. */
class JcefRefinedRequirementConfirmationBridge(
    private val project: Project?,
    private val timeoutSeconds: Long = 300, // 5분, Stage 3 확인과 같다
    /** 이 카드를 만든 요청의 식별자(rid + messageId). 카드 payload에 실리고, 웹뷰가 응답에 되돌려 보낸다. null이면 식별자를 따지지 않는 이전 동작. */
    private val requestId: String? = null
) : RefinedRequirementConfirmationBridge {

    companion object {
        private val registry = RefinedConfirmationRegistry()

        /** 확인 → 저장 → 분석 시작이 진행 중인 프로젝트. 이 동안 들어온 두 번째 /clarify-confirm은 무시한다. */
        private val activeHandoffs: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun beginHandoff(projectId: String): Boolean = activeHandoffs.add(projectId)

        fun endHandoff(projectId: String) {
            activeHandoffs.remove(projectId)
        }

        fun isHandoffActive(projectId: String): Boolean = activeHandoffs.contains(projectId)

        /** 새 /clarify가 시작되면 기다리는 확인 카드를 정리하고(이전 분석을 시작하지 않는다) 진행 표지를 푼다. */
        fun supersedePending(projectId: String) {
            println("[RefineGate] 새 /clarify로 대기 카드 정리 projectId=$projectId 대기중=${registry.isPending(projectId)} 확인진행중=${activeHandoffs.contains(projectId)}")
            registry.supersede(projectId)
            activeHandoffs.remove(projectId)
        }

        /** "이대로 분석"(editedText=null) 또는 "수정한 내용으로 분석"(editedText=고친 문장). */
        fun completeConfirmation(projectId: String, editedText: String?) {
            registry.complete(
                projectId,
                if (editedText == null) RefinedConfirmationDecision.Proceed else RefinedConfirmationDecision.ProceedEdited(editedText)
            )
        }

        fun cancelConfirmation(projectId: String) = registry.cancel(projectId)

        /**
         * 요청 식별자를 확인하는 응답 처리(B-85 1 F). 지금 기다리는 요청의 응답이면 처리하고 true, 기다리는 요청이 없거나 식별자가 다르면(오래된 카드의 응답) 무시하고 false.
         * 무시하면 "[RefineGate] stale response ignored" 로그를 남기고 호출한 쪽이 [CardWaitLog] 줄을 남긴다.
         */
        fun completeConfirmation(projectId: String, requestId: String?, editedText: String?): Boolean {
            val accepted = registry.completeIfCurrent(
                projectId, requestId,
                if (editedText == null) RefinedConfirmationDecision.Proceed else RefinedConfirmationDecision.ProceedEdited(editedText)
            )
            if (!accepted) logStale(projectId, requestId)
            return accepted
        }

        fun cancelConfirmation(projectId: String, requestId: String?): Boolean {
            val accepted = registry.completeIfCurrent(projectId, requestId, RefinedConfirmationDecision.Cancel)
            if (!accepted) logStale(projectId, requestId)
            return accepted
        }

        private fun logStale(projectId: String, requestId: String?) {
            // 오래된 응답은 무시했으므로 기다린 시간은 모른다("-")
            CardWaitLog.log(CardWaitLog.Card.REFINE, null, CardWaitLog.Result.STALE, null, requestId)
            println("[RefineGate] stale response ignored projectId=$projectId requestId=${requestId ?: "-"} 기다리는 요청=${registry.pendingRequestId(projectId) ?: (if (registry.isPending(projectId)) "식별자 없음" else "없음")}")
        }
    }

    override fun requestConfirmation(originalRequirement: String, refinedRequirement: String): RefinedConfirmationDecision? =
        requestConfirmation(originalRequirement, refinedRequirement, null)

    override fun requestConfirmation(originalRequirement: String, refinedRequirement: String, decisionNote: String?): RefinedConfirmationDecision? {
        if (project == null) return null
        val projectId = project.locationHash
        val future = registry.register(projectId, requestId)
        val startedMs = System.currentTimeMillis() // B-85 7: CardWaitLog의 waitedMs(카드를 보낸 뒤 결과가 정해질 때까지)

        ApplicationManager.getApplication().invokeLater {
            try {
                val bridge = net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                val payload = mutableMapOf<String, Any>(
                    "projectId" to projectId,
                    "originalRequirement" to originalRequirement,
                    "refinedRequirement" to refinedRequirement,
                    "message" to "분석을 시작하기 전에 정제된 요구사항을 확인해 주세요. 그대로 진행하거나 고쳐서 진행할 수 있습니다."
                )
                // 정제문과 따로 보이는 "확인 필요" 문장(B-84). 정제문 칸의 수정과 무관하게 카드에 남는다.
                if (!decisionNote.isNullOrBlank()) payload["decisionNote"] = decisionNote
                // 요청 식별자(B-85 1 F): 웹뷰가 응답에 되돌려 보내고, 지금 기다리는 요청과 다르면 무시한다
                if (!requestId.isNullOrBlank()) payload["requestId"] = requestId
                println(RefineGateLog.requestSentLine(Integer.toHexString(originalRequirement.hashCode()), projectId, refinedRequirement.length, ConfirmClock.sinceText(), requestId))
                bridge.sendMessage("refinedConfirmation/request", Gson().toJson(payload), "system")
            } catch (e: Exception) {
                registry.complete(projectId, null)
            }
        }
        val decision = registry.await(projectId, future, timeoutSeconds)
        CardWaitLog.log(CardWaitLog.Card.REFINE, null, CardWaitLog.refineResult(decision), System.currentTimeMillis() - startedMs, requestId)
        return decision
    }

    override fun requestIdForLog(): String? = requestId

    override fun expireConfirmation() {
        if (project == null) return
        ApplicationManager.getApplication().invokeLater {
            try {
                net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project).sendMessage("refinedConfirmation/expired", "", "system")
            } catch (e: Exception) {
                // ignore
            }
        }
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
