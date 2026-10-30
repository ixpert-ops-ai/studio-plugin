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
    private class Eval(val noun: DataNoun, val subject: String?, val read: Int, val unread: Int, val inDomainCites: List<String>, val other: List<OtherHit>, val seedNames: List<String> = emptyList(), val domainScope: Boolean = true)

    class Finding(
        val noun: String, val subject: String?, val filesRead: Int, val otherCites: List<String>, val otherTables: List<String> = emptyList(),
        /** 범위를 정한 시드 클래스 이름(읽은 파일 수가 무엇을 센 것인지 화면에 밝히는 데 쓴다). */
        val seedNames: List<String> = emptyList(),
        /** 사용자가 Stage 0 질문에 한 답(ClarifyIntent의 "<항목> 출처: <답>" 제약). 없으면 null. */
        val answer: String? = null,
        /** 실제로 읽은 자바 폴더가 모두 시드가 가리키는 도메인(설문 등)의 패키지인가. 아니면 화면 문구는 도메인 이름을 쓰지 않는다(B-80). */
        val domainScope: Boolean = true
    ) {
        /** 화면에 보이는 항목 이름(예: "응답자 이메일"). */
        val label: String get() = (subject?.let { "$it " } ?: "") + noun

        fun withAnswer(a: String?): Finding = Finding(noun, subject, filesRead, otherCites, otherTables, seedNames, a, domainScope)
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
        e.other.flatMap { it.tables }.distinct().take(3), e.seedNames, domainScope = e.domainScope
    )

    private fun evaluate(srText: String, graph: ProjectGraph, projectRoot: File?, seedPaths: List<String>): List<Eval> {
        if (projectRoot == null || seedPaths.isEmpty()) return emptyList()
        val nouns = requestedNouns(srText)
        if (nouns.isEmpty()) return emptyList()
        val (files, tokens) = scopeOf(graph, seedPaths)
        if (files.isEmpty()) return emptyList()
        // 읽은 자바 폴더가 모두 시드 이름 토큰(survey 등)이 든 패키지일 때만 화면에서 도메인 이름("설문 관련 코드")으로 말한다(B-80 8)
        val javaDirs = files.filter { it.endsWith(".java") || it.endsWith(".kt") }.map { it.substringBeforeLast('/').lowercase() }.distinct()
        val domainScope = javaDirs.isNotEmpty() && javaDirs.all { d -> tokens.any { it in d } }
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
            results.add(Eval(noun, subjectOf(srText, noun), read, unread, inDomainCites, other, seedPaths.mapNotNull { graph.files[it]?.className }, domainScope))
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
        else "${foundOnlyIn(f)}: ${f.otherCites.joinToString(", ")}"
        return "${f.label}${eulReul(f.label)} 저장하거나 조회하는 코드를 찾지 못했습니다 (${scopeNote(f.seedNames, f.domainScope)}).\n$where\n어디서 가져올까요? (예: 다른 테이블 조인, 새로 저장, 잘 모르겠음)"
    }

    /**
     * 다른 곳에서만 발견됐다는 말(B-80 8): 발견된 SQL이 읽는 테이블이 있으면 테이블 기준으로 말한다("설문 매퍼 안의 ISSW_ADMIN_USER 테이블 쿼리에서만 발견됩니다").
     * 도메인 이름은 읽은 폴더가 모두 그 도메인 패키지일 때만 쓰고 아니면 "읽은 범위 안의"라고 한다. 테이블이 없는 발견(자바 코드)은 "다른 업무 코드"라고만 한다.
     */
    private fun foundOnlyIn(f: Finding): String {
        if (f.otherTables.isEmpty()) return "다른 업무 코드에서만 발견됩니다"
        val word = if (f.domainScope) DomainDictionary.domainWordFor(f.seedNames.flatMap { camelTokens(it) }) else null
        val where = if (word != null) "$word 매퍼 안의" else "읽은 범위 안의"
        return "$where ${f.otherTables.joinToString("/")} 테이블 쿼리에서만 발견됩니다"
    }

    /**
     * 화면에 보이는 범위 문구(B-79): 시드 클래스 이름, 폴더 수, 파일 수를 사용자 문구에서 뺀다. 시드 클래스가 가리키는 도메인 명사를 사전에서 찾아 "설문 관련 코드"로 말한다(사전에 없으면 "관련 코드").
     * 실제로 읽은 범위는 [scopeOf]의 files(시드 클래스가 있는 폴더의 자바 파일 + 시드 이름 토큰이 파일명에 있는 리소스)이며 숫자는 로그와 시험에서만 쓴다.
     * Stage 0 카드는 키워드 시드(heuristicSeeds), 결과 화면은 탐색기가 확정한 시드를 쓰므로 시드가 다르면 읽은 범위도 다르다.
     */
    fun scopeArea(seedNames: List<String>, domainScope: Boolean = true): String {
        // B-80: "설문 관련 코드"는 실제로 읽은 폴더가 모두 그 도메인 패키지일 때만 쓴다. 배치 등 다른 폴더가 섞였으면 중립 문구를 쓴다.
        if (!domainScope) return "관련 코드"
        val word = DomainDictionary.domainWordFor(seedNames.flatMap { camelTokens(it) })
        return if (word != null) "$word 관련 코드" else "관련 코드"
    }

    fun scopeNote(seedNames: List<String>, domainScope: Boolean = true): String = "${scopeArea(seedNames, domainScope)}를 읽어 확인"

    /**
     * 요약 프롬프트의 [코드 확인 목록]에 넣을 한 줄(B-78: 평서문 사실만 둔다. 금지 지시는 시스템 프롬프트에만 있다. 전에는 줄 끝의 "…쓰지 말 것" 명령문이 요약에 그대로 나왔다).
     */
    fun promptLine(f: FieldFact): String =
        if (f.found) "- ${f.label}: ${f.cite}에 있음"
        else "- ${f.label}: 없음(후보 폴더의 소스·매퍼 ${f.filesRead}개를 읽음)" +
            (if (f.otherCites.isNotEmpty()) ", 다른 업무의 쿼리에만 있음(${f.otherCites.joinToString(", ")})" else "")

    /** 결과 화면에 붙일 경고 한 줄. */
    fun render(f: Finding): String =
        "확인 안 됨: ${f.label}${eulReul(f.label)} 조회하는 코드/SQL을 찾지 못했습니다 (${scopeNote(f.seedNames, f.domainScope)})" +
            (if (f.otherCites.isNotEmpty()) ". ${foundOnlyIn(f)}: ${f.otherCites.joinToString(", ")}" else "")

    /**
     * 사용자가 Stage 0 질문에 답해 해소된 항목의 안내 한 줄(B-79: "확인 안 됨" 경고 대신 이 줄만 보인다).
     * 답이 "새로 저장"이면 기존 데이터가 없어 컬럼 추가·수집 경로가 구현 범위에 들어간다는 뜻이고, 그 밖의 답이면 출처가 지정됐다는 뜻이다.
     */
    fun renderResolved(f: Finding): String {
        val a = f.answer ?: return render(f)
        // 답에서 출처 부분만 인용한다(Stage 0 "기록함"과 같은 해석, 앞 60자 자르기 대신)
        val quote = "사용자 답변 \"${net.ib.ixpert.ops.wuwagent.agent.clarify.AnswerInterpreter.sourceQuote(a)}\""
        return if (isNewData(a)) "${f.label}: ${scopeArea(f.seedNames, f.domainScope)}를 읽어 확인했으나 기존 데이터가 없습니다. $quote 에 따라 새 컬럼 추가와 수집·저장 경로가 구현 범위에 포함됩니다."
        else "${f.label}: ${scopeArea(f.seedNames, f.domainScope)}를 읽어 확인했으나 기존 코드에는 없고, $quote 으로 출처가 지정되었습니다(조인 대상 확인 필요)."
    }

    private val SERVICE_FILE = Regex("(Service(Impl)?|SVC(Impl)?)\\.(java|kt)$")

    /**
     * 결과 표의 사유와 ℹ️ 줄의 일치(B-80 6): 답변으로 "수집·저장"이 범위에 들어갔으면 ℹ️ 줄이 말하는 저장 경로가 표의 Service 사유에도 보여야 한다.
     * 최종 목록의 첫 Service 파일에만 붙이고, 그 사유가 이미 저장·수집을 말하면 붙이지 않는다. 해당 없으면 null.
     */
    fun savePathReason(findings: List<Finding>, path: String, finalPaths: List<String>, description: String): String? {
        if (!SERVICE_FILE.containsMatchIn(path) || finalPaths.firstOrNull { SERVICE_FILE.containsMatchIn(it) } != path) return null
        if (description.contains("저장") || description.contains("수집")) return null
        val labels = findings.filter { it.answer != null && isNewData(it.answer) }.map { it.label }
        if (labels.isEmpty()) return null
        return "${labels.joinToString(", ")} 수집·저장 경로 추가(저장 쿼리·프로시저 호출 등)"
    }

    private val NEW_COLUMN = Regex("컬럼|칼럼|column", RegexOption.IGNORE_CASE)

    /** 답이 새 컬럼을 만드는 것이면 DB 스키마 변경이 필요하다는 안내 한 줄(DDL 파일은 만들지 않는다). 해당 없으면 null. */
    fun ddlNote(f: Finding): String? {
        val a = f.answer ?: return null
        if (!isNewData(a) || !NEW_COLUMN.containsMatchIn(a)) return null
        return "${f.label} 저장용 새 컬럼은 DB 스키마 변경(ALTER TABLE … ADD COLUMN)이 먼저 필요합니다. 이 분석은 DDL 파일을 만들지 않았습니다."
    }

    private val ENTRY_POINT = Regex("Controller\\.(java|kt)$|\\.(jsp|html|vue|js|jsx|tsx)$")

    /** 최종 목록에 요청 진입점(컨트롤러 또는 화면 파일)이 하나라도 있는가. */
    fun hasEntryPoint(paths: List<String>): Boolean = paths.any { ENTRY_POINT.containsMatchIn(it) }

    /** 새로 저장하기로 했는데 요청 진입점이 최종 목록에 없을 때의 안내 한 줄(파일은 추가하지 않는다). */
    fun entryPointNote(f: Finding, suggestedController: String? = null): String =
        "${f.subject ?: "사용자"}가 ${f.noun}${eulReul(f.noun)} 입력·전달하는 경로(컨트롤러·화면)는 분석 대상에 없습니다. 이 분석은 파일을 추가하지 않았습니다. " +
            // B-80 7: 다음 행동. 후보 단계에 있었지만 최종에서 빠진 컨트롤러가 있으면 그 이름을 쓴다
            (if (suggestedController != null) "필요하면 @$suggestedController 를 추가해 다시 분석하세요." else "필요하면 요청을 받는 컨트롤러나 화면 파일을 @로 추가해 다시 분석하세요.")

    private val CONTROLLER_FILE = Regex("Controller\\.(java|kt)$")

    /** 다음 행동 안내에 쓸 컨트롤러: 후보 단계에 있었지만 최종 목록에 없는 첫 컨트롤러의 클래스 이름. 없으면 null. */
    fun suggestedEntryController(candidatePaths: List<String>, finalPaths: List<String>): String? =
        candidatePaths.firstOrNull { CONTROLLER_FILE.containsMatchIn(it) && it !in finalPaths }?.substringAfterLast('/')?.substringBeforeLast('.')

    private val NEW_DATA = Regex("새로|신규|추가|수집|저장")

    /** 사용자의 출처 답이 "기존에 없으니 새로 만든다"는 뜻인가(새로 저장, 컬럼 추가, 수집 등). */
    fun isNewData(answer: String): Boolean = NEW_DATA.containsMatchIn(answer)

    /** Stage 0 질문에 대한 답("<항목> 출처: <답>" 제약의 값)을 항목 이름별로 모은다. */
    fun answersOf(constraintValues: List<String>): Map<String, String> =
        constraintValues.mapNotNull { v -> v.indexOf(" 출처: ").takeIf { it > 0 }?.let { v.substring(0, it) to v.substring(it + " 출처: ".length) } }.toMap()

    /** 구현 전에 확인이 필요한 항목 한 줄(경고가 있을 때 /implement 안내 앞에 붙는다). */
    fun implementCaution(items: List<String>): String =
        "구현 전에 확인이 필요합니다: ${items.joinToString(", ")}의 출처를 정해 주세요"

    /** /implement 안내 문구. 아직 출처가 정해지지 않은 경고 항목이 있으면 그 사실을 먼저 알린다(B-78: 전에는 경고가 있어도 같은 문구였다). */
    fun implementHint(unresolved: List<String>): String =
        if (unresolved.isEmpty()) "💡 위 파일들의 구체적인 코드 수정을 원하시면 `/implement`를 입력하세요."
        else "💡 ${unresolved.joinToString(", ")}의 출처가 정해지지 않았습니다. 정한 뒤 `/implement`를 입력하는 것을 권합니다(지금 입력하면 이 항목은 추정으로 구현됩니다)."

    fun warnings(srText: String, graph: ProjectGraph, projectRoot: File?, seedPaths: List<String>): List<String> =
        check(srText, graph, projectRoot, seedPaths).map { render(it) }
}
