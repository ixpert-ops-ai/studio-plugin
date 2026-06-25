package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

data class ParsedImplementBlock(
    val type: BlockType,
    val title: String,
    val content: String,
    val targetMethod: String? = null // [수정], [삭제] 시 기입된 대상 메서드
)

enum class BlockType {
    ADD, MODIFY, DELETE, NO_CHANGE, CREATE, UNKNOWN
}

data class PseudoCodeResult(
    val filePath: String,
    val blocks: List<ParsedImplementBlock>,
    val warnings: List<String>
)

object PseudoCodeParser {

    /**
     * LLM의 응답에서 pseudo-code 블록들을 추출하고, 검증(Method 존재 여부, 호출부 누락 등)을 수행합니다.
     */
    fun parse(filePath: String, response: String, graph: ProjectGraph): PseudoCodeResult {
        val blocks = mutableListOf<ParsedImplementBlock>()
        val warnings = mutableListOf<String>()

        // 1. 헤더 기반 블록 파싱 (단순 분리)
        val lines = response.lines()
        var currentType = BlockType.UNKNOWN
        var currentTitle = ""
        val currentContent = StringBuilder()
        var currentTargetMethod: String? = null

        fun saveBlock() {
            if (currentType != BlockType.UNKNOWN) {
                blocks.add(
                    ParsedImplementBlock(
                        type = currentType,
                        title = currentTitle,
                        content = currentContent.toString().trim(),
                        targetMethod = currentTargetMethod
                    )
                )
            }
        }

        val headerRegex = Regex("""^#{0,6}\s*\*?\[(추가|수정|삭제|변경\s*없음|신규\s*생성)\]\*?\s*(.*)$""")

        // 대상 메서드 파싱 정규식: "- 대상 메서드:", "대상 요소:", "쿼리 ID:", "대상 섹션:" 등을 유연하게 매칭
        val targetMethodRegex = Regex("""^-?\s*(대상\s*메서드|대상\s*요소|대상\s*태그|타겟\s*쿼리|쿼리\s*ID|대상\s*함수|메서드명|대상\s*섹션)\s*:\s*`?(.*?)`?$""")

        for (line in lines) {
            val trimmed = line.trim()
            val match = headerRegex.find(trimmed)
            if (match != null) {
                saveBlock()
                val typeStr = match.groupValues[1].replace("\\s+".toRegex(), "")
                currentTitle = match.groupValues[2].trim()
                currentType = when (typeStr) {
                    "추가" -> BlockType.ADD
                    "수정" -> BlockType.MODIFY
                    "삭제" -> BlockType.DELETE
                    "변경없음" -> BlockType.NO_CHANGE
                    "신규생성" -> BlockType.CREATE
                    else -> BlockType.UNKNOWN
                }
                if (currentType == BlockType.NO_CHANGE) {
                    currentTitle = "변경 없음"
                }
                currentContent.clear()
                currentTargetMethod = null
            } else {
                if (currentType != BlockType.UNKNOWN) {
                    currentContent.appendLine(line)
                    // 대상 메서드 추출
                    val methodMatch = targetMethodRegex.find(trimmed)
                    if (methodMatch != null && (currentType == BlockType.MODIFY || currentType == BlockType.DELETE)) {
                        currentTargetMethod = methodMatch.groupValues[2].trim()
                    }
                }
            }
        }
        saveBlock()

        val noChangeBlock = blocks.find { it.type == BlockType.NO_CHANGE }
        if (noChangeBlock != null && blocks.size > 1) {
            val hasAddBlock = blocks.any { it.type == BlockType.ADD }
            val hasModifyBlock = blocks.any { it.type == BlockType.MODIFY }
            
            val fwType = graph.resolveFrameworkType()
            val canSkip = fwType.controllerCanSkip

            if (hasAddBlock || hasModifyBlock) {
                // [추가] 또는 [수정] 블록이 있으면 [변경 없음]을 폐기
                blocks.remove(noChangeBlock)
                warnings.add("💡 안내: [변경 없음]과 다른 수정 지시(추가/수정)가 동시 발견되어, 수정 지시를 우선 처리합니다.")
            } else {
                // 기존 규칙: 다른 수정 지시가 없으면 [변경 없음] 유지
                blocks.clear()
                blocks.add(noChangeBlock)
                warnings.add("💡 안내: 파일 내 [변경 없음] 블록이 발견되어, [변경 없음]으로 최종 처리되었습니다.")
            }
        }

        // 2. 파싱 실패(블록 없음) 감지
        if (blocks.isEmpty()) {
            warnings.add("파싱 실패: 지정된 헤더([추가], [수정] 등)를 찾을 수 없습니다.")
        }

        // 3. 검증 1: 메서드명 대조 (MODIFY, DELETE 블록 대상)
        val fileNode = graph.files[filePath]
        if (fileNode != null) {
            val existingMethods = fileNode.methodNames
            for (block in blocks) {
                if (block.type == BlockType.MODIFY || block.type == BlockType.DELETE) {
                    if (block.targetMethod != null) {
                        val matched = existingMethods.any { it.contains(block.targetMethod) || block.targetMethod.contains(it) }
                        if (!matched) {
                            warnings.add("⚠️ 경고: 대상 메서드 '${block.targetMethod}'가 기존 클래스의 메서드 목록에 존재하지 않을 수 있습니다.")
                        }
                    } else {
                        warnings.add("⚠️ 경고: [${block.type.name}] 블록에 대상 메서드가 명시되지 않았습니다.")
                    }
                }
            }
        }

        // 4. 검증 2: 호출부 누락 감지
        val addBlocks = blocks.filter { it.type == BlockType.ADD }
        val hasModify = blocks.any { it.type == BlockType.MODIFY }
        if (addBlocks.isNotEmpty() && !hasModify) {
            for (block in addBlocks) {
                val content = block.content
                val isStandalone = content.contains("@Override") ||
                    content.contains("@GetMapping") || content.contains("@PostMapping") || 
                    content.contains("@RequestMapping") ||
                    (content.trimEnd().endsWith(";") && !content.contains("{")) ||
                    (!content.contains("(") && content.contains(";")) ||
                    content.contains("@Column") ||
                    block.title.contains("필드") || block.title.contains("field") || 
                    block.title.contains("getter") || block.title.contains("setter")
                
                if (!isStandalone) {
                    warnings.add("⚠️ 경고: [추가] '${block.title}' 블록의 호출부가 없습니다.")
                }
            }
        }

        // 5. 검증 3: CREATE 컨벤션 확인
        if (blocks.any { it.type == BlockType.CREATE }) {
            val packageName = filePath.substringBeforeLast("/").replace("/", ".")
            val packageHeaderExists = blocks.any { it.content.contains("package $packageName") }
            if (!packageHeaderExists) {
                // Not strictly an error since they might not write the package line explicitly, but worth a warning if missing
                // warnings.add("⚠️ 경고: 신규 생성 패키지 경로($packageName)가 응답 코드에 누락되었거나 일치하지 않을 수 있습니다.")
            }
        }
        // 다른 파일 수정 시도 감지 (규칙 1 위반 방지)
        val fileNameWithoutExt = filePath.substringAfterLast("/").substringBeforeLast(".")
        
        // 소스 코드 로드 (필드 트리밍 및 어노테이션 확인 용도)
        var sourceCode: String? = null
        try {
            val sourceFile = java.nio.file.Path.of(graph.projectRoot, filePath).toFile()
            if (sourceFile.exists()) {
                sourceCode = sourceFile.readText()
            }
        } catch (e: Exception) {
            // 무시
        }
        
        val iterator = blocks.listIterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            var shouldRemove = false
            
            // 규칙 1: 메타 텍스트 검사 대신 실제 코드 내 외래 타입 선언 검사
            val isJavaOrKotlin = filePath.endsWith(".java") || filePath.endsWith(".kt")
            if (isJavaOrKotlin) {
                // 마크다운 코드 블록 내부만 추출
                var codeBody = block.content
                val matcher = Regex("```[a-zA-Z]*\n([\\s\\S]*?)```").find(block.content)
                if (matcher != null) {
                    codeBody = matcher.groupValues[1]
                }
                
                val foreignDeclaration = Regex(
                    """\b(?:public\s+|private\s+|protected\s+)?(?:abstract\s+)?(?:class|interface|enum|@interface)\s+(\w+)"""
                ).findAll(codeBody)
                    .map { it.groupValues[1] }
                    .any { declaredName ->
                        !declaredName.equals(fileNameWithoutExt, ignoreCase = true)
                    }

                if (foreignDeclaration) {
                    warnings.add("⚠️ 경고: 대상 파일($fileNameWithoutExt) 외의 타입 선언이 감지되어 다른 파일의 코드일 가능성이 있습니다. 블록('${block.title}')의 내용을 검토해주세요. (규칙 1 위반 의심)")
                }
            }
            
            var currentBlockContent = block.content
            var modified = false
            
            // 주석 내 setter -> updateXxx 변환
            val setterRegex = Regex("""\bset([A-Z]\w*)\s*\(""")
            var replacedLines = currentBlockContent.lines().map { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    val newLine = setterRegex.replace(line) { "update${it.groupValues[1]}(" }
                    if (newLine != line) modified = true
                    newLine
                } else {
                    line
                }
            }
            
