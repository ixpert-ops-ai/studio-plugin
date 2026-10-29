package net.ib.ixpert.ops.wuwagent.agent

import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.agent.FileRelevanceVerifier.Companion.substituteCandidateNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * B-41 Stage 3 요약의 후보 번호 인용을 화면 표시 시점에 파일명으로 바꾸는 규칙.
 */
class FileRelevanceVerifierCitationTest {

    private val paths = listOf(
        "/src/AService.java", "/src/AServiceImpl.java", "/src/BDao.java", "/res/sql_b.xml",
        "/src/C.java", "/src/D.java"
    )

    // N1 이름 없는 번호 목록은 파일명으로 바꾸고, 괄호는 유지한다. 쉼표·공백 어느 구분도 허용한다.
    @Test
    fun n1_bareNumberListIsReplacedByFileNames() {
        val r = substituteCandidateNumbers("기존 파일(1,3)은 참조만 하고 (2 4)는 제거", paths)
        assertEquals("기존 파일(AService.java, BDao.java)은 참조만 하고 (AServiceImpl.java, sql_b.xml)는 제거", r.text)
        assertTrue(r.mismatches.isEmpty())
    }

    // N2 파일명이 3개를 넘으면 앞 3개 외 n개로 줄인다(3개는 그대로).
    @Test
    fun n2_moreThanThreeNamesAreAbbreviated() {
        assertEquals("(AService.java, AServiceImpl.java, BDao.java 외 3개)", substituteCandidateNumbers("(1,2,3,4,5,6)", paths).text)
        assertEquals("(AService.java, AServiceImpl.java, BDao.java)", substituteCandidateNumbers("(1,2,3)", paths).text)
    }

    // N3 치환하지 않는 형태: 말줄임, 범위, N 초과, 0, 목록 표기 "1)", 대괄호, 숫자 외 문자가 섞인 괄호
    @Test
    fun n3_otherFormsAreLeftUntouched() {
        val src = "(1,…) (1-3) (7) (0,1) 1) 가. [2] (1, 가) (1,2 외) 2) 나."
        val r = substituteCandidateNumbers(src, paths)
        assertEquals(src, r.text)
        assertTrue(r.mismatches.isEmpty())
        assertEquals("원문", substituteCandidateNumbers("원문", emptyList()).text)
    }

    // N4 이름(번호)에서 후보 이름이 정확히 같으면(확장자 유무·대소문자 무관) 번호만 지우고, 접미사로만 일치하면 번호 자리에 파일명을 넣는다.
    @Test
    fun n4_numberIsDroppedWhenNameMatchesCandidate() {
        val r = substituteCandidateNumbers("BDao(3)와 sql_b.xml(4), aservice(1)", paths)
        assertEquals("BDao와 sql_b.xml, aservice", r.text)
        assertTrue(r.mismatches.isEmpty())
        val s = substituteCandidateNumbers("Dao(3) 와 DEM(1)", listOf("/a/ACMDEM.java", "/a/Other.java", "/a/ECMBDao.java"))
        assertEquals("Dao(ECMBDao.java) 와 DEM(ACMDEM.java)", s.text)
        assertTrue(s.mismatches.isEmpty())
    }

    // N5 이름과 번호가 가리키는 후보가 다르면 불일치로 기록한다. B-78: 번호는 화면에 내보내지 않는다(가리키는 파일 이름이 이미 앞에 있으면 괄호만 지운다).
    @Test
    fun n5_nameNumberMismatchIsKeptAndRecorded() {
        val r = substituteCandidateNumbers("BDao(5)는 수정, C(5)는 유지", paths)
        assertEquals("BDao는 수정, C는 유지", r.text)
        assertEquals(listOf("BDao(5) → C.java"), r.mismatches)
    }

