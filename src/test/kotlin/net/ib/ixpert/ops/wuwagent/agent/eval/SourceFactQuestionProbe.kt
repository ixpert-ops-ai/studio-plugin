package net.ib.ixpert.ops.wuwagent.agent.eval

import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.io.File

/**
 * B-73 3단계 실험 코드(테스트 소스에만 있다, 운영 경로에 연결하지 않는다, LLM 호출 없음).
 *
 * 목표: SR의 핵심 데이터 명사(예: 이메일)가 대상 도메인 코드에 실제로 있는지 **소스 파일을 읽어** 확인하고,
 * 없거나 다른 도메인에만 있으면 업무 질문 1개를 만든다.
 * - 등급: 질문이 기대는 모든 대상 파일을 디스크에서 실제로 읽었을 때만 CONFIRMED(✓ 확인). 하나라도 못 읽었거나 그래프만 본 경우는 ESTIMATED(추정).
 * - 질문에는 근거 file:line을 붙이고, 파일 목록은 질문 본문에 넣지 않는다. 정답 출처는 판정하지 않는다(선택지에 "잘 모르겠음"을 둔다).
 */
object SourceFactQuestionProbe {

    enum class Grade(val label: String) { CONFIRMED("✓ 확인"), ESTIMATED("추정") }

    data class Evidence(val file: String, val line: Int, val text: String, val note: String) {
        fun cite(): String = "${file.substringAfterLast('/')}:$line"
    }

    data class FactQuestion(
        val subject: String?,
        val noun: String,
        val grade: Grade,
        val question: String,
        val evidence: List<Evidence>,
        val scopeFilesRead: Int,
        val scopeFilesUnread: Int,
        val inDomainHitCount: Int,
        val otherHitCount: Int,
        /** SQL 문장·자바 클래스로 업무를 가릴 수 없는 파일(화면 스크립트 등)의 일치 줄. 분류하지 못했으므로 추정으로만 알린다. */
        val unclassified: List<Evidence> = emptyList()
    )

    /** 데이터 명사 사전(실험용): 한글 명사 → 식별자 패턴. 사전에 없는 명사는 질문 대상이 아니다. */
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

    /** SR에서 사전에 있는 데이터 명사와 그 앞 단어(예: "응답자 이메일"의 "응답자")를 찾는다. */
    fun dataNounsIn(sr: String): List<Pair<String?, DataNounHit>> {
        val out = mutableListOf<Pair<String?, DataNounHit>>()
        for (d in LEXICON) for (k in d.korean) {
            val m = Regex("([가-힣]{2,})?\\s*" + Regex.escape(k)).find(sr) ?: continue
            val subject = m.groups[1]?.value?.takeIf { it != k }
            out.add(subject to DataNounHit(k, d.pattern))
        }
        return out.distinctBy { it.second.korean }
    }

    class DataNounHit internal constructor(val korean: String, internal val pattern: Regex) {
        fun matches(line: String): Boolean = pattern.containsMatchIn(line)
    }

    /** 대상 범위: 시드 파일의 패키지 디렉터리의 자바 파일 + 시드에서 읽은 도메인 토큰이 이름에 있는 리소스(매퍼 등). */
    data class Scope(val files: List<String>, val domainTokens: Set<String>)

    private fun camelTokens(name: String): List<String> =
        DomainDictionary.tokenizeCamelCase(name).map { it.lowercase() }

    fun scopeOf(graph: ProjectGraph, seedPaths: List<String>): Scope {
        val seedNodes = seedPaths.mapNotNull { graph.files[it] }
        val dirs = seedNodes.map { it.path.substringBeforeLast('/') }.toSet()
        val javaFiles = graph.files.values.filter { it.path.substringBeforeLast('/') in dirs }.map { it.path }
        val tokens = seedNodes.flatMap { camelTokens(it.className) }.filter { it.length >= 4 && it !in GENERIC }.toSet()
        val resources = graph.resourceNodes.map { it.path }.filter { p ->
            val n = p.substringAfterLast('/').substringBeforeLast('.').lowercase()
            tokens.any { it in n }
        }
        return Scope((javaFiles + resources).distinct(), tokens)
    }

    private class Stmt(val id: String, val start: Int, val end: Int, val tables: Set<String>, val commented: Boolean)

