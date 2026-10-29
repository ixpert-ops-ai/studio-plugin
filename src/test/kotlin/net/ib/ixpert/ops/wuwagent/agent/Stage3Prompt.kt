package net.ib.ixpert.ops.wuwagent.agent

/**
 * 시험용: Stage 3 검증기 요청에서 후보 목록을 읽는다.
 * B-79부터 프롬프트는 번호 없이 `- 이름` 줄 다음 줄에 `경로: path`를 쓴다. 이전 형식(`- [n] path`)의 저장된 요청도 읽는다.
 */
object Stage3Prompt {
    private val NAMED = Regex("(?m)^- (?!\\[)(\\S+)\\r?\\n경로: (\\S+)")
    private val NUMBERED = Regex("(?m)^- \\[(\\d+)] (\\S+)")

    /** (후보 순번 1부터, 경로) 목록. 프롬프트에 나온 순서 그대로다. */
    fun candidates(user: String): List<Pair<Int, String>> {
        val named = NAMED.findAll(user).map { it.groupValues[2] }.toList()
        if (named.isNotEmpty()) return named.mapIndexed { i, p -> (i + 1) to p }
        return NUMBERED.findAll(user).map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
    }

    /** 요청에 나온 후보 이름 줄(`- 이름`)만. 이름 방식에서만 값이 있다. */
    fun names(user: String): List<String> = NAMED.findAll(user).map { it.groupValues[1] }.toList()
}
