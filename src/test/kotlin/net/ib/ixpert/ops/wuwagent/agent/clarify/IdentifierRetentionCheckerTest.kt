package net.ib.ixpert.ops.wuwagent.agent.clarify

import org.junit.Assert.*
import org.junit.Test

class IdentifierRetentionCheckerTest {

    @Test
    fun testExtractIdentifiersWithKoreanPostpositions() {
        val texts = listOf(
            "SurveyServiceImpl.java에 설문 등록 메소드를 추가해야해",
            "SurveyDaoImpl을 통해 DB를 조회하고",
            "sql_survey.xml에서 쿼리를 조회하고 aCMBTBAPC024DEM과 selTrcdIsInf를 확인해줘",
            "`CareMemberMgmtServiceImpl`쪽이야"
        )

        val identifiers = IdentifierRetentionChecker.extractIdentifiers(texts)

        assertTrue("확장자 파일명에 조사가 붙어도 추출되어야 함", identifiers.contains("SurveyServiceImpl.java"))
        assertTrue("CamelCase 단독 클래스명에 조사가 붙어도 추출되어야 함", identifiers.contains("SurveyDaoImpl"))
        assertTrue("XML 파일명에 조사가 붙어도 추출되어야 함", identifiers.contains("sql_survey.xml"))
        assertTrue("영숫자 AP 코드가 추출되어야 함", identifiers.contains("aCMBTBAPC024DEM"))
        assertTrue("lowerCamel 메서드명이 추출되어야 함", identifiers.contains("selTrcdIsInf"))
        assertTrue("백틱으로 감싼 식별자가 추출되어야 함", identifiers.contains("CareMemberMgmtServiceImpl"))
    }

    @Test
    fun testCommonAcronymsAndStandaloneWordsAreNotExtractedAsIdentifiers() {
        val texts = listOf(
            "외부 API 연동은 하지 않고 내부 DB 및 JSON 통신으로 처리합니다. String 타입 변환 필요."
        )

        val identifiers = IdentifierRetentionChecker.extractIdentifiers(texts)

        assertFalse("일반 약어 API는 식별자로 추출되지 않아야 함", identifiers.contains("API"))
        assertFalse("일반 약어 DB는 식별자로 추출되지 않아야 함", identifiers.contains("DB"))
        assertFalse("일반 약어 JSON은 식별자로 추출되지 않아야 함", identifiers.contains("JSON"))
        assertFalse("표준 타입 String은 식별자로 추출되지 않아야 함", identifiers.contains("String"))
    }

    @Test
    fun testPureNumbersAndDatesWithKoreanAreNotExtractedAsIdentifiers() {
        val texts = listOf(
            "2026년까지 1000건 이상의 데이터를 3개월 단위로 배치 처리하고 100_000건 제한 및 2024년 데이터 백업"
        )

        val identifiers = IdentifierRetentionChecker.extractIdentifiers(texts)

        assertFalse("연도 숫자 2026은 식별자로 추출되지 않아야 함", identifiers.contains("2026"))
        assertFalse("연도 숫자 2024는 식별자로 추출되지 않아야 함", identifiers.contains("2024"))
        assertFalse("건수 숫자 1000은 식별자로 추출되지 않아야 함", identifiers.contains("1000"))
        assertFalse("숫자 3은 식별자로 추출되지 않아야 함", identifiers.contains("3"))
        assertFalse("언더스코어 포함 숫자 100_000은 식별자로 추출되지 않아야 함", identifiers.contains("100_000"))
        assertTrue("순수 숫자/날짜 텍스트에서는 식별자가 추출되지 않아야 함", identifiers.isEmpty())
    }

    @Test
    fun testPureShapeRuleIdentifiersExtractedProperly() {
        val texts = listOf(
            "APCMMTrcdIsSVC와 SAPACMM0802S01 및 ECMBTBISM006 전문을 호출하여 ProductController에서 selectSurveyM을 실행"
        )

        val identifiers = IdentifierRetentionChecker.extractIdentifiers(texts)

        assertTrue("삼성카드 기간계 SVC 인터페이스 추출", identifiers.contains("APCMMTrcdIsSVC"))
        assertTrue("삼성카드 기간계 전문 식별자 추출", identifiers.contains("SAPACMM0802S01"))
        assertTrue("ISM 매퍼/전문 식별자 추출", identifiers.contains("ECMBTBISM006"))
        assertTrue("Controller 클래스명 추출", identifiers.contains("ProductController"))
        assertTrue("lowerCamel 메서드명 추출", identifiers.contains("selectSurveyM"))
    }