            if (modified && !warnings.contains("⚠️ 경고: 주석 내의 setter(setXxx) 호출을 'updateXxx' 도메인 메서드로 자동 변환했습니다.")) {
                warnings.add("⚠️ 경고: 주석 내의 setter(setXxx) 호출을 'updateXxx' 도메인 메서드로 자동 변환했습니다.")
            }
            
            // 주석 블록 최대 줄 수 제한 (3줄 초과 시 요약)
            val newLines = mutableListOf<String>()
            var i = 0
            var commentBlockModified = false
            while (i < replacedLines.size) {
                if (replacedLines[i].trim().startsWith("//")) {
                    var commentEndIndex = i
                    while (commentEndIndex + 1 < replacedLines.size && replacedLines[commentEndIndex + 1].trim().startsWith("//")) {
                        commentEndIndex++
                    }
                    val commentCount = commentEndIndex - i + 1
                    if (commentCount > 3) {
                        newLines.add(replacedLines[i])
                        newLines.add(replacedLines[i+1])
                        val indent = replacedLines[i].takeWhile { it.isWhitespace() }
                        newLines.add("$indent// ... (이하 기존 로직 생략)")
                        i = commentEndIndex + 1
                        commentBlockModified = true
                        continue
                    }
                }
                newLines.add(replacedLines[i])
                i++
            }
            
