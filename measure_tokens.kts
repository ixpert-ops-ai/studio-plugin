import java.io.File
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject

fun main() {
    val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
    val gson = GsonBuilder().create()
    val json = gson.fromJson(graphFile.readText(), JsonObject::class.java)
    val files = json.getAsJsonObject("files")
    
    var totalNodes = 0
    var totalChars = 0
    var sampleNodes = mutableListOf<String>()
    
    for (entry in files.entrySet()) {
        val fileObj = entry.value.asJsonObject
        
        // Simulating the compressed representation: ClassName (PackageName)
        val className = fileObj.get("className")?.asString ?: ""
        val packageName = fileObj.get("packageName")?.asString ?: ""
        val path = fileObj.get("path")?.asString ?: ""
        
        val compressedRep = if (className.isNotEmpty() && packageName.isNotEmpty()) {
            "$className ($packageName)"
        } else {
            "${path.substringAfterLast('/')} (${path.substringBeforeLast('/', "")})"
        }
        
        totalChars += compressedRep.length
        totalNodes++
        
        if (sampleNodes.size < 5) {
            sampleNodes.add(compressedRep)
        }
    }
    
    val avgChars = totalChars.toDouble() / totalNodes
    // Assuming 1 token ~ 3.5 characters for camelCase and dots
    val estimatedTokensPerNode = avgChars / 3.5
    
    println("Total Nodes: $totalNodes")
    println("Average Characters per Compressed Node: $avgChars")
    println("Estimated Tokens per Compressed Node (chars/3.5): $estimatedTokensPerNode")
    
    // Reverse-engineering Max N for a 40,960 limit
    val tokenLimit = 40960
    val outputBudget = 1500
    val promptOverhead = 2000
    val availableTokensForCandidates = tokenLimit - outputBudget - promptOverhead
    val maxN = (availableTokensForCandidates / estimatedTokensPerNode).toInt()
    
    println("\n=== Token Budget Reverse-Engineering ===")
    println("Available Tokens for Candidates: $availableTokensForCandidates")
    println("Max N (Available Tokens / Estimated Tokens per Node): $maxN")
    
    println("\n=== Samples ===")
    sampleNodes.forEach { println(it) }
}
main()
