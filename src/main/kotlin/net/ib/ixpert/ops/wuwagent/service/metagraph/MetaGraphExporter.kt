package net.ib.ixpert.ops.wuwagent.service.metagraph

import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
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
     * ProjectGraph를 JSON 파일로 저장합니다.
     * @return 저장된 파일의 절대 경로
     */
    fun exportToJson(graph: ProjectGraph, project: Project): String {
        val basePath = project.basePath ?: throw IllegalStateException("프로젝트 경로를 찾을 수 없습니다.")
        val metaDir = File(basePath, META_DIR)
        if (!metaDir.exists()) {
            metaDir.mkdirs()
        }

        val outputFile = File(metaDir, FILE_NAME)
        val json = gson.toJson(graph)
        outputFile.writeText(json, Charsets.UTF_8)

        logger.info("Meta graph exported: ${outputFile.absolutePath} (${json.length} chars)")
        return outputFile.absolutePath
    }

    /**
     * ProjectGraph를 JSON 문자열로 변환합니다 (LLM 프롬프트 주입용).
     */
    fun toJsonString(graph: ProjectGraph): String {
        return gson.toJson(graph)
    }
}