            if (commentBlockModified) {
                currentBlockContent = newLines.joinToString("\n")
                modified = true
                if (!warnings.contains("⚠️ 경고: 주석 블록이 3줄을 초과하여 핵심 내용만 남기고 요약 처리했습니다.")) {
                    warnings.add("⚠️ 경고: 주석 블록이 3줄을 초과하여 핵심 내용만 남기고 요약 처리했습니다.")
                }
            } else {
                currentBlockContent = replacedLines.joinToString("\n")
            }
            
            // [추가] 블록 라인 수 감지 및 트리밍
            if (block.type == BlockType.ADD) {
                val fieldLines = currentBlockContent.lines().count { it.trim().matches(Regex("""(private|protected|public)\s+[\w<>,\[\]?]+\s+\w+\s*;""")) }
                if (fieldLines >= 3) {
                    warnings.add("⚠️ 경고: [추가] 블록('${block.title}')에 기존 필드 전체가 재출력되었을 수 있습니다. 새로 추가되는 최소한의 필드만 있는지 확인하세요. (규칙 2, 6 위반 의심)")
                }
                
                // 소스코드가 있다면 기존 필드 자동 트리밍
                if (sourceCode != null) {
                    val existingFields = Regex("""(private|protected|public)\s+[\w<>,\[\]?]+\s+(\w+)\s*;""")
                        .findAll(sourceCode)
                        .map { it.groupValues[2] }
                        .toSet()
                    
                    val lines = currentBlockContent.lines().toMutableList()
                    var trimmed = false
                    val lineIter = lines.iterator()
                    while (lineIter.hasNext()) {
                        val line = lineIter.next()
                        val fieldMatch = Regex("""(private|protected|public)\s+[\w<>,\[\]?]+\s+(\w+)\s*;""").find(line)
                        if (fieldMatch != null) {
                            val fieldName = fieldMatch.groupValues[2]
                            if (existingFields.contains(fieldName)) {
                                lineIter.remove()
                                trimmed = true
                            }
                        }
                    }
                    if (trimmed) {
                        currentBlockContent = lines.joinToString("\n")
                        modified = true
                        if (!warnings.contains("⚠️ 경고: [추가] 블록에 기존 필드가 포함되어 있어 자동으로 트리밍했습니다.")) {
                            warnings.add("⚠️ 경고: [추가] 블록에 기존 필드가 포함되어 있어 자동으로 트리밍했습니다.")
                        }
                    }
                }
            }
            
