package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

/**
 * 탐색기 종료 로그의 형식(B-85 7). 카드 대기는 사용자가 힌트 카드에 답하거나 5분이 지나기를 기다린 시간이라 턴마다 따로 보여야 합계(여러 카드의 합)가 한 카드로 읽히지 않는다.
 * 11-11 사례: 카드 대기 434721ms = Turn 1 시간 초과 300011ms + Turn 2 건너뛰기 134676ms → cardWaitByTurn=[300011, 134676, 0, 0, 0].
 * cardWaitByTurn은 턴별 LLM 목록과 같은 길이·순서다(그 턴에 카드를 띄우지 않았으면 0). 순수 함수라 형식을 시험이 고정한다.
 */
object ExplorerTimingLog {
    fun line(totalMs: Long, turnLlmMs: List<Long>, cardWaitByTurn: List<Long>): String {
        val cardWaitMs = cardWaitByTurn.sum()
        return "[ExplorerTiming] 총 ${totalMs}ms, 턴별 LLM ${turnLlmMs}ms, 카드 대기(사용자) ${cardWaitMs}ms, cardWaitByTurn=${cardWaitByTurn}, 대기를 뺀 순수 ${totalMs - cardWaitMs}ms (턴 ${turnLlmMs.size}개)"
    }
}
