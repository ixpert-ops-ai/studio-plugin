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

    suspend fun requestScopeSelection(
        project: Project?,
        tree: List<DirectoryNode>,
        config: ScopeConfig,
        onChunk: ((String) -> Unit)?
    ): ScopeSelectionResult? {
        if (project == null) return null
        val projectId = project.locationHash

        return withTimeoutOrNull(5 * 60 * 1000L) { // 5 minutes timeout
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
