package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.google.gson.GsonBuilder
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.OllamaChatResponse
import net.ib.ixpert.ops.wuwagent.model.OllamaMessage
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class MultiModuleGraphTest {

    private lateinit var tempDir: File
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("multi-module-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createMockProject(basePath: String, graphLoaderProvider: () -> GraphLoader?): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBasePath" -> basePath
                "getService" -> {
                    val serviceClass = args[0] as Class<*>
                    if (serviceClass == GraphLoader::class.java) {
                        graphLoaderProvider()
                    } else {
                        null
                    }
                }
                "toString" -> "MockProject(basePath=$basePath)"
                else -> null
            }
        } as Project
    }

    private fun createDummyFileNode(path: String, className: String): FileNode {
        return FileNode(
            path = path,
            packageName = "com.example",
            className = className,
            fileType = SpringFileType.SERVICE,
            layer = ArchitectureLayer.BUSINESS,
            isInterface = false,
            isAbstract = false,
            dependsOn = mutableListOf(),
            dependedBy = mutableListOf(),
            apiEndpoints = emptyList(),
            beanDefinitions = emptyList(),
            entityRelations = emptyList(),
            injections = emptyList(),
            implementedInterfaces = emptyList(),
            annotations = emptyList()
        )
    }

    @Test
    fun testPathNormalizationAndMerge() {
        val basePath = tempDir.absolutePath
        
        // 1. Level 1 Metadata 작성
        val level1Meta = File(tempDir, ".meta/project-graph.json")
        level1Meta.parentFile.mkdirs()
        
        val moduleA = ModuleInfo(
            name = "module-a",
            rootPath = "core/module-a",
            metadataPath = ".meta/module-a-graph.json",
            lastIndexedAt = 1000L,
            fileCount = 1,
            publicApis = listOf("/api/a")
        )
        val moduleB = ModuleInfo(
            name = "module-b",
            rootPath = "core/module-b",
            metadataPath = ".meta/module-b-graph.json",
            lastIndexedAt = 2000L,
            fileCount = 1,
            publicApis = listOf("/api/b")
        )

        val level1Graph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = basePath,
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_1,
            modules = listOf(moduleA, moduleB),
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 2)
        )
        level1Meta.writeText(gson.toJson(level1Graph))

        // 2. Level 2 (Module A) 작성
        val moduleAMeta = File(tempDir, ".meta/module-a-graph.json")
        val nodeA = createDummyFileNode("src/main/kotlin/Foo.kt", "Foo").copy(
            dependsOn = mutableListOf("src/main/kotlin/Common.kt")
        )
        val moduleAGraph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = "$basePath/core/module-a",
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_2,
            files = mapOf("src/main/kotlin/Foo.kt" to nodeA),
            relationships = listOf(Relationship("src/main/kotlin/Foo.kt", "src/main/kotlin/Common.kt", RelationshipType.CALLS)),
            statistics = GraphStatistics(totalFiles = 1)
        )
        moduleAMeta.writeText(gson.toJson(moduleAGraph))

        // 3. Level 2 (Module B) 작성
        val moduleBMeta = File(tempDir, ".meta/module-b-graph.json")
        val nodeB = createDummyFileNode("src/main/kotlin/Bar.kt", "Bar")
        val moduleBGraph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = "$basePath/core/module-b",
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_2,
            files = mapOf("src/main/kotlin/Bar.kt" to nodeB),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 1)
        )
        moduleBMeta.writeText(gson.toJson(moduleBGraph))

        // Loader 실행
        var loaderRef: GraphLoader? = null
        val mockProject = createMockProject(basePath) { loaderRef }
        val loader = GraphLoader(mockProject)
        loaderRef = loader

        val merged = loader.loadGraph()
        assertNotNull(merged)
        assertEquals(GraphType.MULTI_LEVEL_1, merged!!.graphType)

        // 경로 정규화 검증
        assertTrue(merged.files.containsKey("core/module-a/src/main/kotlin/Foo.kt"))
        assertTrue(merged.files.containsKey("core/module-b/src/main/kotlin/Bar.kt"))

        val mergedNodeA = merged.files["core/module-a/src/main/kotlin/Foo.kt"]!!
        assertEquals("core/module-a/src/main/kotlin/Foo.kt", mergedNodeA.path)

        // 관계 정규화 검증
        assertEquals(1, merged.relationships.size)
        val rel = merged.relationships[0]
        assertEquals("core/module-a/src/main/kotlin/Foo.kt", rel.source)
        assertEquals("core/module-a/src/main/kotlin/Common.kt", rel.target)
    }

    @Test
    fun testGracefulDegradation() {
        val basePath = tempDir.absolutePath
        
        // 1. Level 1 Metadata 작성
        val level1Meta = File(tempDir, ".meta/project-graph.json")
        level1Meta.parentFile.mkdirs()
        
        val moduleA = ModuleInfo(
            name = "module-a",
            rootPath = "core/module-a",
            metadataPath = ".meta/module-a-graph.json",
            lastIndexedAt = 1000L,
            fileCount = 1
        )
        val moduleB = ModuleInfo(
            name = "module-b",
            rootPath = "core/module-b",
            metadataPath = ".meta/module-b-graph.json", // 존재하지 않음!
            lastIndexedAt = 2000L,
            fileCount = 1
        )

        val level1Graph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = basePath,
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_1,
            modules = listOf(moduleA, moduleB),
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 2)
        )
        level1Meta.writeText(gson.toJson(level1Graph))

        // 2. Level 2 (Module A) 작성
        val moduleAMeta = File(tempDir, ".meta/module-a-graph.json")
        val nodeA = createDummyFileNode("src/main/kotlin/Foo.kt", "Foo")
        val moduleAGraph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = "$basePath/core/module-a",
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_2,
            files = mapOf("src/main/kotlin/Foo.kt" to nodeA),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 1)
        )
        moduleAMeta.writeText(gson.toJson(moduleAGraph))

        // Loader 실행 (Module B는 메타데이터가 존재하지 않지만 크래시가 없어야 함)
        var loaderRef: GraphLoader? = null
        val mockProject = createMockProject(basePath) { loaderRef }
        val loader = GraphLoader(mockProject)
        loaderRef = loader

        val merged = loader.loadGraph()
        assertNotNull(merged)
        
        // Module A의 파일만 존재하고 크래시가 나지 않음을 확인
        assertEquals(1, merged!!.files.size)
        assertTrue(merged.files.containsKey("core/module-a/src/main/kotlin/Foo.kt"))
        assertFalse(merged.files.containsKey("core/module-b/src/main/kotlin/Bar.kt"))
    }

    @Test
    fun testLevel1OnlyLoad() {
        val basePath = tempDir.absolutePath
        
        // 1. Level 1 Metadata 작성
        val level1Meta = File(tempDir, ".meta/project-graph.json")
        level1Meta.parentFile.mkdirs()
        
        val moduleA = ModuleInfo(
            name = "module-a",
            rootPath = "core/module-a",
            metadataPath = ".meta/module-a-graph.json",
            lastIndexedAt = 1000L,
            fileCount = 1
        )

        val level1Graph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = basePath,
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_1,
            modules = listOf(moduleA),
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 1)
        )
        level1Meta.writeText(gson.toJson(level1Graph))

        var loaderRef: GraphLoader? = null
        val mockProject = createMockProject(basePath) { loaderRef }
        val loader = GraphLoader(mockProject)
        loaderRef = loader

        // level1Only = true 로드 실행
        val level1Loaded = loader.loadGraph(level1Only = true)
        assertNotNull(level1Loaded)
        assertTrue(level1Loaded!!.files.isEmpty())
        assertTrue(level1Loaded.relationships.isEmpty())
        assertNotNull(level1Loaded.modules)
        assertEquals(1, level1Loaded.modules!!.size)
    }

    @Test
    fun testSelectiveModuleLoading() {
        val basePath = tempDir.absolutePath
        
        // 1. Level 1 Metadata 작성
        val level1Meta = File(tempDir, ".meta/project-graph.json")
        level1Meta.parentFile.mkdirs()
        
        val moduleA = ModuleInfo(
            name = "module-a",
            rootPath = "core/module-a",
            metadataPath = ".meta/module-a-graph.json",
            lastIndexedAt = 1000L,
            fileCount = 1
        )
        val moduleB = ModuleInfo(
            name = "module-b",
            rootPath = "core/module-b",
            metadataPath = ".meta/module-b-graph.json",
            lastIndexedAt = 2000L,
            fileCount = 1
        )

        val level1Graph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = basePath,
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_1,
            modules = listOf(moduleA, moduleB),
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 2)
        )
        level1Meta.writeText(gson.toJson(level1Graph))

        // 2. Level 2 (Module A) 작성
        val moduleAMeta = File(tempDir, ".meta/module-a-graph.json")
        val nodeA = createDummyFileNode("src/main/kotlin/Foo.kt", "Foo")
        val moduleAGraph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = "$basePath/core/module-a",
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_2,
            files = mapOf("src/main/kotlin/Foo.kt" to nodeA),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 1)
        )
        moduleAMeta.writeText(gson.toJson(moduleAGraph))

        // 3. Level 2 (Module B) 작성
        val moduleBMeta = File(tempDir, ".meta/module-b-graph.json")
        val nodeB = createDummyFileNode("src/main/kotlin/Bar.kt", "Bar")
        val moduleBGraph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = "$basePath/core/module-b",
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_2,
            files = mapOf("src/main/kotlin/Bar.kt" to nodeB),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 1)
        )
        moduleBMeta.writeText(gson.toJson(moduleBGraph))

        var loaderRef: GraphLoader? = null
        val mockProject = createMockProject(basePath) { loaderRef }
        val loader = GraphLoader(mockProject)
        loaderRef = loader

        // targetModules = listOf("module-a") 로 로드
        val partialLoaded = loader.loadGraph(targetModules = listOf("module-a"))
        assertNotNull(partialLoaded)
        assertEquals(1, partialLoaded!!.files.size)
        assertTrue(partialLoaded.files.containsKey("core/module-a/src/main/kotlin/Foo.kt"))
        assertFalse(partialLoaded.files.containsKey("core/module-b/src/main/kotlin/Bar.kt"))
    }

    @Test
    fun testRelevanceFilterStage0ModuleSelection() {
        val basePath = tempDir.absolutePath
        
        // 1. Level 1 Metadata 작성
        val level1Meta = File(tempDir, ".meta/project-graph.json")
        level1Meta.parentFile.mkdirs()
        
        val moduleA = ModuleInfo(
            name = "payment-module",
            rootPath = "core/payment-module",
            metadataPath = ".meta/payment-module-graph.json",
            lastIndexedAt = 1000L,
            fileCount = 1,
            publicApis = listOf("/api/pay", "/api/checkout")
        )
        val moduleB = ModuleInfo(
            name = "user-module",
            rootPath = "core/user-module",
            metadataPath = ".meta/user-module-graph.json",
            lastIndexedAt = 2000L,
            fileCount = 1,
            publicApis = listOf("/api/user/profile")
        )

        val level1Graph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = basePath,
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_1,
            modules = listOf(moduleA, moduleB),
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 2)
        )
        level1Meta.writeText(gson.toJson(level1Graph))

        // 2. Level 2 (payment-module) 작성
        val moduleAMeta = File(tempDir, ".meta/payment-module-graph.json")
        val nodeA = createDummyFileNode("src/main/kotlin/PaymentService.kt", "PaymentService").copy(
            apiEndpoints = listOf(ApiEndpoint("POST", "/api/pay", "pay", "String"))
        )
        val moduleAGraph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = "$basePath/core/payment-module",
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_2,
            files = mapOf("src/main/kotlin/PaymentService.kt" to nodeA),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 1)
        )
        moduleAMeta.writeText(gson.toJson(moduleAGraph))

        // 3. Level 2 (user-module) 작성
        val moduleBMeta = File(tempDir, ".meta/user-module-graph.json")
        val nodeB = createDummyFileNode("src/main/kotlin/UserService.kt", "UserService").copy(
            apiEndpoints = listOf(ApiEndpoint("GET", "/api/user/profile", "profile", "String"))
        )
        val moduleBGraph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = "$basePath/core/user-module",
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_2,
            files = mapOf("src/main/kotlin/UserService.kt" to nodeB),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 1)
        )
        moduleBMeta.writeText(gson.toJson(moduleBGraph))

        var loaderRef: GraphLoader? = null
        val mockProject = createMockProject(basePath) { loaderRef }
        val loader = GraphLoader(mockProject)
        loaderRef = loader

        // Mock LLM Client
        val mockClient = object : LLMClient {
            override fun chat(systemPrompt: String, userCode: String, maxTokens: Int?, onChunk: ((String) -> Unit)?): OllamaChatResponse {
                // Keywords 리턴 모킹
                val responseContent = """
                    TRANSLATION: pay and checkout requirements
                    KEYWORDS: payment, pay, checkout
                """.trimIndent()
                return OllamaChatResponse(
                    model = "mock-model",
                    createdAt = "2026-05-20",
                    message = OllamaMessage("assistant", responseContent),
                    done = true
                )
            }

            override fun fetchModels(baseUrl: String, apiKey: String): List<String>? {
                return emptyList()
            }
        }

        // RelevanceFilter 실행
        val requirement = "결제 및 체크아웃 기능에 오류가 있습니다."
        val filterResult = RelevanceFilter.filter(
            requirement = requirement,
            graph = level1Graph,
            client = mockClient,
            project = mockProject
        )

        // 결제 모듈만 동적 병합되어 로딩되었는지 검증
        assertNotNull(filterResult.filteredGraph)
        // payment-module의 PaymentService는 keywords "pay" 등과 매칭되어 진입점이 됨
        // user-module은 targetModules에 잡히지 않으므로 파일 목록에 없어야 함
        assertTrue(filterResult.filteredGraph.files.containsKey("core/payment-module/src/main/kotlin/PaymentService.kt"))
        assertFalse(filterResult.filteredGraph.files.containsKey("core/user-module/src/main/kotlin/UserService.kt"))
    }

    @Test
    fun testLoadingPerformanceEightModules() {
        val basePath = tempDir.absolutePath
        val modulesCount = 8
        val modules = mutableListOf<ModuleInfo>()

        // 1. 8개 모듈의 Level 1 요약 작성
        for (i in 1..modulesCount) {
            val mName = "module-$i"
            val mInfo = ModuleInfo(
                name = mName,
                rootPath = "core/$mName",
                metadataPath = ".meta/$mName-graph.json",
                lastIndexedAt = System.currentTimeMillis(),
                fileCount = 100, // 각 100개 파일 시뮬레이션
                publicApis = listOf("/api/$mName")
            )
            modules.add(mInfo)

            // 각 모듈의 Level 2 작성 (파일 100개씩)
            val moduleMeta = File(tempDir, ".meta/$mName-graph.json")
            moduleMeta.parentFile.mkdirs()
            val filesMap = mutableMapOf<String, FileNode>()
            for (j in 1..100) {
                filesMap["src/main/kotlin/Class$j.kt"] = createDummyFileNode("src/main/kotlin/Class$j.kt", "Class$j")
            }
            val moduleGraph = ProjectGraph(
                generatedAt = "2026-05-20",
                projectRoot = "$basePath/core/$mName",
                framework = "Spring Boot",
                graphType = GraphType.MULTI_LEVEL_2,
                files = filesMap,
                relationships = emptyList(),
                statistics = GraphStatistics(totalFiles = filesMap.size)
            )
            moduleMeta.writeText(gson.toJson(moduleGraph))
        }

        val level1Meta = File(tempDir, ".meta/project-graph.json")
        val level1Graph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = basePath,
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_1,
            modules = modules,
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 800)
        )
        level1Meta.writeText(gson.toJson(level1Graph))

        var loaderRef: GraphLoader? = null
        val mockProject = createMockProject(basePath) { loaderRef }
        val loader = GraphLoader(mockProject)
        loaderRef = loader

        // 성능 측정 시작
        val startTime = System.currentTimeMillis()
        val merged = loader.loadGraph()
        val endTime = System.currentTimeMillis()
        
        val duration = endTime - startTime
        println("=== Loading 8 Modules Performance ===")
        println("Merged Graph File Count: ${merged?.files?.size} files")
        println("Time Taken: $duration ms")
        
        assertNotNull(merged)
        assertEquals(800, merged!!.files.size)
        // 5초(5000ms) 이내에 성공적으로 병합 로딩을 완료해야 함 (실제로는 수십 ms 이내 완료 예상)
        assertTrue("Loading 8 modules should take less than 5 seconds", duration < 5000)
    }

    @Test
    fun testMetaGraphExporterTargetRoot() {
        val basePath = tempDir.absolutePath
        val mockProject = createMockProject(basePath) { null }
        
        val exporter = net.ib.ixpert.ops.wuwagent.service.metagraph.MetaGraphExporter()
        
        // 1. Test MULTI_LEVEL_2 saving in module root
        val moduleRootPath = "$basePath/core/module-c"
        val moduleGraph = ProjectGraph(
            generatedAt = "2026-05-20",
            projectRoot = moduleRootPath,
            framework = "Spring Boot",
            graphType = GraphType.MULTI_LEVEL_2,
            files = mapOf("src/main/kotlin/Baz.kt" to createDummyFileNode("src/main/kotlin/Baz.kt", "Baz")),
            relationships = emptyList(),
            statistics = GraphStatistics(totalFiles = 1)
        )
        
        val exportedPath = exporter.exportToJson(moduleGraph, mockProject)
        val expectedFile = File(moduleRootPath, ".meta/project-graph.json")
        assertEquals(expectedFile.absolutePath, exportedPath)
        assertTrue(expectedFile.exists())
        
        // .gitignore check
        val gitignoreFile = File(moduleRootPath, ".gitignore")
        assertTrue(gitignoreFile.exists())
        assertTrue(gitignoreFile.readText().contains(".meta/"))
    }
}