    @Test
    fun testPartialSubstringMatchDoesNotFalselySatisfyBoundary() {
        val original = "SurveyService 및 SurveyServiceImpl 수정"
        val userStatements = listOf("SurveyService 인터페이스 확인")
        // 정제문에 SurveyServiceImpl만 존재하고 SurveyService 단독 컴포넌트는 누락된 시나리오
        val refined = "SurveyServiceImpl 구현체를 수정한다."

        val result = IdentifierRetentionChecker.checkRetention(refined, original, userStatements)

        assertFalse("SurveyService가 누락되었으므로 wasRetainedWithoutModification은 false여야 함", result.wasRetainedWithoutModification)
        assertTrue("누락 목록에 SurveyService가 포함되어야 함", result.missingIdentifiers.contains("SurveyService"))
        assertTrue("보정된 정제문에 누락된 SurveyService가 병기되어야 함", result.effectiveRefinedRequirement.contains("SurveyService"))
    }

    @Test
    fun testVerifyAndRetainPreservesWhenAllIdentifiersPresent() {
        val original = "SurveyServiceImpl.java 및 sql_survey.xml 기반 개발"
        val userStatements = listOf("SurveyServiceImpl 파일 수정")
        val refined = "SurveyServiceImpl.java와 sql_survey.xml을 기반으로 설문 등록 기능을 구현한다."

        val result = IdentifierRetentionChecker.checkRetention(refined, original, userStatements)

        assertTrue(result.wasRetainedWithoutModification)
        assertEquals(emptyList<String>(), result.missingIdentifiers)
        assertEquals("식별자가 모두 존재할 때는 원 정제문 그대로 유지되어야 함", refined, result.effectiveRefinedRequirement)
    }

    @Test
    fun testCheckRetentionPreservesRawRefinedRequirementForMetrics() {
        val original = "설문 일괄 등록 개발인데 SurveyServiceImpl.java 및 sql_survey.xml 쪽이야"
        val userStatements = listOf("AlimtalkTemplateBatchRepository 제외")
        val rawRefined = "설문 일괄 등록 기능을 구현한다."

        val result = IdentifierRetentionChecker.checkRetention(rawRefined, original, userStatements)

        assertEquals("원시 정제문(rawRefinedRequirement)이 보존되어야 측정 지표를 계산할 수 있음", rawRefined, result.rawRefinedRequirement)
        assertFalse(result.wasRetainedWithoutModification)
        assertEquals(3, result.missingIdentifiers.size)
        assertTrue(result.effectiveRefinedRequirement.contains("(명시된 식별자:"))
    }

    @Test
    fun testJavaExtensionAndBaseNameEquivalence() {
        // 1. SurveyServiceImpl.java가 명시되었을 때 정제문에 기본명(SurveyServiceImpl)만 있어도 보존 인정
        val textWithBaseName = "SurveyServiceImpl을 기반으로 설문 일괄 등록 기능을 구현한다."
        assertTrue(
            "SurveyServiceImpl.java 검사 시 기본명만 있어도 containsIdentifier는 true여야 함",
            IdentifierRetentionChecker.containsIdentifier(textWithBaseName, "SurveyServiceImpl.java")
        )

        val original = "SurveyServiceImpl.java 파일 수정 요청"
        val refined = "SurveyServiceImpl에서 설문 일괄 등록 메서드를 추가한다."
        val result = IdentifierRetentionChecker.checkRetention(refined, original, emptyList())

        assertTrue("확장자가 빠지고 기본명만 남아도 보존 성공(true)이어야 함", result.wasRetainedWithoutModification)
        assertEquals(emptyList<String>(), result.missingIdentifiers)
        assertEquals(refined, result.effectiveRefinedRequirement)
    }

    @Test
    fun testPureShapeRuleWithoutHardcodedSuffixes() {
        val texts = listOf(
            "APCMMTrcdIsSVC와 SAPACMM0802S01 및 ECMBTBISM006 전문을 호출하여 ProductController에서 selectSurveyM을 실행. OrderDto와 `CustomSingleToken`도 확인."
        )

        val identifiers = IdentifierRetentionChecker.extractIdentifiers(texts)

        assertTrue("대문자 2개 이상 + 소문자 혼합 UpperCamel", identifiers.contains("APCMMTrcdIsSVC"))
        assertTrue("영숫자 혼합 AP 코드", identifiers.contains("SAPACMM0802S01"))
        assertTrue("영숫자 혼합 ISM 코드", identifiers.contains("ECMBTBISM006"))
        assertTrue("Controller 클래스명", identifiers.contains("ProductController"))
        assertTrue("lowerCamel 메서드명", identifiers.contains("selectSurveyM"))
        assertTrue("OrderDto 클래스명", identifiers.contains("OrderDto"))
        assertTrue("백틱 감싼 단일 식별자", identifiers.contains("CustomSingleToken"))

        // 단일 대문자 시작 일반 단어는 백틱/확장자 없이는 추출되지 않음
        val plainText = listOf("Product 및 Service 단독 단어는 식별자가 아님")
        val plainIds = IdentifierRetentionChecker.extractIdentifiers(plainText)
        assertFalse("단일 대문자 Product는 비추출", plainIds.contains("Product"))
        assertFalse("단일 대문자 Service는 비추출", plainIds.contains("Service"))
    }

