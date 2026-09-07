package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 매 턴 검색 직후 또는 앵커 탐색 과정에서 사용자에게 검색 결과 및 LLM 해석을 공유하고 도메인/업무 확인을 요청하는 L1 Bridge 인터페이스
 */
fun interface L1ClarificationBridge {
    /**
     * @param turn 현재 탐색 턴 수
     * @param query 현재 턴에 실행된 검색 쿼리
     * @param topCandidates 상위 매칭 후보 목록 (className, localName, fileType, score 등)
     * @param domains 후보군이 속한 패키지/도메인 목록
     * @param explanation LLM이 생성한 이번 턴 결과 해석 및 다음 탐색 계획 설명
     * @return 사용자가 입력한 업무 교정 텍스트 (예: "통계 리포트 화면"), 무응답/타임아웃/헤드리스/단순확인 시 null
     */
    fun requestClarification(
        turn: Int,
        query: String,
        topCandidates: List<Map<String, Any>>,
        domains: List<String>,
        explanation: String
    ): String?

    /**
     * 탐색이 완료되었을 때 웹뷰의 L1 Clarification 카드를 숨김 처리
     */
    fun hideClarification() {}
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
        turn: Int,
        query: String,
        topCandidates: List<Map<String, Any>>,
        domains: List<String>,
        explanation: String
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
                    "turn" to turn,
                    "query" to query,
                    "topCandidates" to topCandidates.take(5),
                    "domains" to domains,
                    "explanation" to explanation,
                    "message" to "이번 검색 결과와 다음 진행 방향입니다. 맞으면 그대로 진행, 방향을 바꾸려면 업무/화면 영역이나 추가 정보를 알려주세요."
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

    override fun hideClarification() {
        if (project == null) return
        ApplicationManager.getApplication().invokeLater {
            try {
                val bridge = net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                bridge.sendMessage("l1Clarification/hide", "", "system")
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
