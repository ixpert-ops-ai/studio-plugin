package net.ib.ixpert.ops.wuwagent.service.metagraph

import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import java.io.File

/**
 * 메타 그래프를 JSON 파일로 출력합니다.
 * 저장 위치: 프로젝트 루트/.meta/project-graph.json
 */
class MetaGraphExporter {

    private val logger = Logger.getInstance(MetaGraphExporter::class.java)
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    companion object {
        private const val META_DIR = ".meta"
        private const val FILE_NAME = "project-graph.json"
    }

    /**
     * ProjectGraph를 적응형 분할 저장 전략에 맞게 저장합니다.
     * @return 저장된 루트 메타파일의 절대 경로
     */
    fun exportToJson(graph: ProjectGraph, project: Project): String {
        val basePath = project.basePath ?: throw IllegalStateException("프로젝트 경로를 찾을 수 없습니다.")
        
        // 1. 루트 프로젝트 .gitignore 등록 검사 및 추가
        ensureGitignore(File(basePath))

        if (graph.graphType == GraphType.MULTI_LEVEL_1 && graph.modules != null) {
            logger.info("Adaptive Storage: Multi-module system detected. Splitting graph...")

            // 2. 각 모듈별 상세 데이터(Level 2) 저장
            for (module in graph.modules) {
                val moduleRoot = File(basePath, module.rootPath)
                if (!moduleRoot.exists()) continue
                
                // 모듈 .gitignore 자동 등록
                ensureGitignore(moduleRoot)

                // 이 모듈에 속한 파일과 관계만 추출하고, 키를 모듈 기준 상대 경로로 변환
                val moduleFiles = graph.files.filter { (path, _) ->
                    path.startsWith("${module.rootPath}/") || (module.rootPath.isEmpty() && !path.contains("/"))
                }.mapKeys { (path, _) ->
                    if (module.rootPath.isNotEmpty()) {
                        path.removePrefix("${module.rootPath}/")
                    } else {
                        path
                    }
                }.mapValues { (localPath, node) ->
                    node.copy(path = localPath)
                }
                
                val moduleFilePaths = moduleFiles.keys
                val moduleRelationships = graph.relationships.filter { rel ->
                    // 관계 매칭을 위해 원본 경로의 prefix를 제거하고 비교
                    val sourceLocal = if (module.rootPath.isNotEmpty()) rel.source.removePrefix("${module.rootPath}/") else rel.source
                    val targetLocal = if (module.rootPath.isNotEmpty()) rel.target.removePrefix("${module.rootPath}/") else rel.target
                    sourceLocal in moduleFilePaths || targetLocal in moduleFilePaths
                }.map { rel ->
                    // 관계 데이터 내부의 source, target 경로도 모듈 로컬 상대 경로로 정규화
                    val sourceLocal = if (module.rootPath.isNotEmpty()) rel.source.removePrefix("${module.rootPath}/") else rel.source
                    val targetLocal = if (module.rootPath.isNotEmpty()) rel.target.removePrefix("${module.rootPath}/") else rel.target
                    rel.copy(source = sourceLocal, target = targetLocal)
                }

                // 모듈별 세부 그래프 객체 생성
                val moduleGraph = ProjectGraph(
                    graphType = GraphType.MULTI_LEVEL_2,
                    generatedAt = graph.generatedAt,
                    projectRoot = moduleRoot.absolutePath,
                    files = moduleFiles,
                    relationships = moduleRelationships,
                    statistics = graph.statistics
                )

                // 모듈 .meta 디렉터리 생성 및 저장
                val moduleMetaDir = File(moduleRoot, META_DIR)
                if (!moduleMetaDir.exists()) {
                    moduleMetaDir.mkdirs()
                }
                val moduleOutputFile = File(moduleMetaDir, FILE_NAME)
                moduleOutputFile.writeText(gson.toJson(moduleGraph), Charsets.UTF_8)
                logger.info("Saved Level 2 graph for module ${module.name} to ${moduleOutputFile.absolutePath}")
            }

            // 3. 루트 요약본(Level 1) 저장: files와 relationships를 비워 수십 KB 이하로 경량화!
            val rootMetaDir = File(basePath, META_DIR)
            if (!rootMetaDir.exists()) {
                rootMetaDir.mkdirs()
            }
            val rootOutputFile = File(rootMetaDir, FILE_NAME)
            val level1Graph = graph.copy(
                files = emptyMap(),
                relationships = emptyList()
            )
            rootOutputFile.writeText(gson.toJson(level1Graph), Charsets.UTF_8)
            logger.info("Saved Level 1 global graph to ${rootOutputFile.absolutePath}")
            return rootOutputFile.absolutePath

        } else if (graph.graphType == GraphType.MULTI_LEVEL_2) {
            // MULTI_LEVEL_2 (단독 모듈 재분석): 해당 모듈 디렉터리의 .meta/ 에 저장
            val moduleRootPath = if (!graph.projectRoot.isNullOrBlank()) graph.projectRoot else basePath
            val moduleRoot = File(moduleRootPath)
            if (!moduleRoot.exists()) {
                moduleRoot.mkdirs()
            }
            ensureGitignore(moduleRoot)

            val moduleMetaDir = File(moduleRoot, META_DIR)
            if (!moduleMetaDir.exists()) {
                moduleMetaDir.mkdirs()
            }
            val outputFile = File(moduleMetaDir, FILE_NAME)
            outputFile.writeText(gson.toJson(graph), Charsets.UTF_8)
            logger.info("Saved Level 2 graph to ${outputFile.absolutePath}")
            return outputFile.absolutePath
        } else {
            // 단일 프로젝트(SINGLE)인 경우: 기존과 동일하게 루트에 통째로 저장
            val targetRootPath = if (!graph.projectRoot.isNullOrBlank()) graph.projectRoot else basePath
            val targetRoot = File(targetRootPath)
            if (!targetRoot.exists()) {
                targetRoot.mkdirs()
            }
            ensureGitignore(targetRoot)

            val metaDir = File(targetRoot, META_DIR)
            if (!metaDir.exists()) {
                metaDir.mkdirs()
            }
            val outputFile = File(metaDir, FILE_NAME)
            outputFile.writeText(gson.toJson(graph), Charsets.UTF_8)
            logger.info("Saved Single graph to ${outputFile.absolutePath}")
            return outputFile.absolutePath
        }
    }

