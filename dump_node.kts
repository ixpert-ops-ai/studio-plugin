import java.io.File
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject

fun main() {
    val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
    val gson = GsonBuilder().setPrettyPrinting().create()
    val json = gson.fromJson(graphFile.readText(), JsonObject::class.java)
    val files = json.getAsJsonObject("files")
    
    var bizNode: JsonObject? = null
    var demNode: JsonObject? = null
    
    for (entry in files.entrySet()) {
        val fileObj = entry.value.asJsonObject
        val className = fileObj.get("className")?.asString
        if (className == "APCMMOpnApiO011BIZ") bizNode = fileObj
        if (className == "ACMBTBAPC031DEM") demNode = fileObj
    }
    
    println("=== APCMMOpnApiO011BIZ Dump ===")
    println(gson.toJson(bizNode))
    
    println("\n=== ACMBTBAPC031DEM Dump ===")
    println(gson.toJson(demNode))
}
main()
