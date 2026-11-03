package net.ib.ixpert.ops.wuwagent.agent

/**
 * 응답 저장 경로 판정(B-83 4, 5-1). 읽은 범위의 MyBatis 매퍼에서 "응답 테이블에 값을 받아 쓰는 INSERT/UPDATE"가 있는지 본다.
 * - 응답 테이블: SELECT 문에서 ANSWER_n 컬럼을 읽는 별칭의 테이블(예: `B.ANSWER_1`의 B가 가리키는 테이블).
 * - 쓰는 문장: 그 테이블을 대상으로 하는 INSERT/UPDATE 중 `#{...}` 파라미터를 받는 것. 파라미터 없는 집계 UPDATE(예: 통계 갱신)는 저장 경로로 세지 않는다.
 * - 판정은 "읽은 범위 안에서 발견되지 않음"까지만 말한다(다른 모듈·다른 시스템에 있을 수 있다).
 */
object ResponseWritePath {
    data class Judgement(
        /** 읽은 범위의 SELECT가 응답 컬럼(ANSWER_n)을 읽는 테이블(대문자). */
        val responseTables: Set<String>,
        /** 응답 테이블에 파라미터를 받아 쓰는 문장("파일:id"). */
        val parameterizedWriters: List<String>,
        /** 응답 테이블을 쓰지만 파라미터가 없어 저장 경로로 세지 않은 문장(집계 갱신). */
        val ignoredAggregates: List<String>,
        val mappersRead: Int
    ) {
        /** 응답 테이블은 확인되는데 값을 받아 쓰는 문장이 읽은 범위에 없다. */
        val noWritePath: Boolean get() = responseTables.isNotEmpty() && parameterizedWriters.isEmpty()
    }

    private val OPEN = Regex("<(select|insert|update|delete)\\s+id=\"([^\"]+)\"")
    private const val SCHEMA = "(?:\\$\\{\\w+}\\.)?"

    /** mappers: 파일 이름 → 줄 목록. 주석(<!-- -->) 안의 문장은 건너뛴다. */
    fun judge(mappers: Map<String, List<String>>): Judgement {
        val responseTables = linkedSetOf<String>()
        val writes = mutableListOf<Triple<String, String, Boolean>>() // (표시 이름, 테이블, 파라미터 여부)
        for ((file, lines) in mappers) {
            var inComment = false
            var i = 0
            while (i < lines.size) {
                val l = lines[i]
                if (!inComment && l.contains("<!--") && !l.contains("-->")) inComment = true
                val m = OPEN.find(l)
                if (m != null && !inComment && !l.contains("<!--")) {
                    val kind = m.groupValues[1]
                    var j = i
                    while (j < lines.size && !lines[j].contains("</$kind>")) j++
                    val body = lines.subList(i, minOf(j + 1, lines.size)).joinToString("\n")
                    val id = m.groupValues[2]
                    when (kind) {
                        "select" -> {
                            val aliases = Regex("\\b([A-Za-z_]\\w*)\\.ANSWER_\\w+", RegexOption.IGNORE_CASE).findAll(body).map { it.groupValues[1] }.toSet()
                            for (a in aliases) {
                                Regex("(?i)(?:FROM|JOIN)\\s+$SCHEMA([A-Za-z_]\\w*)\\s+(?:AS\\s+)?${Regex.escape(a)}\\b").find(body)?.let { responseTables.add(it.groupValues[1].uppercase()) }
                            }
                        }
                        "insert" -> Regex("(?i)INSERT\\s+INTO\\s+$SCHEMA([A-Za-z_]\\w*)").find(body)?.let { writes.add(Triple("$file:$id", it.groupValues[1].uppercase(), body.contains("#{"))) }
                        // 여는 태그(<update id=...>)의 "update id"가 UPDATE 문으로 읽히지 않도록 태그 뒤의 SQL만 본다
                        "update" -> Regex("(?i)UPDATE\\s+$SCHEMA([A-Za-z_]\\w*)").find(body.substringAfter('>'))?.let { writes.add(Triple("$file:$id", it.groupValues[1].uppercase(), body.contains("#{"))) }
                    }
                    i = j
                }
                if (inComment && l.contains("-->")) inComment = false
                i++
            }
        }
        val onResponse = writes.filter { it.second in responseTables }
        return Judgement(responseTables, onResponse.filter { it.third }.map { it.first }, onResponse.filter { !it.third }.map { it.first }, mappers.size)
    }

    /** 화면·사실 블록에 쓰는 한 문장(읽은 범위 안에서 발견되지 않음으로 한정한다). 판정할 수 없으면(응답 테이블이 안 보임) null. */
    fun notFoundSentence(j: Judgement?): String? {
        if (j == null || !j.noWritePath) return null
        return "읽은 범위의 매퍼(${j.mappersRead}개)에서 응답 테이블(${j.responseTables.joinToString("/")})에 값을 받아 쓰는 INSERT/UPDATE는 발견되지 않았습니다. 이 프로젝트 밖의 다른 시스템이 응답을 저장할 수 있습니다."
    }
}
