package net.ib.ixpert.ops.wuwagent.agent.clarify

import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 형제 유추(Brother Analogy) 합성 그래프 위상 불변식 5대 검증 테스트.
 * 
 * [도메인 중립성 원칙]
 * - 'survey', 'alimtalk', 'kakao', 'brandmessage', 'job', 'runner' 등 특정 도메인/역할 리터럴 0건 사용.
 * - 임의의 알파벳 명칭(Alpha, Beta, Gamma, Foo, Bar)으로 순수 그래프 위상 동작만 검증.
 */
class BrotherAnalogySyntheticTest {

    @Test
    fun `불변식1 및 3 - 공유 DTO 브릿지 식별 및 형제 클러스터 저신뢰 회수 검증`() {
        // Given: Seed 클러스터 (Package: pkg/foo) -> FooService, FooDao, AlphaBridgeDto
        //        Sibling 클러스터 (Package: pkg/beta) -> BetaWorker, BetaStore, BetaScheduler
        val files = mapOf(
            "src/pkg/foo/FooService.java" to createDummyFile("src/pkg/foo/FooService.java", "FooService", listOf("handleFoo")),
            "src/pkg/foo/FooDao.java" to createDummyFile("src/pkg/foo/FooDao.java", "FooDao", listOf("selectFoo")),
            "src/pkg/foo/AlphaBridgeDto.java" to createDummyFile("src/pkg/foo/AlphaBridgeDto.java", "AlphaBridgeDto", listOf("getBridgeId", "setBridgeId")),
            "src/pkg/beta/BetaWorker.java" to createDummyFile("src/pkg/beta/BetaWorker.java", "BetaWorker", listOf("processBeta")),
            "src/pkg/beta/BetaStore.java" to createDummyFile("src/pkg/beta/BetaStore.java", "BetaStore", listOf("saveBeta")),
            "src/pkg/beta/BetaScheduler.java" to createDummyFile("src/pkg/beta/BetaScheduler.java", "BetaScheduler", listOf("runBeta"))
        )

        val relationships = listOf(
            // FooService -> FooDao -> AlphaBridgeDto
            Relationship(source = "src/pkg/foo/FooService.java", target = "src/pkg/foo/FooDao.java", type = RelationshipType.INJECTS),
            Relationship(source = "src/pkg/foo/FooDao.java", target = "src/pkg/foo/AlphaBridgeDto.java", type = RelationshipType.USES_TYPE),
            
            // BetaWorker calls AlphaBridgeDto (External Consumer)
            Relationship(source = "src/pkg/beta/BetaWorker.java", target = "src/pkg/foo/AlphaBridgeDto.java", type = RelationshipType.CALLS),
            
            // Sibling internal cohesive graph: BetaScheduler -> BetaWorker -> BetaStore
            Relationship(source = "src/pkg/beta/BetaScheduler.java", target = "src/pkg/beta/BetaWorker.java", type = RelationshipType.INJECTS),
            Relationship(source = "src/pkg/beta/BetaWorker.java", target = "src/pkg/beta/BetaStore.java", type = RelationshipType.INJECTS)
        )

        val graph = ProjectGraph(
            version = "1.0",
            graphType = GraphType.SINGLE,
            framework = "spring",
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files,
            resourceNodes = emptyList(),
            relationships = relationships,
            projectRoot = "C:/dummy",
            generatedAt = "2026-09-10",
            statistics = GraphStatistics(0, 0, 0, 0, 0, 0, 0, 0)
        )

        val scanner = BrotherAnalogyScanner(graph, maxBridgeDegree = 15, maxExternalShared = 3)
        val seedNodes = listOfNotNull(files["src/pkg/foo/FooService.java"], files["src/pkg/foo/FooDao.java"])

        // When
        val items = scanner.scanSiblings(seedNodes)

        // Then
        // 1. Beta 클러스터 파일들이 회수되어야 함
        val recoveredPaths = items.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertTrue("BetaWorker가 회수되어야 함", recoveredPaths.contains("src/pkg/beta/BetaWorker.java"))
        assertTrue("BetaStore가 회수되어야 함", recoveredPaths.contains("src/pkg/beta/BetaStore.java"))
        assertTrue("BetaScheduler가 회수되어야 함", recoveredPaths.contains("src/pkg/beta/BetaScheduler.java"))

        // 2. 모든 회수 파일은 LOW_CONFIDENCE 여야 함 (정밀도 보존)
        val existingItems = items.filter { it.hint is LinkHint.ExistingRef }
        assertTrue("회수된 모든 기존 파일은 LOW_CONFIDENCE여야 함", existingItems.all { it.confidence == ConfidenceBucket.LOW_CONFIDENCE })
        assertTrue("출처 신호는 SIBLING_ANALOGY여야 함", existingItems.all { it.provenanceSignals.contains(ProvenanceSignal.SIBLING_ANALOGY) })
    }