    /** MyBatis 매퍼의 문장 구간(select/insert/update/delete)과 문장이 쓰는 테이블, 주석 처리 여부. */
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
                out.add(Stmt(m.groupValues[2], i + 1, minOf(j + 1, lines.size), tables, inComment || (l.contains("<!--"))))
                i = j
            }
            if (inComment && l.contains("-->")) inComment = false
            i++
        }
        return out
    }

    private fun readLines(root: String, rel: String): List<String>? {
        val f = File(root, rel)
        return if (f.isFile) f.readLines(Charsets.UTF_8) else null
    }

    /**
     * 데이터 명사별로 범위 안 소스를 읽어 사실을 모으고 질문 0~1개를 만든다.
     * 범위(도메인) 안의 활성 코드에 명사가 있으면 질문하지 않는다.
     */
    fun questionsFor(sr: String, graph: ProjectGraph, projectRoot: String, scope: Scope): List<FactQuestion> {
        val questions = mutableListOf<FactQuestion>()
        for ((subject, noun) in dataNounsIn(sr)) {
            var read = 0
            var unread = 0
            val inDomain = mutableListOf<Evidence>()
            val other = mutableListOf<Evidence>()
            val unclassified = mutableListOf<Evidence>()
            for (rel in scope.files) {
                val lines = readLines(projectRoot, rel)
                if (lines == null) { unread++; continue }
                read++
                val stmts = if (rel.endsWith(".xml")) parseStatements(lines) else emptyList()
                lines.forEachIndexed { idx, text ->
                    if (!noun.pattern.containsMatchIn(text)) return@forEachIndexed
                    val ln = idx + 1
                    val st = stmts.firstOrNull { ln in it.start..it.end }
                    if (st != null) {
                        val domainTable = st.tables.any { t -> scope.domainTokens.any { it in t.lowercase() } }
                        val note = "문장 ${st.id}, 테이블 ${st.tables.joinToString("/")}${if (st.commented) ", 주석 처리됨" else ""}"
                        val ev = Evidence(rel, ln, text.trim(), note)
                        if (domainTable && !st.commented) inDomain.add(ev) else other.add(ev)
                    } else if (!rel.endsWith(".java")) {
                        // 화면 스크립트 등: 어느 업무의 코드인지 줄만으로는 가릴 수 없다(분류하지 않고 추정으로만 알린다)
                        unclassified.add(Evidence(rel, ln, text.trim(), "파일 ${rel.substringAfterLast('/')}"))
                    } else {
                        // 자바: 범위 안 파일이므로 같은 도메인 이름을 가진 파일의 활성 줄이면 도메인 안
                        val cls = rel.substringAfterLast('/').substringBeforeLast('.')
                        val domainFile = camelTokens(cls).any { it in scope.domainTokens }
                        val ev = Evidence(rel, ln, text.trim(), "파일 ${cls}")
                        if (domainFile) inDomain.add(ev) else other.add(ev)
                    }
                }
            }
            if (inDomain.isNotEmpty()) continue // 도메인 코드에 이미 있다: 질문 없음
            val grade = if (unread == 0 && read > 0) Grade.CONFIRMED else Grade.ESTIMATED
            val who = subject?.let { "$it " } ?: ""
            val where = if (other.isEmpty()) "이 기능 코드에서 확인되지 않습니다"
            else "이 기능 코드에서는 확인되지 않고, 다른 업무(${other.flatMap { Regex("테이블 ([^,]+)").find(it.note)?.groupValues?.get(1)?.split("/") ?: emptyList() }.distinct().joinToString(", ").ifBlank { "다른 파일" }}) 코드에만 있습니다"
            val cites = if (other.isEmpty()) "소스 ${read}개 파일을 읽어 확인" else other.take(6).joinToString(", ") { it.cite() + if (it.note.contains("주석 처리됨")) "(주석 처리됨)" else "" }
            val caveat = if (unclassified.isEmpty()) "" else " (참고, 추정: 화면 스크립트에도 같은 명사가 있으나 어느 업무인지 분류하지 못했습니다: ${unclassified.take(3).joinToString(", ") { it.cite() }})"
            val text = "[${grade.label}] ${who}${noun.korean} 정보는 $where. ${who}${noun.korean}을(를) 어디서 가져올까요? (가) 다른 테이블에서 조인 (나) 새로 저장 (다) 잘 모르겠음 [근거: $cites]$caveat"
            questions.add(FactQuestion(subject, noun.korean, grade, text, other.take(8), read, unread, inDomain.size, other.size, unclassified.take(8)))
        }
        return questions
    }
}
