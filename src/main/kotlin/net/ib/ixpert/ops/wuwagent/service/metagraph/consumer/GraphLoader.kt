package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicReference

/**
 * .meta/project-graph.json 파일을 읽고 파싱하여 메모리에 캐싱합니다.
 */
@Service(Service.Level.PROJECT)
class GraphLoader(private val project: Project) {
    private val logger = Logger.getInstance(GraphLoader::class.java)
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    
    // 캐싱된 그래프 객체와 해당 파일의 마지막 수정 시간
    @Volatile
    private var cachedGraph: ProjectGraph? = null
    @Volatile
    private var lastModifiedTime: Long = 0

    /**
     * 프로젝트 그래프를 로드합니다.
     * 파일 변경이 없으면 캐시를 반환하며, 파싱 실패 시 null을 반환합니다.
     */
    fun loadGraph(): ProjectGraph? {
        val basePath = project.basePath ?: return null
        val metaFile = File(basePath, ".meta/project-graph.json")
        
        if (!metaFile.exists()) {
            logger.warn("Project graph file not found at ${metaFile.absolutePath}")
            return null
        }

        try {
            val attributes = Files.readAttributes(metaFile.toPath(), BasicFileAttributes::class.java)
            val currentModifiedTime = attributes.lastModifiedTime().toMillis()

            // 캐시가 유효한지 1차 검사 (Read without lock)
            if (cachedGraph != null && currentModifiedTime == lastModifiedTime) {
                return cachedGraph
            }

            // 파일이 변경되었거나 캐시가 없으면 새로 파싱 (Thread-safe)
            synchronized(this) {
                // Double-checked locking
                if (cachedGraph != null && currentModifiedTime == lastModifiedTime) {
                    return cachedGraph
                }
                
                logger.info("Loading project graph from ${metaFile.absolutePath}")
                val jsonContent = metaFile.readText(Charsets.UTF_8)
                val parsedGraph = gson.fromJson(jsonContent, ProjectGraph::class.java)
                
                // Phase 1a JSON 하위 호환성: Gson이 누락된 컬렉션 필드를 null로 만들 수 있으므로 기본값 복구
                val safeFiles = parsedGraph.files.mapValues { (_, node) ->
                    node.copy(
                        apiEndpoints = node.apiEndpoints ?: emptyList(),
                        beanDefinitions = node.beanDefinitions ?: emptyList(),
                        entityRelations = node.entityRelations ?: emptyList(),
                        dependsOn = node.dependsOn ?: mutableListOf(),
                        dependedBy = node.dependedBy ?: mutableListOf(),
                        injections = node.injections ?: emptyList(),
                        implementedInterfaces = node.implementedInterfaces ?: emptyList(),
                        annotations = node.annotations ?: emptyList()
                    )
                }
                
                val safeGraph = parsedGraph.copy(files = safeFiles)
                
                cachedGraph = safeGraph
                lastModifiedTime = currentModifiedTime
                
                return safeGraph
            }


        } catch (e: JsonSyntaxException) {
            logger.error("Failed to parse project graph JSON (malformed): ${e.message}")
            return null
        } catch (e: Exception) {
            logger.error("Error reading project graph file: ${e.message}", e)
            return null
        }
    }
    
    fun invalidateCache() {
        synchronized(this) {
            cachedGraph = null
            lastModifiedTime = 0
        }
    }
}
