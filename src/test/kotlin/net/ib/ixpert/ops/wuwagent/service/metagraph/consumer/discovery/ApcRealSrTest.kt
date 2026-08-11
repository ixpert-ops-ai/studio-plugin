package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File
import java.io.FileReader

class ApcRealSrTest {

    @Test
    fun testRealSrPipeline() {
        val gson = Gson()
        val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
        println("Loading graph from " + graphFile.absolutePath + "...")
        
        FileReader(graphFile).use { reader ->
            val projectGraph = gson.fromJson(reader, ProjectGraph::class.java)
            println("Graph loaded! Files: " + projectGraph.files.size)
            
            val srText = """
                SAPCMM1601S01 온라인결제 결제정보 요청 처리시
                APCMMOpnApiO011BIZ().askStlminf(isvo) 오픈앱카드 카드사구분코드 체크해서 롯데카드(LTC)일 경우에만 card_member_id 세팅해서 호출 (롯데 이외는 기존대로 empty)
                ACMBTBapc031 조회 모니모페이회원ID, OACD_CDCO_DV_C='LTC', OACD_RG_STS_YN ='Y' 조건으로 등록된 롯데카드 정보 1건 조회 하여 OACD_CDCO_MB_ID 확인
                OACD_CDCO_MB_ID 암호화 처리 하여 세팅 askUtil.getDekEncValue 이용
            """.trimIndent()
            
            // 1. Keyword-based Seed Selection Simulation
            val seeds = projectGraph.files.values.filter { srText.contains(it.className, ignoreCase = true) }
            println("=========================================================")
            println("Phase 1: Seed Selection")
            println("=========================================================")
            seeds.forEach { println("Selected Seed: ${it.className} (${it.fileType})") }
            
            val seedResult = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.SeedSelectionResult(
                seedClasses = seeds.map { it.className },
                changeIntent = net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.ChangeIntent.MODIFY,
                layerHint = listOf("SERVICE", "BIZ", "DATA_ACCESS"),
                frontendRelevant = false,
                reasoning = "Test Real SR"
            )
            
            val domainExtractor = DomainExtractor(projectGraph.files)
            
            println("\n=========================================================")
            println("Phase 1.5: Domain Extraction")
            println("=========================================================")
            println("BIZ Domain: " + domainExtractor.getDomain("src/main/java/sc/chn/aps/apc/mm/mm16/biz/APCMMOpnApiO011BIZ.java"))
            println("DEM Domain: " + domainExtractor.getDomain("src/main/java/sc/chn/aps/apc/zz/dem/ACMBTBAPC031DEM.java"))

            val expander = GraphExpander(projectGraph, domainExtractor, DiscoveryConfig(maxHop = 5))
            
            // 2. Graph Expansion
            val expandedNodes = expander.expand(seedResult, srText)
            
            // Find Hop distance and type for target files
            val targetBiz = "APCMMOpnApiO011BIZ"
            val targetData = "ACMBTBapc031" // Might be lowercased or capitalized in graph?
            
            println("\n=========================================================")
            println("Phase 2: Expansion Results for GT Files")
            println("=========================================================")
            println("Total expandedNodes size: ${expandedNodes.size}")
            expandedNodes.forEach { (path, step) ->
                val fileNode = projectGraph.files[path]
                if (fileNode != null && (fileNode.className.contains(targetBiz, ignoreCase = true) || fileNode.className.contains(targetData, ignoreCase = true))) {
                    println("Found GT File: ${fileNode.className} | Type: ${fileNode.fileType} | Hop: ${step.hop} | Via: ${step.via}")
                }
                if (path.contains("ACMBTBAPC031DEM", ignoreCase = true)) {
                    println("EXACT PATH MATCH in expandedNodes: $path | Hop: ${step.hop} | Via: ${step.via}")
                }
            }
            
            println("\n=========================================================")
            println("DEBUGGING: Why wasn't ACMBTBAPC031DEM expanded?")
            val bizNode = projectGraph.files.values.find { it.className == "APCMMOpnApiO011BIZ" }!!
            val demNode = projectGraph.files.values.find { it.className == "ACMBTBAPC031DEM" }!!
            println("BIZ dependsOn DEM? ${bizNode.dependsOn.contains(demNode.path)}")
            println("DEM fileType is DATA_ACCESS? ${demNode.fileType.name == "DATA_ACCESS"}")
            
            // Check isCommonInfrastructure
            val isInfra = demNode.dependedBy.size >= 30
            println("isCommonInfrastructure? $isInfra (size: ${demNode.dependedBy.size})")
            
            // Check isDomainAllowed manually
            val targetDomain = domainExtractor.getDomain(demNode.path)
            val sourceDomain = domainExtractor.getDomain(bizNode.path)
            println("targetDomain: $targetDomain, sourceDomain: $sourceDomain")
            if (targetDomain == null) {
                val isValuable = when (demNode.fileType.name) {
                    "ABSTRACT_CLASS" -> true
                    "INTERFACE" -> true
                    "DTO" -> true
                    "DATA_ACCESS" -> true
                    else -> false
                }
                println("targetDomain is null, isValuableCommonFile? $isValuable")
            }
            
            // 3. Scoring (After Option C - Current Code)
            val scorer = RelevanceScorer(projectGraph, 1000, 55)
            
            println("\n=========================================================")
            println("Phase 3: Scoring Results (AFTER Option C)")
            println("=========================================================")
            val scoredResultAfter = scorer.scoreAndFilter(srText, expandedNodes, seedResult)
            
            scoredResultAfter.forEach { scored ->
                if (scored.className.contains(targetBiz, ignoreCase = true) || scored.className.contains(targetData, ignoreCase = true)) {
                    println("Scored GT File: ${scored.className} | Score: ${scored.score} | Result: SURVIVE")
                }
            }
            
            // Check for noise (Precision check)
            val noiseCount = scoredResultAfter.count { it.discoveryReason == "BIZ_TO_DATA_ACCESS" && !it.className.contains(targetData, ignoreCase = true) }
            println("Number of non-GT DATA_ACCESS nodes that survived via BIZ_TO_DATA_ACCESS: $noiseCount")
            
            // Simulate BEFORE Option C by overriding the score calculation logic locally
            println("\n=========================================================")
            println("Phase 3: Scoring Results (BEFORE Option C Simulation)")
            println("=========================================================")
            expandedNodes.forEach { (path, step) ->
                val fileNode = projectGraph.files[path]
                if (fileNode != null && (fileNode.className.contains(targetBiz, ignoreCase = true) || fileNode.className.contains(targetData, ignoreCase = true))) {
                    val hopScore = when (step.hop) {
                        0 -> 100
                        1 -> 70
                        2 -> 40
                        else -> 0
                    }
                    var nameMatchScore = 0
                    if (srText.contains(fileNode.className, ignoreCase = true)) nameMatchScore += 10
                    val typeBonus = 2 // For DATA_ACCESS/BIZ
                    val layerAlignScore = 15 // Assuming Wide hint
                    val totalBefore = hopScore + nameMatchScore + layerAlignScore + typeBonus
                    
                    val survive = if (totalBefore >= 55) "SURVIVE" else "DIE"
                    println("Simulated Before Score for ${fileNode.className}: $totalBefore | Result: $survive")
                }
            }
        }
    }
}
