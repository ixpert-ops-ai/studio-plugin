package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.io.FileReader

class ApcCondition3Test {

    @Test
    fun testCondition3WithRealSr() {
        val gson = Gson()
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        println("Loading graph from " + graphFile.absolutePath + "...")
        
        FileReader(graphFile).use { reader ->
            val projectGraph = gson.fromJson(reader, ProjectGraph::class.java)
            println("Graph loaded! Files: " + projectGraph.files.size)
            
            // The SR Text as it goes into AdaptiveFileDiscovery (initialRequirement + enhancedRequirements)
            val srText = """
                [요건]
                녹스 봇 화면을 등해 장애상환 대비
                - 조회항목으로 들어은 조회시작시간 ~ 증료시간내의 앱카드 및 모니모페이 결제 건수 출력
                - 공등내용 . 서비스 호출 되면 블루원권한체크(APC M COM 00001) -> 업무 서비스 수행 --> 블쿠원르그생성 효줄(APC M COM 00002) -> 서비스 결과 리턴
                   블루원권한체크 성공시에만 업무서비스 수행. 실패 용답코드일때 분기처리(카브모니터링 공등서비스효출가이드 문서 참고
                1. IGW_E_APC_00003(신규인터페이스) 호출
                -블쿠원 권한체크 (APC_M_COM_00001 / SCOMBS0015S01) --> 우즉 =사용자화면 권한체크 참조. 모니터링봇작업권한구분코드는 '01A'세팅
                -서비스구분코드 (A) : 앱카드 결제 건 수 조회, (M): 모니모페이 결제 건 수 조희
                SELECT
                (
                SELECT /*+ INDEX(ACAMTBAPCO05 ACAMTBAPCC05 _PK) */ COUNT(*)
                FROM ACAMTBAPC005 /*ACAM앱카드은라인결제내역 */
                WHERE TO_CHAR(SYS_FST_RG_TS, YYYMMDDHH24MI) BETWEEN :조회시작일시 AND :조회증료일시
                AND SUBSTR(SPACD_MB_ID,0,1) = :서비스구분코드
                AND MSG_C='0000'
                AND STLM_IZ_OC_DT = YYYMMDD /* 즈회일시 들어은 값에서 yyyymmdd */
                AND AK_RCVSV_ID =:서비스ID 
                ) SPACD_ONL_STLM_CT /* 은라인결제건수 */
                (SELECT /*+ INDEX(ACAMTBAPC007 ACAMTBAPCOO7_IX01) */ COUNT(*)
                FROM ACAMTBAPCO07 
                WHERE OTC_IS_RSP_DTM BETWEEN TO_Date(:조희시작일시, YYYYMMDDHH24MI) and TO_Date(:조희증료일시, YYYYMMDDHH24MI)
                AND
                SPACD_HV_CARD_ID LIKE :서비스구분코드||'%'
                AND MSG_C = "0000"
                ) SPACD_OTC_STLM_CT /* 으프라인 결제건수 */
                FROM DUAL
                
                IGW_E_APC_00003 인터페이스는 호출 시 APC_M_COM_00001을 통해 사용자의 모니터링봇작업권한구분('01A') 권한을 체크해야 하며, 권한 체크 성공 시에만 본 서비스 로직을 수행해야 한다. 권한 체크 실패 시에는 해당 에러 코드로 응답해야 한다.
                IGW_E_APC_00003 인터페이스는 서비스구분코드(A: 앱카드, M: 모니모페이), 조회시작일시, 조회종료일시를 입력값으로 받아 해당 조건에 맞는 결제 건수(온라인 및 오프라인)를 조회하여 반환해야 한다.
                IGW_E_APC_00003 인터페이스는 서비스구분코드가 누락되었거나 'A' 또는 'M'이 아닌 경우 응답코드 3024로 처리해야 한다.
                IGW_E_APC_00003 인터페이스는 조회시작일시 또는 조회종료일시가 누락되거나 올바른 포맷이 아닌 경우 각각 응답코드 3102, 3104로 처리해야 한다.
                IGW_E_APC_00003 인터페이스는 조회종료일시가 조회시작일시보다 이전인 경우 응답코드 3015로 처리해야 한다.
                IGW_E_APC_00003 인터페이스는 조회시작일시부터 조회종료일시까지의 기간이 1시간을 초과하는 경우 응답코드 3106으로 처리해야 한다.
                IGW_E_APC_00003 인터페이스는 모든 입력 검증이 정상적으로 통과하고 서비스 수행이 완료된 경우 응답코드 0000으로 정상 처리 결과를 반환해야 한다.
                IGW_E_APC_00003 인터페이스는 서비스 수행 중 예상치 못한 예외가 발생한 경우 응답코드 3000으로 처리 실패를 반환해야 한다.
            """.trimIndent()
            
            val seedPath = "src/main/java/sc/chn/aps/apc/co/co16/svc/APCCOMbotSvProcsSVC.java"
            
            val domainExtractor = DomainExtractor(projectGraph.files)
            val expander = GraphExpander(projectGraph, domainExtractor, DiscoveryConfig(maxHop = 5, domainFilterEnabled = false, minInfraThreshold = 999))
            
            println("=========================================================")
            println("ApcCondition3Test: Evaluating DEM Survival")
            println("=========================================================")
            
            val seedResult = SeedSelectionResult(
                seedClasses = listOf("APCCOMbotSvProcsSVC"),
                changeIntent = ChangeIntent.MODIFY,
                layerHint = listOf("SERVICE", "BIZ", "DATA_ACCESS"),
                frontendRelevant = false,
                reasoning = "Test Condition 3"
            )
            
            // Phase 1: Expansion
            val expandedFilesMap = expander.expand(seedResult, srText)
            
            // Phase 2: Scoring with minScore = 0 to capture all scores
            val scorer = RelevanceScorer(projectGraph, fileLimit = 1000, minScore = 0)
            val allScoredFiles = scorer.scoreAndFilter(srText, expandedFilesMap, seedResult)
            
            println("Expanded Files count: " + expandedFilesMap.size)
            println("All Scored Files count: " + allScoredFiles.size)
            
            println(String.format("%-25s | %-12s | %-20s | %-5s | %-6s", "ClassName", "Type", "Hop(Via)", "Score", "Result"))
            println("-----------------------------------------------------------------------------------------")
            
            for (scored in allScoredFiles) {
                if (true) {
                    val survival = if (scored.score >= 55) "SURVIVE" else "DIE"
                    val hopInfo = "${scored.hopDistance} (${scored.discoveryReason})"
                    println(String.format("%-25s | %-12s | %-20s | %-5d | %-6s", 
                        scored.className.take(25), 
                        scored.fileType.take(12), 
                        hopInfo, 
                        scored.score, 
                        survival))
                }
            }
        }
    }
}
