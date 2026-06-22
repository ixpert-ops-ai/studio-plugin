package net.ib.ixpert.ops.wuwagent.agent.stage0

object SrQualityAnalyzer {

    fun analyze(input: SrInput): QualityIndicators {
        val fullText = "${input.title} ${input.description} ${input.additionalContext.orEmpty()}"
        
        return QualityIndicators(
            descriptionLength = input.description.length,
            hasFilePaths = FILE_PATH_PATTERN.containsMatchIn(fullText),
            filePathCount = FILE_PATH_PATTERN.findAll(fullText).count(),
            hasCodeSnippets = CODE_SNIPPET_PATTERN.containsMatchIn(fullText),
            hasTechnicalTerms = extractTechnicalTerms(fullText).isNotEmpty(),
            technicalTerms = extractTechnicalTerms(fullText),
            hasExpectedBehavior = EXPECTED_BEHAVIOR_KEYWORDS.any { fullText.contains(it) },
            hasCurrentBehavior = CURRENT_BEHAVIOR_KEYWORDS.any { fullText.contains(it) }
        )
    }

    private val FILE_PATH_PATTERN = Regex(
        """(?:src|main|java|kotlin|resources|views|components|api|domain|service)[\\/][\w\\/.-]+\.\w+"""
    )
    
    private val CODE_SNIPPET_PATTERN = Regex(
        """(?:```|@\w+|public\s+\w+|private\s+\w+|function\s+\w+|def\s+\w+|const\s+\w+)"""
    )
    
    private val EXPECTED_BEHAVIOR_KEYWORDS = listOf(
        "되어야", "변경해야", "추가해야", "표시되도록", "동작하도록",
        "반환해야", "응답해야", "리다이렉트", "should", "expected"
    )
    
    private val CURRENT_BEHAVIOR_KEYWORDS = listOf(
        "현재는", "기존에는", "지금은", "현 상태", "as-is",
        "현재 동작", "기존 동작", "currently"
    )
    
    private fun extractTechnicalTerms(text: String): List<String> {
        val patterns = listOf(
            Regex("""\b[A-Z][a-z]+(?:[A-Z][a-z]+)+\b"""),       // CamelCase
            Regex("""\b[a-z]+(?:_[a-z]+)+\b"""),                 // snake_case
            Regex("""@\w+"""),                                    // annotations
            Regex("""\b(?:GET|POST|PUT|DELETE|PATCH)\s+/[\w/{}]+""")  // endpoints
        )
        return patterns.flatMap { it.findAll(text).map { m -> m.value }.toList() }.distinct()
    }
}
