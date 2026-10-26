package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode
import java.io.File

/*
 * "결정적 그래프 조회 + 소스에서 현재 동작 추출 + 질문 생성 LLM 1회" 구조의 실험 도구(테스트 전용, 운영 경로 아님).
 * 노드 선정 규칙과 현재 동작 문장 만들기는 LLM 없이 결정적이다. 규칙 문장은 build/eval-reports/pred_qgen.md와 docs B-69에 있다.
 */

/** 선정된 노드 하나. */
class QgenNode(
    val label: String,
    val path: String,
    val kind: String,          // FILE | RESOURCE
    val fileType: String,
    val score: Int,
    val reasons: List<String>,
    val file: FileNode?,
    val resource: ResourceNode?
)

/** 현재 동작 문장의 근거: 파일의 줄 범위와, 그 범위 안에 실제로 있어야 하는 토큰. */
class QgenEvidence(val file: String, val startLine: Int, val endLine: Int, val mustContain: List<String>)

/** 현재 동작 문장. grade는 "확인"(본문 근거), "추정"(이름·구조), "미확인". */
class QgenFact(val grade: String, val text: String, val evidence: List<QgenEvidence> = emptyList())

object QgenSelector {
    private val JOSA = listOf(
        "에서는", "으로는", "에서", "으로", "까지", "부터", "에는", "에게", "이나", "라도", "처럼", "보다", "마다", "별로",
        "을", "를", "이", "가", "은", "는", "의", "에", "로", "와", "과", "도", "만", "나", "며", "고", "면", "별"
    ).sortedByDescending { it.length }

    /** 동작어와 일반어: 변별력이 없어 용어로 쓰지 않는다. */
    private val STOP = setOf(
        "추가", "변경", "수정", "조회", "목록", "기능", "화면", "등록", "삭제", "해", "주세요", "있는", "같은", "방식", "지금", "현재",
        "모두", "그대로", "하게", "되게", "대한", "위한", "경우", "때", "것", "수", "등", "및", "또는", "이상", "쪽", "처리", "결과",
        "정보", "관리", "나오게", "보이게", "보여", "고쳐", "바꿔", "해야", "싶어요", "있게", "않게", "만들면", "됩니다", "하면",
        "엄격하게", "함께", "나오", "있도록", "하도록", "에게는", "이상하게", "같이", "다음", "먼저", "또한", "그리고"
    )

    private val TYPE_ORDER = listOf("SERVICE", "CONTROLLER", "REST_CONTROLLER", "REPOSITORY", "ENTITY", "MYBATIS_MAPPER")

    fun koreanTerms(sr: String): List<String> =
        Regex("[가-힣]+").findAll(sr).map { m ->
            var w = m.value
            val j = JOSA.firstOrNull { w.endsWith(it) && w.length - it.length >= 2 }
            if (j != null) w = w.removeSuffix(j)
            w
        }.filter { it.length >= 2 && it !in STOP }.distinct().toList()

    fun englishIdentifiers(sr: String): List<String> =
        Regex("[A-Za-z][A-Za-z0-9_]{3,}").findAll(sr).map { it.value }.distinct().toList()

    private class NodeView(
        val label: String, val path: String, val kind: String, val fileType: String,
        val comments: List<String>, val camel: Set<String>, val methodTokens: Set<String>,
        val file: FileNode?, val resource: ResourceNode?
    )

