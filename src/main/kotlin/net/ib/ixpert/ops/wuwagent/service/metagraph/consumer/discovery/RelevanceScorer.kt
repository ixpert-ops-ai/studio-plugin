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
            java.io.File("C:/Workspace/member-market/debug-all-files.txt").appendText("Evaluating: $path\n")
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
                // NameMatchScore for FileNode
                var nameMatchScore = 0
                if (keywords.directEnglish.any { eng -> fileNode.className.contains(eng, ignoreCase = true) }) {
                    nameMatchScore = 30
                } else if (keywords.translatedEnglish.any { eng -> fileNode.className.contains(eng, ignoreCase = true) }) {
                    nameMatchScore = 15
                } else if (keywords.weakTranslatedEnglish.any { eng -> fileNode.className.contains(eng, ignoreCase = true) }) {
                    nameMatchScore = 7
                }

                // MethodMatchScore
                var matchedMethods = 0
                fileNode.demMethods?.forEach { dm ->
                    val method = dm.methodName
                    if (keywords.verbs.any { verb -> method.contains(verb, ignoreCase = true) } ||
                        keywords.english.any { eng -> method.contains(eng, ignoreCase = true) }) {
                        matchedMethods++
                    }
                }
                val methodMatchScore = minOf(matchedMethods * 5, 20)

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

                // TypeBonusScore
                val typeBonusScore = when (fileNode.fileType) {
                    net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.BIZ -> 3
                    net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.SERVICE,
                    net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.DATA_ACCESS -> 2
                    net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.SERVICE_INTERFACE,
                    net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.VO -> 1
                    net.ib.ixpert.ops.wuwagent.service.metagraph.model.SpringFileType.BIZ_UTIL -> -1
                    else -> 0
                }

                // Feature Flag for Critical Chain Bonus (Unverified in Production)
                // TODO: Set to true once Condition 3 SR real-world validation is acquired
                val enableCriticalChainBonus = false

                var criticalChainBonus = 0
                if (enableCriticalChainBonus) {
                    if (step.via == "BIZ_TO_DATA_ACCESS") {
                        criticalChainBonus = 55 // Compensate for Hop 3 drop to 0, ensuring minScore (55) is met
                    } else if (step.via == "SERVICE_TO_REPO_OR_BIZ") {
                        criticalChainBonus = 20 // Compensate for Hop 2 drop to 40
                    }
                }

                val totalScore = hopScore + nameMatchScore + methodMatchScore + layerAlignScore + commentMatchScore + typeBonusScore + criticalChainBonus
                
                if (fileNode.className.contains("ACAMTBAPC005DEM") || fileNode.className.contains("Product") || path.contains("ProductCreateView")) {
                    java.io.File("C:/Workspace/member-market/debug-score.txt").appendText("BREAKDOWN for ${fileNode.className}: hopDistance=${step.hop}, hopScore=$hopScore, nameMatchScore=$nameMatchScore, methodMatchScore=$methodMatchScore, layerAlignScore=$layerAlignScore, commentMatchScore=$commentMatchScore, typeBonusScore=$typeBonusScore, criticalChainBonus=$criticalChainBonus -> totalScore=$totalScore\n")
                }
                
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
                if (keywords.directEnglish.any { eng -> fileName.contains(eng, ignoreCase = true) }) {
                    nameMatchScore = 30
                } else if (keywords.translatedEnglish.any { eng -> fileName.contains(eng, ignoreCase = true) }) {
                    nameMatchScore = 15
                } else if (keywords.weakTranslatedEnglish.any { eng -> fileName.contains(eng, ignoreCase = true) }) {
                    nameMatchScore = 7
                }
                
                // LayerAlignScore
                var layerAlignScore = 0
                if (seedResult.layerHint.any { layer -> resourceNode.layer.contains(layer, ignoreCase = true) || resourceNode.type.name.contains(layer, ignoreCase = true) }) {
                    layerAlignScore = 15
                }
                
                val totalScore = hopScore + nameMatchScore + layerAlignScore
                
                if (fileName.contains("Product") || fileName.contains("ProductCreateView")) {
                    java.io.File("C:/Workspace/member-market/debug-score.txt").appendText("BREAKDOWN for RESOURCE $fileName: hopDistance=${step.hop}, hopScore=$hopScore, nameMatchScore=$nameMatchScore, layerAlignScore=$layerAlignScore -> totalScore=$totalScore\n")
                }
                
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
        // 순수 아키텍처/레이어 접미사 (도메인 매칭에서 완전히 배제할 단어들)
        val stopWords = setOf(
            "controller", "service", "repository", "entity", "dto", "vo", "request", "response", 
            "mapper", "view", "page", "screen", "api", "impl", "config", "exception", "handler", 
            "util", "action", "svc", "svo", "dvo", "dao", "bo",
            "화면", "컨트롤러", "서비스", "레파지토리", "저장소", "엔티티", "디티오", "매퍼", 
            "액션", "페이지", "에이피아이", "구현체", "인터페이스"
        )
        
        // CRUD 범용 동사 목록 (여기서 파생된 영어 단어는 강등 처리됨)
        val crudVerbs = setOf("등록", "조회", "수정", "추가", "삭제", "변경", "목록", "상세")

        // 간단한 규칙 기반 키워드 추출 (추후 형태소 분석기 연동 가능)
        val directEnglish = Regex("[a-zA-Z]{3,}").findAll(text).map { it.value }.toMutableList()
        directEnglish.removeAll { stopWords.contains(it.lowercase()) }
        
        val translatedEnglish = mutableListOf<String>()
        val weakTranslatedEnglish = mutableListOf<String>()
        
        // 공백 기준 분리 후 어미/조사 단순 제거
        val words = text.split(Regex("\\s+"))
        val nouns = mutableListOf<String>()
        val verbs = mutableListOf<String>()

        for (word in words) {
            val cleanWord = word.replace(Regex("[^가-힣a-zA-Z0-9]"), "")
            if (cleanWord.length < 2) continue
            if (stopWords.contains(cleanWord.lowercase())) continue
            
            if (cleanWord.endsWith("한다") || cleanWord.endsWith("해라") || cleanWord.endsWith("추가") || cleanWord.endsWith("수정") || cleanWord.endsWith("삭제")) {
                verbs.add(cleanWord.replace("한다", "").replace("해라", ""))
            } else {
                nouns.add(cleanWord.replace("을", "").replace("를", "").replace("이", "").replace("가", "").replace("은", "").replace("는", ""))
            }
            
            // 한글 명사에 대한 영문 번역(도메인 사전) 추가
            val translated = dictionary.translate(cleanWord).filterNot { stopWords.contains(it.lowercase()) }
            if (crudVerbs.any { cleanWord.contains(it) }) {
                weakTranslatedEnglish.addAll(translated)
            } else {
                translatedEnglish.addAll(translated)
            }
        }

        // 특정 핵심 동사들을 추가 (수동 매핑)
        if (text.contains("추가") || text.contains("등록") || text.contains("생성")) verbs.add("create")
        if (text.contains("추가") || text.contains("등록") || text.contains("생성")) verbs.add("add")
        if (text.contains("수정") || text.contains("변경") || text.contains("업데이트")) verbs.add("update")
        if (text.contains("삭제") || text.contains("제거")) verbs.add("delete")
        if (text.contains("조회") || text.contains("검색") || text.contains("목록")) verbs.add("get")
        if (text.contains("조회") || text.contains("검색") || text.contains("목록")) verbs.add("find")

        return ExtractedKeywords(nouns, verbs, directEnglish, translatedEnglish, weakTranslatedEnglish)
    }

    data class ExtractedKeywords(
        val nouns: List<String>,
        val verbs: List<String>,
        val directEnglish: List<String>,
        val translatedEnglish: List<String>,
        val weakTranslatedEnglish: List<String> = emptyList()
    ) {
        // Method match 등의 오염을 방지하기 위해 english 프로퍼티에는 weakTranslatedEnglish를 포함하지 않습니다.
        // 범용 동사 파생어는 오직 NameMatchScore의 7점 매칭에만 사용됩니다.
        val english: List<String> get() = directEnglish + translatedEnglish
    }
}
