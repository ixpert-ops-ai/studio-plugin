package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.FieldFact
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.io.File

/**
 * SR(정제문)이 다루는 데이터 필드(응답자 이메일 등)가 시드의 업무 코드(자바, MyBatis 매퍼)에 실제로 있는지 소스를 읽어 확인하고,
 * 없으면 결과 화면에 한 줄 경고를 만든다(B-75 5, B-76 3-d에서 요약 문구가 아니라 SR에서 항목을 뽑도록 바꿨다).
 * B-77: 같은 검사를 /clarify 첫 요약 단계에서도 쓴다(stage0Facts). 그때는 시드를 LLM 없이 키워드로 고르고(AgenticSeedSelector.heuristicSeeds), 결과를 질문과 요약 프롬프트의 사실로 쓴다.
 * 4292986 프로토타입의 근거 수집을 옮긴 것이다. 결과 요약의 문구는 보지 않는다(요약이 "이미 존재"라고 말하는 방식은 모델마다 달라 패턴으로 잡을 수 없었다).
 *
 * - 경고는 범위 파일을 **모두 읽었을 때만** 낸다(못 읽은 파일이 있으면 침묵: 오탐 방지).
 * - 업무 판단은 하지 않는다: "코드/SQL을 찾지 못했다"는 사실만 알리고 다른 업무에서 발견된 위치(file:line)를 붙인다.
 */
object ClaimedFieldCheck {

    private class DataNoun(val korean: List<String>, val pattern: Regex)

    private val LEXICON = listOf(
        DataNoun(listOf("이메일", "메일주소"), Regex("(?i)e[-_ ]?mail")),
        DataNoun(listOf("전화번호", "휴대폰", "핸드폰", "연락처"), Regex("(?i)(phone|mobile|\\btel\\b|\\bhp\\b|cellphone)")),
        DataNoun(listOf("생년월일"), Regex("(?i)birth")),
        DataNoun(listOf("비밀번호"), Regex("(?i)passw(or)?d")),
        DataNoun(listOf("계좌번호"), Regex("(?i)(account_?(no|num)|acct)")),
        DataNoun(listOf("카드번호"), Regex("(?i)card_?(no|num)"))
    )

    private val GENERIC = setOf(
        "controller", "service", "impl", "dao", "mapper", "dto", "vo", "response", "request", "entity", "repository", "base",
        "util", "helper", "common", "config", "job", "batch", "list", "get", "set", "api", "main", "app"
    )

    private class Stmt(val id: String, val start: Int, val end: Int, val tables: Set<String>, val commented: Boolean)

    /** 다른 업무 코드에서 발견된 줄. SQL 문장 안이면 그 문장이 읽는 테이블을 함께 가진다. */
    private class OtherHit(val cite: String, val tables: Set<String>)

    /** 항목 하나의 확인 결과(찾았는지, 근거, 읽은 파일 수). */
    private class Eval(val noun: DataNoun, val subject: String?, val read: Int, val unread: Int, val inDomainCites: List<String>, val other: List<OtherHit>, val seedNames: List<String> = emptyList())

    class Finding(
        val noun: String, val subject: String?, val filesRead: Int, val otherCites: List<String>, val otherTables: List<String> = emptyList(),
        /** 범위를 정한 시드 클래스 이름(읽은 파일 수가 무엇을 센 것인지 화면에 밝히는 데 쓴다). */
        val seedNames: List<String> = emptyList(),
        /** 사용자가 Stage 0 질문에 한 답(ClarifyIntent의 "<항목> 출처: <답>" 제약). 없으면 null. */
        val answer: String? = null
    ) {
        /** 화면에 보이는 항목 이름(예: "응답자 이메일"). */
        val label: String get() = (subject?.let { "$it " } ?: "") + noun

        fun withAnswer(a: String?): Finding = Finding(noun, subject, filesRead, otherCites, otherTables, seedNames, a)
    }

    /** 문장에 사전의 데이터 명사(이메일 등)가 있는가. */
    fun hasDataNoun(srText: String): Boolean = requestedNouns(srText).isNotEmpty()

    /** SR에서 사전에 있는 데이터 명사를 찾는다. */
    private fun requestedNouns(srText: String): List<DataNoun> = LEXICON.filter { d -> d.korean.any { srText.contains(it) } }