    private fun views(graph: ProjectGraph): List<NodeView> {
        val out = ArrayList<NodeView>()
        graph.files.values.forEach { f ->
            val methodTokens = f.methods.flatMap { DomainDictionary.tokenizeCamelCase(it.name) }.toSet()
            out.add(NodeView(f.className, f.path, "FILE", f.fileType.name,
                f.koreanComments + listOfNotNull(f.localName), DomainDictionary.tokenizeCamelCase(f.className).toSet(), methodTokens, f, null))
        }
        graph.resourceNodes.forEach { r ->
            val base = r.path.substringAfterLast('/').substringBeforeLast('.')
            val sqlTokens = ((r.metadata["sql_id"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()).flatMap { DomainDictionary.tokenizeCamelCase(it) }.toSet()
            out.add(NodeView(base, r.path, "RESOURCE", r.type.name, listOfNotNull(r.localName), DomainDictionary.tokenizeCamelCase(base).toSet(), sqlTokens, null, r))
        }
        return out
    }

    /** 한글 용어 하나가 노드에 주는 점수(0이면 맞지 않음). 주석·로컬 이름 3점, 사전 번역 토큰이 클래스 토큰과 같으면 2점, 메서드 토큰과만 같으면 1점. */
    private fun koreanPoints(term: String, v: NodeView, translated: Set<String>): Int = when {
        v.comments.any { it.contains(term) } -> 3
        translated.any { it in v.camel } -> 2
        translated.any { it in v.methodTokens } -> 1
        else -> 0
    }

    private fun englishPoints(id: String, v: NodeView): Int = if (v.label.contains(id, ignoreCase = true)) 3 else 0

    class Selection(val nodes: List<QgenNode>, val terms: List<String>, val droppedTerms: List<String>)

    fun select(graph: ProjectGraph, sr: String, max: Int = 5): Selection {
        val vs = views(graph)
        val dict = DomainDictionary.load(graph)
        val ko = koreanTerms(sr)
        val en = englishIdentifiers(sr)
        val translated = ko.associateWith { dict.translate(it) }
        val limit = vs.size * 0.10
        val dropped = ArrayList<String>()
        val keptKo = ko.filter { t ->
            val n = vs.count { koreanPoints(t, it, translated.getValue(t)) > 0 }
            if (n > limit) { dropped.add("$t($n)"); false } else true
        }
        val keptEn = en.filter { id ->
            val n = vs.count { englishPoints(id, it) > 0 }
            if (n > limit) { dropped.add("$id($n)"); false } else true
        }
        val scored = vs.mapNotNull { v ->
            val reasons = ArrayList<String>()
            var total = 0
            keptKo.forEach { t ->
                val p = koreanPoints(t, v, translated.getValue(t))
                if (p > 0) { total += p; reasons.add("$t:$p") }
            }
            keptEn.forEach { id ->
                val p = englishPoints(id, v)
                if (p > 0) { total += p; reasons.add("$id:$p") }
            }
            if (total >= 2) QgenNode(v.label, v.path, v.kind, v.fileType, total, reasons, v.file, v.resource) else null
        }
        val sorted = scored.sortedWith(
            compareByDescending<QgenNode> { it.score }.thenBy { n ->
                val i = TYPE_ORDER.indexOf(n.fileType)
                if (i < 0) TYPE_ORDER.size else i
            }.thenBy { it.label }
        )
        return Selection(sorted.take(max), ko + en, dropped)
    }
}

/** 소스 본문에서 현재 동작 문장을 만든다. LLM 없음. 소스는 projectRoot + 노드의 상대 경로로 읽는다. */
object QgenFacts {
    private const val BODY_LINE_LIMIT = 60
    private val CALL_SKIP = setOf("log", "logger", "System", "Collectors", "Optional", "Objects", "Arrays", "Collections", "String", "Integer", "Long")
    private val OBJECT_METHODS = setOf("toString", "hashCode", "equals")

    /** 값을 읽고 쓰기만 하는 메서드(매개변수 없는 getX/isX, 매개변수 하나인 setX)와 Object 메서드는 현재 동작으로 읽지 않는다. 매개변수가 있는 getX(예: getOrganizations(String type))는 읽는다. */
    private fun trivial(name: String, params: Int): Boolean =
        name in OBJECT_METHODS || (params == 0 && Regex("^(get|is)[A-Z].*").matches(name)) || (params == 1 && Regex("^set[A-Z].*").matches(name))

    class MethodBody(val startLine: Int, val endLine: Int, val declOnly: Boolean, val lines: List<String>, val truncated: Boolean)

    private fun paramCount(sig: String): Int {
        val inner = sig.substringAfter('(', "").substringBeforeLast(')', "")
        if (inner.isBlank()) return 0
        var depth = 0; var commas = 0
        for (c in inner) { if (c == '<' || c == '(') depth++ else if (c == '>' || c == ')') depth-- else if (c == ',' && depth == 0) commas++ }
        return commas + 1
    }

    fun extractJavaMethod(lines: List<String>, name: String, wantParams: Int): MethodBody? {
        val candidates = ArrayList<Int>()
        val re = Regex("""(^|[^A-Za-z0-9_])${Regex.escape(name)}\s*\(""")
        for ((i, line) in lines.withIndex()) {
            val m = re.find(line) ?: continue
            val nameStart = m.range.first + m.groupValues[1].length
            val before = line.substring(0, nameStart).trim()
            if (before.isEmpty() || before.endsWith(".") || before.contains("=") || before.contains("return") || before.startsWith("//") ||
                before.startsWith("*") || before.contains("new ")) continue
            candidates.add(i)
        }
        if (candidates.isEmpty()) return null
        fun sigText(i: Int): String {
            val sb = StringBuilder()
            for (k in i until minOf(lines.size, i + 8)) {
                sb.append(lines[k]).append(' ')
                if (lines[k].contains('{') || lines[k].contains(';')) break
            }
            return sb.toString()
        }
        val pick = candidates.firstOrNull { paramCount(sigText(it)) == wantParams } ?: candidates.first()
        val sig = sigText(pick)
        val semi = sig.indexOf(';'); val brace = sig.indexOf('{')
        if (brace < 0 || (semi in 0 until brace)) return MethodBody(pick + 1, pick + 1, true, listOf(lines[pick]), false)
        var depth = 0; var started = false; var end = pick
        outer@ for (k in pick until lines.size) {
            val cleaned = lines[k].replace(Regex("\"([^\"\\\\]|\\\\.)*\""), "\"\"").replace(Regex("//.*"), "")
            for (c in cleaned) {
                if (c == '{') { depth++; started = true } else if (c == '}') { depth--; if (started && depth == 0) { end = k; break@outer } }
            }
            end = k
        }
        val truncated = end - pick + 1 > BODY_LINE_LIMIT
        val last = if (truncated) pick + BODY_LINE_LIMIT - 1 else end
        return MethodBody(pick + 1, last + 1, false, lines.subList(pick, last + 1), truncated)
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun javaFacts(node: QgenNode, rel: String, lines: List<String>, srTokens: Set<String>, facts: MutableList<QgenFact>) {
        val f = node.file!!
        val methods = f.methods.filter { !it.isInherited && !trivial(it.name, it.parameters.size) }.distinctBy { it.name + it.parameters.size }
        val matched = methods.filter { m -> DomainDictionary.tokenizeCamelCase(m.name).any { it in srTokens } }
        val chosen = (matched + methods.filter { it !in matched }).take(3)
        for (m in chosen) {
            val b = extractJavaMethod(lines, m.name, m.parameters.size)
            val params = m.parameters.joinToString(", ")
            if (b == null) { facts.add(QgenFact("미확인", "`${node.label}.${m.name}($params)`의 본문을 소스에서 찾지 못했다.")); continue }
            if (b.declOnly) {
                facts.add(QgenFact("추정", "`${node.label}.${m.name}($params)`는 선언만 있고 본문이 없다(인터페이스나 추상 메서드, $rel:${b.startLine})."))
                continue
            }
            val body = b.lines.joinToString("\n")
            val calls = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""").findAll(body)
                .map { it.groupValues[1] to it.groupValues[2] }.filter { it.first !in CALL_SKIP }.map { "${it.first}.${it.second}" }.distinct().take(6).toList()
            val conds = Regex("""if\s*\((.*)\)\s*\{""").findAll(body).map { norm(it.groupValues[1]) }.filter { it.isNotEmpty() }.distinct().take(2).toList()
            val ret = Regex("""return\s+(.+?);""").find(body)?.groupValues?.get(1)?.let { norm(it) }
            val thr = Regex("""throw\s+new\s+(\w+)""").find(body)?.groupValues?.get(1)
            val parts = ArrayList<String>()
            if (calls.isNotEmpty()) parts.add("본문에서 호출하는 것: " + calls.joinToString(", "))
            if (conds.isNotEmpty()) parts.add("조건: " + conds.joinToString("; ") { "`$it`" })
            if (ret != null) parts.add("반환: `${ret.take(120)}`")
            if (thr != null) parts.add("예외: $thr")
            val must = calls.map { it } + conds + listOfNotNull(ret?.take(120)) + listOfNotNull(thr)
            facts.add(QgenFact("확인",
                "`${node.label}.${m.name}($params)`는 $rel:${b.startLine}-${b.endLine}에 있다. " + (if (parts.isEmpty()) "본문에서 읽을 호출·조건이 없다." else parts.joinToString(". ") + "."),
                listOf(QgenEvidence(rel, b.startLine, b.endLine, must))))
        }
        if (chosen.isEmpty()) facts.add(QgenFact("추정", "`${node.label}`에는 읽을 메서드 본문이 없다(필드·상수 위주)."))
    }

    private fun xmlFacts(node: QgenNode, rel: String, lines: List<String>, srTokens: Set<String>, facts: MutableList<QgenFact>) {
        val ids = (node.resource!!.metadata["sql_id"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val matched = ids.filter { id -> DomainDictionary.tokenizeCamelCase(id).any { it in srTokens } }
        val chosen = (matched + ids.filter { it !in matched }).take(3)
        for (id in chosen) {
            val startRe = Regex("""<(select|insert|update|delete)\b[^>]*\bid\s*=\s*"${Regex.escape(id)}"""")
            val si = lines.indexOfFirst { startRe.containsMatchIn(it) }
            if (si < 0) { facts.add(QgenFact("미확인", "`${node.label}`의 SQL `$id`를 소스에서 찾지 못했다.")); continue }
            val kind = startRe.find(lines[si])!!.groupValues[1]
            var ei = si
            while (ei < lines.size - 1 && !lines[ei].contains("</$kind>")) ei++
            val text = lines.subList(si, ei + 1).joinToString("\n")
            val ranges = arrayListOf(si + 1 to ei + 1)
            var full = text
            Regex("""<include\s+refid\s*=\s*"([^"]+)"""").findAll(text).map { it.groupValues[1] }.distinct().forEach { ref ->
                val fs = lines.indexOfFirst { Regex("""<sql\b[^>]*\bid\s*=\s*"${Regex.escape(ref)}"""").containsMatchIn(it) }
                if (fs >= 0) {
                    var fe = fs
                    while (fe < lines.size - 1 && !lines[fe].contains("</sql>")) fe++
                    full += "\n" + lines.subList(fs, fe + 1).joinToString("\n")
                    ranges.add(fs + 1 to fe + 1)
                }
            }
            val tables = Regex("""(?i)\b(?:from|join|update|into)\s+([A-Za-z_][\w.]*)""").findAll(full).map { it.groupValues[1] }
                .filter { it.lowercase() !in setOf("select", "dual", "set", "where") }.distinct().take(6).toList()
            val where = Regex("""(?is)\bwhere\b(.{0,200})""").find(full)?.groupValues?.get(1)?.let { norm(it).take(160) }
            val ifs = Regex("""<if\s+test\s*=\s*"([^"]+)"""").findAll(full).map { it.groupValues[1] }.distinct().take(3).toList()
            val parts = ArrayList<String>()
            if (tables.isNotEmpty()) parts.add("대상 테이블: " + tables.joinToString(", "))
            if (where != null) parts.add("WHERE: `$where`")
            if (ifs.isNotEmpty()) parts.add("동적 조건: " + ifs.joinToString("; ") { "`$it`" })
            val must = tables + listOfNotNull(where) + ifs
            val ev = ranges.map { (a, b) -> QgenEvidence(rel, a, b, emptyList()) }.toMutableList()
            ev[0] = QgenEvidence(rel, ranges[0].first, ranges[0].second, must)
            facts.add(QgenFact("확인", "`${node.label}`의 $kind `$id`($rel:${ranges[0].first}-${ranges[0].second}). " + (if (parts.isEmpty()) "읽을 조건이 없다." else parts.joinToString(". ") + "."), ev))
        }
    }

    fun build(graph: ProjectGraph, nodes: List<QgenNode>, sr: String): List<QgenFact> {
        val facts = ArrayList<QgenFact>()
        if (nodes.isEmpty()) {
            facts.add(QgenFact("미확인", "요구사항의 용어와 맞는 그래프 노드를 찾지 못했다. 현재 동작은 모두 미확인이다."))
            return facts
        }
        val dict = DomainDictionary.load(graph)
        val srTokens = QgenSelector.koreanTerms(sr).flatMap { dict.translate(it) }.toSet() +
            QgenSelector.englishIdentifiers(sr).flatMap { DomainDictionary.tokenizeCamelCase(it) }
        for (n in nodes) {
            val comments = (n.file?.koreanComments ?: emptyList()) + listOfNotNull(n.file?.localName ?: n.resource?.localName)
            facts.add(QgenFact("추정", "`${n.label}`은(는) ${n.fileType}이다" + (if (comments.isNotEmpty()) " (한글 주석: ${comments.take(3).joinToString("; ")})" else "") + "."))
            val abs = File(graph.projectRoot, n.path)
            if (!abs.isFile) { facts.add(QgenFact("미확인", "`${n.label}`의 소스(${n.path})를 읽지 못해 본문은 미확인이다.")); continue }
            val lines = abs.readLines(Charsets.UTF_8)
            if (n.kind == "FILE" && n.path.endsWith(".java")) javaFacts(n, n.path, lines, srTokens, facts)
            else if (n.kind == "RESOURCE" && n.resource?.type?.name == "MYBATIS_MAPPER") xmlFacts(n, n.path, lines, srTokens, facts)
            n.file?.apiEndpoints?.take(5)?.takeIf { it.isNotEmpty() }?.let { eps ->
                facts.add(QgenFact("추정", "`${n.label}`의 엔드포인트: " + eps.joinToString("; ") { "`${it.httpMethod} ${it.path}` → `${it.handlerMethod}`" } + "."))
            }
            n.file?.dependsOn?.takeIf { it.isNotEmpty() }?.let { deps ->
                facts.add(QgenFact("추정", "`${n.label}`이(가) 의존하는 것: " + deps.take(6).joinToString(", ") { it.substringAfterLast('/').substringBeforeLast('.') } + "."))
            }
        }
        return facts
    }

    /** 확인 문장의 근거를 소스에서 다시 읽어 대조한다. 근거 범위에 없는 토큰이 있으면 그 문장은 "본문 근거가 실제로는 없는" 문장이다. */
    fun verifyFact(projectRoot: String, fact: QgenFact): Boolean {
        if (fact.grade != "확인") return true
        if (fact.evidence.isEmpty()) return false
        // 근거 범위(여러 개일 수 있다) 전체 텍스트에 문장이 말한 토큰이 모두 있어야 한다
        val regions = StringBuilder()
        for (ev in fact.evidence) {
            val f = File(projectRoot, ev.file)
            if (!f.isFile) return false
            val lines = f.readLines(Charsets.UTF_8)
            if (ev.startLine < 1 || ev.endLine > lines.size || ev.startLine > ev.endLine) return false
            regions.append(lines.subList(ev.startLine - 1, ev.endLine).joinToString("\n")).append('\n')
        }
        val region = norm(regions.toString())
        for (tok in fact.evidence.flatMap { it.mustContain }) {
            val ok = if (Regex("^[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*$").matches(tok)) {
                val (r, m) = tok.split('.')
                Regex("\\b${Regex.escape(r)}\\s*\\.\\s*${Regex.escape(m)}\\s*\\(").containsMatchIn(region)
            } else region.contains(norm(tok)) || norm(tok).split(" ").all { region.contains(it) }
            if (!ok) return false
        }
        return true
    }
}

class QgenQuestion(val question: String, val options: List<String>, val shortAnswer: Boolean, val recommended: String, val evidence: List<Int>)

class QgenValidation(
    val parsed: Boolean,
    val questions: List<QgenQuestion>,
    val violations: List<String>,
    val fileScope: Int,
    val inferenceFormBad: Int,
    val identifiersOutsideEvidence: List<String>
)

object QgenPrompt {
    fun system(): String =
        QgenPrompt::class.java.getResourceAsStream("/eval/qgen_prompt.txt")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()

    /** 입력은 SR 원문과 현재 동작 문장뿐이다. 후보 파일 목록은 넣지 않는다. */
    fun user(sr: String, facts: List<QgenFact>): String = buildString {
        appendLine("[요구사항]")
        appendLine(sr)
        appendLine()
        appendLine("[현재 동작 문장]")
        if (facts.isEmpty()) appendLine("(조회된 현재 동작 없음: 모든 현재 동작은 미확인입니다)")
        facts.forEachIndexed { i, f -> appendLine("${i + 1}. [${f.grade}] ${f.text}") }
    }.trim()
}

object QgenValidator {
    private val FILE_SCOPE = listOf("어느 파일", "어떤 파일", "파일을 수정", "수정할 파일", "변경할 파일", "제외할 파일", "어떤 클래스", "어느 클래스", "클래스를 수정", "어느 모듈", "어떤 모듈", "패키지")
    private val FILE_EXT = Regex("""\.(java|xml|vue|js|ts|kt|jsp|html)\b""")

    fun parse(content: String?): List<QgenQuestion>? {
        if (content.isNullOrBlank()) return null
        val a = content.indexOf('{'); val b = content.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return try {
            val arr: JsonArray = JsonParser.parseString(content.substring(a, b + 1)).asJsonObject.getAsJsonArray("questions") ?: return null
            arr.map { e ->
                val o = e.asJsonObject
                fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
                QgenQuestion(
                    question = str("question"),
                    options = o.getAsJsonArray("options")?.map { it.asString } ?: emptyList(),
                    shortAnswer = o.get("shortAnswer")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    recommended = o.get("recommended")?.let { r: JsonElement -> if (r.isJsonPrimitive) r.asString else r.toString() } ?: "",
                    evidence = o.getAsJsonArray("evidence")?.mapNotNull { it.takeIf { x -> x.isJsonPrimitive }?.asInt } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /** factGrades[i]는 i+1번 현재 동작 문장의 등급. promptUser는 모델이 본 사용자 입력(식별자 대조용). */
    fun validate(content: String?, factGrades: List<String>, promptUser: String): QgenValidation {
        val qs = parse(content) ?: return QgenValidation(false, emptyList(), listOf("JSON 해석 실패"), 0, 0, emptyList())
        val v = ArrayList<String>()
        if (qs.size > 5) v.add("질문 ${qs.size}개: 상한 5 초과")
        var fileScope = 0; var inferBad = 0
        val outside = ArrayList<String>()
        for ((i, q) in qs.withIndex()) {
            if (q.shortAnswer) { if (q.options.isNotEmpty()) v.add("Q${i + 1}: shortAnswer인데 보기가 있음") }
            else if (q.options.size !in 2..5) v.add("Q${i + 1}: 보기 ${q.options.size}개(2~5 아님)")
            if (q.recommended.isBlank()) v.add("Q${i + 1}: 추천 답 없음")
            if (q.evidence.any { it != 0 && it !in 1..factGrades.size }) v.add("Q${i + 1}: 근거 번호가 범위 밖 ${q.evidence}")
            val text = q.question + " " + q.options.joinToString(" ")
            if (FILE_SCOPE.any { text.contains(it) } || FILE_EXT.containsMatchIn(text)) fileScope++
            val grades = q.evidence.filter { it in 1..factGrades.size }.map { factGrades[it - 1] }
            if (grades.isNotEmpty() && grades.all { it == "추정" } && !(q.question.contains("보입니다") && q.question.contains("맞습니까"))) {
                inferBad++; v.add("Q${i + 1}: 추정 근거인데 \"~로 보입니다. 맞습니까?\" 형태가 아님")
            }
            Regex("[A-Za-z][A-Za-z0-9_]{5,}").findAll(text).map { it.value }.filter { !promptUser.contains(it) }.forEach { outside.add(it) }
        }
        return QgenValidation(true, qs, v, fileScope, inferBad, outside.distinct())
    }
}