    // N6 이름 뒤에 번호가 여러 개이면 가리키는 후보가 모두 그 이름으로 끝날 때만 이름 일치로 본다. 하나라도 다르면 불일치로 기록하되(B-78) 번호 자리에는 파일명을 넣는다.
    @Test
    fun n6_multiNumberAfterNameReplacesParenthesesWhenOneMatches() {
        val own = listOf("/a/ECMTrxMapper.java", "/a/ECMTrxMapper.xml", "/a/Other.java")
        val ok = substituteCandidateNumbers("TrxMapper(1,2)는 조회 전용", own)
        assertEquals("TrxMapper(ECMTrxMapper.java, ECMTrxMapper.xml)는 조회 전용", ok.text)
        assertTrue(ok.mismatches.isEmpty())
        val r = substituteCandidateNumbers("TrxMapper(1,3) 그리고 AService(1,2)", own + "/a/AService.java")
        assertEquals("TrxMapper(ECMTrxMapper.java, Other.java) 그리고 AService(ECMTrxMapper.java, ECMTrxMapper.xml)", r.text)
        assertEquals(2, r.mismatches.size)
        val impl = substituteCandidateNumbers("AService(1,2)", paths)
        assertEquals("AService(AService.java, AServiceImpl.java)", impl.text)
        assertEquals(listOf("AService(1,2) → AService.java, AServiceImpl.java"), impl.mismatches)
    }

    // N7 2026-10-29 IDE: 앞에 이름이 모두 나열돼 있고 마지막 이름에만 번호가 붙은 문장. 번호 괄호는 지워지고 불일치는 기록된다(번호는 그날 Stage 3 표 순서).
    @Test
    fun n7_todaysSummaryNumbersAreNotShown() {
        val names = listOf("SurveyServiceImpl.java", "SurveyDto.java", "SurveyDaoImpl.java", "sql_survey.xml", "AddressDaoImpl.java", "AddressDao.java", "SurveyService.java", "SurveyDao.java", "sql_address.xml", "SurveyQImgDto.java", "UserServiceImpl.java", "ReviewDto.java", "ClosingLinkDto.java", "SearchHistRDto.java", "AddressDto.java")
        val table = names.map { "/a/$it" }
        val text = "2) SurveyQImgDto/ReviewDto/ClosingLinkDto/SearchHistRDto(10,12,13,14)는 이메일과 무관, UserServiceImpl은 제거."
        val r = substituteCandidateNumbers(text, table)
        assertFalse(r.text, Regex("\\(\\s*\\d+(?:[\\s,]+\\d+)*\\s*\\)").containsMatchIn(r.text))
        assertEquals("2) SurveyQImgDto/ReviewDto/ClosingLinkDto/SearchHistRDto는 이메일과 무관, UserServiceImpl은 제거.", r.text)
        assertEquals(1, r.mismatches.size)
    }


    // 저장된 덤프 응답에 치환을 적용한 결과를 출력한다(LLM 호출 없음). -Deval.numcite=true 일 때만 실행.
    @Test
    fun replayStoredStage3Responses() {
        Assume.assumeTrue("-Deval.numcite=true 명시 시에만 실행", System.getProperty("eval.numcite") == "true")
        val dir = File(System.getProperty("eval.numcite.dir") ?: "build/eval-reports/dumps_ui3")
        var mismatchTotal = 0
        for (id in listOf("D1", "D2", "D3", "D4", "D5", "S1", "S2", "S3")) {
            val resp = dir.listFiles { f -> f.name.startsWith("${id}_run1_") && f.name.endsWith(".resp.json") }!!.sortedBy { it.name }.first { f ->
                JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonObject.get("stage").asString.contains("Stage3")
            }
            val reasoning = JsonParser.parseString(JsonParser.parseString(resp.readText(Charsets.UTF_8)).asJsonObject.get("toolArgs").asString)
                .asJsonObject.get("reasoning").asString
            val req = JsonParser.parseString(File(dir, resp.name.removeSuffix(".resp.json") + ".req.json").readText(Charsets.UTF_8)).asJsonObject
            val user = req.getAsJsonArray("messages").map { it.asJsonObject }.first { it.get("role").asString == "user" }.get("content").asString
            val table = Regex("^- \\[(\\d+)] (\\S+)", RegexOption.MULTILINE).findAll(user).map { it.groupValues[2] }.toList()
            val r = substituteCandidateNumbers(reasoning, table)
            mismatchTotal += r.mismatches.size
            println("[CITE] $id 후보 ${table.size}개\n[CITE] 앞: $reasoning\n[CITE] 뒤: ${r.text}\n[CITE] 불일치 ${r.mismatches.size}건 ${r.mismatches}")
        }
        println("[CITE] 번호-이름 불일치 합계: $mismatchTotal")
    }
}
