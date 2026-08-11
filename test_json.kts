
import java.io.File
val jsonString = File("C:/Workspace/graph/project-graph-i/project-graph.json").readText(Charsets.UTF_8)
val index = jsonString.indexOf("OrderProcessBaseRequest")
println("Found at index: " + index)
val sub = jsonString.substring(maxOf(0, index - 500), minOf(jsonString.length, index + 2000))
println(sub)

