import java.io.File
import net.ib.ixpert.ops.wuwagent.service.metagraph.ProjectGraphBuilder
import net.ib.ixpert.ops.wuwagent.agent.completeness.ProjectGraphAdapter
import net.ib.ixpert.ops.wuwagent.agent.completeness.model.MatchStrategy

fun main() {
    val graphFile = File("C:/Workspace/HC_card_survey_admin/.meta/project-graph.json")
    val graph = net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph.fromJson(graphFile.readText())
    val ctx = ProjectGraphAdapter(graph)
    val result = MatchStrategy.SameNamespaceXml.match("survey_admin/src/main/java/net/infobank/iss/survey/dao/SurveyDao.java", ctx)
    println("existsInGraph = \")
    println("matchedPath = \")
    println("note = \")
}
