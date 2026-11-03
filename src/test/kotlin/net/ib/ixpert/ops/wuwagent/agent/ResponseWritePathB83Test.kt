package net.ib.ixpert.ops.wuwagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/** B-83 4(5-1): 응답 저장 경로 판정. 파라미터를 받아 쓰는 INSERT/UPDATE만 저장 경로로 보고, 파라미터 없는 집계 UPDATE는 뺀다. */
class ResponseWritePathB83Test {
    private fun xml(vararg blocks: String) = mapOf("m.xml" to blocks.joinToString("\n").lines())

    private val rawSelect = """
        <select id="selectRaw" resultType="HashMap">
            SELECT B.ANSWER_1, B.ANSWER_2, A.name
            FROM ${'$'}{web_db}.REQ_LOG A
            LEFT JOIN ${'$'}{web_db}.SURVEY_ANS B ON A.id = B.id
        </select>
    """.trimIndent()

    @Test
    fun w1_응답_테이블에_파라미터를_받아_쓰는_문장이_없으면_저장_경로_없음() {
        val j = ResponseWritePath.judge(xml(rawSelect, """
            <insert id="insertSurveyM">INSERT INTO ${'$'}{web_db}.SURVEY_M (a) VALUES (#{a})</insert>
            <update id="updateStat">UPDATE ${'$'}{web_db}.SURVEY_ANS SET cnt = cnt + 1</update>
        """.trimIndent()))
        assertEquals(setOf("SURVEY_ANS"), j.responseTables)
        assertTrue("SURVEY_M은 응답 테이블이 아니다", j.parameterizedWriters.isEmpty())
        assertEquals("파라미터 없는 집계 UPDATE는 저장 경로로 세지 않는다", listOf("m.xml:updateStat"), j.ignoredAggregates)
        assertTrue(j.noWritePath)
        assertNotNull(ResponseWritePath.notFoundSentence(j))
        assertTrue(ResponseWritePath.notFoundSentence(j)!!.contains("발견되지 않았습니다"))
    }

    @Test
    fun w2_응답_테이블에_값을_받아_쓰는_INSERT가_있으면_저장_경로_있음() {
        val j = ResponseWritePath.judge(xml(rawSelect, """
            <insert id="insertAnswer">INSERT INTO ${'$'}{web_db}.SURVEY_ANS (ANSWER_1) VALUES (#{answer1})</insert>
        """.trimIndent()))
        assertEquals(listOf("m.xml:insertAnswer"), j.parameterizedWriters)
        assertFalse(j.noWritePath)
        assertNull(ResponseWritePath.notFoundSentence(j))
    }

    @Test
    fun w3_주석_처리된_문장은_세지_않고_응답_테이블이_안_보이면_판정하지_않는다() {
        val commented = ResponseWritePath.judge(xml(rawSelect, """
            <!--
            <insert id="oldInsert">INSERT INTO ${'$'}{web_db}.SURVEY_ANS (ANSWER_1) VALUES (#{a})</insert>
            -->
        """.trimIndent()))
        assertTrue("주석 안의 INSERT는 저장 경로가 아니다", commented.noWritePath)
        val noResponseTable = ResponseWritePath.judge(xml("""<select id="s">SELECT x FROM T WHERE y = #{y}</select>"""))
        assertTrue(noResponseTable.responseTables.isEmpty())
        assertFalse("응답 테이블을 모르면 없다고 말하지 않는다", noResponseTable.noWritePath)
        assertNull(ResponseWritePath.notFoundSentence(noResponseTable))
        assertNull(ResponseWritePath.notFoundSentence(null))
    }

    @Test
    fun w4_실제_survey_admin_매퍼는_응답_저장_경로가_없다() {
        val f = File("C:/Workspace/HC_card_survey_admin/survey_admin/src/main/java/net/infobank/iss/sql/mysql/sql_survey.xml")
        Assume.assumeTrue(f.isFile)
        val j = ResponseWritePath.judge(mapOf("sql_survey.xml" to f.readLines(Charsets.UTF_8)))
        assertTrue("응답 컬럼을 읽는 테이블이 있다: ${j.responseTables}", j.responseTables.isNotEmpty())
        assertTrue("값을 받아 쓰는 문장이 없다: ${j.parameterizedWriters}", j.noWritePath)
    }
}