    /** 받침 유무에 따라 "을"/"를"을 고른다(한글이 아니면 "을(를)"). */
    fun eulReul(word: String): String {
        val c = word.trimEnd().lastOrNull() ?: return "을(를)"
        if (c !in '가'..'힣') return "을(를)"
        return if ((c - '가') % 28 != 0) "을" else "를"
    }

    private fun subjectOf(sr: String, noun: DataNoun): String? {
        for (k in noun.korean) {
            val m = Regex("([가-힣]{2,})?\\s*" + Regex.escape(k)).find(sr) ?: continue
            m.groups[1]?.value?.takeIf { it != k }?.let { return it }
        }
        return null
    }

    private fun camelTokens(name: String): List<String> = DomainDictionary.tokenizeCamelCase(name).map { it.lowercase() }

    private fun parseStatements(lines: List<String>): List<Stmt> {
        val out = mutableListOf<Stmt>()
        var inComment = false
        var i = 0
        val open = Regex("<(select|insert|update|delete)\\s+id=\"([^\"]+)\"")
        val tableRe = Regex("(?i)\\b(?:FROM|JOIN|INTO|UPDATE)\\s+(?:\\$\\{\\w+}\\.)?([A-Za-z_][A-Za-z0-9_]*)")
        while (i < lines.size) {
            val l = lines[i]
            if (!inComment && l.contains("<!--") && !l.contains("-->")) inComment = true
            val m = open.find(l)
            if (m != null) {
                val kind = m.groupValues[1]
                var j = i
                while (j < lines.size && !lines[j].contains("</$kind>")) j++
                val body = lines.subList(i, minOf(j + 1, lines.size)).joinToString("\n")
                val tables = tableRe.findAll(body).map { it.groupValues[1].uppercase() }.filter { it.length >= 4 && it !in setOf("SELECT", "DUAL") }.toSet()
                out.add(Stmt(m.groupValues[2], i + 1, minOf(j + 1, lines.size), tables, inComment || l.contains("<!--")))
                i = j
            }
            if (inComment && l.contains("-->")) inComment = false
            i++
        }
        return out
    }

    /** 시드 파일의 패키지 디렉터리의 자바 파일과, 시드 클래스 이름 토큰이 파일명에 있는 리소스(매퍼 등). */
    private fun scopeOf(graph: ProjectGraph, seedPaths: List<String>): Pair<List<String>, Set<String>> {
        val seedNodes = seedPaths.mapNotNull { graph.files[it] }
        val dirs = seedNodes.map { it.path.substringBeforeLast('/') }.toSet()
        val javaFiles = graph.files.values.filter { it.path.substringBeforeLast('/') in dirs }.map { it.path }
        val tokens = seedNodes.flatMap { camelTokens(it.className) }.filter { it.length >= 4 && it !in GENERIC }.toSet()
        val resources = graph.resourceNodes.map { it.path }.filter { p ->
            val n = p.substringAfterLast('/').substringBeforeLast('.').lowercase()
            tokens.any { it in n }
        }
        return (javaFiles + resources).distinct() to tokens
    }

    /** 주장한 필드마다, 범위 파일을 모두 읽었고 업무 코드에서 찾지 못했으면 Finding을 돌려준다(근거가 없으면 빈 목록). */
    fun check(srText: String, graph: ProjectGraph, projectRoot: File?, seedPaths: List<String>): List<Finding> =
        evaluate(srText, graph, projectRoot, seedPaths).filter { it.inDomainCites.isEmpty() && it.unread == 0 && it.read > 0 }.map { toFinding(it) }

    private fun toFinding(e: Eval) = Finding(
        e.noun.korean.first(), e.subject, e.read, e.other.map { it.cite }.take(3),
        e.other.flatMap { it.tables }.distinct().take(3), e.seedNames
    )