    @Test
    fun testUserStatementsCapturesOnlyUserUtterancesWithoutAssistantOrDuplicateOriginal() {
        val originalReq = "설문 발송 채널 추가"
        val userStmt1 = "알림톡 배치는 건드리지 말고"
        val userStmt2 = "SurveyServiceImpl만 수정할 거야"

        // Engine 대화 상태 시뮬레이션
        val state = net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State(
            originalRequirement = originalReq,
            items = emptyList(),
            seedSet = emptySet(),
            userStatements = listOf(userStmt1, userStmt2)
        )

        val intent = net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent(
            originalRequirement = state.originalRequirement,
            refinedRequirement = state.originalRequirement,
            userStatements = state.userStatements,
            anchorTokens = emptyList(),
            constraints = emptyList(),
            excludedFiles = emptyList(),
            graphHash = "hash123",
            contractVersion = "1.1"
        )

        assertEquals("contractVersion은 1.1이어야 함", "1.1", intent.contractVersion)
        assertEquals("userStatements 개수는 정확히 사용자 발화 2개여야 함", 2, intent.userStatements.size)
        assertEquals(userStmt1, intent.userStatements[0])
        assertEquals(userStmt2, intent.userStatements[1])
        assertFalse("userStatements에 originalRequirement가 중복 포함되지 않아야 함", intent.userStatements.contains(originalReq))
    }

    @Test
    fun testAuxiliaryIdentifierCamelCaseMatching() {
        val classNames = listOf("BizgoApiServiceImpl", "AddressDao", "SurveyServiceImpl")
        val texts = listOf("외부 Bizgo API를 연동하여 add 및 Address 처리를 구현")

        val aux = IdentifierRetentionChecker.extractAuxiliaryIdentifiers(
            texts = texts,
            classNames = classNames,
            knownExpected = setOf("SurveyServiceImpl") // 이미 1차 식별자로 추출된 것 제외
        )

        // 1. Bizgo는 BizgoApiServiceImpl의 camelCase 분절('Bizgo')과 정확히 일치하여 보조 식별자로 추출
        assertTrue("Bizgo는 클래스명 분절 단어와 일치하여 보조 식별자로 추출되어야 함", aux.contains("Bizgo"))

        // 2. add는 AddressDao의 부분 문자열이지만 단어 단위 불일치로 오탐 방지 (add != Address)
        assertFalse("add는 AddressDao의 부분 문자열이지만 단어 단위 불일치로 추출되지 않아야 함", aux.contains("add"))

        // 3. API는 일반 불용어로 사전 제외
        assertFalse("API는 일반 불용어로 제외되어야 함", aux.contains("API"))

        // 4. Address는 AddressDao의 분절('Address')과 일치하므로 추출
        assertTrue("Address는 클래스명 분절 단어와 일치하여 추출되어야 함", aux.contains("Address"))
    }

    @Test
    fun testScopeModifierRetentionAndDropDetection() {
        val userStmts = listOf("SurveyServiceImpl만 수정하면 돼")
        val original = "설문 일괄 등록 개발"

        // 1. 정제문에서 'SurveyServiceImpl만'이 보존된 경우 -> scopeModifierDropped = false
        val refinedRetained = "SurveyServiceImpl만 수정하여 설문 일괄 등록 기능을 개발한다."
        val resultRetained = IdentifierRetentionChecker.checkRetention(refinedRetained, original, userStmts)
        assertFalse("한정 조사 '만'이 보존된 경우 scopeModifierDropped는 false여야 함", resultRetained.scopeModifierDropped)
        assertTrue("식별자 자체도 보존됨", resultRetained.wasRetainedWithoutModification)

        // 2. 정제문에서 'SurveyServiceImpl 파일 내에서'로 희석되어 '만'이 누락된 경우 -> scopeModifierDropped = true
        val refinedDropped = "SurveyServiceImpl 파일 내에서 설문 일괄 등록 기능을 구현하도록 수정한다."
        val resultDropped = IdentifierRetentionChecker.checkRetention(refinedDropped, original, userStmts)
        assertTrue("한정 조사 '만'이 누락된 경우 scopeModifierDropped는 true여야 함", resultDropped.scopeModifierDropped)
        assertTrue("식별자 이름 자체는 존재함", resultDropped.wasRetainedWithoutModification)

        // 3. '만약', '만들다' 등 일반 단어는 scopeModifier로 오인하지 않음
        val plainStmts = listOf("새로운 서비스를 만들면 돼. 만약 에러나면 처리해줘.")
        val plainRefined = "새로운 서비스를 개발한다."
        val resultPlain = IdentifierRetentionChecker.checkRetention(plainRefined, original, plainStmts)
        assertFalse("일반 단어 '만들다', '만약'은 scopeModifier로 오탐되지 않아야 함", resultPlain.scopeModifierDropped)
    }
}

