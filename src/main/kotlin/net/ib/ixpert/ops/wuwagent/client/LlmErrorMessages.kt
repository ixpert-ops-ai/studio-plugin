package net.ib.ixpert.ops.wuwagent.client

import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * LLM 호출 실패 시 채팅 말풍선에 노출할 한국어 안내 메시지를 만듭니다.
 *
 * OpenAIClient/OllamaClient가 공통으로 사용 — HTTP 상태 코드나 타임아웃 등
 * 원인을 분류해 사용자가 무엇을 해야 하는지 알 수 있는 문구로 치환합니다.
 * 분류가 안 되는 예외도 포함해 원본 예외 메시지는 사용자에게 노출하지 않으며,
 * 원본은 항상 logger로만 남긴다 (호출부에서 별도로 로깅할 것).
 *
 * 반환값에는 "[Error]"/"[오류]" 같은 접두어가 붙지 않으므로,
 * 호출부에서 필요한 접두어를 한 번만 붙여서 사용한다.
 * 서버 주소는 이 함수가 메시지 끝에 "서버: {serverUrl}" 형태로 정확히 한 번만 표시한다.
 */
object LlmErrorMessages {

    private val TRANSIENT_SERVER_CODES = setOf(502, 503, 504)

    fun describe(e: Throwable, serverUrl: String): String {
        val statusCode = extractHttpStatusCode(e)
        val isTimeout = e is SocketTimeoutException ||
            e.message?.contains("timeout", ignoreCase = true) == true
        val isRefused = e is ConnectException ||
            e.message?.contains("refused", ignoreCase = true) == true

        val body = when {
            statusCode in TRANSIENT_SERVER_CODES || isRefused ->
                "AI 서버에 연결할 수 없습니다. 서버가 점검 중이거나 모델이 준비되지 않았을 수 있습니다."
            statusCode == 401 || statusCode == 403 ->
                "인증에 실패했습니다. 설정에서 API Key를 확인해주세요."
            statusCode == 404 ->
                "요청 경로를 찾을 수 없습니다. 설정에서 서버 주소를 확인해주세요."
            isTimeout ->
                "응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."
            statusCode != null ->
                "AI 서버 요청에 실패했습니다. (HTTP $statusCode)"
            // 그 외 예외(분류 불가) — 원본 예외 메시지는 노출하지 않음
            else ->
                "AI 서버 요청 중 오류가 발생했습니다.\n설정에서 서버 주소와 연결 상태를 확인해주세요."
        }
        return "$body\n서버: $serverUrl"
    }

    /**
     * JDK HttpURLConnection이 던지는 기본 예외 형식에서 상태 코드를 추출합니다.
     * 예: "Server returned HTTP response code: 502 for URL: http://..."
     */
    private fun extractHttpStatusCode(e: Throwable): Int? {
        val msg = e.message ?: return null
        return Regex("""response code:\s*(\d{3})""", RegexOption.IGNORE_CASE)
            .find(msg)?.groupValues?.get(1)?.toIntOrNull()
    }
}