    private fun evaluate(srText: String, graph: ProjectGraph, projectRoot: File?, seedPaths: List<String>): List<Eval> {
        if (projectRoot == null || seedPaths.isEmpty()) return emptyList()
        val nouns = requestedNouns(srText)
        if (nouns.isEmpty()) return emptyList()
        val (files, tokens) = scopeOf(graph, seedPaths)
        if (files.isEmpty()) return emptyList()
        val results = mutableListOf<Eval>()
        for (noun in nouns) {
            var read = 0
            var unread = 0
            val inDomainCites = mutableListOf<String>()
            val other = mutableListOf<OtherHit>()
            for (rel in files) {
                val f = File(projectRoot, rel)
                if (!f.isFile) { unread++; continue }
                val lines = f.readLines(Charsets.UTF_8)
                read++
                val stmts = if (rel.endsWith(".xml")) parseStatements(lines) else emptyList()
                lines.forEachIndexed { idx, text ->
                    if (!noun.pattern.containsMatchIn(text)) return@forEachIndexed
                    val ln = idx + 1
                    val st = stmts.firstOrNull { ln in it.start..it.end }
                    val cite = "${rel.substringAfterLast('/')}:$ln"
                    if (st != null) {
                        val domainTable = st.tables.any { t -> tokens.any { it in t.lowercase() } }
                        if (domainTable && !st.commented) inDomainCites.add(cite) else other.add(OtherHit(cite, st.tables))
                    } else if (!rel.endsWith(".java")) {
                        // 화면 스크립트 등: 업무를 줄만으로 가릴 수 없다(근거에서 뺀다)
                    } else {
                        val cls = rel.substringAfterLast('/').substringBeforeLast('.')
                        if (camelTokens(cls).any { it in tokens }) inDomainCites.add(cite) else other.add(OtherHit(cite, emptySet()))
                    }
                }
            }
            results.add(Eval(noun, subjectOf(srText, noun), read, unread, inDomainCites, other, seedPaths.mapNotNull { graph.files[it]?.className }))
        }
        return results // 못 읽은 파일이 있으면 호출한 쪽이 침묵한다(오탐 방지)
    }

    /**
     * /clarify 첫 요약 단계의 소스 확인(B-77). 시드는 LLM 없이 키워드로 고른다. 항목마다 업무 코드에서 찾았는지/못 찾았는지를 사실로 돌려주고,
     * 못 찾은 항목에는 근거를 붙인 질문을 만든다. 범위 파일을 다 읽지 못했거나 시드가 없으면 그 항목은 사실로 만들지 않는다(침묵).
     */
    fun stage0Facts(srText: String, graph: ProjectGraph, projectRoot: File?): List<FieldFact> {
        if (projectRoot == null || requestedNouns(srText).isEmpty()) return emptyList()
        val seeds = AgenticSeedSelector.heuristicSeeds(srText, graph).seedClasses
            .mapNotNull { c -> graph.files.values.firstOrNull { it.className == c }?.path }
        return evaluate(srText, graph, projectRoot, seeds).filter { it.unread == 0 && it.read > 0 }.map { e ->
            val f = toFinding(e)
            if (e.inDomainCites.isNotEmpty()) FieldFact(f.label, found = true, cite = e.inDomainCites.first(), filesRead = e.read)
            else FieldFact(f.label, found = false, otherCites = f.otherCites, filesRead = e.read, question = question(f))
        }
    }

    /** Stage 0 대화에 보일 질문 한 개. 못 찾았다는 사실과 다른 업무 코드에서 발견된 위치(file:line, 테이블)를 근거로 붙이고, 정답 출처는 판정하지 않는다. */
    fun question(f: Finding): String {
        val where = if (f.otherCites.isEmpty()) "다른 업무 코드에서도 발견되지 않았습니다."
        else "다른 업무 코드에서만 발견됩니다: ${f.otherCites.joinToString(", ")}" + (if (f.otherTables.isNotEmpty()) " (테이블 ${f.otherTables.joinToString("/")})" else "")
        return "${f.label}${eulReul(f.label)} 저장하거나 조회하는 코드를 찾지 못했습니다 (${scopeNote(f.seedNames, f.filesRead)}).\n$where\n어디서 가져올까요? (예: 다른 테이블 조인, 새로 저장, 잘 모르겠음)"
    }

