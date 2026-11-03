package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-83 0: 시드 규칙(B-82 2)의 시험. 배치 이름은 시드에서 빠지고, 그래프 순서 앞 5개 규칙은 없으며,
 * SR이 배치를 직접 말하거나 그 클래스 이름을 쓰면 예외로 시드가 된다.
 */
class SeedRuleB83Test {
    private val batchName = Regex("Batch|Runner|Job|Cli")

    private fun graph(): ProjectGraph {
        val f = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        Assume.assumeTrue(f.isFile)
        return Gson().fromJson(f.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    @Test
    fun s1_오늘_SR의_시드에는_배치가_없고_설문_백엔드가_든다() {
        val seeds = AgenticSeedSelector.heuristicSeeds("기존 설문조사 상세 조회 기능에 응답자 이메일 정보도 같이 내려주도록 백엔드를 수정해주세요", graph()).seedClasses
        assertTrue("배치 시드 없음: $seeds", seeds.none { batchName.containsMatchIn(it) })
        assertTrue("설문 백엔드가 시드: $seeds", seeds.containsAll(listOf("SurveyServiceImpl", "SurveyDaoImpl", "SurveyDto")))
        assertTrue("시드는 최대 7개(상위 5 + 한글 근거 전용 2): $seeds", seeds.size <= 7)
    }

    @Test
    fun s2_그래프_순서_앞쪽_클래스는_근거_없이_시드가_되지_않는다() {
        // 근거(이름·한글명·주석)가 하나도 없는 단어만 있는 SR은 시드가 비거나 근거 있는 노드뿐이다(그래프 순서 앞 5개 대체 없음)
        val seeds = AgenticSeedSelector.heuristicSeeds("qwertyuiop zxcvbnm 처리", graph()).seedClasses
        assertTrue("근거 없는 SR: $seeds", seeds.isEmpty())
    }

    @Test
    fun s3_SR이_배치를_직접_말하면_배치_클래스도_시드가_된다() {
        val g = graph()
        val explicitName = AgenticSeedSelector.heuristicSeeds("SurveyPartExportRunner 에서 설문 응답 이메일을 내보내도록 수정", g).seedClasses
        assertTrue("직접 쓴 클래스 이름은 배치여도 시드: $explicitName", "SurveyPartExportRunner" in explicitName)
        val batchWord = AgenticSeedSelector.heuristicSeeds("설문 응답 내보내기 배치를 수정", g).seedClasses
        assertTrue("SR이 배치를 말하면 배치 클래스가 시드에 들 수 있다: $batchWord", batchWord.any { batchName.containsMatchIn(it) })
        // 반대로 배치를 말하지 않은 같은 SR은 배치가 없다
        val noBatch = AgenticSeedSelector.heuristicSeeds("설문 응답 내보내기를 수정", g).seedClasses
        assertFalse("배치를 말하지 않으면 배치 시드 없음: $noBatch", noBatch.any { batchName.containsMatchIn(it) })
    }
}
