import com.google.gson.Gson
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.*
import java.io.File

fun main() {
    val jsonContent = File("C:/Workspace/HC_card_survey_admin/survey_admin/.meta/project-graph.json").readText(Charsets.UTF_8)
    val gson = Gson()
    val parsedGraph = gson.fromJson(jsonContent, ProjectGraph::class.java)
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

    val summary = ProjectSummaryFormatter.format(safeGraph)
    println(summary)
}
