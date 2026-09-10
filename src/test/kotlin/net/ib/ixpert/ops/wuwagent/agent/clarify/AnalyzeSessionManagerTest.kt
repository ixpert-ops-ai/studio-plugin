package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.Stage0State
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class AnalyzeSessionManagerTest {

    private fun createDummyProject(basePath: String): Project {
        return Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "getName" -> "TestProject"
                "getLocationHash" -> "hash_$basePath"
                else -> null
            }
        } as Project
    }

    @Before
    @After
    fun cleanup() {
        AnalyzeSessionManager.clearAll()
    }

    @Test
    fun testSaveAndGetSession() {
        val project = createDummyProject("/test/project/a")
        val graph = ProjectGraph(
            generatedAt = java.time.Instant.now().toString(),
            projectRoot = "/test/project/a",
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )
        val scanner = Stage0GraphScanner(graph)
        val engine = Stage0ClarificationEngine(scanner, graph)

        val turnResult = engine.initSession("설문 발송")

        AnalyzeSessionManager.saveSession(
            project = project,
            initialRequirement = "설문 발송",
            engine = engine,
            currentState = turnResult.state,
            lastTurnResult = turnResult
        )

        val session = AnalyzeSessionManager.getSession(project)
        assertNotNull("Session should be retrieved successfully", session)
        assertEquals("설문 발송", session?.initialRequirement)
        assertEquals(turnResult.state, session?.currentState)
    }

    @Test
    fun testSessionExpiration() {
        val project = createDummyProject("/test/project/b")
        val graph = ProjectGraph(
            generatedAt = java.time.Instant.now().toString(),
            projectRoot = "/test/project/b",
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )
        val scanner = Stage0GraphScanner(graph)
        val engine = Stage0ClarificationEngine(scanner, graph)
        val turnResult = engine.initSession("테스트")

        val expiredSession = Stage0Session(
            initialRequirement = "테스트",
            engine = engine,
            currentState = turnResult.state,
            lastTurnResult = turnResult,
            createdAtMs = System.currentTimeMillis() - (10 * 60 * 1000L) // 10 minutes ago
        )
        assertTrue(expiredSession.isExpired(5))
    }

    @Test
    fun testRemoveSession() {
        val project = createDummyProject("/test/project/c")
        val graph = ProjectGraph(
            generatedAt = java.time.Instant.now().toString(),
            projectRoot = "/test/project/c",
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )
        val scanner = Stage0GraphScanner(graph)
        val engine = Stage0ClarificationEngine(scanner, graph)
        val turnResult = engine.initSession("삭제 테스트")

        AnalyzeSessionManager.saveSession(project, "삭제 테스트", engine, turnResult.state, turnResult)
        assertNotNull(AnalyzeSessionManager.getSession(project))

        AnalyzeSessionManager.removeSession(project)
        assertNull(AnalyzeSessionManager.getSession(project))
    }
}
