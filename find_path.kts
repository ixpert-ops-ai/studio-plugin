import java.io.File

fun main() {
    val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
    val text = graphFile.readText()
    
    // Find IDs
    var bizId = ""
    var demId = ""
    
    val nodeRegex = "\"([^\"]+)\":\\{\"className\":\"([^\"]+)\"".toRegex()
    for (match in nodeRegex.findAll(text)) {
        val id = match.groupValues[1]
        val className = match.groupValues[2]
        if (className == "APCMMOpnApiO011BIZ") bizId = id
        if (className == "ACMBTBAPC031DEM") demId = id
    }
    
    println("BIZ ID: $bizId")
    println("DEM ID: $demId")
    
    if (bizId.isNotEmpty() && demId.isNotEmpty()) {
        val edgeRegex = "\\{\"source\":\"([^\"]+)\",\"target\":\"([^\"]+)\"".toRegex()
        val adj = mutableMapOf<String, MutableList<String>>()
        
        for (match in edgeRegex.findAll(text)) {
            val s = match.groupValues[1]
            val t = match.groupValues[2]
            adj.getOrPut(s) { mutableListOf() }.add(t)
        }
        
        fun getClassName(id: String): String {
            val m = "\"$id\":\\{\"className\":\"([^\"]+)\"".toRegex().find(text)
            return m?.groupValues?.get(1) ?: id
        }
        
        fun findPath(start: String, end: String, visited: MutableSet<String>, path: MutableList<String>): Boolean {
            if (start == end) return true
            visited.add(start)
            for (neighbor in adj[start] ?: emptyList()) {
                if (neighbor !in visited) {
                    path.add(neighbor)
                    if (findPath(neighbor, end, visited, path)) return true
                    path.removeAt(path.size - 1)
                }
            }
            return false
        }
        
        val path = mutableListOf<String>()
        val found = findPath(bizId, demId, mutableSetOf(), path)
        if (found) {
            println("Path found! BIZ -> " + path.map { getClassName(it) }.joinToString(" -> "))
        } else {
            println("No path found between BIZ and DEM.")
        }
    }
}
main()
