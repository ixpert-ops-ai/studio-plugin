package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraphQueryable

class RelevanceScorer(
    private val graph: ProjectGraphQueryable,
    private val fileLimit: Int = 30,
    private val minScore: Int = 10
) {

    fun scoreAndFilter(
        srText: String,
        expandedFiles: Map<String, ExpansionStep>,
        seedResult: SeedSelectionResult
    ): List<ScoredFile> {
        // 1. SR 키워드 추출 (간단한 명사/동사/영문 형태)
        val keywords = extractKeywords(srText)

        // LLM이 명시적으로 지목한 파일 판별 헬퍼 (FileNode/ResourceNode 양 분기에서 공유)
        fun protectionReason(name: String): String? {
            if (seedResult.judgePicks.any { it.equals(name, ignoreCase = true) }) return "Judge Pick"
            if (seedResult.seedClasses.any { it.equals(name, ignoreCase = true) }) return "Seed Class"
            if (seedResult.frontendRelevant &&
                (seedResult.frontendFileHints ?: emptyList()).any { hint ->
                    val baseName = name.substringAfterLast('/').substringBeforeLast('.')
                    hint.length >= 4 && (baseName.equals(hint, ignoreCase = true) || baseName.startsWith(hint, ignoreCase = true))
                }
            ) return "Frontend Hint"
            return null
        }
        
        val totalNodes = graph.files.size + graph.resourceNodes.size
        val maxIdf = Math.log(totalNodes.toDouble()) // max possible IDF (when df=1)
        
        val dfCache = mutableMapOf<String, Int>()
        fun getDf(token: String): Int {
            return dfCache.getOrPut(token.lowercase()) {
                val lowerToken = token.lowercase()
                val fileMatches = graph.files.values.count { it.className.contains(lowerToken, ignoreCase = true) }
                val resourceMatches = graph.resourceNodes.count { it.path.substringAfterLast("/").contains(lowerToken, ignoreCase = true) }
                fileMatches + resourceMatches
            }
        }
        
        fun getIdfWeight(token: String): Double {
            val df = maxOf(1, getDf(token))
            return Math.log(totalNodes.toDouble() / df) / maxIdf
        }
        
        val englishTokenWeights = keywords.english.associateWith { getIdfWeight(it) }
        val verbTokenWeights = keywords.verbs.associateWith { getIdfWeight(it) }
        
        val scoredFiles = mutableListOf<ScoredFile>()
        val seedDepCounts = mutableMapOf<String, Int>()

        for ((path, step) in expandedFiles) {
            val fileNode = graph.files[path]
            
            if (fileNode != null) {
                // HopScore
                val hopScore = when (step.hop) {
                    0 -> 40
                    1 -> 30
                    2 -> 15
                    else -> 0
                }
                
                // NameMatchScore for FileNode
                var nameMatchScore = 0.0
                val directMatches = keywords.directEnglish.filter { eng -> fileNode.className.contains(eng, ignoreCase = true) }
                val transMatches = keywords.translatedEnglish.filter { eng -> fileNode.className.contains(eng, ignoreCase = true) }
                val weakMatches = keywords.weakTranslatedEnglish.filter { eng -> fileNode.className.contains(eng, ignoreCase = true) }

                if (directMatches.isNotEmpty()) {
                    nameMatchScore = 50.0 * directMatches.maxOf { getIdfWeight(it) }
                } else if (transMatches.isNotEmpty()) {
                    nameMatchScore = 30.0 * transMatches.maxOf { getIdfWeight(it) }
                } else if (weakMatches.isNotEmpty()) {
                    nameMatchScore = 15.0 * weakMatches.maxOf { getIdfWeight(it) }
                }

                if (directMatches.isNotEmpty() || transMatches.isNotEmpty() || weakMatches.isNotEmpty()) {
                    println("[NAMEMATCH-DIAG] File: ${fileNode.className} | Direct: $directMatches, Trans: $transMatches, Weak: $weakMatches | Score: ${String.format("%.1f", nameMatchScore)}")
                }

                // MethodMatchScore
                val matchedTokens = mutableSetOf<String>()
                fileNode.demMethods?.forEach { dm ->
                    val method = dm.methodName
                    val verbMatches = keywords.verbs.filter { verb -> method.contains(verb, ignoreCase = true) }
                    matchedTokens.addAll(verbMatches)
                    val engMatches = keywords.english.filter { eng -> method.contains(eng, ignoreCase = true) }
                    matchedTokens.addAll(engMatches)
                }
                var methodScoreSum = 0.0
                matchedTokens.forEach { token ->
                    val weight = verbTokenWeights[token] ?: englishTokenWeights[token] ?: 0.0
                    methodScoreSum += 10.0 * weight
                }
                val methodMatchScore = minOf(methodScoreSum, 30.0)
                
                // LayerAlignScore
                var layerAlignScore = 0
                if (seedResult.layerHint.any { layer -> fileNode.layer.name.contains(layer, ignoreCase = true) || fileNode.fileType.name.contains(layer, ignoreCase = true) }) {
                    layerAlignScore = 15
                }

                // CommentMatchScore
                var commentMatchScore = 0.0
                if (fileNode.koreanComments.any { comment ->
                    keywords.nouns.any { noun -> comment.contains(noun) } ||
                    keywords.verbs.any { verb -> comment.contains(verb) }
                }) {
                    commentMatchScore = 20.0
                }

                // TypeBonusScore (MyBatis Mapper/DAO, Entity, DTO 계열에 보너스)
                var typeBonusScore = 0
                if (fileNode.fileType.name == "MAPPER" || fileNode.fileType.name == "REPOSITORY" || fileNode.className.endsWith("Mapper") || fileNode.className.endsWith("Dao")) {
                    typeBonusScore = 10
                }

                // CriticalChainBonus: Controller -> Service -> Repository (Hop 2), Service -> BIZ -> Repository/VO (Hop 3)
                var criticalChainBonus = 0
                if (fileNode.fileType.name == "REPOSITORY" || fileNode.fileType.name == "DATA_ACCESS" || fileNode.className.endsWith("Mapper") || fileNode.className.endsWith("Dao")) {
                    if (step.via == "BIZ_TO_DOWNSTREAM") {
                        criticalChainBonus = 55 // Compensate for Hop 3 drop to 0, ensuring minScore (55) is met
                    } else if (step.via == "SERVICE_TO_REPO_OR_BIZ") {
                        criticalChainBonus = 20 // Compensate for Hop 2 drop to 40
                    }
                }

                val totalScore = (hopScore + nameMatchScore + methodMatchScore + layerAlignScore + commentMatchScore + typeBonusScore + criticalChainBonus).toInt()
                
                println("[RelevanceScorer] Node: ${fileNode.className}, Score: $totalScore")

                val fromClassName = step.from?.substringAfterLast('/')?.substringBeforeLast('.')
                val fromFileNode = if (step.from != null) graph.files[step.from] else null
                val fromDaoCount = fromFileNode?.dependsOn?.count { dp ->
                    val dep = graph.files[dp]
                    if (dep != null) {
                        val depType = dep.fileType.name
                        val depName = dep.className
                        depType == "REPOSITORY" || depType == "DATA_ACCESS" ||
                        depName.endsWith("Dao") || depName.endsWith("DaoImpl") ||
                        depName.endsWith("Mapper") || depName.endsWith("DEM") || depName.endsWith("DQM")
                    } else false
                } ?: 0
                val isGodServiceSource = fromDaoCount > 5

                val totalDependedBy = fileNode.dependedBy.size + (fileNode.usedByTypes?.size ?: 0)
                val isInfraDao = totalDependedBy >= 8 || fileNode.layer.name == "INFRASTRUCTURE"

                val curCount = if (fromClassName != null) seedDepCounts.getOrDefault(fromClassName, 0) else 0
                val isUnderCap = curCount < 3 // Seed 하나당 최대 3개 하향 의존 허용

                val isSeedDirectDependency = (fileNode.fileType.name == "REPOSITORY" || fileNode.fileType.name == "DATA_ACCESS" || fileNode.className.endsWith("Dao") || fileNode.className.endsWith("DaoImpl") || fileNode.className.endsWith("Mapper") || fileNode.className.endsWith("DEM") || fileNode.className.endsWith("DQM"))
                                             && (step.hop == 1 || step.via == "SERVICE_TO_REPO_OR_BIZ" || step.via == "BIZ_TO_DOWNSTREAM")
                                             && fromClassName != null
                                             && (seedResult.seedClasses.any { it.equals(fromClassName, ignoreCase = true) } || seedResult.judgePicks.any { it.equals(fromClassName, ignoreCase = true) })
                                             && !isInfraDao
                                             && !isGodServiceSource
                                             && isUnderCap

                if (isSeedDirectDependency && fromClassName != null) {
                    seedDepCounts[fromClassName] = curCount + 1
                }

                // LLM이 명시적으로 지목한 파일 판별 (submit_seeds 결과 + Judge pick + Frontend hint + Seed 직접 하향 의존)
                val protection = protectionReason(fileNode.className)
                    ?: if (isSeedDirectDependency) "Seed Direct Dependency" else null

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
                            fromPath = step.from,
                            isProtected = (protection != null),
                            protectionReason = protection
                        )
                    )
                } else if (protection != null) {
                    println("[RelevanceScorer] VETTED BYPASS: ${fileNode.className} (Score: $totalScore < $minScore) rescued by $protection!")
                    scoredFiles.add(
                        ScoredFile(
                            path = path,
                            className = fileNode.className,
                            fileType = fileNode.fileType.name,
                            layer = fileNode.layer.name,
                            score = totalScore,
                            discoveryReason = step.via + " [Bypass: $protection]",
                            hopDistance = step.hop,
                            fromPath = step.from,
                            isProtected = true,
                            protectionReason = protection
                        )
                    )
                }
            } else {
                val resourceNode = graph.resourceNodes.firstOrNull { it.path == path } ?: continue
                
                // HopScore (Fallback 탐색 시 기본 30점 부여)
                val hopScore = if (step.via == "FALLBACK_KEYWORD" || step.via == "KEYWORD_FALLBACK") 30 else when (step.hop) {
                    0 -> 10
                    1 -> 5
                    else -> 0
                }
                
                // NameMatchScore for ResourceNode (using filename)
                val fileName = path.substringAfterLast("/")
                var nameMatchScore = 0.0
                val directMatches = keywords.directEnglish.filter { eng -> fileName.contains(eng, ignoreCase = true) }
                if (directMatches.isNotEmpty()) {
                    nameMatchScore = 50.0 * directMatches.maxOf { getIdfWeight(it) }
                } else {
                    val transMatches = keywords.translatedEnglish.filter { eng -> fileName.contains(eng, ignoreCase = true) }
                    if (transMatches.isNotEmpty()) {
                        nameMatchScore = 30.0 * transMatches.maxOf { getIdfWeight(it) }
                    } else {
                        val weakMatches = keywords.weakTranslatedEnglish.filter { eng -> fileName.contains(eng, ignoreCase = true) }
                        if (weakMatches.isNotEmpty()) {
                            nameMatchScore = 15.0 * weakMatches.maxOf { getIdfWeight(it) }
                        }
                    }
                }
                
                // LayerAlignScore
                var layerAlignScore = 0
                if (seedResult.layerHint.any { layer -> resourceNode.layer.contains(layer, ignoreCase = true) || resourceNode.type.name.contains(layer, ignoreCase = true) }) {
                    layerAlignScore = 15
                }
                
                val totalScore = (hopScore + nameMatchScore + layerAlignScore).toInt()
                
                println("[RelevanceScorer] Node: $fileName, Score: $totalScore")
                
                val pathLower = path.lowercase()
                val isInfrastructurePath = pathLower.contains("/common/") || 
                                           pathLower.contains("/libs/") || 
                                           pathLower.contains("/plugin/") || 
                                           pathLower.contains("/plugins/") || 
                                           pathLower.contains("/vendor/")
                
                val isCommonViewOrScript = fileName.matches(Regex("^(400|403|404|500|error|header|footer|loginForm|top|menu)(_old)?\\.(jsp|html|js)$", RegexOption.IGNORE_CASE)) ||
                                           fileName.endsWith(".min.js") ||
                                           fileName.startsWith("jquery", ignoreCase = true) ||
                                           fileName.startsWith("bootstrap", ignoreCase = true)

                val isCommonResource = resourceNode.linkedTo.size >= 2 || 
                                       resourceNode.layer == "INFRASTRUCTURE" ||
                                       isInfrastructurePath ||
                                       isCommonViewOrScript

                val isLinkedFrontend = (resourceNode.type == net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceType.VIEW || 
                                        resourceNode.type == net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceType.SCRIPT) 
                                       && step.via == "LINKED_TO"
                                       && !isCommonResource

                // 1단계에서 생존한 Java 매퍼가 실제로 존재하는지 확인 (인터페이스 <-> Impl 구현체 상호 매칭 지원)
                val hasLivingJavaMapper = resourceNode.linkedTo.any { javaPath ->
                    val javaName = javaPath.substringAfterLast('/').substringBeforeLast('.')
                    scoredFiles.any { sf -> 
                        sf.path == javaPath || 
                        sf.className == javaName ||
                        sf.className == "${javaName}Impl" ||
                        sf.className.removeSuffix("Impl") == javaName
                    }
                }

                val isLinkedReverseResource = (resourceNode.type == net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceType.MYBATIS_MAPPER)
                                              && (step.via == "RESOURCE_REVERSE_LINK" || step.via == "SAME_PACKAGE")
                                              && !isCommonResource
                                              && hasLivingJavaMapper

                println("[RelevanceScorer-RESOURCE] Node: $fileName, Score: $totalScore, via: ${step.via}, isCommon: $isCommonResource, hasLiving: $hasLivingJavaMapper, isLinkedReverse: $isLinkedReverseResource")

                val protection = protectionReason(fileName)
                    ?: protectionReason(fileName.substringBeforeLast("."))
                    ?: if (isLinkedFrontend) "Frontend Resource" else null
                    ?: if (isLinkedReverseResource) "Resource Reverse Link" else null

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
                            fromPath = step.from,
                            isProtected = (protection != null),
                            protectionReason = protection
                        )
                    )
                } else if (protection != null) {
                    println("[RelevanceScorer] VETTED BYPASS: $fileName (Score: $totalScore < $minScore) rescued by $protection!")
                    scoredFiles.add(
                        ScoredFile(
                            path = path,
                            className = fileName,
                            fileType = resourceNode.type.name,
                            layer = resourceNode.layer,
                            score = totalScore,
                            discoveryReason = step.via + " [Bypass: $protection]",
                            hopDistance = step.hop,
                            fromPath = step.from,
                            isProtected = true,
                            protectionReason = protection
                        )
                    )
                }
            }
        }

        // 2. 정렬 및 필터링
        // Tier 1~4 우선순위 (낮을수록 우선) → Score 내림차순 → hop 낮은 순 → riskScore 오름차순
        return scoredFiles.sortedWith(compareBy<ScoredFile> { protectionTier(it.protectionReason) }
            .thenByDescending { it.score }
            .thenBy { it.hopDistance }
            .thenBy { graph.files[it.path]?.riskAssessment?.riskScore ?: 0 })
            .take(fileLimit)
    }

    private fun protectionTier(protection: String?): Int = when (protection) {
        "Seed Class", "Judge Pick", "Frontend Hint" -> 1  // Tier 1: LLM 직접 지목
        "Seed Direct Dependency", "Resource Reverse Link" -> 2  // Tier 2: 생존 노드 결정론적 연계
        "Frontend Resource" -> 3  // Tier 3: Controller 연계 리소스
        else -> 4  // Tier 4: 일반 탐색
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
        val directEnglish = Regex("[a-zA-Z0-9]{3,}").findAll(text)
            .map { it.value }
            .filter { it.any { c -> c.isLetter() } }
            .toMutableList()
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
