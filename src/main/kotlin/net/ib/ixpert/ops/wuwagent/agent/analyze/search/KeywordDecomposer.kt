package net.ib.ixpert.ops.wuwagent.agent.analyze.search

import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery.DomainDictionary
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

class KeywordDecomposer(
    private val metaGraph: ProjectGraph,
    private val domainDictionary: DomainDictionary
) {
    data class Keywords(
        val tokens: List<String>,
        val contentTokens: List<String>,
        val matchedClassNames: List<String>,
        val matchedMethodNames: List<String>,
        val matchedFileNames: List<String>
    )

    fun decompose(input: String): Keywords {
        val rawTokens = tokenize(input)
        val stopWords = setOf("기능", "만들어줘", "해줘", "추가", "구현", "좀", "하는", "로", "을", "를", "결과", "기능을", "합니다")
        
        val contentTokens = mutableListOf<String>()
        val dictionaryTokens = mutableListOf<String>()
        
        // 1. 도메인 사전 매칭 및 Fallback (옵션 A 적용)
        val koreanPattern = Regex("[가-힣]+")
        rawTokens.filter { it !in stopWords && it.length >= 2 }.forEach { token ->
            if (koreanPattern.matches(token)) {
                val matches = domainDictionary.translate(token)
                if (matches.isEmpty()) {
                    // 도메인 사전에 매칭되지 않는 한글 토큰은 Fallback으로 Content 검색(주석 등)에 사용
                    contentTokens.add(token)
                } else {
                    // 매칭된 영문 토큰 추가 (중요도 가중치 등은 향후 고도화 가능, 현재는 전부 사용)
                    dictionaryTokens.addAll(matches)
                    // 원본 한글 토큰도 Content 검색(주석)을 위해 추가
                    contentTokens.add(token)
                }
            } else {
                // 영문 토큰은 식별자 검색 및 Content 검색 모두 사용
                dictionaryTokens.add(token)
                contentTokens.add(token)
            }
        }
        
        val meaningful = dictionaryTokens.distinct()
        val meaningfulContent = contentTokens.distinct()

        // 2. 메타그래프의 이름 인덱스 확보
        val allClassNames = metaGraph.files.values.mapNotNull { it.className }
        val allMethodNames = metaGraph.files.values.flatMap { it.methodNames }
        val allFileNames = metaGraph.files.values.map { it.path.substringAfterLast("/") }

        // 3. 식별자 매칭 수행
        val classHits = matchAgainstNames(meaningful, allClassNames)
        val methodHits = matchAgainstNames(meaningful, allMethodNames)
        val fileHits = matchAgainstNames(meaningful, allFileNames)

        return Keywords(
            tokens = meaningful,
            contentTokens = meaningfulContent,
            matchedClassNames = classHits,
            matchedMethodNames = methodHits,
            matchedFileNames = fileHits
        )
    }

    /**
     * 사용자 토큰을 메타그래프 내 이름들과 매칭한다.
     * - CamelCase 분해 후 부분 매칭
     */
    private fun matchAgainstNames(tokens: List<String>, names: List<String>): List<String> {
        return names.filter { name ->
            val nameParts = splitCamelCase(name).map { it.lowercase() }
            tokens.any { token ->
                val tokenLower = token.lowercase()
                nameParts.any { part -> 
                    part.contains(tokenLower) || tokenLower.contains(part)
                }
            }
        }.distinct()
    }

    private fun tokenize(input: String): List<String> {
        val results = mutableListOf<String>()
        // 한글 단어, 영문 단어, 숫자를 각각 분리
        val pattern = Regex("[가-힣]+|[a-zA-Z]+|[0-9]+")
        results += pattern.findAll(input).map { it.value }.toList()
        
        // 영문 CamelCase 추가 분리
        val additionalTokens = mutableListOf<String>()
        results.filter { it.isNotEmpty() && it[0].isLetter() && it.length > 3 && it.matches(Regex("[a-zA-Z]+")) }
            .forEach { additionalTokens += splitCamelCase(it) }
            
        results.addAll(additionalTokens)
        return results.map { it.lowercase() }.distinct()
    }

    private fun splitCamelCase(name: String): List<String> {
        return Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
            .split(name).filter { it.isNotEmpty() }
    }
}
