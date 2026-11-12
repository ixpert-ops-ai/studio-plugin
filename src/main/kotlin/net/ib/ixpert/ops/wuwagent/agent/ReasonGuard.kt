package net.ib.ixpert.ops.wuwagent.agent

/**
 * 결과 단계의 결정적 사유 검사(B-84 4-(a)). LLM이 쓴 수정 사유가 코드에서 확인한 사실과 어긋나면 **삭제하지 않고 표시만** 덧붙인다.
 * LLM 호출이 없고 같은 입력이면 같은 출력이며(두 번 적용해도 같다), 덧붙이는 문구는 읽은 매퍼의 문 정보(종류, 대상 테이블, CALL 여부, 파라미터 유무)로 만든다.
 *
 * - G1 이름 대 사실: 사유 안의 이름이 읽은 매퍼의 문 id와 같고 update/insert/save 같은 쓰기처럼 보이는데 실제로는 응답 테이블에 값을 받아 쓰는 문이 아니면(프로시저 호출, 다른 테이블, 조회 문, 파라미터 없는 집계) 그 이름 뒤에 실제를 덧붙인다.
 * - G2 쓰기 경로 없음: 읽은 매퍼에 응답 테이블로 값을 쓰는 문이 없다는 판정(noWritePath)인데 문장이 "조회/저장", "응답 저장", "저장 로직" 같은 저장 단정을 부정어 없이 말하면 그 문장 뒤에 판정을 덧붙인다.
 * - G3 규칙 판정과 충돌(결과 표 한 행에만): 선택(경로 통과) 행인데 LLM 사유가 추가·수정을 말하면 "(규칙 판정: <판정 내용>)"을 덧붙인다(예: "(규칙 판정: 수정 불필요)", 판정 내용은 [PathRoles.Role.verdict]).
 * - G4 요약과 표의 불일치(Stage 3 요약 문장에만, B-85 4): 요약 문장에 선택(경로 통과) 행의 클래스 이름이 직접 나오고 같은 문장에 수정 동사가 있으면 "(<이름> 규칙 판정: 수정 불필요)"를 덧붙인다.
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
    /**
     * "구현"을 수정 동사로 보는 낱말(G3·G4가 함께 쓴다, B-85 후속 5). 11-12 사례: 선택 행 SurveyDaoImpl의 사유가 "이메일 컬럼 매핑 및 쿼리 구현"이라 추가·수정 낱말이 없어 표시가 빠졌다.
     * 이미 있는 것을 말하거나 하지 않겠다는 쓰임은 동사가 아니다: 구현체(Impl 같은 구현 클래스), 구현되/구현된/구현돼(이미 있음), 구현 불필요, 구현하지 않/말, 구현 안 함/해/한.
     * (부정어 판정 [NEGATION_PATTERN]은 recount-b84.js와 같아야 해서 "불필요"를 넣지 않고, 이 낱말 자체에서 뺀다.)
     */
    private const val IMPLEMENT_VERB = "구현(?!체|되|된|돼|\\s*불필요|하지\\s*(않|말)|\\s*안\\s*(함|해|한))"
    private val CHANGE = Regex("추가|수정|변경|신설|신규|새로|$IMPLEMENT_VERB")
    private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")

    private const val ACTUAL_PREFIX = "(실제:"
    private const val WRITE_MARK_PREFIX = "(읽은 범위의 매퍼"
    private const val ROLE_MARK_PREFIX = "(규칙 판정:"

    /** 요약 문장에서 선택 행의 이름을 찾는 G4의 수정 동사(B-85 4). "반영"은 넣지 않는다(뜻이 넓어 "결과에 반영" 같은 말까지 잡는다). */
    private val MODIFY_VERB = Regex("수정|변경|추가|고치|개선|교체|보완|$IMPLEMENT_VERB")
    private const val ROLE_NAME_MARK = "규칙 판정:"
    /** G4가 덧붙인 "(SurveyDaoImpl 규칙 판정: …)" 표시. 마침표 뒤에 따로 떨어져 있어도 앞 문장의 일부로 본다. */
    private val ROLE_NAME_MARK_START = Regex("^\\([A-Za-z0-9_, ]+ 규칙 판정:")

    /**
     * judgement: G1·G2에 쓰는 읽은 매퍼 판정. role: 결과 표 한 행의 필수/선택 판정(G3).
     * optionalFiles: G4가 요약 문장에서 찾는 "선택(경로 통과)" 행의 클래스 이름(확장자 없는 파일 이름, 예: SurveyDaoImpl). 결과 표가 같은 판정(PathRoles)으로 정한다.
     */
    data class Context(val judgement: ResponseWritePath.Judgement?, val role: PathRoles.Role? = null, val optionalFiles: List<String> = emptyList())

    /** 결과 표 한 행의 LLM 사유: G1, G2, G3. (G4는 요약 문장에만 쓴다.) */
    fun annotateRow(reason: String, ctx: Context): String {
        if (reason.isBlank()) return reason
        var out = annotateText(reason, ctx.copy(optionalFiles = emptyList()))
        if (ctx.role == PathRoles.Role.OPTIONAL && !out.contains(ROLE_MARK_PREFIX)) {
            // 덧붙인 표시가 아니라 LLM이 쓴 원래 사유로 판정한다
            val change = CHANGE.findAll(reason).firstOrNull { !negatedAround(reason, it.range.first, it.range.last + 1) }
            // 판정 문구는 PathRoles의 판정(Role.verdict)에서 가져온다: "(규칙 판정: 수정 불필요)"
            if (change != null) out = "$out $ROLE_MARK_PREFIX ${ctx.role.verdict})"
        }
        return out
    }

    /**
     * Stage 3 요약 문단: G1, G2, G4를 문장 단위로(G3 제외). 줄바꿈은 유지하고 문장 사이 공백은 한 칸으로 맞춘다.
     * G1·G2는 읽은 매퍼 판정이 있을 때만, G4는 선택 행이 있을 때만 일한다. 둘 다 없으면 원문 그대로다.
     */
    fun annotateText(text: String, ctx: Context): String {
        val j = ctx.judgement?.takeIf { it.responseTables.isNotEmpty() }
        val optional = ctx.optionalFiles
        if (j == null && optional.isEmpty()) return text
        return text.split("\n").joinToString("\n") { line ->
            // 앞서 덧붙인 표시("(읽은 범위의 매퍼 …)", "(SurveyDaoImpl 규칙 판정: …)")는 마침표 뒤에 따로 떨어져 있어도 앞 문장의 일부로 본다(두 번 적용해도 같게)
            val sentences = mutableListOf<String>()
            for (piece in line.split(SENTENCE_BREAK)) {
                if ((piece.startsWith(WRITE_MARK_PREFIX) || ROLE_NAME_MARK_START.containsMatchIn(piece)) && sentences.isNotEmpty()) sentences[sentences.lastIndex] = sentences.last() + " " + piece
                else sentences += piece
            }
            sentences.joinToString(" ") { sentence -> annotateSentence(sentence, j, optional) }
        }
    }

    private fun annotateSentence(sentence: String, j: ResponseWritePath.Judgement?, optional: List<String>): String {
        var s = if (j != null) withActuals(sentence, j) else sentence
        if (j != null && j.noWritePath && !s.contains(WRITE_MARK_PREFIX)) {
            val hit = ASSERTION.findAll(s).firstOrNull { !negatedAround(s, it.range.first, it.range.last + 1) }
            if (hit != null) s = "$s ${writeMark(j)}"
        }
        return withRoleMark(sentence, s, optional)
    }

    /**
     * G4(B-85 4): 요약 문장에 "선택(경로 통과)" 행의 클래스 이름이 직접 나오고(식별자가 완전히 같을 때, 파일 이름 "SurveyDaoImpl.java"도 확장자 앞의 이름으로 같다) 같은 문장에 수정 동사가 있으면,
     * 그 문장 뒤에 "(<이름> 규칙 판정: 수정 불필요)"를 덧붙인다. 이름 앞뒤 [NEGATION_WINDOW]자 안에 부정어가 있으면 그 이름은 건너뛴다. 판정 내용은 [PathRoles.Role.verdict]이고,
     * 삭제·바꿔쓰기는 하지 않는다. 같은 표시가 이미 있으면 덧붙이지 않는다(멱등). 이름은 덧붙이기 전의 원래 문장([original])에서 찾는다.
     */
    private fun withRoleMark(original: String, annotated: String, optional: List<String>): String {
        if (optional.isEmpty() || !MODIFY_VERB.containsMatchIn(original)) return annotated
        val names = LinkedHashSet<String>()
        for (m in IDENT.findAll(original)) {
            if (m.value in optional && !negatedAround(original, m.range.first, m.range.last + 1)) names += m.value
        }
        if (names.isEmpty()) return annotated
        val mark = "(${names.joinToString(", ")} $ROLE_NAME_MARK ${PathRoles.Role.OPTIONAL.verdict})"
        return if (annotated.contains(mark)) annotated else "$annotated $mark"
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
