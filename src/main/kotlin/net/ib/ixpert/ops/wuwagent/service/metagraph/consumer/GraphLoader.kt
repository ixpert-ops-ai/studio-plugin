package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
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
    fun loadGraph(targetModules: List<String>? = null, level1Only: Boolean = false): ProjectGraph? {
        val basePath = project.basePath ?: return null
        var metaFile = File(basePath, ".meta/project-graph.json")
        
        if (!metaFile.exists()) {
            val fallbackFile = findMetadataInSubmodules(project)
            if (fallbackFile != null) {
                metaFile = fallbackFile
                logger.info("Found fallback project graph file at ${metaFile.absolutePath}")
            } else {
                logger.warn("Project graph file not found at ${metaFile.absolutePath}")
                return null
            }
        }

        try {
            val attributes = Files.readAttributes(metaFile.toPath(), BasicFileAttributes::class.java)
            val currentModifiedTime = attributes.lastModifiedTime().toMillis()

            // 캐시가 유효한지 1차 검사 (Read without lock) - 부분 로드이거나 level1Only인 경우는 캐시를 우회합니다.
            if (targetModules == null && !level1Only && cachedGraph != null && currentModifiedTime == lastModifiedTime) {
                return cachedGraph
            }

            // 파일이 변경되었거나 캐시가 없으면 새로 파싱 (Thread-safe)
            synchronized(this) {
                // Double-checked locking - 부분 로드이거나 level1Only인 경우는 캐시를 우회합니다.
                if (targetModules == null && !level1Only && cachedGraph != null && currentModifiedTime == lastModifiedTime) {
                    return cachedGraph
                }
                
                logger.info("Loading project graph from ${metaFile.absolutePath} (targetModules=${targetModules?.joinToString() ?: "ALL"}, level1Only=$level1Only)")
                val jsonContent = metaFile.readText(Charsets.UTF_8)
                var parsedGraph = gson.fromJson(jsonContent, ProjectGraph::class.java)

                // 하위 호환 마이그레이션 로직 및 Alias 처리
                if (parsedGraph.frameworkType.name == "SPRING_BOOT" || parsedGraph.frameworkType.name == "ANYFRAME" || parsedGraph.frameworkType.name == "ANYFRAME_JAP") {
                    if (parsedGraph.frameworkType.name == "ANYFRAME_JAP") {
                        // TODO: 추출기 쪽에 ANYFRAME_JAP가 출력되는 원인 백로그 생성 필요
                        logger.warn("Undefined framework type 'ANYFRAME_JAP' detected. Aliasing to 'ANYFRAME_AP'. Please check the extractor.")
                    } else {
                        logger.info("Legacy framework type detected (${parsedGraph.frameworkType.name}). Running auto-detection for migration...")
                    }
                    val detectionResult = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.FrameworkDetector.detectFramework(parsedGraph.files)
                    val newType = if (parsedGraph.frameworkType.name == "ANYFRAME" || parsedGraph.frameworkType.name == "ANYFRAME_JAP") FrameworkType.ANYFRAME_AP else detectionResult.detected
                    parsedGraph = parsedGraph.copy(
                        frameworkType = newType,
                        frameworkDetection = detectionResult,
                        framework = if (newType == FrameworkType.ANYFRAME_AP) "anyframe" else "spring-boot"
                    )
                    
                    // 마이그레이션된 결과를 파일에 저장하여 매번 감지하지 않게 함
                    try {
                        val migratedJson = gson.toJson(parsedGraph)
                        metaFile.writeText(migratedJson, Charsets.UTF_8)
                        logger.info("Graph migration completed and saved.")
                    } catch (e: Exception) {
                        logger.warn("Failed to save migrated graph JSON: ${e.message}")
                    }
                }

                val safeGraph = if (parsedGraph.graphType == GraphType.MULTI_LEVEL_1 && parsedGraph.modules != null) {
                    if (level1Only) {
                        logger.info("Level 1 Only requested. Returning lightweight summary graph.")
                        parsedGraph.copy(
                            files = emptyMap(),
                            relationships = emptyList()
                        )
                    } else {
                        logger.info("Multi-level graph detected. Merging sub-module metadata...")
                        
                        val mergedFiles = mutableMapOf<String, FileNode>()
                        val mergedRelationships = mutableListOf<Relationship>()


                    val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance()
                    val isAnyframe = settings.state.frameworkType == FrameworkType.ANYFRAME_AP
                    val actualTargetModules = if (isAnyframe && targetModules != null) {
                        val hasZz = targetModules.any { it.lowercase() == "zz" || it.lowercase().endsWith("zz") }
                        if (!hasZz) {
                            val zzModule = parsedGraph.modules.firstOrNull { it.name.lowercase() == "zz" || it.name.lowercase().endsWith("zz") }
                            if (zzModule != null) {
                                targetModules + zzModule.name
                            } else {
                                targetModules + "zz"
                            }
                        } else {
                            targetModules
                        }
                    } else {
                        targetModules
                    }

                    for (module in parsedGraph.modules) {
                        // 특정 타겟 모듈만 부분 동적 로딩하도록 필터링 적용
                        if (actualTargetModules != null && !actualTargetModules.contains(module.name)) {
                            logger.info("Skipping module ${module.name} as it is not in the target modules list")
                            continue
                        }

                        val moduleMetaFile = File(basePath, module.metadataPath)
                        if (!moduleMetaFile.exists()) {
                            // Graceful Degradation: 일부 모듈 메타데이터가 누락된 경우 스킵하고 계속 병합
                            logger.warn("Sub-module metadata file missing at ${moduleMetaFile.absolutePath}. Skipping module ${module.name}")
                            continue
                        }

                        try {
                            val moduleJson = moduleMetaFile.readText(Charsets.UTF_8)
                            val moduleGraph = gson.fromJson(moduleJson, ProjectGraph::class.java)

                            // 1. 경로 정규화 및 files 병합 (키 충돌 방지)
                            moduleGraph.files.forEach { (localPath, node) ->
                                val normalizedPath = if (module.rootPath.isNotEmpty()) "${module.rootPath}/$localPath" else localPath
                                val safeNode = node.copy(
                                    path = normalizedPath,
                                    apiEndpoints = node.apiEndpoints ?: emptyList(),
                                    beanDefinitions = node.beanDefinitions ?: emptyList(),
                                    entityRelations = node.entityRelations ?: emptyList(),
                                    dependsOn = node.dependsOn ?: mutableListOf(),
                                    dependedBy = node.dependedBy ?: mutableListOf(),
                                    injections = node.injections ?: emptyList(),
                                    implementedInterfaces = node.implementedInterfaces ?: emptyList(),
                                    annotations = node.annotations ?: emptyList(),
                                    methods = node.methods ?: emptyList(),
                                    koreanComments = node.koreanComments ?: emptyList(),
                                    dynamicViewFolders = node.dynamicViewFolders ?: emptyList()
                                )
                                mergedFiles[normalizedPath] = safeNode
                            }

                            // 2. 관계 데이터 경로 정규화 및 병합
                            moduleGraph.relationships.forEach { rel ->
                                val normalizedSource = if (module.rootPath.isNotEmpty()) "${module.rootPath}/${rel.source}" else rel.source
                                val normalizedTarget = if (module.rootPath.isNotEmpty()) "${module.rootPath}/${rel.target}" else rel.target
                                val normalizedRel = rel.copy(source = normalizedSource, target = normalizedTarget)
                                mergedRelationships.add(normalizedRel)
                            }

                        } catch (e: Exception) {
                            logger.error("Failed to parse metadata for sub-module ${module.name}: ${e.message}")
                            // 에러가 나도 크래시 나지 않고 계속 다음 모듈 진행 (Graceful Degradation)
                        }
                    }

                    parsedGraph.copy(
                        files = mergedFiles,
                        relationships = mergedRelationships
                    )
                }
            } else {
                // 단일 프로젝트(SINGLE) 또는 단독 모듈(MULTI_LEVEL_2)인 경우
                val relativeRoot = if (basePath.isNotEmpty() && !parsedGraph.projectRoot.isNullOrBlank()) {
                    val baseFile = File(basePath)
                    val graphRootFile = File(parsedGraph.projectRoot)
                    if (graphRootFile.isAbsolute && graphRootFile.absolutePath.startsWith(baseFile.absolutePath) && 
                        graphRootFile.absolutePath != baseFile.absolutePath) {
                        graphRootFile.absolutePath.removePrefix(baseFile.absolutePath)
                            .removePrefix("/").removePrefix("\\")
                            .replace("\\", "/")
                    } else {
                        ""
                    }
                } else {
                    ""
                }

                if (relativeRoot.isNotEmpty()) {
                    logger.info("Normalizing fallback graph paths with prefix: $relativeRoot")
                    val normalizedFiles = mutableMapOf<String, FileNode>()
                    parsedGraph.files.forEach { (localPath, node) ->
                        val normalizedPath = "$relativeRoot/$localPath"
                        val safeNode = node.copy(
                            path = normalizedPath,
                            apiEndpoints = node.apiEndpoints ?: emptyList(),
                            beanDefinitions = node.beanDefinitions ?: emptyList(),
                            entityRelations = node.entityRelations ?: emptyList(),
                            dependsOn = (node.dependsOn ?: mutableListOf()).map { if (it.startsWith(relativeRoot)) it else "$relativeRoot/$it" }.toMutableList(),
                            dependedBy = (node.dependedBy ?: mutableListOf()).map { if (it.startsWith(relativeRoot)) it else "$relativeRoot/$it" }.toMutableList(),
                            injections = node.injections ?: emptyList(),
                            implementedInterfaces = node.implementedInterfaces ?: emptyList(),
                            annotations = node.annotations ?: emptyList(),
                            methods = node.methods ?: emptyList(),
                            koreanComments = node.koreanComments ?: emptyList(),
                            dynamicViewFolders = node.dynamicViewFolders ?: emptyList()
                        )
                        normalizedFiles[normalizedPath] = safeNode
                    }

                    val normalizedRelationships = parsedGraph.relationships.map { rel ->
                        val normSource = if (rel.source.startsWith(relativeRoot)) rel.source else "$relativeRoot/${rel.source}"
                        val normTarget = if (rel.target.startsWith(relativeRoot)) rel.target else "$relativeRoot/${rel.target}"
                        rel.copy(source = normSource, target = normTarget)
                    }

                    parsedGraph.copy(files = normalizedFiles, relationships = normalizedRelationships)
                } else {
                    val safeFiles = parsedGraph.files.mapValues { (_, node) ->
                        node.copy(
                            apiEndpoints = node.apiEndpoints ?: emptyList(),
                            beanDefinitions = node.beanDefinitions ?: emptyList(),
                            entityRelations = node.entityRelations ?: emptyList(),
                            dependsOn = node.dependsOn ?: mutableListOf(),
                            dependedBy = node.dependedBy ?: mutableListOf(),
                            injections = node.injections ?: emptyList(),
                            implementedInterfaces = node.implementedInterfaces ?: emptyList(),
                            annotations = node.annotations ?: emptyList(),
                            methods = node.methods ?: emptyList(),
                            koreanComments = node.koreanComments ?: emptyList(),
                            dynamicViewFolders = node.dynamicViewFolders ?: emptyList()
                        )
                    }
                    parsedGraph.copy(files = safeFiles)
                }
            }

                // 부분 로드된 그래프는 캐시에 갱신하지 않고 즉시 반환
                if (targetModules != null) {
                    return safeGraph
                }

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

    private fun findMetadataInSubmodules(project: Project): File? {
        val basePath = project.basePath ?: return null
        
        // 1. ModuleManager를 이용해 등록된 모든 모듈 디렉터리 내의 .meta/project-graph.json 탐색
        try {
            val modules = com.intellij.openapi.module.ModuleManager.getInstance(project).modules
            for (module in modules) {
                val rootManager = com.intellij.openapi.roots.ModuleRootManager.getInstance(module)
                val contentRoots = rootManager.contentRoots
                for (root in contentRoots) {
                    val metaFile = File(root.path, ".meta/project-graph.json")
                    if (metaFile.exists()) {
                        return metaFile
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to find metadata in modules: ${e.message}")
        }
        
        // 2. 만약 모듈 정보 탐색 실패시, 프로젝트 하위 1-2단계 깊이의 폴더에서 직접 .meta/project-graph.json 탐색
        val rootDir = File(basePath)
        if (rootDir.exists() && rootDir.isDirectory) {
            val subDirs = rootDir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            if (subDirs != null) {
                for (subDir in subDirs) {
                    val metaFile = File(subDir, ".meta/project-graph.json")
                    if (metaFile.exists()) {
                        return metaFile
                    }
                }
            }
        }
        return null
    }
}
