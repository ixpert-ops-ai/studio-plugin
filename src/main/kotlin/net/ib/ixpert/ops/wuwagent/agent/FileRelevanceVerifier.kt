package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

class FileRelevanceVerifier(
    private val llmClient: LLMClient,
    private val graph: ProjectGraph,
    private val mdRoot: Path
) {
    private val logger = Logger.getInstance(FileRelevanceVerifier::class.java)
    private val gson = Gson()

    data class VerificationResult(
        val path: String,
        val verdict: String, // REQUIRED, OPTIONAL, UNNECESSARY
        val reason: String
    )
    
    private data class SingleResponse(
        val verdict: String?,
        val reason: String?
    )

    fun verify(userQuery: String, candidates: List<TargetFileSpec>): List<TargetFileSpec> {
        if (candidates.isEmpty()) return candidates

        // CREATE(신규)는 검증 통과, MODIFY(수정)만 LLM 검증
        val (createSpecs, modifySpecs) = candidates.partition { it.type == "신규" }
        if (modifySpecs.isEmpty()) return candidates

        val allResults = mutableListOf<VerificationResult>()
        
        val fwType = graph.resolveFrameworkType()
        val frameworkRules = when (fwType) {
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_JPA -> 
                "- 프레임워크: SPRING_BOOT_JPA\n- JPA Entity 기반이므로 JpaRepository 인터페이스 위주로 변경이 일어납니다.\n- Controller가 단순 위임(Service 호출 → 결과 반환)만 하는 구조라면, Service/DTO 변경으로 충분한 경우 Controller는 UNNECESSARY로 판정하세요."
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_MVC_MYBATIS -> 
                "- 프레임워크: SPRING_MVC_MYBATIS\n- 이 프로젝트는 Service Interface + ServiceImpl 쌍을 사용합니다.\n- 새 기능 추가 시 반드시 Interface에 메서드 선언 → Impl에 구현이 필요하므로 둘 중 하나를 무시(UNNECESSARY)하면 안 됩니다.\n- DAO 계층은 Interface + DaoImpl(SqlSessionDaoSupport) 쌍을 사용합니다.\n- 새 쿼리가 필요하면 DaoInterface와 DaoImpl 모두 수정 대상이 됩니다."
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_MYBATIS -> 
                "- 프레임워크: SPRING_BOOT_MYBATIS\n- DAO 계층은 @Mapper 인터페이스를 사용합니다(DaoImpl 없음).\n- 새 쿼리가 필요하면 @Mapper 인터페이스와 XML 파일을 수정합니다."
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANYFRAME_AP -> 
                "- 프레임워크: ANYFRAME_AP\n- 이 프로젝트는 Anyframe 환경입니다. 인터페이스/구현체(Impl) 쌍을 작성하세요.\n- 새 기능 추가 시 반드시 Interface에 메서드 선언 → Impl에 구현이 필요하므로 둘 중 하나를 무시(UNNECESSARY)하면 안 됩니다.\n- DEM/DQM, BIZ, SVC 구조를 엄격히 준수하세요."
            else -> 
                "- 프레임워크: ${fwType.name}\n- 프로젝트의 기존 계층형(Layered) 아키텍처 규칙을 따르세요."
        }

        val systemMessage = """
            당신은 코드 수정 필요성을 판단하는 아키텍처 검증자입니다.
            파일의 실제 목적과 요구사항을 비교하여, 수정이 필요한지 독립적으로 판단하세요.
            
            ## 프로젝트 아키텍처 제약
$frameworkRules
        """.trimIndent()

        for (spec in modifySpecs) {
            val context = buildFileContext(spec.path, graph, mdRoot)
            val prompt = buildSinglePrompt(userQuery, spec, context)
            
            // Ollama client call
            val response = llmClient.chat(
                systemPrompt = systemMessage,
                userCode = prompt
            )
            
            val responseText = response?.message?.content ?: continue
            val parsed = parseSingleResponse(spec.path, responseText)
            if (parsed != null) {
                allResults.add(parsed)
            }
        }
        
        val keptModifySpecs = modifySpecs.mapNotNull { spec ->
            val res = allResults.find { it.path == spec.path }
            if (res != null && res.verdict != "UNNECESSARY") {
                if (res.verdict == "OPTIONAL") {
                    spec.copy(description = "${spec.description} [⚠️ 선택적 수정: ${res.reason}]")
                } else {
                    spec
                }
            } else null
        }
        
        if (keptModifySpecs.isEmpty()) {
            logger.warn("Stage 3: 전체 UNNECESSARY 판정 – Stage 2 결과 유지")
            return candidates
        }
        
        return keptModifySpecs + createSpecs
    }

    private fun buildSinglePrompt(userQuery: String, spec: TargetFileSpec, context: String): String {
        return buildString {
            appendLine("## 파일 수정 필요성 검증")
            appendLine("### 요구사항")
            appendLine(userQuery)
            appendLine()
            appendLine("### 대상 파일")
            appendLine("경로: ${spec.path}")
            appendLine("파일 분석 정보:")
            appendLine(context)
            appendLine("---")
            appendLine("### 판정 규칙")
            appendLine("1. 요구사항을 충족하기 위해 이 파일의 코드를 실제로 수정해야 하면 REQUIRED")
            appendLine("2. 수정하면 좋지만 필수는 아니면 OPTIONAL")
            appendLine("3. 이 파일을 수정할 필요가 없으면 UNNECESSARY")
            appendLine()
            appendLine("반드시 아래 JSON 형식으로만 응답하세요. 다른 설명은 출력하지 마세요.")
            appendLine("""
{
  "verdict": "REQUIRED | OPTIONAL | UNNECESSARY",
  "reason": "독립적 판단 근거 한 줄"
}
            """.trimIndent())
        }
    }

    private fun parseSingleResponse(path: String, response: String): VerificationResult? {
        val jsonText = extractJson(response)
        return try {
            val res = gson.fromJson(jsonText, SingleResponse::class.java)
            if (res?.verdict != null) {
                VerificationResult(path, res.verdict, res.reason ?: "")
            } else null
        } catch (e: JsonSyntaxException) {
            logger.error("Failed to parse single response: $jsonText", e)
            null
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf("{")
        val end = text.lastIndexOf("}")
        return if (start != -1 && end != -1 && end >= start) {
            text.substring(start, end + 1)
        } else {
            text
        }
    }

    private fun buildFileContext(path: String, graph: ProjectGraph, mdRoot: Path): String {
        val fileName = extractFileName(path)
        val mdFile = mdRoot.resolve("$fileName.md")
        val node = graph.files[path]
        
        if (mdFile.exists()) {
            return extractMdSections(mdFile, listOf(1, 3, 5), node)
        }
        
        if (node == null) return "정보 없음"
        return buildGraphContext(node)
    }

    private fun extractFileName(path: String): String {
        return path.substringAfterLast("/")
    }

    private fun extractMdSections(mdFile: Path, sections: List<Int>, node: FileNode?): String {
        val content = mdFile.readText()
        val lines = content.lines()
        val result = StringBuilder()
        
        var currentSection = 0
        var linesInSection = 0
        for (line in lines) {
            if (line.startsWith("## ")) {
                currentSection++
                linesInSection = 0
            }
            if (currentSection in sections) {
                if (currentSection == 3 && linesInSection > 10) {
                    if (linesInSection == 11) {
                        val methods = node?.methodNames ?: emptyList()
                        val getterSetterCount = methods.count { it.startsWith("get") || it.startsWith("set") || it.startsWith("is") }
                        val ratio = if (methods.isNotEmpty()) getterSetterCount.toDouble() / methods.size else 0.0
                        
                        if (ratio >= 0.7) {
                            result.appendLine("... (이하 생략 - 메서드 다수 존재, 주로 getter/setter로 추정)")
                        } else {
                            result.appendLine("... (이하 생략 - 메서드 다수 존재)")
                        }
                    }
                } else {
                    result.appendLine(line)
                }
                linesInSection++
            }
        }
        return result.toString().trim()
    }

    private fun summarizeMethods(methods: List<String>): String {
        if (methods.size <= 20) return "methods: ${methods.joinToString(", ")}"
        
        val getterSetterCount = methods.count { 
            it.startsWith("get") || it.startsWith("set") || it.startsWith("is") 
        }
        val ratio = getterSetterCount.toDouble() / methods.size
        
        return if (ratio > 0.7) {
            "methods: ${methods.size}개 (주로 getter/setter, 비즈니스 메서드 ${methods.size - getterSetterCount}개)"
        } else {
            val keyMethods = methods.filter { !it.startsWith("get") && !it.startsWith("set") && !it.startsWith("is") }
            "methods: ${keyMethods.joinToString(", ")} 외 getter/setter ${getterSetterCount}개"
        }
    }

    private fun buildGraphContext(node: FileNode): String {
        return buildString {
            appendLine("- fileType: ${node.fileType}, layer: ${node.layer}")
            appendLine("- className: ${node.className}")
            node.superClass?.let { appendLine("- superClass: $it") }
            appendLine("- ${summarizeMethods(node.methodNames)}")
            appendLine("- injections: ${node.injections.joinToString(", ").ifEmpty { "없음" }}")
            appendLine("- dependedBy: ${node.dependedBy.joinToString(", ").ifEmpty { "없음" }}")
            if (node.koreanComments.isNotEmpty()) {
                appendLine("- koreanComments:")
                node.koreanComments.take(5).forEach { appendLine("  - $it") }
            }
        }
    }
}
