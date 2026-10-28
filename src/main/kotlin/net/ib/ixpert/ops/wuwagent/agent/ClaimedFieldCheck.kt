package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.io.File

/**
 * SR(정제문)이 다루는 데이터 필드(응답자 이메일 등)가 시드의 업무 코드(자바, MyBatis 매퍼)에 실제로 있는지 소스를 읽어 확인하고,
 * 없으면 결과 화면에 한 줄 경고를 만든다(B-75 5, B-76 3-d에서 요약 문구가 아니라 SR에서 항목을 뽑도록 바꿨다).
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

    class Finding(val noun: String, val subject: String?, val filesRead: Int, val otherCites: List<String>) {
        /** 화면에 보이는 항목 이름(예: "응답자 이메일"). */
        val label: String get() = (subject?.let { "$it " } ?: "") + noun
    }

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
    fun check(srText: String, graph: ProjectGraph, projectRoot: File?, seedPaths: List<String>): List<Finding> {
        if (projectRoot == null || seedPaths.isEmpty()) return emptyList()
        val nouns = requestedNouns(srText)
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

    /** 결과 화면에 붙일 경고 한 줄. */
    fun render(f: Finding): String =
        "확인 안 됨: ${f.label}${eulReul(f.label)} 조회하는 코드/SQL을 찾지 못했습니다 (범위 파일 ${f.filesRead}개를 읽어 확인)" +
            (if (f.otherCites.isNotEmpty()) ". 다른 업무 코드에서만 발견: ${f.otherCites.joinToString(", ")}" else "")

    /** 구현 전에 확인이 필요한 항목 한 줄(경고가 있을 때 /implement 안내 앞에 붙는다). */
    fun implementCaution(items: List<String>): String =
        "구현 전에 확인이 필요합니다: ${items.joinToString(", ")}의 출처를 정해 주세요"

    fun warnings(srText: String, graph: ProjectGraph, projectRoot: File?, seedPaths: List<String>): List<String> =
        check(srText, graph, projectRoot, seedPaths).map { render(it) }
}
