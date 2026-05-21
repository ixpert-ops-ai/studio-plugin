package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.psi.*
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import net.ib.ixpert.ops.wuwagent.setting.SettingsState

class AnyframeVerificationTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        // Ensure SettingsState is clean
        val settings = SettingsState.getInstance()
        settings.state.frameworkType = FrameworkType.ANYFRAME
    }

    override fun tearDown() {
        val settings = SettingsState.getInstance()
        settings.state.frameworkType = FrameworkType.SPRING_BOOT
        super.tearDown()
    }

    // ──────────────────────────────────────────────
    // 1. VO Chain Analyzer Test (Unit Level)
    // ──────────────────────────────────────────────
    fun testVoChainAnalyzer() {
        val nodes = mapOf(
            "mm02/biz/bvo/APCMMPsnzInfSVO.java" to FileNode(
                path = "mm02/biz/bvo/APCMMPsnzInfSVO.java",
                packageName = "mm02.biz.bvo",
                className = "APCMMPsnzInfSVO",
                fileType = SpringFileType.VO,
                layer = ArchitectureLayer.COMMON,
                anyframeRole = AnyframeRole.SVO
            ),
            "mm02/biz/bvo/APCMMPsnzInfBVO.java" to FileNode(
                path = "mm02/biz/bvo/APCMMPsnzInfBVO.java",
                packageName = "mm02.biz.bvo",
                className = "APCMMPsnzInfBVO",
                fileType = SpringFileType.VO,
                layer = ArchitectureLayer.COMMON,
                anyframeRole = AnyframeRole.BVO
            ),
            "zz/dem/dvo/APCMMPsnzInfDVO.java" to FileNode(
                path = "zz/dem/dvo/APCMMPsnzInfDVO.java",
                packageName = "zz.dem.dvo",
                className = "APCMMPsnzInfDVO",
                fileType = SpringFileType.VO,
                layer = ArchitectureLayer.COMMON,
                anyframeRole = AnyframeRole.DVO
            )
        )

        val analyzer = AnyframeVoChainAnalyzer()
        val relationships = analyzer.analyze(nodes)

        // There should be 2 relationships: SVO -> BVO, and BVO -> DVO
        assertEquals(2, relationships.size)

        val svoToBvo = relationships.firstOrNull { it.source == "mm02/biz/bvo/APCMMPsnzInfSVO.java" }
        assertNotNull(svoToBvo)
        assertEquals("mm02/biz/bvo/APCMMPsnzInfBVO.java", svoToBvo!!.target)
        assertEquals(RelationshipType.TRANSFORMS_VO, svoToBvo.type)
        assertEquals("APCMMPsnzInfSVO", svoToBvo.metadata?.get("sourceVo"))
        assertEquals("BVO", svoToBvo.metadata?.get("targetVo")?.toString()?.substringAfterLast("APCMMPsnzInf")) // APCMMPsnzInfBVO

        val bvoToDvo = relationships.firstOrNull { it.source == "mm02/biz/bvo/APCMMPsnzInfBVO.java" }
        assertNotNull(bvoToDvo)
        assertEquals("zz/dem/dvo/APCMMPsnzInfDVO.java", bvoToDvo!!.target)
        assertEquals(RelationshipType.TRANSFORMS_VO, bvoToDvo.type)
        assertEquals("INBOUND", bvoToDvo.metadata?.get("direction"))
    }

    // ──────────────────────────────────────────────
    // 2. SVC Service ID Analyzer Test (PSI Level)
    // ──────────────────────────────────────────────
    fun testServiceIdAnalyzer() {
        val svcCode = """
            package mm02.svc;
            
            import net.sf.anyframe.core.properties.LocalName;
            import net.ib.ixpert.ops.wuwagent.ServiceIdMapping;
            
            @LocalName("개인화정보SVC")
            public interface APCMMPsnzInfSVC {
                @LocalName("개인화정보조회")
                @ServiceIdMapping("SAPCMM0204S01")
                public APCMMPsnzInfSVO selPsnzInf(APCMMPsnzInfSVO isvo);
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("APCMMPsnzInfSVC.java", svcCode) as PsiJavaFile
        val psiClass = psiFile.classes.first()

        val analyzer = AnyframeServiceIdAnalyzer()
        val endpoints = analyzer.analyze(psiClass)

        assertEquals(1, endpoints.size)
        val endpoint = endpoints[0]
        assertEquals("SAPCMM0204S01", endpoint.serviceId)
        assertEquals("selPsnzInf", endpoint.methodName)
        assertEquals("개인화정보조회", endpoint.localName)
        assertEquals("APCMMPsnzInfSVO", endpoint.inputSvo)
        assertEquals("APCMMPsnzInfSVO", endpoint.outputSvo)
    }

    // ──────────────────────────────────────────────
    // 3. DEM / SQL Analyzer Test (PSI Level)
    // ──────────────────────────────────────────────
    fun testDemAnalyzer() {
        val demCode = """
            package zz.dem;
            
            import net.sf.anyframe.core.properties.LocalName;
            import java.util.List;
            
            @LocalName("삼성페이앱카드등록용도카드인증")
            public class ACAMTBAPC001DEM {
                public List<ACAMTBAPC001DVO> selectPsnzInf(ACAMTBAPC001DVO dvo) {
                    // SQL_ID : SELECT_PSNZ_INF
                    StringBuilder query = new StringBuilder();
                    query.append("SELECT USER_ID, USER_NAME ");
                    query.append("FROM ACAMTBAPC001 ");
                    query.append("WHERE USER_ID = ?");
                    return null;
                }
            }
        """.trimIndent()

        val psiFile = myFixture.configureByText("ACAMTBAPC001DEM.java", demCode) as PsiJavaFile
        val psiClass = psiFile.classes.first()

        val analyzer = AnyframeDemAnalyzer()
        val methods = analyzer.analyze(psiClass)

        assertEquals(1, methods.size)
        val method = methods[0]
        assertEquals("selectPsnzInf", method.methodName)
        assertEquals("SELECT_PSNZ_INF", method.sqlId)
        assertEquals(SqlOpType.SELECT, method.operationType)
        assertEquals(1, method.tables.size)
        assertEquals("ACAMTBAPC001", method.tables[0])
        assertEquals("ACAMTBAPC001DVO", method.inputDvoClass)
        assertEquals("ACAMTBAPC001DVO", method.returnDvoClass)
    }

    // ──────────────────────────────────────────────
    // 4. Dependency & BIZ Call Analyzer Test (PSI Level)
    // ──────────────────────────────────────────────
    fun testDependencyAndBizCallAnalyzers() {
        val demCode = """
            package zz.dem;
            
            public class ACAMTBAPC001DEM {
                private static ACAMTBAPC001DEM instance = new ACAMTBAPC001DEM();
                public static ACAMTBAPC001DEM getInstance() {
                    return instance;
                }
                public void selectPsnzInf(Object param) {
                }
            }
        """.trimIndent()

        val bizCode = """
            package mm02.biz;
            
            import zz.dem.ACAMTBAPC001DEM;
            
            public class APCMMPsnzInfBIZ {
                ACAMTBAPC001DEM aCAMTBAPC001DEM = ACAMTBAPC001DEM.getInstance();
                
                public void process() {
                    /*-FD-CALL-START-(01)-*/
                    aCAMTBAPC001DEM.selectPsnzInf(null);
                    /*-FD-CALL-END-(01)-*/
                }
            }
        """.trimIndent()

        val svcImplCode = """
            package mm02.svc.impl;
            
            import mm02.biz.APCMMPsnzInfBIZ;
            
            public class APCMMPsnzInfSVCImpl {
                public void doService() {
                    APCMMPsnzInfBIZ biz = new APCMMPsnzInfBIZ();
                    biz.process();
                }
            }
        """.trimIndent()

        val demPsiFile = myFixture.addFileToProject("zz/dem/ACAMTBAPC001DEM.java", demCode) as PsiJavaFile
        val bizPsiFile = myFixture.addFileToProject("mm02/biz/APCMMPsnzInfBIZ.java", bizCode) as PsiJavaFile
        val svcImplPsiFile = myFixture.addFileToProject("mm02/svc/impl/APCMMPsnzInfSVCImpl.java", svcImplCode) as PsiJavaFile

        val projectBasePath = bizPsiFile.project.basePath ?: ""

        // A. Test Dependency Analyzer (getInstance() BIZ -> DEM and new BIZ() SVCImpl -> BIZ)
        val depAnalyzer = AnyframeDependencyAnalyzer()
        
        val bizRels = depAnalyzer.analyze(bizPsiFile.classes.first(), projectBasePath)
        // BIZ should have SINGLETON dependency on DEM
        assertTrue(bizRels.any { it.type == RelationshipType.CALLS_DEM_METHOD && it.metadata?.get("bindingType") == "SINGLETON" })

        val svcRels = depAnalyzer.analyze(svcImplPsiFile.classes.first(), projectBasePath)
        // SVCImpl should have NEW_INSTANCE dependency on BIZ
        assertTrue(svcRels.any { it.type == RelationshipType.CALLS_BIZ && it.metadata?.get("bindingType") == "NEW_INSTANCE" })

        // B. Test BIZ Call Analyzer (FD-CALL comments and method calls)
        val bizCallAnalyzer = AnyframeBizCallAnalyzer()
        val calls = bizCallAnalyzer.analyze(bizPsiFile.classes.first(), projectBasePath)

        // Check if the call to selectPsnzInf is captured with the FD-CALL comment ID
        assertEquals(1, calls.size)
        val call = calls[0]
        assertEquals(RelationshipType.CALLS_DEM_METHOD, call.type)
        assertEquals("selectPsnzInf()", call.detail)
        assertEquals("01", call.metadata?.get("fdCallId"))
    }

    // ──────────────────────────────────────────────
    // 5. Relevance Filter and Formatter Integration
    // ──────────────────────────────────────────────
    fun testRelevanceFilterAndFormatterIntegration() {
        // Construct a mock Anyframe graph
        val files = mutableMapOf<String, FileNode>()
        
        files["mm02/svc/APCMMPsnzInfSVC.java"] = FileNode(
            path = "mm02/svc/APCMMPsnzInfSVC.java",
            packageName = "mm02.svc",
            className = "APCMMPsnzInfSVC",
            fileType = SpringFileType.INTERFACE,
            layer = ArchitectureLayer.PRESENTATION,
            anyframeRole = AnyframeRole.SVC,
            serviceEndpoints = listOf(
                ServiceEndpoint(
                    serviceId = "SAPCMM0204S01",
                    methodName = "selPsnzInf",
                    localName = "개인화정보조회",
                    inputSvo = "APCMMPsnzInfSVO",
                    outputSvo = "APCMMPsnzInfSVO"
                )
            ),
            localName = "개인화정보SVC",
            dependedBy = mutableListOf("mm02/svc/impl/APCMMPsnzInfSVCImpl.java")
        )

        files["mm02/svc/impl/APCMMPsnzInfSVCImpl.java"] = FileNode(
            path = "mm02/svc/impl/APCMMPsnzInfSVCImpl.java",
            packageName = "mm02.svc.impl",
            className = "APCMMPsnzInfSVCImpl",
            fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS,
            anyframeRole = AnyframeRole.SVC_IMPL,
            dependsOn = mutableListOf("mm02/svc/APCMMPsnzInfSVC.java", "mm02/biz/APCMMPsnzInfBIZ.java"),
            implementedInterfaces = listOf("mm02.svc.APCMMPsnzInfSVC")
        )

        files["mm02/biz/APCMMPsnzInfBIZ.java"] = FileNode(
            path = "mm02/biz/APCMMPsnzInfBIZ.java",
            packageName = "mm02.biz",
            className = "APCMMPsnzInfBIZ",
            fileType = SpringFileType.COMPONENT,
            layer = ArchitectureLayer.BUSINESS,
            anyframeRole = AnyframeRole.BIZ,
            dependsOn = mutableListOf("zz/dem/ACAMTBAPC001DEM.java"),
            dependedBy = mutableListOf("mm02/svc/impl/APCMMPsnzInfSVCImpl.java")
        )

        files["zz/dem/ACAMTBAPC001DEM.java"] = FileNode(
            path = "zz/dem/ACAMTBAPC001DEM.java",
            packageName = "zz.dem",
            className = "ACAMTBAPC001DEM",
            fileType = SpringFileType.REPOSITORY,
            layer = ArchitectureLayer.PERSISTENCE,
            anyframeRole = AnyframeRole.DEM,
            demMethods = listOf(
                DemMethodInfo(
                    methodName = "selectPsnzInf",
                    sqlId = "SELECT_PSNZ_INF",
                    inputDvoClass = "ACAMTBAPC001DVO",
                    returnDvoClass = "ACAMTBAPC001DVO",
                    tables = listOf("ACAMTBAPC001"),
                    operationType = SqlOpType.SELECT,
                    localName = "개인화정보조회"
                )
            ),
            localName = "개인화정보조회",
            dependedBy = mutableListOf("mm02/biz/APCMMPsnzInfBIZ.java")
        )

        val relationships = listOf(
            Relationship("mm02/svc/impl/APCMMPsnzInfSVCImpl.java", "mm02/svc/APCMMPsnzInfSVC.java", RelationshipType.IMPLEMENTS),
            Relationship("mm02/svc/impl/APCMMPsnzInfSVCImpl.java", "mm02/biz/APCMMPsnzInfBIZ.java", RelationshipType.CALLS_BIZ, metadata = mapOf("bindingType" to "NEW_INSTANCE")),
            Relationship("mm02/biz/APCMMPsnzInfBIZ.java", "zz/dem/ACAMTBAPC001DEM.java", RelationshipType.CALLS_DEM_METHOD, detail = "selectPsnzInf", metadata = mapOf("bindingType" to "SINGLETON", "fdCallId" to "01"))
        )

        val projectGraph = ProjectGraph(
            generatedAt = "2026-05-21",
            projectRoot = "/project",
            framework = "anyframe",
            frameworkType = FrameworkType.ANYFRAME,
            files = files,
            relationships = relationships,
            statistics = GraphStatistics(totalFiles = files.size)
        )

        // Mock LLM Client (Not used for Stage 1 LocalName matching, but passed as argument)
        val mockClient = object : LLMClient {
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
                return OllamaChatResponse("model", "2026-05-21", OllamaMessage("assistant", "KEYWORDS: psnz, inf"), true)
            }
            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = emptyList()
        }

        // Run RelevanceFilter: requirement contains "개인화정보조회" which should match LocalName on SVC endpoints
        val filterResult = RelevanceFilter.filter(
            requirement = "개인화정보조회 화면의 기능 오류 수정",
            graph = projectGraph,
            client = mockClient,
            project = project
        )

        // Assert all chain classes are preserved through BFS expansion from the entry point
        val filteredFiles = filterResult.filteredGraph.files
        assertTrue(filteredFiles.containsKey("mm02/svc/APCMMPsnzInfSVC.java"))
        assertTrue(filteredFiles.containsKey("mm02/svc/impl/APCMMPsnzInfSVCImpl.java"))
        assertTrue(filteredFiles.containsKey("mm02/biz/APCMMPsnzInfBIZ.java"))
        assertTrue(filteredFiles.containsKey("zz/dem/ACAMTBAPC001DEM.java"))

        // Run RepoMapFormatter and assert custom output contents
        val repoMap = RepoMapFormatter.format(filterResult.filteredGraph)
        println("FORMATTED ANYFRAME REPO MAP:\n$repoMap")

        assertTrue(repoMap.contains("role: SVC"))
        assertTrue(repoMap.contains("role: DEM"))
        assertTrue(repoMap.contains("ServiceId: SAPCMM0204S01"))
        assertTrue(repoMap.contains("- selectPsnzInf [SELECT] (tables: ACAMTBAPC001)"))
        assertTrue(repoMap.contains("→ calls BIZ: APCMMPsnzInfBIZ"))
        assertTrue(repoMap.contains("→ calls DEM: ACAMTBAPC001DEM.selectPsnzInf (FD-CALL: 01)"))
    }
}
