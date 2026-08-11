package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class Phase3ReRankingTest {

    companion object {
        lateinit var graph: ProjectGraph
        lateinit var scorer: RelevanceScorer
        val mapper = jacksonObjectMapper()
        lateinit var expandedPools: Map<String, Map<String, ExpansionStepDTO>>

        data class ExpansionStepDTO(val hop: Int, val from: String)

        @JvmStatic
        @BeforeClass
        fun setup() {
            val jsonFile = File("C:/Workspace/graph/project-graph-i/project-graph.json")
            graph = mapper.readValue(jsonFile)
            scorer = RelevanceScorer(graph, fileLimit = 30) // Request Top 30

            val poolsFile = File("C:/Users/dffrp/.gemini/antigravity/brain/dead1e82-1168-4141-b615-c0196d0a2637/scratch/expanded-pools.json")
            expandedPools = mapper.readValue(poolsFile)
        }
    }

    private val srTexts = mapOf(
        "TC1" to "어드민 주문상세에서 수기결제 시 카드사 환불 실패 시, 보상금으로 환불(수기결제/보상금환불/결제실패/망취소/망취소이력/승인취소)",
        "TC2" to "상품상세/장바구니/주문서/마이페이지 내 쿠폰 목록에 다운로드/적용 가능한 일본 직매입 배송비 쿠폰 추가.",
        "TC3" to "어드민 기획전/특가매장 관리 화면에서 하단에 전시되는 배너 이미지 등록 기능 추가 (기존 배너 관리 로직 활용)",
        "TC5" to "클레임 (취소/반품/교환) 시 회수지시가 이뤄지면, 배송업체에서 회수 후 입고완료 처리가 되어야 하나, 시스템 오류나 수기 처리 지연 등으로 인해 클레임 상태가 '회수중'에 멈춰있는 경우, 일정 시간이 지나면 자동으로 '입고완료(반품완료)' 상태로 변경되도록 배치 프로그램 추가.",
        "TC6" to "어드민 전시 관리의 GNB/LNB 메뉴 관리에서, 각 메뉴에 매핑된 전시 카테고리(전시코너)가 현재 활성 상태인지 비활성 상태인지를 목록에서 바로 확인할 수 있도록 '상태' 컬럼을 추가.",
        "TC7" to "주문 취소 시, 이미 출고지시가 내려져 배송이 시작된 상품(출고완료/배송중)에 대해 취소 요청이 들어올 경우, 기존에는 취소가 불가능했지만, 앞으로는 '반품' 접수로 자동 전환되어 처리되도록 취소 로직 수정.",
        "TC8" to "마이페이지의 1:1 문의 내역이나 상품 Q&A 내역에서, 고객이 작성한 문의글 중 '주문'과 연동된 문의인 경우, 목록이나 상세 화면에서 해당 주문의 현재 배송 상태(결제완료, 배송중, 배송완료 등)가 함께 표시되도록 기능 개선."
    )

    private val gtClasses = mapOf(
        "TC1" to "OrderProcessBaseRequest",
        "TC2" to "CponInfoRequest",
        "TC3" to "BnnrMngtServiceImpl",
        "TC5" to "PdSaveServiceImpl",
        "TC6" to "GnbMenuMstServiceImpl",
        "TC7" to "ClaimMgmtServiceImpl",
        "TC8" to "OrdrInqrListResponse"
    )

    @Test
    fun runPhase3Test() {
                val outFile = File("C:/Users/dffrp/.gemini/antigravity/brain/dead1e82-1168-4141-b615-c0196d0a2637/scratch/phase3-output.txt")
        outFile.writeText("=== Phase 3 RelevanceScorer Re-ranking Test ===\n")
        
        for ((tc, srText) in srTexts) {
            val gtClass = gtClasses[tc]!!
            val poolDTO = expandedPools[tc] ?: emptyMap()
            
            // Map DTO to ExpansionStep
            val expandedFiles = mutableMapOf<String, ExpansionStep>()
            var gtPathInPool: String? = null
            
            for ((path, dto) in poolDTO) {
                expandedFiles[path] = ExpansionStep(
                    via = "USES_TYPE_EXPANSION",
                    from = dto.from,
                    hop = dto.hop
                )
                
                val nodeClass = graph.files[path]?.className ?: graph.resourceNodes.find { it.path == path }?.path?.substringAfterLast("/")
                if (nodeClass != null && nodeClass.equals(gtClass, ignoreCase = true)) {
                    gtPathInPool = path
                }
            }

            outFile.appendText("\n[$tc] GT: $gtClass" + "\n")
            outFile.appendText("1) SR Text: $srText" + "\n")
            
            // Log Extracted Keywords using Reflection (since it's private)
            val method = scorer.javaClass.getDeclaredMethod("extractKeywords", String::class.java)
            method.isAccessible = true
            val keywords = method.invoke(scorer, srText)
            outFile.appendText("2) Extracted Keywords: $keywords" + "\n")
            
            outFile.appendText("3) Expanded Pool Size (Recall): ${expandedFiles.size}" + "\n")
            outFile.appendText("   GT in Expanded Pool? ${gtPathInPool != null}" + "\n")
            
            if (gtPathInPool == null) {
                outFile.appendText("   -> Skipping scoring because GT is missing in pool." + "\n")
                continue
            }
            
            // Empty Layer Hint to prevent LLM contamination
            val seedResult = SeedSelectionResult(
                seedClasses = emptyList(),
                changeIntent = ChangeIntent.MODIFY,
                layerHint = emptyList(),
                frontendRelevant = false,
                reasoning = "Test Harness"
            )

            // Run Scorer
            val scoredFiles = scorer.scoreAndFilter(srText, expandedFiles, seedResult)
            
            var gtRank = -1
            var gtScore = -1
            
            for (i in scoredFiles.indices) {
                if (scoredFiles[i].path == gtPathInPool) {
                    gtRank = i + 1
                    gtScore = scoredFiles[i].score
                    break
                }
            }
            
            outFile.appendText("4) Final GT Rank in Top 30: ${if (gtRank != -1) gtRank else "OUT_OF_TOP30"} (Score: $gtScore)" + "\n")
            
            if (gtRank > 1 || gtRank == -1) {
                val limit = if (gtRank == -1) 30 else Math.min(gtRank, 10)
                outFile.appendText("5) Nodes ranked above GT (Top $limit):" + "\n")
                for (i in 0 until limit) {
                    val sf = scoredFiles.getOrNull(i) ?: break
                    outFile.appendText("   ${i + 1}. ${sf.className} (Score: ${sf.score}, Hop: ${sf.hopDistance}, Type: ${sf.fileType})" + "\n")
                }
            } else if (gtRank == 1) {
                outFile.appendText("5) GT is Rank 1!" + "\n")
            }
        }
    }
}
