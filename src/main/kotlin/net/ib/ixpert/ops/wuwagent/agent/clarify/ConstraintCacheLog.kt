package net.ib.ixpert.ops.wuwagent.agent.clarify

/**
 * 제약 추출 Future를 확정 뒤 받는 경로의 로그 형식(B-85 7). 기존 `[ConstraintCache] hit|miss|inflight|cancel` 줄은 Future를 만들거나 재사용할 때 남고,
 * 이 줄은 카드 응답 뒤 `complete()`가 그 Future를 받을 때(`extractionFuture.get()`) 남는다. 두 줄을 합치면 로그만으로 "추출 1회"를 판정할 수 있다:
 * miss가 1번이고 future-reuse가 done=true(이미 끝남) 또는 done=false(진행 중이던 것을 기다림)로 한 번 나오면 같은 호출 하나를 쓴 것이다.
 * 순수 함수라 형식을 시험이 고정한다.
 */
object ConstraintCacheLog {
    fun futureReuseLine(waitMs: Long, done: Boolean): String = "[ConstraintCache] future-reuse waitMs=${maxOf(0L, waitMs)} done=$done"
}
