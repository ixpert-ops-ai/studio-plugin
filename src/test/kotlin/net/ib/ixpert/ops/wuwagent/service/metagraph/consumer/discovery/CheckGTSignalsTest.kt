package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class CheckGTSignalsTest {

    companion object {
        lateinit var graph: ProjectGraph
        lateinit var scorer: RelevanceScorer
        val mapper = jacksonObjectMapper()

        @JvmStatic
        @BeforeClass
        fun setup() {
            val jsonFile = File("C:/Workspace/graph/project-graph-i/project-graph.json")
            graph = mapper.readValue(jsonFile)
            scorer = RelevanceScorer(graph, fileLimit = 30)
        }
    }

    private val srTexts = mapOf(
        "TC5" to "클레임 (취소/반품/교환) 시 회수지시가 이뤄지면, 배송업체에서 회수 후 입고완료 처리가 되어야 하나, 시스템 오류나 수기 처리 지연 등으로 인해 클레임 상태가 '회수중'에 멈춰있는 경우, 일정 시간이 지나면 자동으로 '입고완료(반품완료)' 상태로 변경되도록 배치 프로그램 추가.",
        "TC8" to "마이페이지의 1:1 문의 내역이나 상품 Q&A 내역에서, 고객이 작성한 문의글 중 '주문'과 연동된 문의인 경우, 목록이나 상세 화면에서 해당 주문의 현재 배송 상태(결제완료, 배송중, 배송완료 등)가 함께 표시되도록 기능 개선."
    )

    private val gtClasses = mapOf(
        "TC5" to "PdSaveServiceImpl",
        "TC8" to "OrdrInqrListResponse"
    )

    @Test
    fun runSignalCheck() {
        println("=== GT Signal Check for TC5 and TC8 ===")
        
        for ((tc, srText) in srTexts) {
            val gtClass = gtClasses[tc]!!
            
            // Find path
            var gtPath: String? = null
            for ((path, node) in graph.files) {
                if (node.className.equals(gtClass, ignoreCase = true)) {
                    gtPath = path
                    break
                }
            }
            if (gtPath == null) continue

            // 1. Keyword extraction
            val method = scorer.javaClass.getDeclaredMethod("extractKeywords", String::class.java)
            method.isAccessible = true
            val keywords = method.invoke(scorer, srText) as RelevanceScorer.ExtractedKeywords

            println("\n[$tc] GT: $gtClass")
            println("Keywords Nouns: \${keywords.nouns}")
            println("Keywords Verbs: \${keywords.verbs}")
            println("Keywords Translated: \${keywords.translatedEnglish}")
            println("Keywords WeakTranslated: \${keywords.weakTranslatedEnglish}")
            println("Keywords DirectEnglish: \${keywords.directEnglish}")

            // 2. Manual score breakdown
            val fileNode = graph.files[gtPath]!!
            
            var nameMatchScore = 0
            var nameMatchedBy = ""
            if (keywords.directEnglish.any { eng -> fileNode.className.contains(eng, ignoreCase = true) }) {
                nameMatchScore = 30
                nameMatchedBy = "directEnglish"
            } else if (keywords.translatedEnglish.any { eng -> fileNode.className.contains(eng, ignoreCase = true) }) {
                nameMatchScore = 15
                nameMatchedBy = "translatedEnglish"
            } else if (keywords.weakTranslatedEnglish.any { eng -> fileNode.className.contains(eng, ignoreCase = true) }) {
                nameMatchScore = 7
                nameMatchedBy = "weakTranslatedEnglish"
            }
            
            var matchedMethods = 0
            fileNode.demMethods?.forEach { dm ->
                val methodStr = dm.methodName
                if (keywords.directEnglish.any { eng -> methodStr.contains(eng, ignoreCase = true) } ||
                    keywords.translatedEnglish.any { eng -> methodStr.contains(eng, ignoreCase = true) }) {
                    matchedMethods++
                }
            }
            val methodMatchScore = minOf(matchedMethods * 5, 20)

            var commentMatchScore = 0
            if (fileNode.koreanComments.any { comment ->
                keywords.nouns.any { noun -> comment.contains(noun) } ||
                keywords.verbs.any { verb -> comment.contains(verb) }
            }) {
                commentMatchScore = 10
            }

            println("- NameMatchScore: $nameMatchScore (Matched By: $nameMatchedBy)")
            println("- MethodMatchScore: $methodMatchScore (Methods matched: $matchedMethods)")
            println("- CommentMatchScore: $commentMatchScore")
            
            // Note: LayerAlignScore and TypeBonusScore don't depend on text match
            println("Total Signal Match Bonus: \${nameMatchScore + methodMatchScore + commentMatchScore}")
        }
    }
}
