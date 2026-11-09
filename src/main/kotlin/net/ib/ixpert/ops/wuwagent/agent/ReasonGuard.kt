package net.ib.ixpert.ops.wuwagent.agent

/**
 * 결과 단계의 결정적 사유 검사(B-84 4-(a)). LLM이 쓴 수정 사유가 코드에서 확인한 사실과 어긋나면 **삭제하지 않고 표시만** 덧붙인다.
 * LLM 호출이 없고 같은 입력이면 같은 출력이며(두 번 적용해도 같다), 덧붙이는 문구는 읽은 매퍼의 문 정보(종류, 대상 테이블, CALL 여부, 파라미터 유무)로 만든다.
 *
 * - G1 이름 대 사실: 사유 안의 이름이 읽은 매퍼의 문 id와 같고 update/insert/save 같은 쓰기처럼 보이는데 실제로는 응답 테이블에 값을 받아 쓰는 문이 아니면(프로시저 호출, 다른 테이블, 조회 문, 파라미터 없는 집계) 그 이름 뒤에 실제를 덧붙인다.
 * - G2 쓰기 경로 없음: 읽은 매퍼에 응답 테이블로 값을 쓰는 문이 없다는 판정(noWritePath)인데 문장이 "조회/저장", "응답 저장", "저장 로직" 같은 저장 단정을 부정어 없이 말하면 그 문장 뒤에 판정을 덧붙인다.
 * - G3 규칙 판정과 충돌(결과 표 한 행에만): 선택(경로 통과) 행인데 LLM 사유가 추가·수정을 말하면 "(규칙 판정과 다름)"을 덧붙인다.
 * 읽지 않은 매퍼의 문은 판정 정보에 없으므로 표시하지 않는다. 판정(Judgement)이 없거나 응답 테이블을 알 수 없으면 아무것도 바꾸지 않는다.
 *
 * 부정어 판정은 scripts/recount-b84.js와 같은 규칙(언급 앞뒤 [NEGATION_WINDOW]자 안에 [NEGATION_PATTERN])을 쓴다. 두 곳이 어긋나지 않는지는 시험이 확인한다.
 */
object ReasonGuard {
    /** 부정어 판정 범위(언급 앞뒤 글자 수)와 낱말. recount-b84.js의 `neg`와 같아야 한다. */
    const val NEGATION_WINDOW = 25
    const val NEGATION_PATTERN = "없|않|발견되지|확인되지|미확인|아니"

    private val NEGATION = Regex(NEGATION_PATTERN)
    private val WRITE_PREFIX = Regex("^(update|insert|save|merge|upsert|delete)", RegexOption.IGNORE_CASE)
    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")
    /** recount-b84.js의 `assertion`과 같은 낱말에 INSERT를 더한 저장 단정 표현. */
    private val ASSERTION = Regex("조회\\s*/\\s*저장|저장\\s*/\\s*조회|수집\\s*/\\s*저장|저장\\s*(처리|로직|담당)|수집\\s*(처리|로직|담당)|응답\\s*(저장|수집)|INSERT")
    private val CHANGE = Regex("추가|수정|변경|신설|신규|새로")
    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")

    private const val ACTUAL_PREFIX = "(실제:"
    private const val WRITE_MARK_PREFIX = "(읽은 범위의 매퍼"
    private const val ROLE_MARK = "(규칙 판정과 다름)"

    data class Context(val judgement: ResponseWritePath.Judgement?, val role: PathRoles.Role? = null)

    /** 결과 표 한 행의 LLM 사유: G1, G2, G3. */
    fun annotateRow(reason: String, ctx: Context): String {
        if (reason.isBlank()) return reason
        var out = annotateText(reason, ctx)
        if (ctx.role == PathRoles.Role.OPTIONAL && !out.contains(ROLE_MARK)) {
            // 덧붙인 표시가 아니라 LLM이 쓴 원래 사유로 판정한다
            val change = CHANGE.findAll(reason).firstOrNull { !negatedAround(reason, it.range.first, it.range.last + 1) }
            if (change != null) out = "$out $ROLE_MARK"
        }
        return out
    }

