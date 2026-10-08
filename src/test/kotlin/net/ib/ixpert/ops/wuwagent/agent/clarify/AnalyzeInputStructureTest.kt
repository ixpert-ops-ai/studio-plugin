package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.ClarifyIntent
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.HintSource
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.RetentionAudit
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.UnresolvedItem
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.UnresolvedKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 커밋 2b: ResolvedAnalyzeInput 구조화와 effectiveRequirement 조립식 검증.
 * 조립식: 누락 식별자가 없으면 rawRefined, 있으면 `"$rawRefined\n" + missing.joinToString("") { "\n$it" }`.
 */
class AnalyzeInputStructureTest {

    private fun intent(
        original: String = "원문",
        refined: String,
        audit: RetentionAudit? = null,
        unresolved: List<UnresolvedItem> = emptyList(),
        excluded: List<String> = emptyList()
    ) = ClarifyIntent(
        originalRequirement = original,
        refinedRequirement = refined,
        excludedFiles = excluded,
        graphHash = "dummyHash",
        retentionAudit = audit,
        unresolvedItems = unresolved
    )

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    // 1. retentionAudit == null 이면 refinedRequirement를 그대로 쓴다 (옛 인텐트, 감사 기록 없는 테스트 픽스처)
    @Test
    fun auditNullFallsBackToRefinedRequirementByteForByte() {
        val refined = "ExistingService 및 ObsoleteService 기능 점검 및 수정 (명시된 식별자: A, B)"
        val unresolved = UnresolvedItem("BizgoApiService", UnresolvedKind.NEW_CREATION, null, HintSource.USER_UTTERED, 1, null, null)
        val resolved = AnalyzeInputResolver.resolve(
            rawInput = "무시되는 입력",
            inMemoryIntent = intent(refined = refined, audit = null, unresolved = listOf(unresolved), excluded = listOf("a/B.java"))
        )

        assertArrayEquals(bytes(refined), bytes(resolved.effectiveRequirement))
        assertEquals(refined, resolved.rawRefined)
        assertEquals(emptyList<String>(), resolved.missingIdentifiers)
        assertEquals(listOf(unresolved), resolved.unresolvedItems)
        assertEquals(listOf("a/B.java"), resolved.excludedFiles)
    }

    // 2. missingBeforeFix가 비어 있으면 effectiveRequirement는 기존 refinedRequirement와 바이트 단위로 같다
    @Test
    fun emptyMissingKeepsEffectiveRequirementIdenticalToRefined() {
        val refined = "설문 발송 채널에 브랜드메시지 추가"
        val audit = RetentionAudit(rawRefinedRequirement = refined, missingBeforeFix = emptyList())
        val resolved = AnalyzeInputResolver.resolve(
            rawInput = refined,
            inMemoryIntent = intent(refined = refined, audit = audit)
        )

        assertArrayEquals(bytes(refined), bytes(resolved.effectiveRequirement))
        assertEquals(refined, resolved.rawRefined)
        assertEquals(emptyList<String>(), resolved.missingIdentifiers)
    }

    // 2-보충. 정제문이 공백이면 originalRequirement로 복원하는 기존 동작을 유지한다 (감사 기록이 있어도)
    @Test
    fun blankRefinedStillFallsBackToOriginal() {
        val audit = RetentionAudit(rawRefinedRequirement = "", missingBeforeFix = emptyList())
        val resolved = AnalyzeInputResolver.resolve(
            rawInput = "rawInput",
            inMemoryIntent = intent(original = "원본 요구사항", refined = "", audit = audit)
        )
        assertEquals("원본 요구사항", resolved.effectiveRequirement)
        assertEquals("원본 요구사항", resolved.rawRefined)
    }

    // 3. 누락 식별자가 있으면 "$raw\n" + 줄마다 "\n$id" 형식, 순서는 주어진 그대로(재정렬하지 않음)
    @Test
    fun missingIdentifiersAreAppendedAsLinesInGivenOrder() {
        val raw = "정제문 본문"
        val missing = listOf("AlphaService", "BetaDto")
        val audit = RetentionAudit(rawRefinedRequirement = raw, missingBeforeFix = missing)
        val resolved = AnalyzeInputResolver.resolve(
            rawInput = "x",
            inMemoryIntent = intent(refined = "$raw (명시된 식별자: AlphaService, BetaDto)", audit = audit)
        )

        assertEquals("정제문 본문\n\nAlphaService\nBetaDto", resolved.effectiveRequirement)
        assertEquals(raw, resolved.rawRefined)
        assertEquals(missing, resolved.missingIdentifiers)

        val reversed = AnalyzeInputResolver.resolve(
            rawInput = "x",
            inMemoryIntent = intent(refined = raw, audit = RetentionAudit(rawRefinedRequirement = raw, missingBeforeFix = listOf("BetaDto", "AlphaService")))
        )
        assertEquals("정제문 본문\n\nBetaDto\nAlphaService", reversed.effectiveRequirement)
    }

    // 5. 인텐트가 null인 단독 /analyze: 사용자 입력 그대로, 나머지 필드는 비어 있다
    @Test
    fun standaloneAnalyzeKeepsRawInputAndEmptyFields() {
        val resolved = AnalyzeInputResolver.resolve(rawInput = "새 SR: 회원 탈퇴 처리", inMemoryIntent = null)

        assertEquals("새 SR: 회원 탈퇴 처리", resolved.effectiveRequirement)
        assertNull(resolved.effectiveIntent)
        assertEquals("새 SR: 회원 탈퇴 처리", resolved.rawRefined)
        assertEquals(emptyList<String>(), resolved.missingIdentifiers)
        assertEquals(emptyList<UnresolvedItem>(), resolved.unresolvedItems)
        assertEquals(emptyList<String>(), resolved.excludedFiles)
    }
}
