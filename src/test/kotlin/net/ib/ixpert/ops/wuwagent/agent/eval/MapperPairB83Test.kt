package net.ib.ixpert.ops.wuwagent.agent.eval

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.AgenticSeedSelector
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DiscoveryConfig
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainExtractor
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.GraphExpander
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.RelevanceScorer
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-83 2: 보호된 Dao/DaoImpl의 인터페이스가 linkedTo에 있는 매퍼는 확장 결과에 없어도(Step E가 못 넣어도) 계약 쌍으로 최종에 포함된다.
 * 긍정: 시드에 매퍼·DaoImpl이 없고 Dao만 보호돼도 sql_survey.xml이 남는다. 부정: 무관한 매퍼는 더하지 않는다.
 */
class MapperPairB83Test {
    private val sr = "설문 응답 테이블에 이메일 컬럼을 추가하여 응답자 이메일을 수집하고, 관리자 설문 상세 조회 시 해당 이메일을 마스킹 처리하여 백엔드 응답에 포함하도록 수정한다."

    private fun graph(): ProjectGraph {
        val f = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json")
        Assume.assumeTrue(f.isFile)
        return Gson().fromJson(f.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    private fun run(g: ProjectGraph, seeds: List<String>, dropMapperFromExpansion: Boolean) =
        AgenticSeedSelector.heuristicSeeds(sr, g).copy(seedClasses = seeds).let { s ->
            val ex = GraphExpander(g, DomainExtractor(g.files), DiscoveryConfig(maxHop = 3)).expand(s, sr)
            // 확장이 매퍼를 못 넣은 경우를 만든다(Step E가 빠진 것과 같다)
            val expanded = if (dropMapperFromExpansion) ex.filterKeys { !it.endsWith(".xml") } else ex
            RelevanceScorer(g, 30, 55).scoreAndFilter(sr, expanded, s)
        }

    @Test
    fun m1_긍정_시드에_매퍼와_DaoImpl이_없고_Dao만_보호돼도_sql_survey가_최종에_남는다() {
        val g = graph()
        val r = run(g, listOf("SurveyDao"), dropMapperFromExpansion = true)
        val mapper = r.firstOrNull { it.className == "sql_survey.xml" }
        assertNotNull("sql_survey.xml 포함: ${r.map { it.className }}", mapper)
        assertEquals("Seed Direct Dependency", mapper!!.protectionReason)
        assertTrue(mapper.discoveryReason, mapper.discoveryReason.contains("CONTRACT_PAIR [Mapper: SurveyDao]"))
    }

    @Test
    fun m2_긍정_DaoImpl만_보호돼도_인터페이스를_거쳐_매퍼가_남는다() {
        val g = graph()
        val r = run(g, listOf("SurveyDaoImpl"), dropMapperFromExpansion = true)
        assertNotNull("sql_survey.xml 포함: ${r.map { it.className }}", r.firstOrNull { it.className == "sql_survey.xml" })
    }

    @Test
    fun m3_부정_무관한_매퍼는_더하지_않는다() {
        val g = graph()
        val r = run(g, listOf("SurveyDao"), dropMapperFromExpansion = true)
        for (other in listOf("sql_address.xml", "sql_user.xml", "sql_batch.xml", "sql_system.xml", "sql_app.xml")) {
            assertNull("$other 는 이 Dao와 무관: ${r.map { it.className }}", r.firstOrNull { it.className == other && it.discoveryReason.contains("CONTRACT_PAIR [Mapper") })
        }
    }

    @Test
    fun m4_계약_쌍_매퍼는_항상_보호된_Dao_계열에서만_나온다() {
        val g = graph()
        // Dto만 시드여도 SurveyDaoImpl이 Seed Direct Dependency로 보호되면 그 Dao의 매퍼는 정당하게 붙는다. 어떤 경우든 매퍼의 출처(fromPath)는 보호된 Dao/DaoImpl이다.
        for (seeds in listOf(listOf("SurveyDto"), listOf("SurveyServiceImpl"), listOf("SurveyDao"), listOf("IpsController"))) {
            val r = run(g, seeds, dropMapperFromExpansion = true)
            for (m in r.filter { it.discoveryReason.contains("CONTRACT_PAIR [Mapper") }) {
                val from = r.firstOrNull { it.path == m.fromPath }
                assertNotNull("$seeds: 출처가 결과에 있다 ${m.fromPath}", from)
                assertTrue("$seeds: 출처는 보호된 Dao 계열 ${from!!.className}", from.isProtected && (from.className.endsWith("Dao") || from.className.endsWith("DaoImpl")))
            }
        }
    }
}
