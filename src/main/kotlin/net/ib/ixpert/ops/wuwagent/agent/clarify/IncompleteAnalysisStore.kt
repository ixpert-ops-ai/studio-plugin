package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent

/**
 * 파일 분석이 대체 경로로 끝나 결과 없이 멈췄을 때 "다시 시도"가 같은 인텐트(제외 파일, 제약, 정제문)로 시작하도록 인텐트를 메모리에만 잠시 보관한다.
 * ADR-001: 디스크에 쓰지 않고 새 세션은 항상 인텐트 없이 시작한다(여기서 꺼내 쓰는 것은 같은 세션에서 방금 실패한 분석의 재시도뿐이다).
 */
object IncompleteAnalysisStore {
    class Entry(val rawInput: String, val intent: ClarifyIntent?)

    private const val MAX_ENTRIES = 5
    private val entries = LinkedHashMap<String, Entry>()
    private var counter = 0

    /** 실패한 분석의 시작 입력과 인텐트를 보관하고 재시도 키를 돌려준다. 오래된 항목은 5개를 넘으면 버린다. */
    @Synchronized
    fun put(rawInput: String, intent: ClarifyIntent?): String {
        val key = "retry_${++counter}_${System.currentTimeMillis()}"
        entries[key] = Entry(rawInput, intent)
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        return key
    }

    @Synchronized
    fun get(key: String?): Entry? = if (key.isNullOrBlank()) null else entries[key]

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
