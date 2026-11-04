package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.ClaimedFieldCheck
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * B-84 2·3 측정 전용(실서버, -DrunLiveLlmTests=true일 때만). 탐색기 temperature가 0.0이라 같은 입력 반복은 하지 않고 입력 변형마다 1회만 돈다.
 * 변형: SR 끝 줄바꿈(전: "\n" 하나, 후: 없음) x SR 97자/108자 x 사실 블록 유/무. 결과는 build/b84_variants.md.
 */
class MeasureB84Test {
    private val root = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val original = "기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요"
    private val answer = "응답자 이메일은 아직 수집하지 않아. 설문 응답 테이블에 이메일 컬럼을 새로 추가해서 저장하는 방향으로 해줘."
    private val sr97 = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 보이도록 마스킹하여 반환한다."
    private val sr108 = "설문 응답 테이블에 이메일 컬럼을 추가하여 응답자 이메일을 수집하고, 관리자 설문 상세 조회 시 해당 이메일을 앞 2자리와 도메인만 노출되도록 마스킹 처리하여 백엔드 응답에 포함하도록 수정한다."

    private fun live() = System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"

    @Test
    fun variants() {
        Assume.assumeTrue(live())
        val gf = File(root, ".meta/project-graph.json")
        Assume.assumeTrue(gf.isFile)
        val g = Gson().fromJson(gf.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        val factsBlock = ClaimedFieldCheck.explorerFacts(original, g, root, ClaimedFieldCheck.answersOf(listOf("응답자 이메일 출처: $answer")))
        val out = StringBuilder("# B-84 2 입력 변형 비교(변형마다 1회, temperature 0.0)\n| SR | 끝 줄바꿈 | 사실 블록 | 시드 | 턴 | 시드에 매퍼 | Stage 1 후보 수 | 후보에 sql_survey.xml | 이메일 단정 줄 | 저장·수집 단정 줄 | 시간(초) |\n|---|---|---|---|---|---|---|---|---|---|---|\n")
        for ((srName, sr) in listOf("97자" to sr97, "108자" to sr108)) for (trailing in listOf(true, false)) for (facts in listOf(false, true)) {
            val srText = if (trailing) "$sr\n" else sr
            val old = System.out
            val baos = ByteArrayOutputStream()
            System.setOut(PrintStream(baos, true, "UTF-8"))
            val t0 = System.currentTimeMillis()
            val seed = try { AgenticSeedSelector(OpenAIClient(), root.path, null, if (facts) factsBlock else null).selectSeeds(srText, g) } finally { System.setOut(old) }
            val ms = System.currentTimeMillis() - t0
            val log = baos.toString("UTF-8").lines()
            val turns = log.count { it.contains("=== TURN") }
            val msgs = log.filter { it.contains("Message:") || it.contains("rationale") }
            val emailClaims = msgs.count { Regex("이메일.*(이미|포함|있|조회)").containsMatchIn(it) && !Regex("없|추가|신설|새로|수집|마스킹").containsMatchIn(it) }
            // 저장·수집 단정: 저장/수집/updateResult가 이미 있다·처리한다는 줄(없다·않다·추가·신설·새로를 함께 말하면 제외)
            val saveClaims = msgs.count { Regex("(응답|이메일)?\\s*(저장|수집)\\S*.*(처리|로직|담당|조회/저장)|updateResult").containsMatchIn(it) && !Regex("없|않|발견되지|확인되지|추가|신설|새로|하도록").containsMatchIn(it) }
            val ex = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(seed, sr)
            val scored = try { System.setOut(PrintStream(ByteArrayOutputStream())); RelevanceScorer(g, 30, 55).scoreAndFilter(sr, ex, seed) } finally { System.setOut(old) }
            val mapper = scored.firstOrNull { it.className == "sql_survey.xml" }
            out.appendLine("| $srName | ${if (trailing) "있음(전)" else "없음(후)"} | ${if (facts) "유" else "무"} | ${seed.seedClasses} | $turns | ${"sql_survey.xml" in seed.seedClasses} | ${scored.size} | ${mapper != null}${mapper?.let { "(${it.score}/${it.protectionReason})" } ?: ""} | $emailClaims | $saveClaims | ${ms / 1000} |")
            File("build/b84_variants.md").writeText(out.toString(), Charsets.UTF_8)
        }
        println(out)
    }
}
