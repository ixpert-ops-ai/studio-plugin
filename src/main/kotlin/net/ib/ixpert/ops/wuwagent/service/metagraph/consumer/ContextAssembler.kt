package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import net.ib.ixpert.ops.wuwagent.agent.AgentContext
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

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
            append("## 프로젝트 구조 컨텍스트\n")
            append("- 프레임워크: ${graph.framework} / 총 ${graph.statistics.totalFiles}개 파일\n\n")
            append("> [지시사항]\n")
            append("> 이 프로젝트는 ${graph.framework} 기반으로 총 ${graph.statistics.totalFiles}개의 파일로 구성되어 있습니다.\n")
            append("> 타겟 파일이 명확하지 않아 부분 구조 정보만 제공됩니다.")
        }
        logger.info("ContextAssembler: 조립 완료 (타겟: 없음(Layer 1만 제공), 크기: ${finalText.length} chars, 약 ${finalText.length / 4} 토큰 추정)")
        return finalText
    }
}
