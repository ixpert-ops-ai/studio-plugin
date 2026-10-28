package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.io.File

/**
 * 결과 요약이 "기존 조회/전달된다"고 주장하는 데이터 필드(이메일 등)가 시드의 업무 코드(자바, MyBatis 매퍼)에 실제로 있는지 소스를 읽어 확인하고,
 * 없으면 결과 화면에 한 줄 경고를 만든다(B-75 5). 4292986 프로토타입의 근거 수집을 옮긴 것이다.
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

    /** "그대로/기존 + 조회/전달/반환/select" 형태의 주장 문장. */
    private val CLAIM = Regex("(그대로|기존)[^.\\n]{0,40}(조회|전달|반환|select)|(조회|전달|반환)[^.\\n]{0,20}그대로", RegexOption.IGNORE_CASE)

    private class Stmt(val id: String, val start: Int, val end: Int, val tables: Set<String>, val commented: Boolean)

    class Finding(val noun: String, val subject: String?, val filesRead: Int, val otherCites: List<String>)

    /** 요약에서 주장 문장이 가리키는 데이터 명사(사전에 있는 것)와 SR에서 그 앞 단어(예: "응답자")를 찾는다. */
    private fun claimedNouns(summary: String): List<DataNoun> {
        val sentences = summary.split(Regex("(?<=[.다요])\\s+|\\n")).filter { CLAIM.containsMatchIn(it) }
        return LEXICON.filter { d -> sentences.any { s -> d.korean.any { s.contains(it) } } }
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
    fun check(summary: String, srText: String, graph: ProjectGraph, projectRoot: File?, seedPaths: List<String>): List<Finding> {
        if (projectRoot == null || seedPaths.isEmpty()) return emptyList()
        val nouns = claimedNouns(summary)
        if (nouns.isEmpty()) return emptyList()
        val (files, tokens) = scopeOf(graph, seedPaths)
        if (files.isEmpty()) return emptyList()
        val findings = mutableListOf<Finding>()
        for (noun in nouns) {
            var read = 0
            var unread = 0
            var inDomain = 0
            val other = mutableListOf<String>()
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
                        if (domainTable && !st.commented) inDomain++ else other.add(cite)
                    } else if (!rel.endsWith(".java")) {
                        // 화면 스크립트 등: 업무를 줄만으로 가릴 수 없다(근거에서 뺀다)
                    } else {
                        val cls = rel.substringAfterLast('/').substringBeforeLast('.')
                        if (camelTokens(cls).any { it in tokens }) inDomain++ else other.add(cite)
                    }
                }
            }
            if (inDomain > 0) continue
            if (unread > 0 || read == 0) continue // 못 읽은 파일이 있으면 침묵(오탐 방지)
            findings.add(Finding(noun.korean.first(), subjectOf(srText, noun), read, other.take(3)))
        }
        return findings
    }

    /** 결과 화면에 붙일 경고 한 줄(없으면 빈 목록). */
    fun warnings(summary: String, srText: String, graph: ProjectGraph, projectRoot: File?, seedPaths: List<String>): List<String> =
        check(summary, srText, graph, projectRoot, seedPaths).map { f ->
            val who = f.subject?.let { "$it " } ?: ""
            "확인 안 됨: ${who}${f.noun}을(를) 조회하는 코드/SQL을 찾지 못했습니다 (범위 파일 ${f.filesRead}개를 읽어 확인)" +
                (if (f.otherCites.isNotEmpty()) ". 다른 업무 코드에서만 발견: ${f.otherCites.joinToString(", ")}" else "")
        }
}
