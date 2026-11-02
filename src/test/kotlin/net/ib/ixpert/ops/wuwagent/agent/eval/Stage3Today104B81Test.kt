package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.agent.FileRelevanceVerifier
import net.ib.ixpert.ops.wuwagent.agent.TargetFileSpec
import net.ib.ixpert.ops.wuwagent.client.OpenAIClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * B-81 4(측정만, 운영 연결 없음): 2026-11-02 IDE의 정제문 104자 전문(저장된 채팅 chat_1793596397928)과 그 Stage 3 후보 13개(로그 순서 그대로)로
 * Stage 3(이름 방식)를 5회 실서버에 보내 SurveyDao(인터페이스) 포함 횟수를 센다. B-80 9(97자 기준 5/5)와 비교한다.
 * 실행: ./gradlew test --tests "*Stage3Today104B81Test" -DrunLiveLlmTests=true
 */
class Stage3Today104B81Test {
    private val surveyRoot = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val sr = "설문 응답 테이블에 이메일 컬럼을 추가하여 수집하고, 관리자 설문 상세 조회 시 응답자 이메일을 앞 2자리와 도메인만 노출되도록 마스킹 처리하여 백엔드 API 응답에 포함하도록 수정한다."
    private val candidates = listOf(
        "src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java",
        "src/main/java/net/infobank/iss/survey/dto/SurveyDto.java",
        "src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java",
        "src/main/java/net/infobank/iss/sql/mysql/sql_survey.xml",
        "src/main/java/net/infobank/iss/survey/service/SurveyService.java",
        "src/main/java/net/infobank/iss/survey/dao/SurveyDao.java",
        "src/main/java/net/infobank/iss/address/dao/AddressDaoImpl.java",
        "src/main/java/net/infobank/iss/address/dao/AddressDao.java",
        "src/main/java/net/infobank/iss/sql/mysql/sql_address.xml",
        "src/main/java/net/infobank/iss/survey/dto/SurveyQImgDto.java",
        "src/main/java/net/infobank/iss/user/service/UserServiceImpl.java",
        "src/main/java/net/infobank/iss/survey/dto/ReviewDto.java",
        "src/main/java/net/infobank/iss/survey/dto/ClosingLinkDto.java"
    )

    private data class Out(val run: Int, val ms: Long, val fallback: Boolean, val kept: List<String>)

    @Test
    fun measure() {
        Assume.assumeTrue(
            "Live vLLM 실험은 -DrunLiveLlmTests=true 또는 환경변수 RUN_LIVE_LLM_TESTS=true 명시 시에만 활성화됩니다.",
            System.getProperty("runLiveLlmTests") == "true" || System.getenv("RUN_LIVE_LLM_TESTS") == "true"
        )
        Assume.assumeTrue(File(surveyRoot, ".meta/project-graph.json").isFile)
        assertEquals("IDE 로그의 정제문 길이(104자)와 같은 문장이다", 104, sr.length)
        val g = Gson().fromJson(File(surveyRoot, ".meta/project-graph.json").readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
        val runs = System.getProperty("eval.replay.runs")?.toIntOrNull() ?: 5
        val specs = candidates.mapIndexed { i, p -> TargetFileSpec(i + 1, p, "MODIFY", "") }

        val pool = Executors.newFixedThreadPool(3)
        val jobs = (1..runs).map { run ->
            pool.submit(Callable {
                val t0 = System.currentTimeMillis()
                val out = try { FileRelevanceVerifier(OpenAIClient(), g, Paths.get("nonexistent-docs"), candidateNames = true).verify(sr, specs) } catch (e: Throwable) { null }
                val o = Out(run, System.currentTimeMillis() - t0, out?.usedFallback ?: true, out?.files?.map { it.path.substringAfterLast('/') } ?: emptyList())
                println("[B81S3] run$run: ${o.ms / 1000}s 대체=${o.fallback} 남음 ${o.kept.size} SurveyDao=${"SurveyDao.java" in o.kept}")
                o
            })
        }
        val results = jobs.mapNotNull { try { it.get(300, TimeUnit.SECONDS) } catch (e: Exception) { it.cancel(true); null } }
        pool.shutdownNow()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val sb = StringBuilder("# B-81 4 Stage 3 오늘 정제문(104자) 실험 ($stamp)\n\nSR(${sr.length}자): $sr\n후보 ${candidates.size}개: ${candidates.map { it.substringAfterLast('/') }}\n\n")
        sb.appendLine("| 회차 | 시간(초) | 대체 경로 | 남은 수 | SurveyDao 포함 | 남은 파일 |\n|---|---|---|---|---|---|")
        for (r in results.sortedBy { it.run }) sb.appendLine("| ${r.run} | ${r.ms / 1000} | ${r.fallback} | ${r.kept.size} | ${"SurveyDao.java" in r.kept} | ${r.kept} |")
        val included = results.count { "SurveyDao.java" in it.kept }
        sb.appendLine("\nSurveyDao 포함: ${included}/${results.size}회, 서로 다른 남은 목록 ${results.map { it.kept.sorted() }.toSet().size}종, 대체 경로 ${results.count { it.fallback }}회")
        File("build/eval-reports").mkdirs()
        val f = File("build/eval-reports/b81_stage3_today104_$stamp.md")
        f.writeText(sb.toString(), Charsets.UTF_8)
        println("[B81S3] 보고서: ${f.absolutePath}")
    }
}
