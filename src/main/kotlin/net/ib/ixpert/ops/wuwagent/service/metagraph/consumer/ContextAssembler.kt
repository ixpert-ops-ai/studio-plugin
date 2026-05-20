package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * 프로젝트 그래프, 질문, 에디터 상태를 조합하여 메타그래프 컨텍스트를 구성합니다.
 */
@Service(Service.Level.PROJECT)
class ContextAssembler(private val project: Project) {

    private val logger = Logger.getInstance(ContextAssembler::class.java)
    
    // 단위 테스트에서 Mock을 주입할 수 있도록 internal var로 열어둡니다.
    internal var graphLoader: GraphLoader = project.getService(GraphLoader::class.java)

    /**
     * 메타그래프 컨텍스트(마크다운 텍스트)를 조립하여 반환합니다.
     * 캐시된 그래프가 없거나 타겟을 찾지 못하면 빈 문자열을 반환합니다.
     * 
     * @param context 에이전트 실행 컨텍스트 (에디터 상태 등 포함)
     * @param question 사용자 질문
     * @return 조립된 서브그래프 컨텍스트 문자열
     */
    fun assemble(context: AgentContext, question: String?): String {
        val graph = graphLoader.loadGraph() ?: return ""
        
        // 타겟 추출
        val targets = TargetExtractor.extractTargets(project, question, graph)
        if (targets.isEmpty()) {
            return formatLayer1Only(graph)
        }
        
        // 서브그래프 추출
        val subGraph = SubGraphExtractor.extract(graph, targets)
        if (subGraph.targets.isEmpty()) {
            return formatLayer1Only(graph)
        }
        
        // 포맷팅
        val formattedGraph = SubGraphFormatter.format(graph, subGraph)
        
        val finalText = buildString {
            append(formattedGraph)
            append("\n\n> [지시사항]\n")
            append("> 위 프로젝트 구조 컨텍스트(서브그래프)를 참고하여 답변을 작성하세요.\n")
            append("> 서브그래프에 나열된 클래스 간의 의존성 및 영향을 반드시 고려해야 합니다.")
        }
        
        val logMsg = "ContextAssembler: 조립 완료 (타겟: ${targets.joinToString()}, 크기: ${finalText.length} chars, 약 ${finalText.length / 4} 토큰 추정)"
        logger.info(logMsg)
        logger.debug("ContextAssembler 전문:\n$finalText")
        return finalText
    }

    private fun formatLayer1Only(graph: ProjectGraph): String {
        val finalText = buildString {
            append("## 프로젝트 모듈 및 API 컨텍스트 (Level 1)\n")
            append("- 프레임워크: ${graph.framework}\n")
            append("- 총 파일 수: ${graph.statistics.totalFiles}개\n")
            append("- **프로젝트 구성 통계:**\n")
            append("  - Controller: ${graph.statistics.controllers}개\n")
            append("  - Service: ${graph.statistics.services}개\n")
            append("  - Repository: ${graph.statistics.repositories}개\n")
            if (graph.statistics.entities > 0) append("  - Entity: ${graph.statistics.entities}개\n")
            if (graph.statistics.dtos > 0) append("  - DTO: ${graph.statistics.dtos}개\n")
            append("\n")

            if (graph.graphType == GraphType.MULTI_LEVEL_1 && graph.modules != null) {
                append("### 멀티 모듈 구성 정보\n")
                for (module in graph.modules) {
                    append("- **모듈명:** `${module.name}` (경로: `${module.rootPath}`)\n")
                    append("  - 파일 수: ${module.fileCount ?: 0}개\n")
                    if (!module.publicApis.isNullOrEmpty()) {
                        append("  - 노출 API Endpoints:\n")
                        module.publicApis.take(15).forEach { api ->
                            append("    - `$api`\n")
                        }
                        if (module.publicApis.size > 15) {
                            append("    - ... 외 ${module.publicApis.size - 15}개 더 있음\n")
                        }
                    }
                    if (module.dependsOnModules.isNotEmpty()) {
                        append("  - 의존 모듈: `${module.dependsOnModules.joinToString()}`\n")
                    }
                    append("\n")
                }
            } else {
                // SINGLE 또는 MULTI_LEVEL_2 상세 그래프인 경우 주요 API Endpoints를 추출하여 보여줌
                val allEndpoints = graph.files.values.flatMap { it.apiEndpoints }
                if (allEndpoints.isNotEmpty()) {
                    append("### 노출 API Endpoints\n")
                    allEndpoints.sortedBy { it.path }.take(15).forEach { api ->
                        append("- `[${api.httpMethod}] ${api.path}` (핸들러: `${api.controllerClass.substringAfterLast('.')}.${api.handlerMethod}`)\n")
                    }
                    if (allEndpoints.size > 15) {
                        append("- ... 외 ${allEndpoints.size - 15}개 더 있음\n")
                    }
                    append("\n")
                }
            }

            append("> [지시사항]\n")
            append("> 위 프로젝트 구성 및 API 인터페이스 정보를 바탕으로, 사용자의 질문 해결에 핵심이 되는 타겟 모듈과 파일을 탐색하여 답변을 작성하세요.\n")
        }
        logger.info("ContextAssembler: 조립 완료 (Level 1 구조 요약 제공, 크기: ${finalText.length} chars, 약 ${finalText.length / 4} 토큰 추정)")
        return finalText
    }
}
