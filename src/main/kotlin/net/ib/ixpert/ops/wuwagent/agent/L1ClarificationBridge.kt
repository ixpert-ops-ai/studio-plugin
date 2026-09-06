package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 1턴 앵커 실종/플래토 감지 시 사용자에게 업무 도메인 확인을 요청하는 L1 Bridge 인터페이스
 */
fun interface L1ClarificationBridge {
    /**
     * @param query 1턴에 실행된 검색 쿼리
     * @param topCandidates 1턴 상위 매칭 후보 목록 (className, localName, fileType, score 등)
     * @param domains 1턴 후보군이 속한 패키지/도메인 목록
     * @return 사용자가 입력한 업무 교정 텍스트 (예: "통계 리포트 화면"), 무응답/타임아웃/헤드리스 시 null
     */
    fun requestClarification(
        query: String,
        topCandidates: List<Map<String, Any>>,
        domains: List<String>
    ): String?
}

/**
 * IntelliJ JCEF Webview UI와 통신하는 프로덕션 L1 Clarification Bridge
 */
class JcefL1ClarificationBridge(
    private val project: Project?,
    private val timeoutSeconds: Long = 300 // 5분 타임아웃 (ScopeSelectionBridge와 동일)
) : L1ClarificationBridge {

    companion object {
        private val pendingClarifications = ConcurrentHashMap<String, CompletableFuture<String?>>()

        fun completeClarification(projectId: String, userHint: String?) {
            val future = pendingClarifications.remove(projectId)
            future?.complete(userHint)
        }

        fun cancelClarification(projectId: String) {
            val future = pendingClarifications.remove(projectId)
            future?.complete(null)
        }
    }

    override fun requestClarification(
        query: String,
        topCandidates: List<Map<String, Any>>,
        domains: List<String>
    ): String? {
        if (project == null) return null
        val projectId = project.locationHash

        val future = CompletableFuture<String?>()
        pendingClarifications[projectId] = future

        ApplicationManager.getApplication().invokeLater {
            try {
                val bridge = net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                val payload = mapOf(
                    "projectId" to projectId,
                    "query" to query,
                    "topCandidates" to topCandidates.take(5),
                    "domains" to domains,
                    "message" to "1턴 검색 결과가 여러 도메인에 분산되어 앵커를 특정하기 어렵습니다. 구현하고자 하는 기능의 업무/화면 영역(도메인) 힌트를 입력해주세요."
                )
                val payloadStr = Gson().toJson(payload)
                bridge.sendMessage("l1Clarification/request", payloadStr, "system")
            } catch (e: Exception) {
                pendingClarifications.remove(projectId)
                future.complete(null)
            }
        }

        return try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pendingClarifications.remove(projectId)
            null
        }
    }
}
