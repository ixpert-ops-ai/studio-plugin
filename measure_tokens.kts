import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject

fun main() {
    val graphFile = File("C:\\Users\\dffrp\\Downloads\\project-graph_a\\project-graph.json")
    val gson = GsonBuilder().setPrettyPrinting().create()
    val json = gson.fromJson(graphFile.readText(), JsonObject::class.java)
    val files = json.getAsJsonObject("files")
    
    // 1. Extract nodes
    val allFiles = mutableListOf<Pair<String, String>>()
    for (entry in files.entrySet()) {
        val fileObj = entry.value.asJsonObject
        val className = fileObj.get("className")?.asString ?: ""
        val packageName = fileObj.get("packageName")?.asString ?: ""
        if (className.isNotEmpty()) {
            allFiles.add(Pair(className, packageName))
        }
    }
    
    // 2. Worst-case scenario logic
    val prefix = "APCMMSmpy"
    val track1Matches = allFiles.filter { it.first.startsWith(prefix, ignoreCase = true) }
    val track2Matches = allFiles.filter { !it.first.startsWith(prefix, ignoreCase = true) }.take(30)
    
    val unionCandidates = (track1Matches + track2Matches).distinct()
    
    println("Track 1 Matches ($prefix*): ${track1Matches.size}")
    println("Track 2 Matches: ${track2Matches.size}")
    println("Union Total Candidates: ${unionCandidates.size}")
    
    val candidatesStr = unionCandidates.joinToString("\n") { "${it.first} (${it.second})" }
    
    // 3. Assemble exact Prompt and Tool Schema
    val systemPrompt = """
        당신은 Anyframe Enterprise 프로젝트의 코드 변경 분석가입니다.
        아래 SR(요구사항)을 읽고, 변경이 시작되어야 할 핵심 진입점(Seed) 클래스를 선정하세요.
        반드시 제공된 `submit_seeds` 도구를 호출하여 결과를 제출하세요.
        
        ## 지침
        - `seedClasses`에는 최대 3~4개의 핵심 클래스명(패키지 제외)을 지정하세요.
        - `changeIntent`는 MODIFY, CREATE, DELETE 중 하나여야 합니다.
        - `layerHint`는 변경이 걸치는 계층(ENTITY, SERVICE, PRESENTATION 등)을 배열로 제공하세요.
        - `frontendRelevant`는 화면 변경 포함 여부(true/false)입니다.
        - `frontendRelevant`가 true이면, 관련될 가능성이 높은 Vue/React 파일명 키워드를 `frontendFileHints`에 포함하세요. SR 텍스트의 화면명을 영문 파일명으로 변환하세요. (예: '마이페이지' → 'MyPage', '장바구니' → 'Cart')
        - `reasoning`은 전체 요구사항의 요약과 함께, 각 대상 파일별 선정 사유를 반드시 "1. [파일명] - [사유]", "2. [파일명] - [사유]" 형식으로 번호를 매겨 상세히 작성하세요.
        - 중요: JSON 응답 생성 시, reasoning 필드 값 내부에 실제 줄바꿈 문자(\n)를 사용하지 마세요. 줄바꿈 대신 띄어쓰기나 마침표를 사용하세요.
        - Anyframe Enterprise 프레임워크 특징:
          - BIZ: 핵심 업무 로직 (보통 *BIZ 클래스)
          - SVC: 서비스 인터페이스(*SVC) 및 구현체(*SVCImpl)
          - DATA_ACCESS: DB 접근 객체 (보통 *DEM 또는 *DQM)
          - VO: Value Object (BVO, SVO, DVO 등으로 계층화됨)
          - BIZ_UTIL: 공통 로직 (보통 *Util)
    """.trimIndent()
    
    val srText = "APCMMSmpy 스마일페이 오류 수정"
    val userPrompt = """
        ## SR
        $srText

        ## 프로젝트 클래스 목록 (클래스명 (패키지명))
        $candidatesStr
    """.trimIndent()

    val requestBodyMap = mapOf(
        "model" to "Qwen/Qwen3.6-35B-A3B-FP8",
        "max_tokens" to 1,
        "messages" to listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        ),
        "tools" to listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "submit_seeds",
                    "description" to "요구사항 분석 결과(Seed 클래스 및 인텐트)를 제출합니다.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "seedClasses" to mapOf("type" to "array", "description" to "변경의 진입점이 되는 핵심 클래스 목록", "items" to mapOf("type" to "string")),
                            "changeIntent" to mapOf("type" to "string", "description" to "작업 의도", "enum" to listOf("MODIFY", "CREATE", "DELETE")),
                            "layerHint" to mapOf("type" to "array", "description" to "영향을 받는 계층 목록", "items" to mapOf("type" to "string")),
                            "frontendRelevant" to mapOf("type" to "boolean", "description" to "프론트엔드 연관 여부"),
                            "reasoning" to mapOf("type" to "string", "description" to "선정 근거"),
                            "frontendFileHints" to mapOf("type" to "array", "description" to "frontendRelevant가 true일 때, 관련될 가능성이 높은 프론트엔드 파일명 키워드 (예: MyPage, ProductDetail, Cart)", "items" to mapOf("type" to "string"))
                        ),
                        "required" to listOf("seedClasses", "changeIntent", "layerHint", "frontendRelevant", "reasoning")
                    )
                )
            )
        ),
        "tool_choice" to mapOf(
            "type" to "function",
            "function" to mapOf("name" to "submit_seeds")
        )
    )
    
    val requestBodyJson = gson.toJson(requestBodyMap)
    
    println("\n=== Sending Request to VLLM Server ===")
    val url = URL("http://vllm.ixpertops.cloud/v1/chat/completions")
    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.setRequestProperty("Content-Type", "application/json; utf-8")
    connection.setRequestProperty("Accept", "application/json")
    connection.doOutput = true
    
    try {
        connection.outputStream.use { os ->
            val input = requestBodyJson.toByteArray(Charsets.UTF_8)
            os.write(input, 0, input.size)
        }
        
        val responseCode = connection.responseCode
        println("Response Code: $responseCode")
        
        val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = inputStream.bufferedReader().use { it.readText() }
        
        val respJson = gson.fromJson(responseText, JsonObject::class.java)
        
        if (respJson.has("usage")) {
            val usage = respJson.getAsJsonObject("usage")
            val promptTokens = usage.get("prompt_tokens")?.asInt
            println("\n=== RESULT ===")
            println("prompt_tokens: $promptTokens (The True Ground Truth Token Count)")
        } else {
            println("No usage object found in response.")
            println(responseText)
        }
    } catch (e: Exception) {
        println("Error during HTTP request: ${e.message}")
    }
}
main()
