package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DirectoryNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScopeConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ScopeSelectionResult
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DependencySuggestion
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

object ScopeSelectionBridge {
    private val scopeSelectionContinuations = ConcurrentHashMap<String, Continuation<ScopeSelectionResult?>>()
    private val dependencyContinuations = ConcurrentHashMap<String, Continuation<List<String>?>>()

    /**
     * 블록이 어떻게 끝나든(값, null(건너뛰기·시간 초과), 예외, 코루틴 취소) 마지막에 [hide]를 부른다(B-86 c (i)). Stage 3 카드의 `finally { hideConfirmation() }`와 같은 방식이다.
     * hide가 던져도 결과는 그대로다.
     */
    internal suspend fun <T> hideAfter(hide: () -> Unit, block: suspend () -> T): T {
        try {
            return block()
        } finally {
            try { hide() } catch (e: Exception) { /* 화면 정리 실패는 진행에 영향 없음 */ }
        }
    }

    /** 패키지 카드를 화면에서 내린다(웹뷰가 scopeSelection/hideTree로 카드를 지운다). */
    private fun sendHideTree(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            try {
                net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project).sendMessage("scopeSelection/hideTree", "", "system")
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    suspend fun requestScopeSelection(
        project: Project?,
        tree: List<DirectoryNode>,
        config: ScopeConfig,
        onChunk: ((String) -> Unit)?
    ): ScopeSelectionResult? {
        if (project == null) return null
        // 제출·건너뛰기·시간 초과·오류 모두 끝에서 카드를 내린다(전에는 시간 초과나 분석이 끝난 뒤에도 카드가 남아 타이머가 계속 늘었다)
        return hideAfter({ sendHideTree(project) }) { requestScopeSelectionBody(project, tree, config) }
    }

    private suspend fun requestScopeSelectionBody(project: Project, tree: List<DirectoryNode>, config: ScopeConfig): ScopeSelectionResult? {
        val projectId = project.locationHash

        val startedMs = System.currentTimeMillis()
        val result = withTimeoutOrNull(5 * 60 * 1000L) { // 5 minutes timeout
            suspendCancellableCoroutine { continuation ->
                scopeSelectionContinuations[projectId] = continuation

                continuation.invokeOnCancellation {
                    scopeSelectionContinuations.remove(projectId)
                }

                // Send event to Webview
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val bridge = net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                        val payload = mapOf("projectName" to project.name, "tree" to tree, "config" to config)
                        val payloadStr = com.google.gson.Gson().toJson(payload)
                        bridge.sendMessage("scopeSelection/showTree", payloadStr, "system")
                    } catch (e: Exception) {
                        continuation.resume(null)
                    }
                }
            }
        }
        // 사용자의 건너뛰기(취소)는 곧바로 null이 되고, 타임아웃은 5분을 채운 뒤 null이다: 후자일 때만 안내한다
        val waitedMs = System.currentTimeMillis() - startedMs
        val timedOut = result == null && waitedMs >= 5 * 60 * 1000L - 1000L
        // B-85 7: 패키지 카드 결과 로그(제출 / 건너뛰기 / 시간 초과)
        CardWaitLog.log(CardWaitLog.Card.PACKAGE, null, if (result != null) CardWaitLog.Result.SUBMIT else if (timedOut) CardWaitLog.Result.TIMEOUT else CardWaitLog.Result.SKIP, waitedMs)
        if (timedOut) {
            CardTimeoutNotice.send(project, CardTimeoutNotice.SCOPE)
        }
        return result
    }

    suspend fun requestDependencyConfirmation(
        project: Project?,
        suggestions: List<DependencySuggestion>,
        onChunk: ((String) -> Unit)?
    ): List<String>? {
        if (project == null) return null
        val projectId = project.locationHash

        return withTimeoutOrNull(3 * 60 * 1000L) { // 3 minutes timeout
            suspendCancellableCoroutine { continuation ->
                dependencyContinuations[projectId] = continuation

                continuation.invokeOnCancellation {
                    dependencyContinuations.remove(projectId)
                }

                // Send event to Webview
                ApplicationManager.getApplication().invokeLater {
                    try {
                        val bridge = net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                        val payload = mapOf("projectName" to project.name, "suggestions" to suggestions)
                        val payloadStr = com.google.gson.Gson().toJson(payload)
                        bridge.sendMessage("scopeSelection/showDependency", payloadStr, "system")
                    } catch (e: Exception) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    fun completeScopeSelection(project: Project, result: ScopeSelectionResult?) {
        val continuation = scopeSelectionContinuations.remove(project.locationHash)
        continuation?.resume(result)
    }

    fun cancelScopeSelection(project: Project) {
        val continuation = scopeSelectionContinuations.remove(project.locationHash)
        continuation?.resume(null)
    }

    fun completeDependencyConfirmation(project: Project, acceptedPaths: List<String>?) {
        val continuation = dependencyContinuations.remove(project.locationHash)
        continuation?.resume(acceptedPaths)
    }
}
