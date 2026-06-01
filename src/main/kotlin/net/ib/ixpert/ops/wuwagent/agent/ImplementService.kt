package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import java.nio.file.Path
import kotlin.io.path.readText

class ImplementService(
    private val llmClient: LLMClient,
    private val graph: ProjectGraph,
    private val mdRoot: Path,
    private val templateRoot: Path, // Path to resources/prompt
    private val sourceRoot: Path
) {
    private val logger = Logger.getInstance(ImplementService::class.java)

    /**
     * /implement 명령어의 메인 진입점.
     * 분석 대상 파일 목록(TargetFileSpec)을 받아 순차적으로 pseudo-code를 생성합니다.
     */
    fun implement(
        userRequirement: String,
        targetFiles: List<TargetFileSpec>,
        onProgress: ((String) -> Unit)? = null
    ): String {
        if (targetFiles.isEmpty()) {
            return "⚠️ 변경 대상 파일이 없습니다. `/analyze`를 먼저 실행해주세요."
        }

        val frameworkRules = when (graph.frameworkType) {
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_JPA -> 
                "- JPA Entity 작성 시 매핑 어노테이션(@Entity, @Table, @Column)을 정확히 명시하세요.\n" +
                "- JpaRepository 인터페이스만 생성하고, 추가 구현체는 꼭 필요할 때만 작성하세요.\n" +
                "- Entity 클래스에 @Builder와 @NoArgsConstructor(access = PROTECTED)가 함께 있으면 불변 패턴입니다.\n" +
                "  이 경우 setter를 만들지 말고, 기존 패턴을 따라 도메인 메서드(예: updateLowestPrice)를 작성하세요.\n" +
                "  또는 필드가 생성 시점에만 설정되는 경우 @Builder 필드로만 추가하세요."
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_MYBATIS, 
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_MVC_MYBATIS -> 
                "- MyBatis 환경이므로 Mapper 인터페이스와 XML 작성에 유의하세요.\n" +
                "- SQL 작성 시 파라미터 바인딩과 resultMap에 주의하세요."
            net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANYFRAME_AP -> 
                "- Anyframe 환경입니다. 인터페이스/구현체(Impl) 쌍을 작성하세요.\n" +
                "- DEM/DQM, BIZ, SVC 구조를 엄격히 준수하고 Layered SVO/BVO/DVO 규칙에 따라 DTO를 작성하세요."
            else -> 
                "- 프로젝트의 기존 계층형(Layered) 아키텍처 규칙을 따르세요."
        }
        
        val requirementType = ImplementContextBuilder.detectRequirementType(userRequirement)
        val similarRefs = if (requirementType != null) {
            ImplementContextBuilder.findSimilarImplementation(graph, requirementType, targetFiles, sourceRoot)
        } else emptyList()

        val fallbackGuideline = if (similarRefs.isEmpty() && requirementType == "FILE_DOWNLOAD") {
            """
            ## 다운로드 API 최소 가이드라인
            - 파일 다운로드(CSV/Excel/PDF) 기능의 Controller 메서드는 반환형을 `void`로 선언하세요.
            - `HttpServletResponse`를 파라미터로 받아 직접 스트림에 write하세요.
            - 뷰 이름(String)을 반환하지 마세요.
            """.trimIndent()
        } else ""

        val interfaceImplConstraint = if (graph.frameworkType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_MVC_MYBATIS || graph.frameworkType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.ANYFRAME_AP) {
            """
            ## ⚠️ Interface-Impl 제약 (절대 규칙)
            - ServiceImpl/DaoImpl 클래스에는 해당 Interface에 선언되지 않은 새로운 public 메서드를 추가하지 마세요.
            - 새로운 기능이 필요하면 반드시 Interface에 먼저 선언하고, Impl에서 구현하세요.
            - 이 파일의 Interface에 선행 단계에서 추가된 메서드 목록은 위의 "필수 호출 메서드"를 참고하세요.
            """.trimIndent()
        } else ""

        var systemPrompt = try {
            net.ib.ixpert.ops.wuwagent.prompt.PromptManager.loadPrompt("implement_template.md")
        } catch (e: Exception) {
            logger.warn("implement_template.md not found. Using fallback.", e)
            "당신은 주어진 요구사항에 맞춰 최적의 코드 변경 사항을 설계하는 시니어 소프트웨어 아키텍트입니다. 필요한 부분만 pseudo-code 블록([추가], [수정])으로 응답하세요.\n\n## Framework Specific Rules\n{{frameworkSpecificRules}}\n{{fallbackGuideline}}"
        }
        
        systemPrompt = systemPrompt.replace("{{frameworkSpecificRules}}", frameworkRules)
        systemPrompt = systemPrompt.replace("{{fallbackGuideline}}", fallbackGuideline)
        systemPrompt = systemPrompt.replace("{{interfaceImplConstraint}}", interfaceImplConstraint)

        // 2. 의존성 순서에 따른 간단한 정렬 (단순 구현: DTO/Entity -> Repository -> Service -> Controller)
        val sortedTargets = sortTargetsByLayer(targetFiles)
        
        val finalResult = StringBuilder()
        val header = "## 🛠️ 변경 지시서 (Pseudo-code)\n> LLM이 작성한 의사코드(pseudo-code) 기반의 변경 블록입니다. 코드를 프로젝트에 맞게 실제 적용해주세요.\n\n"
        finalResult.append(header)
        onProgress?.invoke(header)

        val previousSignatures = mutableListOf<String>()
        val addedFields = mutableMapOf<String, String>() // name -> type
        val signatureRegex = Regex("""(public|private|protected)?\s*[\w<>,\[\]?\s]+\s+\w+\([^)]*\)""")
        
        val entityModifiedFields = mutableSetOf<String>()
        val allRepoMethods = graph.files.values
            .filter { it.className.contains("Repository", ignoreCase = true) }
            .flatMap { it.methodNames }
            .toSet()

        // 3. 파일별 순차 처리
        for ((index, spec) in sortedTargets.withIndex()) {
            onProgress?.invoke("\n(${index + 1}/${sortedTargets.size}) `${spec.path}` 변경 지시서 생성 중...\n")
            
            // 컨텍스트 로딩
            val context = if (spec.type == "신규" || spec.type == "CREATE") {
                ImplementContextBuilder.buildCreateContext(spec.path, graph, mdRoot, sourceRoot, sortedTargets, similarRefs)
            } else {
                ImplementContextBuilder.buildModifyContext(spec.path, graph, mdRoot, sourceRoot, similarRefs)
            }

            val sourceFile = sourceRoot.resolve(spec.path)
            val sourceContent = if (sourceFile.toFile().exists()) sourceFile.toFile().readText() else ""

            val userPrompt = buildUserPrompt(userRequirement, spec, context, sortedTargets, previousSignatures)

            // [디버그 로그 1] LLM 호출 전 previousSignatures 확인
            onProgress?.invoke("\n🔍 [디버그: ${spec.path}] 주입될 previousSignatures 개수: ${previousSignatures.size}")
            if (previousSignatures.isNotEmpty()) {
                onProgress?.invoke("🔍 [디버그 내용]:\n${previousSignatures.joinToString("\n")}\n")
            }

            // LLM 호출
            val response = llmClient.chat(
                systemPrompt = systemPrompt,
                userCode = userPrompt
            )

            val responseText = response?.message?.content
            if (responseText.isNullOrBlank()) {
                val errorMsg = "### 파일: ${spec.path}\n> ⚠️ LLM 응답이 없습니다.\n\n"
                finalResult.append(errorMsg)
                onProgress?.invoke(errorMsg)
                continue
            }

            // 응답 파싱 및 검증
            var parsedResult = PseudoCodeParser.parse(spec.path, responseText, graph)
            
            // 타입 일관성 검증 (필드 추가)
            val fieldRegex = Regex("""private\s+([\w<>,\[\]?]+)\s+(\w+)\s*;""")
            for (i in parsedResult.blocks.indices) {
                var block = parsedResult.blocks[i]
                if (block.type == net.ib.ixpert.ops.wuwagent.agent.BlockType.ADD || block.type == net.ib.ixpert.ops.wuwagent.agent.BlockType.MODIFY) {
                    var modifiedContent = block.content
                    fieldRegex.findAll(block.content).forEach { match ->
                        val type = match.groupValues[1]
                        val name = match.groupValues[2]
                        val existingType = addedFields[name]
                        if (existingType != null && existingType != type) {
                            val newWarnings = parsedResult.warnings.toMutableList()
                            newWarnings.add("⚠️ 경고: 필드 '$name'의 타입이 파일 간 일치하지 않아 자동으로 '$existingType' 타입으로 교정했습니다. (이전: $existingType, LLM출력: $type)")
                            parsedResult = parsedResult.copy(warnings = newWarnings)
                            
                            // 자동 교정
                            val typeEscaped = Regex.escape(type)
                            val nameEscaped = Regex.escape(name)
                            modifiedContent = modifiedContent.replace(Regex("""private\s+$typeEscaped\s+$nameEscaped\s*;"""), "private $existingType $name;")
                        } else if (existingType == null) {
                            addedFields[name] = type
                        }
                    }
                    if (modifiedContent != block.content) {
                        val mutableBlocks = parsedResult.blocks.toMutableList()
                        mutableBlocks[i] = block.copy(content = modifiedContent)
                        parsedResult = parsedResult.copy(blocks = mutableBlocks)
                    }
                }
            }
            
            // 기존 코드 재출력 감지 및 제거
            var hasReOutput = false
            val filteredBlocks = parsedResult.blocks.filter { block ->
                if (block.type == net.ib.ixpert.ops.wuwagent.agent.BlockType.MODIFY && sourceContent.isNotBlank()) {
                    val normalize = { s: String -> s.replace(Regex("\\s+"), "") }
                    val normalizedBlock = normalize(block.content)
                    
                    val isContained = normalizedBlock.isNotBlank() && normalize(sourceContent).contains(normalizedBlock)
                    
                    // [디버그 로그 2] isUnchangedReOutput 판정 결과 확인
                    onProgress?.invoke("🔍 [디버그: 재출력 감지] block title: ${block.title}")
                    onProgress?.invoke("🔍   - 원본 포함 여부(contains): $isContained")
                    
                    if (isContained) {
                        hasReOutput = true
                        return@filter false
                    }
                }
                true
            }
            if (hasReOutput) {
                val warnings = parsedResult.warnings.toMutableList()
                warnings.add("⚠️ 경고: [수정] 블록이 기존 메서드와 동일하여 자동 제거했습니다. (불필요한 재출력)")
                parsedResult = parsedResult.copy(blocks = filteredBlocks, warnings = warnings)
            }
            
            // 혼합 블록(추가/수정 블록 간 중복 시그니처) 감지 및 제거
            val addSignatures = parsedResult.blocks
                .filter { it.type == net.ib.ixpert.ops.wuwagent.agent.BlockType.ADD }
                .flatMap { signatureRegex.findAll(it.content).map { match -> match.value.trim() }.toList() }
                .toSet()

            if (addSignatures.isNotEmpty()) {
                val extractMethodName = { sig: String -> sig.substringBefore("(").substringAfterLast(" ").trim() }
                val addMethodNames = addSignatures.map { extractMethodName(it) }.filter { it.isNotBlank() }.toSet()

                var hasMixedReOutput = false
                val mixedFilteredBlocks = parsedResult.blocks.filter { block ->
                    if (block.type == net.ib.ixpert.ops.wuwagent.agent.BlockType.MODIFY) {
                        val modifySignatures = signatureRegex.findAll(block.content).map { it.value.trim() }.toList()
                        val modifyMethodNames = modifySignatures.map { extractMethodName(it) }.filter { it.isNotBlank() }
                        if (modifyMethodNames.any { addMethodNames.contains(it) }) {
                            hasMixedReOutput = true
                            onProgress?.invoke("🔍 [디버그: 혼합 블록 감지] [수정] 블록에 이미 [추가]된 메서드명 포함됨. block title: ${block.title}")
                            return@filter false
                        }
                    }
                    true
                }
                if (hasMixedReOutput) {
                    val warnings = parsedResult.warnings.toMutableList()
                    warnings.add("⚠️ 경고: [수정] 블록 내에 [추가] 블록과 중복되는 메서드 선언이 감지되어 해당 [수정] 블록을 자동 제거했습니다. (혼합 블록 방지)")
                    parsedResult = parsedResult.copy(blocks = mixedFilteredBlocks, warnings = warnings)
                }
            }
            
            // Entity에서 변환된 setter(updateXxx)를 추출
            if (spec.path.lowercase().contains("entity") || spec.path.lowercase().contains("domain")) {
                parsedResult.blocks.forEach { block ->
                    val updateMatch = Regex("""\bupdate([A-Z]\w*)\s*\(""").findAll(block.content)
                    updateMatch.forEach { entityModifiedFields.add(it.groupValues[1]) }
                }
            }

            // Service/Controller 등에서 Entity의 setter 호출을 updateXxx로 일괄 변환 (주석/실행코드 무관)
            if (entityModifiedFields.isNotEmpty() && !spec.path.lowercase().contains("entity") && !spec.path.lowercase().contains("domain")) {
                var modifiedAny = false
                val mutableBlocks = parsedResult.blocks.toMutableList()
                for (i in mutableBlocks.indices) {
                    var content = mutableBlocks[i].content
                    entityModifiedFields.forEach { field ->
                        val setterRegex = Regex("""\bset$field\s*\(""")
                        if (setterRegex.containsMatchIn(content)) {
                            content = content.replace(setterRegex, "update$field(")
                            modifiedAny = true
                        }
                    }
                    if (content != mutableBlocks[i].content) {
                        mutableBlocks[i] = mutableBlocks[i].copy(content = content)
                    }
                }
                if (modifiedAny) {
                    val warnings = parsedResult.warnings.toMutableList()
                    warnings.add("⚠️ 경고: 선행 Entity 작업 내역을 바탕으로, 코드 내의 setter(setXxx) 호출을 'updateXxx' 도메인 메서드로 일괄 자동 변환했습니다.")
                    parsedResult = parsedResult.copy(blocks = mutableBlocks, warnings = warnings)
                }
            }

            // 규칙 5: 존재하지 않는 Repository 메서드 호출 주석(FIXME) 처리
            if (spec.path.lowercase().contains("service")) {
                var modifiedAny = false
                val mutableBlocks = parsedResult.blocks.toMutableList()
                val repoCallRegex = Regex("""\b\w+Repository\.([a-zA-Z0-9_]+)\s*\(""")
                for (i in mutableBlocks.indices) {
                    var content = mutableBlocks[i].content
                    val newLines = content.lines().map { line ->
                        var newLine = line
                        if (!line.trim().startsWith("//")) {
                            val matches = repoCallRegex.findAll(line)
                            var lineModified = false
                            for (match in matches) {
                                val methodName = match.groupValues[1]
                                // spring data jpa 기본 메서드 예외 처리
                                val defaultMethods = setOf("save", "saveAll", "findById", "existsById", "findAll", "findAllById", "count", "deleteById", "delete", "deleteAll")
                                if (!allRepoMethods.contains(methodName) && !defaultMethods.contains(methodName)) {
                                    lineModified = true
                                    break
                                }
                            }
                            if (lineModified) {
                                newLine = "// FIXME: $line"
                                modifiedAny = true
                            }
                        }
                        newLine
                    }
                    if (modifiedAny && newLines.joinToString("\n") != mutableBlocks[i].content) {
                        mutableBlocks[i] = mutableBlocks[i].copy(content = newLines.joinToString("\n"))
                    }
                }
                if (modifiedAny) {
                    val warnings = parsedResult.warnings.toMutableList()
                    if (!warnings.contains("⚠️ 경고: 존재하지 않는 Repository 메서드 호출이 감지되어 해당 실행 코드를 자동 주석(FIXME) 처리했습니다.")) {
                        warnings.add("⚠️ 경고: 존재하지 않는 Repository 메서드 호출이 감지되어 해당 실행 코드를 자동 주석(FIXME) 처리했습니다.")
                    }
                    parsedResult = parsedResult.copy(blocks = mutableBlocks, warnings = warnings)
                }
            }
            
            // 시그니처 및 이력 추출 후 누적
            val extractedBlocks = parsedResult.blocks.filter { it.type == BlockType.ADD || it.type == BlockType.MODIFY }
            val validBlocks = extractedBlocks.filter { block ->
                val isInvalidBlock = parsedResult.warnings.any { warning ->
                    block.targetMethod != null && warning.contains(block.targetMethod!!) && warning.contains("존재하지 않을 수 있습니다")
                }
                !isInvalidBlock
            }

            val fileHistory = StringBuilder("- [${spec.path.substringAfterLast("/")}] 완료: ${spec.description}\n")
            var hasHistory = false
            for (block in validBlocks) {
                val sigs = signatureRegex.findAll(block.content)
                    .map { it.value.trim() }
                    .filter { !it.matches(Regex(".*\\b(get|set|is)[A-Z].*")) }
                    .toList()
                if (sigs.isNotEmpty()) {
                    fileHistory.appendLine("  - 주요 시그니처: ${sigs.joinToString(", ")}")
                } else {
                    val title = block.title.lowercase()
                    if (!title.contains("getter") && !title.contains("setter")) {
                        fileHistory.appendLine("  - 작업 상세: ${block.title}")
                    }
                }
                hasHistory = true
            }
            if (hasHistory) {
                previousSignatures.add(fileHistory.toString().trimEnd())
            }
            
            // 결과 조합
            val formatted = PseudoCodeParser.formatResult(parsedResult) + "\n---\n"
            finalResult.append(formatted)
            onProgress?.invoke(formatted)
        }

        onProgress?.invoke("\n변경 지시서 생성이 완료되었습니다.")
        return finalResult.toString()
    }

    private fun buildUserPrompt(
        requirement: String,
        spec: TargetFileSpec,
        context: String,
        allTargets: List<TargetFileSpec>,
        previousSignatures: List<String>
    ): String {
        val otherTargets = allTargets.filter { it.path != spec.path }
        val targetsNote = if (otherTargets.isNotEmpty()) {
            "## 참고\n이 대상 파일 외에도 다음 연관 파일들이 별도 단계에서 순차적으로 처리될 예정입니다 (현재 응답에는 다른 파일의 코드를 포함하지 마세요):\n" +
            otherTargets.joinToString("\n") { "- ${it.path}" }
        } else {
            ""
        }
        
        val signaturesNote = if (previousSignatures.isNotEmpty()) {
            "## 선행 작업 이력 (중복 구현 금지)\n" +
            previousSignatures.joinToString("\n") + "\n\n" +
            "## ⚠️ 필수 호출 메서드 (이름·파라미터 변경 절대 금지)\n" +
            "선행 파일에서 아래 메서드들이 추가/선언되었습니다.\n" +
            "후행 파일에서 이 메서드를 호출할 때 반드시 아래 시그니처를 그대로 사용하세요.\n" +
            "메서드명을 임의로 바꾸거나 파라미터를 변경하면 컴파일 에러가 발생합니다.\n" +
            previousSignatures.joinToString("\n")
        } else {
            ""
        }

        return """
            ## 요구사항
            $requirement

            ## 대상 파일
            - 경로: ${spec.path}
            - 작업: ${spec.description}
            
            ## 현재 파일의 역할
            - ${spec.description}

            ## 파일 컨텍스트
            $context

            $targetsNote
            
            $signaturesNote
        """.trimIndent()
    }

    private fun sortTargetsByLayer(targets: List<TargetFileSpec>): List<TargetFileSpec> {
        // 간단한 휴리스틱 정렬
        return targets.sortedBy { spec ->
            val path = spec.path.lowercase()
            when {
                path.contains("entity") || path.contains("domain") -> 1
                path.contains("dto") || path.contains("vo") || path.contains("response") || path.contains("request") -> 2
                path.contains("repository") || path.contains("mapper") || path.contains("dao") -> 3
                path.contains("service") -> 4
                path.contains("controller") -> 5
                else -> 6
            }
        }
    }
}