    /**
     * 화면에 보이는 "읽은 파일 수"가 무엇을 센 것인지 밝히는 문구(B-78). 숫자는 [scopeOf]의 files 크기다:
     * 시드 클래스가 있는 폴더의 자바 파일 + 시드 이름 토큰이 파일명에 있는 리소스(매퍼 등). Stage 0 카드는 키워드 시드(heuristicSeeds), 결과 화면은 탐색기가 확정한 시드를 쓰므로 시드가 다르면 숫자도 다르다.
     */
    fun scopeNote(seedNames: List<String>, count: Int): String {
        val seeds = if (seedNames.isEmpty()) "시드" else "시드 ${seedNames.first()}" + (if (seedNames.size > 1) " 등 ${seedNames.size}개" else "")
        return "$seeds 폴더의 소스·매퍼 ${count}개를 읽어 확인"
    }

    /**
     * 요약 프롬프트의 [코드 확인 목록]에 넣을 한 줄(B-78: 평서문 사실만 둔다. 금지 지시는 시스템 프롬프트에만 있다. 전에는 줄 끝의 "…쓰지 말 것" 명령문이 요약에 그대로 나왔다).
     */
    fun promptLine(f: FieldFact): String =
        if (f.found) "- ${f.label}: ${f.cite}에 있음"
        else "- ${f.label}: 없음(후보 폴더의 소스·매퍼 ${f.filesRead}개를 읽음)" +
            (if (f.otherCites.isNotEmpty()) ", 다른 업무의 쿼리에만 있음(${f.otherCites.joinToString(", ")})" else "")

    /** 결과 화면에 붙일 경고 한 줄. */
    fun render(f: Finding): String =
        "확인 안 됨: ${f.label}${eulReul(f.label)} 조회하는 코드/SQL을 찾지 못했습니다 (${scopeNote(f.seedNames, f.filesRead)})" +
            (if (f.otherCites.isNotEmpty()) ". 다른 업무 코드에서만 발견: ${f.otherCites.joinToString(", ")}" else "") +
            (f.answer?.let { a -> ". 사용자 답변 \"${a.take(60)}\" → " + (if (isNewData(a)) "기존 데이터 없음 → 컬럼 추가·수집 경로 필요" else "출처 지정됨(조인 대상 확인 필요)") } ?: "")

    private val NEW_DATA = Regex("새로|신규|추가|수집|저장")

    /** 사용자의 출처 답이 "기존에 없으니 새로 만든다"는 뜻인가(새로 저장, 컬럼 추가, 수집 등). */
    fun isNewData(answer: String): Boolean = NEW_DATA.containsMatchIn(answer)

    /** Stage 0 질문에 대한 답("<항목> 출처: <답>" 제약의 값)을 항목 이름별로 모은다. */
    fun answersOf(constraintValues: List<String>): Map<String, String> =
        constraintValues.mapNotNull { v -> v.indexOf(" 출처: ").takeIf { it > 0 }?.let { v.substring(0, it) to v.substring(it + " 출처: ".length) } }.toMap()

    /** 구현 전에 확인이 필요한 항목 한 줄(경고가 있을 때 /implement 안내 앞에 붙는다). */
    fun implementCaution(items: List<String>): String =
        "구현 전에 확인이 필요합니다: ${items.joinToString(", ")}의 출처를 정해 주세요"

    /** 출처 답이 "새로 저장"이라 기존 데이터가 없다고 정해진 항목: 구현 범위에 컬럼 추가와 수집 경로가 들어간다는 안내. */
    fun newDataCaution(items: List<String>): String =
        "구현 범위에 새 컬럼 추가와 수집·저장 경로가 포함됩니다: ${items.joinToString(", ")}"

    /** /implement 안내 문구. 아직 출처가 정해지지 않은 경고 항목이 있으면 그 사실을 먼저 알린다(B-78: 전에는 경고가 있어도 같은 문구였다). */
    fun implementHint(unresolved: List<String>): String =
        if (unresolved.isEmpty()) "💡 위 파일들의 구체적인 코드 수정을 원하시면 `/implement`를 입력하세요."
        else "💡 ${unresolved.joinToString(", ")}의 출처가 정해지지 않았습니다. 정한 뒤 `/implement`를 입력하는 것을 권합니다(지금 입력하면 이 항목은 추정으로 구현됩니다)."

    fun warnings(srText: String, graph: ProjectGraph, projectRoot: File?, seedPaths: List<String>): List<String> =
        check(srText, graph, projectRoot, seedPaths).map { render(it) }
}
