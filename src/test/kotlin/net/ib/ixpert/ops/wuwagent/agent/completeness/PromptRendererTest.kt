package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import org.junit.Assert.*
import org.junit.Test
import net.ib.ixpert.ops.wuwagent.agent.completeness.RulesetTestFixtures.jpaFixtureCtx
import net.ib.ixpert.ops.wuwagent.agent.completeness.RulesetTestFixtures.bootMybatisFixtureCtx

class PromptRendererTest {

    private fun mvcCtx() = ProjectGraphAdapter(SurveyGraphFixture.graph())
    private fun bootMybatisCtx() = bootMybatisFixtureCtx()
    private fun jpaCtx() = jpaFixtureCtx()
    
    private fun dummyReport() = CompletenessReport(
        frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
        degradedStrategies = emptyList(),
        roleFindings = emptyList(),
        companionFindings = emptyList(),
        unclassifiedFiles = emptyList()
    )

    @Test fun `MVC-MyBatis 프로파일에 DAO Impl과 XML 세트규칙이 포함된다`() {
        val out = PromptRenderer.renderVerifierProfile(
            FrameworkType.SPRING_MVC_MYBATIS, mvcCtx())
        assertTrue(out.contains("DAO 구현체"))
        assertTrue(out.contains("MyBatis XML"))
        assertTrue(out.contains("기존 Controller 에 메서드 추가를 우선"))
    }

    @Test fun `Boot-MyBatis 프로파일에는 DAO Impl 세트규칙이 없어야 한다`() {
        val out = PromptRenderer.renderVerifierProfile(
            FrameworkType.SPRING_BOOT_MYBATIS, bootMybatisCtx())
        assertFalse(out.contains("DAO 구현체"))   // ★ 프로파일에도 함정 방지
        assertTrue(out.contains("MyBatis XML"))
    }

    @Test fun `JPA 프로파일은 신규 Controller 생성을 허용한다고 표기한다`() {
        val out = PromptRenderer.renderVerifierProfile(
            FrameworkType.SPRING_BOOT_JPA, jpaCtx())
        assertTrue(out.contains("신규") && out.contains("생성이 허용"))
    }

    @Test fun `차단 피드백에 위반 메시지와 사유명시 안내가 포함된다`() {
        val block = GuardOutcome.Block(dummyReport(), listOf("[역할 누락/진입점] ..."))
        val fb = PromptRenderer.renderViolationFeedback(block)
        assertTrue(fb.contains("재판정"))
        assertTrue(fb.contains("사유"))
    }
}