    /** Stage 3 요약 문단: G1, G2를 문장 단위로(G3 제외). 줄바꿈은 유지하고 문장 사이 공백은 한 칸으로 맞춘다. */
    fun annotateText(text: String, ctx: Context): String {
        val j = ctx.judgement ?: return text
        if (j.responseTables.isEmpty()) return text
        return text.split("\n").joinToString("\n") { line ->
            // 앞서 덧붙인 판정 표시("(읽은 범위의 매퍼 …)")는 마침표 뒤에 따로 떨어져 있어도 앞 문장의 일부로 본다(두 번 적용해도 같게)
            val sentences = mutableListOf<String>()
            for (piece in line.split(SENTENCE_BREAK)) {
                if (piece.startsWith(WRITE_MARK_PREFIX) && sentences.isNotEmpty()) sentences[sentences.lastIndex] = sentences.last() + " " + piece
                else sentences += piece
            }
            sentences.joinToString(" ") { sentence -> annotateSentence(sentence, j) }
        }
    }

    private fun annotateSentence(sentence: String, j: ResponseWritePath.Judgement): String {
        var s = withActuals(sentence, j)
        if (j.noWritePath && !s.contains(WRITE_MARK_PREFIX)) {
            val hit = ASSERTION.findAll(s).firstOrNull { !negatedAround(s, it.range.first, it.range.last + 1) }
            if (hit != null) s = "$s ${writeMark(j)}"
        }
        return s
    }

    /** G1: 쓰기처럼 보이는 이름 뒤에 실제를 덧붙인다(뒤에서부터 끼워 앞쪽 위치가 밀리지 않게 한다). */
    private fun withActuals(sentence: String, j: ResponseWritePath.Judgement): String {
        val inserts = mutableListOf<Pair<Int, String>>()
        val seen = mutableSetOf<String>()
        for (m in IDENT.findAll(sentence)) {
            val name = m.value
            if (!WRITE_PREFIX.containsMatchIn(name) || !seen.add(name)) continue
            val info = j.statements[name] ?: continue
            if (info.kind != "select" && info.targetTable != null && info.targetTable in j.responseTables && info.hasParams) continue // 실제로 응답 테이블에 값을 쓰는 문
            if (sentence.startsWith(ACTUAL_PREFIX, m.range.last + 1)) continue
            if (negatedAround(sentence, m.range.first, m.range.last + 1)) continue
            inserts += (m.range.last + 1) to describe(info, j)
        }
        var out = sentence
        for ((pos, text) in inserts.sortedByDescending { it.first }) out = out.substring(0, pos) + text + out.substring(pos)
        return out
    }

    private fun describe(info: ResponseWritePath.StatementInfo, j: ResponseWritePath.Judgement): String {
        val tables = j.responseTables.joinToString("/")
        return when {
            info.callsProcedure != null -> "$ACTUAL_PREFIX 프로시저 ${info.callsProcedure} 호출, 응답 테이블($tables) 쓰기 아님)"
            info.kind == "select" -> "$ACTUAL_PREFIX 조회 문, 응답 테이블($tables) 쓰기 아님)"
            info.targetTable != null && info.targetTable !in j.responseTables -> "$ACTUAL_PREFIX ${info.targetTable} 대상, 응답 테이블($tables) 쓰기 아님)"
            info.targetTable != null -> "$ACTUAL_PREFIX 응답 테이블 대상이나 값을 받지 않는 문(집계), 응답 저장 아님)"
            else -> "$ACTUAL_PREFIX 응답 테이블($tables)에 값을 쓰는 문 아님)"
        }
    }

    private fun writeMark(j: ResponseWritePath.Judgement): String =
        "$WRITE_MARK_PREFIX ${j.mappersRead}개에 응답 테이블(${j.responseTables.joinToString("/")})로 값을 쓰는 쿼리 없음, 저장 주체 미확인)"

    /** [start, endExclusive) 언급 앞뒤 [NEGATION_WINDOW]자 안에 부정어가 있는지. */
    internal fun negatedAround(text: String, start: Int, endExclusive: Int): Boolean =
        NEGATION.containsMatchIn(text.substring(maxOf(0, start - NEGATION_WINDOW), minOf(text.length, endExclusive + NEGATION_WINDOW)))
}
