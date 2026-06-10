package net.ib.ixpert.ops.wuwagent.agent

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph

object DependencyCompletenessChecker {
    /**
     * LLM이 선택한 1차 후보군(TargetFileSpec)의 의존성(Injection, DependedBy)을 1-Depth로 탐색하여,
     * 누락된 필수 파일을 복원(추가)합니다.
     */
    fun check(
        targetFiles: List<TargetFileSpec>,
        graph: ProjectGraph,
        keywords: List<String>
    ): List<TargetFileSpec> {
        val result = targetFiles.toMutableList()
        val existingPaths = targetFiles.map { it.path }.toSet()
        val addedPaths = mutableSetOf<String>()
        val additionalSpecs = mutableListOf<TargetFileSpec>()
        
        // 탐색 성능을 위한 클래스명 인덱스 (O(1) 탐색용)
        val classNameIndex = graph.files.values.associateBy { it.className }

        for (spec in targetFiles) {
            // 신규 생성(Create) 제안 파일 등 그래프에 없는 파일은 조용히 스킵
            val node = graph.files[spec.path] ?: continue
            
            // 1. 직접 주입 (1-depth injections) 검사
            for (injection in node.injections) {
                // 인터페이스나 클래스명 기반으로 노드 탐색
                val typeName = injection.resolvedImpl ?: injection.targetType
                val simpleName = typeName.substringAfterLast(".")
                val injectedNode = classNameIndex[simpleName] ?: graph.files.values.find { 
                    it.path.endsWith("/$simpleName.java") || it.path.endsWith("/$simpleName.kt") 
                }
                
                if (injectedNode != null && injectedNode.path !in existingPaths && injectedNode.path !in addedPaths) {
                    // 공통 유틸 필터링: 나를 참조하는 클래스가 10개 이상이면 공통/유틸로 간주하여 추가 안함
                    if (injectedNode.dependedBy.size >= 10) continue
                    
                    if (isRelevant(injectedNode.methodNames, keywords)) {
                        addedPaths.add(injectedNode.path)
                        additionalSpecs.add(TargetFileSpec(
                            order = 0, 
                            path = injectedNode.path, 
                            type = "수정(의존성)", 
                            description = "선택된 파일에서 주입받아 사용하는 의존성 (자동 추가)"
                        ))
                    }
                }
            }

            // 2. 나를 참조하는 객체 (1-depth dependedBy) 검사
            // 단, 범위 폭발을 막기 위해 동일 패키지이거나 1-depth 하위 패키지인 경우로 한정
            val currentPkg = node.packageName ?: ""
            for (depPath in node.dependedBy) {
                if (depPath in existingPaths || depPath in addedPaths) continue
                val depNode = graph.files[depPath] ?: continue
                
                // 공통 유틸 필터링: 나를 참조하는 클래스가 10개 이상이면 공통/유틸로 간주하여 추가 안함
                if (depNode.dependedBy.size >= 10) continue
                
                val depPkg = depNode.packageName ?: ""
                val isSameOrChildPkg = depPkg == currentPkg || 
                    (depPkg.startsWith("$currentPkg.") && depPkg.substringAfter("$currentPkg.").count { it == '.' } == 0)
                
                if (isSameOrChildPkg) {
                    if (isRelevant(depNode.methodNames, keywords)) {
                        addedPaths.add(depNode.path)
                        additionalSpecs.add(TargetFileSpec(
                            order = 0, 
                            path = depNode.path, 
                            type = "수정(의존성)", 
                            description = "선택된 파일을 참조하는 객체 (자동 추가)"
                        ))
                    }
                }
            }
        }
        
        return result + additionalSpecs
    }

    /**
     * 후보 노드가 요구사항 키워드와 연관이 있는지 검사합니다.
     * 향후 Phase 2 스코어링 로직 재활용(cutoffScore 기반)을 추가할 예정입니다.
     */
    private fun isRelevant(methodNames: List<String>, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        val lowerKeywords = keywords.map { it.lowercase() }
        
        return methodNames.any { method ->
            val lowerMethod = method.lowercase()
            // 정방향 매칭만 수행하여 false positive 방지 (예: 키워드가 getBalance일 때 get이 매칭되는 문제 해결)
            lowerKeywords.any { kw -> 
                kw.length > 2 && lowerMethod.contains(kw) 
            }
        }
    }
}
