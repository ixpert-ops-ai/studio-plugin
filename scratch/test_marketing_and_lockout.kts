import com.google.gson.GsonBuilder
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.model.*
import net.ib.ixpert.ops.wuwagent.agent.FileRelevanceVerifier
import java.io.File
import java.nio.file.Paths

// Dummy LLMClient
val dummyClient = object : LLMClient {
    override fun chat(
        systemPrompt: String,
        userCode: String,
        maxTokens: Int?,
        onChunk: ((String) -> Unit)?
    ): OllamaChatResponse? {
        return OllamaChatResponse(model = "dummy", createdAt = "", message = OllamaMessage("assistant", ""), done = true)
    }
    
    override fun chatWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int?,
        tools: List<ToolDefinition>?,
        toolChoice: Any?
    ): ChatCompletionResponse? = null

    override fun fetchModels(baseUrl: String, apiKey: String): List<String>? = listOf("dummy")
}

val graphFile = File("C:/Workspace/member-market/.meta/project-graph.json")
val gson = GsonBuilder().disableHtmlEscaping().create()
val graph = gson.fromJson(graphFile.readText(Charsets.UTF_8), ProjectGraph::class.java)

// TC-01: Marketing Consent
val tc01Primary = "[TC-01] Marketing Consent"
val tc01Secondary = "마케팅 수신 동의 여부를 회원가입 시 받을 수 있도록 추가하고 마이페이지에서 수정가능하도록 한다."

val srText01 = "\$tc01Primary\n\$tc01Secondary"
val seedResult01 = LlmSeedSelector(dummyClient).selectSeeds(srText01, graph)
// Note: Since dummyClient returns empty, LlmSeedSelector will fallback.
// Let's use ScopeSelector directly to simulate Scope Filtering

val scope01 = ScopeSelector.buildSubMetaGraph(graph, listOf("member-market-api/src/main/java/com/membermarket/api/member"))
println("TC-01 Scope Files:")
scope01.files.keys.forEach { println(" - \$it") }
println("Contains MemberController.java: \${scope01.files.keys.any { it.endsWith("MemberController.java") }}")

// TC-03: Account Lockout
val tc03Primary = "[TC-03] Account Lockout"
val tc03Secondary = "로그인 5회 실패 시 계정을 잠금 처리한다."

val scope03 = ScopeSelector.buildSubMetaGraph(graph, listOf("member-market-api/src/main/java/com/membermarket/api/auth", "member-market-api/src/main/java/com/membermarket/domain/member"))
println("\nTC-03 Scope Files:")
scope03.files.keys.forEach { println(" - \$it") }
println("Contains Member.java: \${scope03.files.keys.any { it.endsWith("Member.java") }}")
println("Contains AuthService.java: \${scope03.files.keys.any { it.endsWith("AuthService.java") }}")
