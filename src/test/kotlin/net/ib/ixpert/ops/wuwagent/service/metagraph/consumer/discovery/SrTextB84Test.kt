package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** B-84 2: 탐색기에 가는 SR 문장에 끝 줄바꿈·공백이 남지 않는다. */
class SrTextB84Test {
    @Test
    fun t1_빈_보조_요구사항이면_줄바꿈이_붙지_않는다() {
        assertEquals("설문 응답 이메일 추가", AdaptiveFileDiscovery.buildSrText("설문 응답 이메일 추가", ""))
        assertEquals("설문 응답 이메일 추가", AdaptiveFileDiscovery.buildSrText("설문 응답 이메일 추가", "  \n "))
    }

    @Test
    fun t2_양쪽_공백은_다듬고_둘_다_있으면_한_줄바꿈으로_잇는다() {
        assertEquals("가나다\n라마바", AdaptiveFileDiscovery.buildSrText("  가나다 \n", "\n라마바  "))
        assertEquals("라마바", AdaptiveFileDiscovery.buildSrText("", "라마바"))
        assertEquals("", AdaptiveFileDiscovery.buildSrText("", ""))
    }

    @Test
    fun t3_탐색기_사용자_메시지에_연속_빈_줄이_생기지_않는다() {
        val sr = AdaptiveFileDiscovery.buildSrText("SR 문장", "")
        // AgenticSeedSelector의 메시지 틀: "요구사항(SR):\n$srText\n\n위 요구사항을 ..."
        val message = "요구사항(SR):\n$sr\n\n위 요구사항을 분석하여"
        assertFalse(message.contains("\n\n\n"))
        val withFacts = "요구사항(SR):\n$sr\n\n[사실]\n내용\n\n위 요구사항을 분석하여"
        assertFalse(withFacts.contains("\n\n\n"))
    }
}