    @Test
    fun `불변식2 - 허브 DTO 폭발 방지 필터링 및 임계값 주입 검증`() {
        // Given: in-degree가 5인 HubDto
        val files = mutableMapOf(
            "src/pkg/foo/FooService.java" to createDummyFile("src/pkg/foo/FooService.java", "FooService", listOf("handleFoo")),
            "src/pkg/foo/MediumHubDto.java" to createDummyFile("src/pkg/foo/MediumHubDto.java", "MediumHubDto", listOf("getVal")),
            "src/pkg/other/OtherWorker.java" to createDummyFile("src/pkg/other/OtherWorker.java", "OtherWorker", listOf("work"))
        )

        val relationships = mutableListOf(
            Relationship(source = "src/pkg/foo/FooService.java", target = "src/pkg/foo/MediumHubDto.java", type = RelationshipType.USES_TYPE),
            Relationship(source = "src/pkg/other/OtherWorker.java", target = "src/pkg/foo/MediumHubDto.java", type = RelationshipType.CALLS)
        )

        // 3개의 추가 호출 엣지 추가하여 in-degree = 5로 구성
        for (i in 1..3) {
            val dummyPath = "src/pkg/dummy/Dummy$i.java"
            files[dummyPath] = createDummyFile(dummyPath, "Dummy$i", listOf("run"))
            relationships.add(Relationship(source = dummyPath, target = "src/pkg/foo/MediumHubDto.java", type = RelationshipType.CALLS))
        }

        val graph = ProjectGraph(
            version = "1.0",
            graphType = GraphType.SINGLE,
            framework = "spring",
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files,
            resourceNodes = emptyList(),
            relationships = relationships,
            projectRoot = "C:/dummy",
            generatedAt = "2026-09-10",
            statistics = GraphStatistics(0, 0, 0, 0, 0, 0, 0, 0)
        )

        val seedNodes = listOfNotNull(files["src/pkg/foo/FooService.java"])

        // Case A: 임의의 엄격한 임계값(maxBridgeDegree = 3) 주입 시 -> in-degree=5 > 3 이므로 허브로 판정되어 0건 차단
        val strictScanner = BrotherAnalogyScanner(graph, maxBridgeDegree = 3, maxExternalShared = 2)
        val strictItems = strictScanner.scanSiblings(seedNodes)
        assertTrue("엄격한 임계값(3)에서는 inDegree=5인 DTO가 허브로 차단되어야 함", strictItems.isEmpty())

        // Case B: 완화된 임계값(maxBridgeDegree = 10) 주입 시 -> in-degree=5 <= 10 이므로 정상 통과하여 회수
        val relaxedScanner = BrotherAnalogyScanner(graph, maxBridgeDegree = 10, maxExternalShared = 5)
        val relaxedItems = relaxedScanner.scanSiblings(seedNodes)
        val relaxedPaths = relaxedItems.mapNotNull { (it.hint as? LinkHint.ExistingRef)?.filePath }
        assertTrue("완화된 임계값(10)에서는 OtherWorker가 정상 회수되어야 함", relaxedPaths.contains("src/pkg/other/OtherWorker.java"))
    }

    @Test
    fun `불변식4 - Category A 구조 슬롯 제안 및 2층 분리 검증`() {
        // Given: Sibling 클러스터 2종
        val files = mapOf(
            "src/pkg/foo/FooDao.java" to createDummyFile("src/pkg/foo/FooDao.java", "FooDao", listOf("selectFoo")),
            "src/pkg/foo/AlphaBridgeDto.java" to createDummyFile("src/pkg/foo/AlphaBridgeDto.java", "AlphaBridgeDto", listOf("getBridgeId")),
            "src/pkg/beta/BetaWorker.java" to createDummyFile("src/pkg/beta/BetaWorker.java", "BetaWorker", listOf("processBeta")),
            "src/pkg/beta/BetaStore.java" to createDummyFile("src/pkg/beta/BetaStore.java", "BetaStore", listOf("saveBeta"))
        )

        val relationships = listOf(
            Relationship(source = "src/pkg/foo/FooDao.java", target = "src/pkg/foo/AlphaBridgeDto.java", type = RelationshipType.USES_TYPE),
            Relationship(source = "src/pkg/beta/BetaWorker.java", target = "src/pkg/foo/AlphaBridgeDto.java", type = RelationshipType.CALLS),
            Relationship(source = "src/pkg/beta/BetaWorker.java", target = "src/pkg/beta/BetaStore.java", type = RelationshipType.INJECTS)
        )

        val graph = ProjectGraph(
            version = "1.0",
            graphType = GraphType.SINGLE,
            framework = "spring",
            frameworkType = FrameworkType.SPRING_MVC_MYBATIS,
            files = files,
            resourceNodes = emptyList(),
            relationships = relationships,
            projectRoot = "C:/dummy",
            generatedAt = "2026-09-10",
            statistics = GraphStatistics(0, 0, 0, 0, 0, 0, 0, 0)
        )

        val scanner = BrotherAnalogyScanner(graph, maxBridgeDegree = 15, maxExternalShared = 3)
        val seedNodes = listOfNotNull(files["src/pkg/foo/FooDao.java"])

        // When
        val items = scanner.scanSiblings(seedNodes)

        // Then: Category A 구조 슬롯 제안 아이템이 생성되어야 함
        val slotItem = items.find { it.hint is LinkHint.NewCreation }
        assertNotNull("Category A 신규 생성 슬롯 제안 아이템이 존재해야 함", slotItem)
        assertNotNull("StructuralSlotProposal이 포함되어야 함", slotItem!!.structuralSlotProposal)
        assertEquals("LOW_CONFIDENCE여야 함", ConfidenceBucket.LOW_CONFIDENCE, slotItem.confidence)
        assertTrue("템플릿 컴포넌트에 BetaWorker와 BetaStore가 포함되어야 함", 
            slotItem.structuralSlotProposal!!.templateComponents.contains("BetaWorker.java") &&
            slotItem.structuralSlotProposal!!.templateComponents.contains("BetaStore.java")
        )
    }

    private fun createDummyFile(path: String, className: String, methodNames: List<String>): FileNode {
        return FileNode(
            path = path,
            packageName = path.substringBeforeLast("/").replace("/", "."),
            className = className,
            fileType = SpringFileType.CONTROLLER,
            layer = ArchitectureLayer.SERVICE,
            methods = methodNames.map { MethodSignature(name = it, returnType = "void", parameters = emptyList()) }
        )
    }
}
