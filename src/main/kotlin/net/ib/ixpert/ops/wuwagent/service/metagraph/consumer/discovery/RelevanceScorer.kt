package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable

class RelevanceScorer(
    private val graph: ProjectGraphQueryable,
    private val fileLimit: Int = 30,
    private val minScore: Int = 50
) {

    fun scoreAndFilter(
        srText: String,
        expandedFiles: Map<String, ExpansionStep>,
        seedResult: SeedSelectionResult
    ): List<ScoredFile> {
        // 1. SR 키워드 추출 (간단한 명사/동사/영문 형태)
        val keywords = extractKeywords(srText)
        
        val scoredFiles = mutableListOf<ScoredFile>()

        for ((path, step) in expandedFiles) {
            val fileNode = graph.files[path]
            
            // HopScore (API_ENDPOINT_FALLBACK/INJECTION_FALLBACK은 점수 하향)
            val isFallback = step.via == "API_ENDPOINT_FALLBACK" || step.via == "INJECTION_FALLBACK"
            val hopScore = if (isFallback) {
                30 // Fallback으로 발견된 파일은 보험용이므로 낮은 기본 점수
            } else {
                when (step.hop) {
                    0 -> 100
                    1 -> 70
                    2 -> 40
                    else -> 0
                }
            }

            if (fileNode != null) {
                // NameMatchScore
                var nameMatchScore = 0
                if (keywords.nouns.any { fileNode.className.contains(it, ignoreCase = true) }) {
                    nameMatchScore = 20
                } else if (keywords.english.any { fileNode.className.contains(it, ignoreCase = true) }) {
                    nameMatchScore = 20
                }

                // MethodMatchScore
                var methodMatchScore = 0
                val matchedMethods = fileNode.methodNames.count { method ->
                    keywords.verbs.any { verb -> method.contains(verb, ignoreCase = true) } ||
                    keywords.english.any { eng -> method.contains(eng, ignoreCase = true) }
                }
                methodMatchScore = minOf(matchedMethods * 5, 20)

                // LayerAlignScore
                var layerAlignScore = 0
                if (seedResult.layerHint.any { layer -> fileNode.layer.name.contains(layer, ignoreCase = true) || fileNode.fileType.name.contains(layer, ignoreCase = true) }) {
                    layerAlignScore = 15
                }

                // CommentMatchScore
                var commentMatchScore = 0
                if (fileNode.koreanComments.any { comment ->
                    keywords.nouns.any { noun -> comment.contains(noun) } ||
                    keywords.verbs.any { verb -> comment.contains(verb) }
                }) {
                    commentMatchScore = 10
                }

                val totalScore = hopScore + nameMatchScore + methodMatchScore + layerAlignScore + commentMatchScore

                if (totalScore >= minScore) {
                    scoredFiles.add(
                        ScoredFile(
                            path = path,
                            className = fileNode.className,
                            fileType = fileNode.fileType.name,
                            layer = fileNode.layer.name,
                            score = totalScore,
                            discoveryReason = step.via,
                            hopDistance = step.hop,
                            fromPath = step.from
                        )
                    )
                }
            } else {
                val resourceNode = graph.resourceNodes.find { it.path == path } ?: continue
                
                // NameMatchScore for ResourceNode (using filename)
                val fileName = path.substringAfterLast("/")
                var nameMatchScore = 0
                if (keywords.english.any { eng -> fileName.contains(eng, ignoreCase = true) }) {
                    nameMatchScore = 30
                }
                
                // LayerAlignScore
                var layerAlignScore = 0
                if (seedResult.layerHint.any { layer -> resourceNode.layer.contains(layer, ignoreCase = true) || resourceNode.type.name.contains(layer, ignoreCase = true) }) {
                    layerAlignScore = 15
                }
                
                val totalScore = hopScore + nameMatchScore + layerAlignScore
                
                if (totalScore >= minScore) {
                    scoredFiles.add(
                        ScoredFile(
                            path = path,
                            className = fileName,
                            fileType = resourceNode.type.name,
                            layer = resourceNode.layer,
                            score = totalScore,
                            discoveryReason = step.via,
                            hopDistance = step.hop,
                            fromPath = step.from
                        )
                    )
                }
            }
        }

        // 2. 정렬 및 필터링
        // Score 내림차순, 동일하면 hop 낮은 순 (오름차순), 그래도 같으면 riskScore 오름차순
        return scoredFiles.sortedWith(compareByDescending<ScoredFile> { it.score }
            .thenBy { it.hopDistance }
            .thenBy { graph.files[it.path]?.riskAssessment?.riskScore ?: 0 })
            .take(fileLimit)
    }

    private val dictionary by lazy { DomainDictionary.load(graph) }

    private fun extractKeywords(text: String): ExtractedKeywords {
        // 간단한 규칙 기반 키워드 추출 (추후 형태소 분석기 연동 가능)
        val english = Regex("[a-zA-Z]{3,}").findAll(text).map { it.value }.toMutableList()
        
        // 공백 기준 분리 후 어미/조사 단순 제거
        val words = text.split(Regex("\\s+"))
        val nouns = mutableListOf<String>()
        val verbs = mutableListOf<String>()

        for (word in words) {
            val cleanWord = word.replace(Regex("[^가-힣a-zA-Z0-9]"), "")
            if (cleanWord.length < 2) continue
            
            if (cleanWord.endsWith("한다") || cleanWord.endsWith("해라") || cleanWord.endsWith("추가") || cleanWord.endsWith("수정") || cleanWord.endsWith("삭제")) {
                verbs.add(cleanWord.replace("한다", "").replace("해라", ""))
            } else {
                nouns.add(cleanWord.replace("을", "").replace("를", "").replace("이", "").replace("가", "").replace("은", "").replace("는", ""))
            }
            
            // 한글 명사에 대한 영문 번역(도메인 사전) 추가
            val translated = dictionary.translate(cleanWord)
            english.addAll(translated)
        }

        // 특정 핵심 동사들을 추가 (수동 매핑)
        if (text.contains("추가") || text.contains("등록") || text.contains("생성")) verbs.add("create")
        if (text.contains("추가") || text.contains("등록") || text.contains("생성")) verbs.add("add")
        if (text.contains("수정") || text.contains("변경") || text.contains("업데이트")) verbs.add("update")
        if (text.contains("삭제") || text.contains("제거")) verbs.add("delete")
        if (text.contains("조회") || text.contains("검색") || text.contains("목록")) verbs.add("get")
        if (text.contains("조회") || text.contains("검색") || text.contains("목록")) verbs.add("find")

        return ExtractedKeywords(nouns, verbs, english)
    }

    data class ExtractedKeywords(
        val nouns: List<String>,
        val verbs: List<String>,
        val english: List<String>
    )
}
