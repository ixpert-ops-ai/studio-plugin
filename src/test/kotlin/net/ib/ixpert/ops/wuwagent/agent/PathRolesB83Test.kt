package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/** B-83 5: 필수/선택 표시. 기준: 같은 SR의 최종 목록에서 필수 3개(sql_survey.xml, SurveyDto, SurveyServiceImpl)가 고정이고 SurveyDao·SurveyDaoImpl은 선택이다. */
class PathRolesB83Test {
    private val root = File("C:/Workspace/HC_card_survey_admin/survey_admin")
    private val base = "src/main/java/net/infobank/iss/"
    private val xml = base + "sql/mysql/sql_survey.xml"
    private val dto = base + "survey/dto/SurveyDto.java"
    private val svcImpl = base + "survey/service/SurveyServiceImpl.java"
    private val dao = base + "survey/dao/SurveyDao.java"
    private val daoImpl = base + "survey/dao/SurveyDaoImpl.java"
    private val sr = "설문 응답 테이블에 이메일 컬럼을 추가하여 응답자 이메일을 수집하고, 관리자 설문 상세 조회 시 해당 이메일을 앞 2자리와 도메인만 노출되도록 마스킹 처리하여 백엔드 응답에 포함하도록 수정한다."

    private fun graph(): ProjectGraph {
        val f = File(root, ".meta/project-graph.json")
        Assume.assumeTrue(f.isFile)
        return Gson().fromJson(f.readText(Charsets.UTF_8), ProjectGraph::class.java).normalizeLegacyCollections()
    }

    @Test
    fun r1_같은_SR의_최종_목록에서_필수_3개_선택_2개() {
        val g = graph()
        // 최종 목록의 순서·구성이 달라도(IDE 4~5개, Stage 3 회차마다 다름) 판정은 같다
        for (list in listOf(listOf(daoImpl, xml, dto, svcImpl), listOf(daoImpl, dao, xml, dto, svcImpl), listOf(svcImpl, dto, xml), listOf(xml, svcImpl, dao, dto, daoImpl))) {
            val roles = PathRoles.classify(g, root, list, sr)
            assertEquals("$list", PathRoles.Role.REQUIRED, roles[xml]?.role)
            assertEquals("$list", PathRoles.Role.REQUIRED, roles[dto]?.role)
            assertEquals("$list", PathRoles.Role.REQUIRED, roles[svcImpl]?.role)
            if (daoImpl in list) assertEquals("$list", PathRoles.Role.OPTIONAL, roles[daoImpl]?.role)
            if (dao in list) assertEquals("$list", PathRoles.Role.OPTIONAL, roles[dao]?.role)
        }
    }

    @Test
    fun r2_SR이_새_조회_저장_메서드를_요구하면_선택_판정을_적용하지_않는다() {
        val g = graph()
        val roles = PathRoles.classify(g, root, listOf(daoImpl, dao, xml, dto, svcImpl), "설문 응답 이메일을 새 조회 메서드를 추가해서 가져오고 마스킹한다")
        assertNull("새 메서드가 필요하면 Dao는 표시하지 않는다", roles[dao])
        assertNull(roles[daoImpl])
        assertEquals(PathRoles.Role.REQUIRED, roles[xml]?.role) // 매퍼·DTO 필수 판정은 그대로
    }

    @Test
    fun r3_DTO가_최종_목록에_없으면_판정하지_않는다() {
        val g = graph()
        assertTrue(PathRoles.classify(g, root, listOf(daoImpl, dao, svcImpl), sr).isEmpty())
        assertTrue(PathRoles.classify(g, null, listOf(daoImpl, dao, dto), sr).none { it.value.role == PathRoles.Role.REQUIRED && it.key.endsWith(".xml") })
    }

    @Test
    fun r4_표시는_결정적_사유가_먼저이고_LLM_사유는_뒤다() {
        val h = PathRoles.Hint(PathRoles.Role.OPTIONAL, "기존 메서드가 SurveyDto를 그대로 전달합니다")
        assertEquals("**선택(경로 통과)** · 기존 메서드가 SurveyDto를 그대로 전달합니다 / LLM 사유: DAO 메서드 선언", PathRoles.describe(h, "DAO 메서드 선언"))
        assertEquals("LLM만", PathRoles.describe(null, "LLM만"))
        assertEquals("**선택(경로 통과)** · 기존 메서드가 SurveyDto를 그대로 전달합니다", PathRoles.describe(h, ""))
    }
}