            if (modified) {
                iterator.set(block.copy(content = currentBlockContent))
            }
        }
        // 6. 후처리: Entity 파일에 대한 잘못된 Getter/Setter 생성 방지 및 경고
        if (fileNode?.fileType == net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.ENTITY) {
            try {
                if (sourceCode != null) {
                    val hasGetter = sourceCode.contains("@Getter") || sourceCode.contains("@Data")
                    val hasBuilder = sourceCode.contains("@Builder")
                    val hasProtectedNoArgs = sourceCode.contains("PROTECTED")

                    for (i in blocks.indices) {
                        val block = blocks[i]
                        if (block.type == BlockType.ADD || block.type == BlockType.MODIFY) {
                            var newContent = block.content
                            
                            // 1. Getter 자동 제거
                            if (hasGetter) {
                                val getterRegex = Regex("""(public|protected|private)?\s+[\w<>,\[\]?\s]+\s+get\w+\s*\([^)]*\)\s*\{[^}]*\}""")
                                if (getterRegex.containsMatchIn(newContent)) {
                                    newContent = newContent.replace(getterRegex, "").trim()
                                    if (!warnings.contains("⚠️ 경고: 원본 Entity에 @Getter가 존재하여 수동 작성된 Getter 블록을 자동 제거했습니다.")) {
                                        warnings.add("⚠️ 경고: 원본 Entity에 @Getter가 존재하여 수동 작성된 Getter 블록을 자동 제거했습니다.")
                                    }
                                }
                            }
                            
                            // 2. Setter 자동 변환 (updateXxx)
                            if (hasBuilder && hasProtectedNoArgs) {
                                val setterRegex = Regex("""(public|protected|private)?\s+void\s+set(\w+)\s*\(([^)]+)\)\s*\{([^}]*)\}""")
                                if (setterRegex.containsMatchIn(newContent)) {
                                    newContent = setterRegex.replace(newContent) { matchResult ->
                                        val mod = matchResult.groupValues[1].let { if (it.isNotBlank()) "$it " else "" }
                                        val name = matchResult.groupValues[2]
                                        val args = matchResult.groupValues[3]
                                        val body = matchResult.groupValues[4]
                                        "${mod}void update$name($args) { $body }"
                                    }
                                    if (!warnings.contains("⚠️ 경고: 해당 Entity는 불변 패턴(Builder+Protected NoArgs)입니다. LLM이 생성한 setter(setXxx)를 'updateXxx' 도메인 메서드로 자동 변환했습니다.")) {
                                        warnings.add("⚠️ 경고: 해당 Entity는 불변 패턴(Builder+Protected NoArgs)입니다. LLM이 생성한 setter(setXxx)를 'updateXxx' 도메인 메서드로 자동 변환했습니다.")
                                    }
                                }
                            }
                            
                            if (newContent != block.content) {
                                blocks[i] = block.copy(content = newContent)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore file read errors
            }
        }
        
        // 빈 블록 제거 (내용이 없거나 공백만 있는 경우) - 모든 파일 타입에 적용
        val finalIterator = blocks.listIterator()
        while (finalIterator.hasNext()) {
            val block = finalIterator.next()
            val codeLines = block.content.lines().filter { 
                val t = it.trim()
                !t.startsWith("-") && !t.startsWith("```") && t.isNotEmpty()
            }
            if ((block.type == BlockType.ADD || block.type == BlockType.MODIFY) && codeLines.isEmpty()) {
                finalIterator.remove()
            }
        }

        return PseudoCodeResult(filePath, blocks, warnings)
    }

    /**
     * 파싱 결과를 Markdown 텍스트로 재조립합니다.
     */
    fun formatResult(result: PseudoCodeResult): String {
        val sb = StringBuilder()
        sb.appendLine("### 파일: ${result.filePath}")
        if (result.warnings.isNotEmpty()) {
            sb.appendLine()
            result.warnings.forEach { sb.appendLine("> $it") }
            sb.appendLine()
        }
        for (block in result.blocks) {
            val header = when (block.type) {
                BlockType.ADD -> "#### [추가] ${block.title}"
                BlockType.MODIFY -> "#### [수정] ${block.title}"
                BlockType.DELETE -> "#### [삭제] ${block.title}"
                BlockType.NO_CHANGE -> "#### [변경 없음]"
                BlockType.CREATE -> "#### [신규 생성] ${block.title}"
                BlockType.UNKNOWN -> "#### [기타]"
            }
            sb.appendLine(header)
            sb.appendLine(block.content)
            sb.appendLine()
        }
        return sb.toString().trim()
    }
}
