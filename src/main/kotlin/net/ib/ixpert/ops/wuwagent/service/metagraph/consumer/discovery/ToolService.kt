package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.ib.ixpert.ops.wuwagent.model.FunctionDefinition
import net.ib.ixpert.ops.wuwagent.model.FunctionParameters
import net.ib.ixpert.ops.wuwagent.model.PropertyDefinition
import net.ib.ixpert.ops.wuwagent.model.ToolDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

object ToolService {
    private val gson = Gson()

    fun buildSchema(): List<ToolDefinition> {
        return listOf(
            ToolDefinition(
                function = FunctionDefinition(
                    name = "search_files",
                    description = "프로젝트 메타그래프에서 키워드로 파일을 검색합니다. 반환 데이터는 [{path, className, type}] 형태입니다. type은 JAVA 또는 RESOURCE입니다. scope는 class_name, method_name, api_url, package, path, all 중 하나를 지정할 수 있습니다. UI 파일(JSP, JS 등)은 scope='all' 또는 'path'로 검색하세요.",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "keyword" to PropertyDefinition(type = "string", description = "검색 키워드"),
                            "scope" to PropertyDefinition(type = "string", enum = listOf("class_name", "method_name", "api_url", "package", "path", "all"))
                        ),
                        required = listOf("keyword", "scope")
                    )
                )
            ),
            ToolDefinition(
                function = FunctionDefinition(
                    name = "get_file_summary",
                    description = "특정 파일의 구조 요약을 조회합니다. 클래스명, 레이어, 메서드 목록, 주입된 의존성, 어노테이션 정보를 반환합니다.",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to PropertyDefinition(type = "string", description = "파일 경로 (부분 매칭 가능)")
                        ),
                        required = listOf("path")
                    )
                )
            ),
            ToolDefinition(
                function = FunctionDefinition(
                    name = "get_dependencies",
                    description = "특정 파일이 의존하는(dependsOn) 파일과 이 파일에 의존하는(dependedBy) 파일 목록을 조회합니다.",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to PropertyDefinition(type = "string", description = "파일 경로 (부분 매칭 가능)"),
                            "direction" to PropertyDefinition(type = "string", enum = listOf("depends_on", "depended_by", "both"))
                        ),
                        required = listOf("path", "direction")
                    )
                )
            ),
            ToolDefinition(
                function = FunctionDefinition(
                    name = "get_resource_summary",
                    description = "비-Java 자원 파일(MyBatis XML, JSP, JS 등)의 구조 요약을 조회합니다. 경로와 메타데이터 힌트를 반환합니다.",
                    parameters = FunctionParameters(
                        properties = mapOf(
                            "path" to PropertyDefinition(type = "string", description = "자원 파일 경로 (부분 매칭 가능)")
                        ),
                        required = listOf("path")
                    )
                )
            )
        )
    }

    fun execute(name: String, argumentsJson: String, graph: ProjectGraph): String {
        return try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val args: Map<String, Any> = gson.fromJson(argumentsJson, type) ?: emptyMap()
            com.intellij.openapi.diagnostic.Logger.getInstance(ToolService::class.java).info("Tool Executed: $name with args: $argumentsJson")

            when (name) {
                "search_files" -> {
                    val keyword = args["keyword"]?.toString() ?: ""
                    val scope = args["scope"]?.toString() ?: "all"
                    searchFiles(keyword, scope, graph)
                }
                "get_file_summary" -> {
                    val path = args["path"]?.toString() ?: ""
                    getFileSummary(path, graph)
                }
                "get_dependencies" -> {
                    val path = args["path"]?.toString() ?: ""
                    val direction = args["direction"]?.toString() ?: "both"
                    getDependencies(path, direction, graph)
                }
                "get_resource_summary" -> {
                    val path = args["path"]?.toString() ?: ""
                    getResourceSummary(path, graph)
                }
                else -> "Unknown tool: $name"
            }
        } catch (e: Exception) {
            "Error executing tool $name: ${e.message}"
        }
    }

    private fun searchFiles(keyword: String, scope: String, graph: ProjectGraph): String {
        if (keyword.isBlank()) return "[]"
        val lowerKeyword = keyword.lowercase()
        
        val matchedFiles = graph.files.values.filter { node ->
            when (scope) {
                "class_name" -> node.className.lowercase().contains(lowerKeyword)
                "method_name" -> node.methodNames.any { it.lowercase().contains(lowerKeyword) }
                "api_url" -> node.apiEndpoints.any { it.path.lowercase().contains(lowerKeyword) }
                "package" -> node.packageName?.lowercase()?.contains(lowerKeyword) == true
                "path" -> node.path.lowercase().contains(lowerKeyword)
                "all" -> {
                    node.className.lowercase().contains(lowerKeyword) ||
                    node.methodNames.any { it.lowercase().contains(lowerKeyword) } ||
                    node.apiEndpoints.any { it.path.lowercase().contains(lowerKeyword) } ||
                    node.packageName?.lowercase()?.contains(lowerKeyword) == true ||
                    node.path.lowercase().contains(lowerKeyword)
                }
                else -> false
            }
        }.take(20).map { mapOf("path" to it.path, "className" to it.className, "type" to "JAVA") }

        com.intellij.openapi.diagnostic.Logger.getInstance(ToolService::class.java).warn("searchFiles: resourceNodes.size=${graph.resourceNodes.size}, keyword=$keyword, scope=$scope")

        val matchedResources = if (scope == "all" || scope == "path") {
            graph.resourceNodes.filter { rNode ->
                rNode.path.lowercase().contains(lowerKeyword) ||
                rNode.metadata.values.any { value ->
                    if (value is List<*>) {
                        value.any { it.toString().lowercase().contains(lowerKeyword) }
                    } else {
                        value.toString().lowercase().contains(lowerKeyword)
                    }
                }
            }.take(15).map { mapOf("path" to it.path, "className" to it.path.substringAfterLast("/"), "type" to "RESOURCE") }
        } else {
            emptyList()
        }
        
        val combined = (matchedFiles + matchedResources).take(30)
        return gson.toJson(combined)
    }

    private fun getFileSummary(pathSubstring: String, graph: ProjectGraph): String {
        if (pathSubstring.isBlank()) return "{}"
        
        val node = graph.files.values.firstOrNull { it.path.contains(pathSubstring, ignoreCase = true) }
            ?: return "File not found matching: $pathSubstring"
            
        val methodList = node.methodNames
        val slimMethods = if (methodList.size > 10) {
            val moreCount = maxOf(0, methodList.size - 10)
            methodList.take(10) + "... and $moreCount more"
        } else {
            methodList
        }
        
        val summary = mapOf(
            "path" to node.path,
            "className" to node.className,
            "layer" to (node.layer?.name ?: "UNKNOWN"),
            "methods" to slimMethods,
            "annotations" to node.annotations
        )
        return gson.toJson(summary)
    }

    private fun getDependencies(pathSubstring: String, direction: String, graph: ProjectGraph): String {
        if (pathSubstring.isBlank()) return "{}"
        
        val node = graph.files.values.firstOrNull { it.path.contains(pathSubstring, ignoreCase = true) }
            ?: return "File not found matching: $pathSubstring"
            
        val result = mutableMapOf<String, Any>()
        result["path"] = node.path
        
        if (direction == "depends_on" || direction == "both") {
            val deps = node.dependsOn
            val slimDeps = if (deps.size > 10) {
                val moreCount = maxOf(0, deps.size - 10)
                deps.take(10) + "... and $moreCount more"
            } else {
                deps
            }
            result["dependsOn"] = slimDeps
        }
        
        if (direction == "depended_by" || direction == "both") {
            val deps = node.dependedBy
            val slimDeps = if (deps.size > 10) {
                val moreCount = maxOf(0, deps.size - 10)
                deps.take(10) + "... and $moreCount more"
            } else {
                deps
            }
            result["dependedBy"] = slimDeps
        }
        
        return gson.toJson(result)
    }

    private fun getResourceSummary(pathSubstring: String, graph: ProjectGraph): String {
        if (pathSubstring.isBlank()) return "{}"
        
        val node = graph.resourceNodes.firstOrNull { it.path.contains(pathSubstring, ignoreCase = true) }
            ?: return "Resource not found matching: $pathSubstring"
            
        val summary = mapOf(
            "path" to node.path,
            "type" to node.type.name,
            "layer" to node.layer,
            "metadata" to node.metadata
        )
        return gson.toJson(summary)
    }
}