    /**
     * .gitignore 파일에 .meta/ 가 등록되어 있는지 검사하고 없으면 추가합니다.
     */
    private fun ensureGitignore(directory: File) {
        try {
            val gitignoreFile = File(directory, ".gitignore")
            if (!gitignoreFile.exists()) {
                gitignoreFile.writeText("\n.meta/\n", Charsets.UTF_8)
                logger.info("Created .gitignore and added .meta/ in ${directory.absolutePath}")
                return
            }

            val lines = gitignoreFile.readLines(Charsets.UTF_8)
            val hasMeta = lines.any { line -> line.trim() == ".meta/" || line.trim() == ".meta" }
            if (!hasMeta) {
                val currentText = gitignoreFile.readText(Charsets.UTF_8)
                val newText = if (currentText.endsWith("\n") || currentText.endsWith("\r\n")) {
                    currentText + ".meta/\n"
                } else {
                    currentText + "\n.meta/\n"
                }
                gitignoreFile.writeText(newText, Charsets.UTF_8)
                logger.info("Added .meta/ to existing .gitignore in ${directory.absolutePath}")
            }
        } catch (e: Exception) {
            logger.warn("Failed to update .gitignore in ${directory.absolutePath}: ${e.message}")
        }
    }

    /**
     * ProjectGraph를 JSON 문자열로 변환합니다 (LLM 프롬프트 주입용).
     */
    fun toJsonString(graph: ProjectGraph): String {
        return gson.toJson(graph)
    }
}
