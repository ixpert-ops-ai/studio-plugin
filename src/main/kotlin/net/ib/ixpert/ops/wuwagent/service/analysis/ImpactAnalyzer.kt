package net.ib.ixpert.ops.wuwagent.service.analysis

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

object ImpactAnalyzer {

    // ================================================================
    // 1. 설정값
    // ================================================================
    private const val MAX_DEPTH = 5
    private const val MAX_TEXT_FALLBACK_DISPLAY = 20

    // ================================================================
    // 2. Enum / 데이터 클래스
    // ================================================================

    enum class RelationType(val label: String) {
        DIRECT_CALL("직접 호출"),
        POLYMORPHIC("다형성(Override/Implement)"),
        SUPER_METHOD("슈퍼 메서드 경유"),
        DATA_FLOW("필드 데이터 흐름"),
        TEXT_FALLBACK("텍스트 폴백")
    }

    enum class ArchitectureLayer(val label: String) {
        CONTROLLER("Controller"),
        SERVICE("Service"),
        REPOSITORY("Repository/DAO"),
        ENTITY("Entity/Model"),
        CONFIGURATION("Configuration"),
        UTILITY("Utility/Helper"),
        TEST("Test"),
        UNKNOWN("기타")
    }

    enum class AnalysisStrategy(val label: String) {
        METHOD("메서드 호출 관계 기반 분석 (호출처 추적, 다형성, 데이터 흐름)"),
        FIELD("필드 참조 기반 분석 (읽기/쓰기 참조, getter/setter, 직렬화 영향)"),
        CLASS("클래스 타입 참조 기반 분석 (타입 참조, 상속 관계, 생성자 호출)"),
        INTERFACE("인터페이스 계약 기반 분석 (모든 구현체, 구현체 호출처)")
    }

    data class ImpactNode(
        val name: String,
        val location: String,
        val signature: String,
        val elementType: String,
        val relationType: RelationType = RelationType.DIRECT_CALL,
        val children: MutableList<ImpactNode> = mutableListOf(),
        val totalChildCount: Int = 0
    )

    data class AnalysisStatistics(
        val directCallerCount: Int,
        val indirectCallerCount: Int,
        val polymorphicCount: Int,
        val dataFlowCount: Int,
        val totalAffected: Int,
        val maxDepthReached: Int,
        val strategy: AnalysisStrategy
    )

    data class ImpactAnalysisResult(
        val targetSignature: String,
        val targetLocation: String,
        val targetType: String,
        val callHierarchyTree: String,
        val layerSummary: String,
        val polymorphismInfo: String,
        val dataFlowInfo: String,
        val allAffectedSignatures: List<String>,
        val statistics: AnalysisStatistics
    ) {
        fun toFormattedString(): String {
            val sb = StringBuilder()
            sb.appendLine("=".repeat(60))
            sb.appendLine("  영향도 분석 결과")
            sb.appendLine("=".repeat(60))
            sb.appendLine()
            sb.appendLine("[분석 대상]")
            sb.appendLine("  시그니처: $targetSignature")
            sb.appendLine("  위치: $targetLocation")
            sb.appendLine("  타입: $targetType")
            sb.appendLine("  분석 전략: ${statistics.strategy.label}")
            sb.appendLine()
            sb.appendLine("[통계]")
            sb.appendLine("  직접 호출처: ${statistics.directCallerCount}개")
            sb.appendLine("  간접 호출처: ${statistics.indirectCallerCount}개")
            sb.appendLine("  다형성 관련: ${statistics.polymorphicCount}개")
            sb.appendLine("  데이터 흐름 영향: ${statistics.dataFlowCount}개")
            sb.appendLine("  총 영향 요소: ${statistics.totalAffected}개")
            sb.appendLine("  최대 호출 깊이: ${statistics.maxDepthReached}")
            sb.appendLine()
            sb.appendLine("[호출 계층 트리]")
            sb.appendLine(callHierarchyTree)
            if (layerSummary.isNotBlank()) {
                sb.appendLine("[아키텍처 계층 분류]")
                sb.appendLine(layerSummary)
            }
            if (polymorphismInfo.isNotBlank()) {
                sb.appendLine("[다형성 영향]")
                sb.appendLine(polymorphismInfo)
            }
            if (dataFlowInfo.isNotBlank()) {
                sb.appendLine("[데이터 흐름 영향]")
                sb.appendLine(dataFlowInfo)
            }
            sb.appendLine("[영향받는 전체 요소 목록]")
            allAffectedSignatures.forEachIndexed { i, sig ->
                sb.appendLine("  ${i + 1}. $sig")
            }
            return sb.toString()
        }
    }

    private data class TextFallbackHit(
        val fileName: String,
        val filePath: String,
        val lineNumber: Int,
        val enclosingSignature: String,
        val enclosingType: String
    )

    // ================================================================
    // 3. 공개 진입점
    // ================================================================

    fun analyzeHierarchyAsync(
        project: Project,
        targetElement: PsiElement,
        callback: (ImpactAnalysisResult) -> Unit
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "영향도 분석 중...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "참조 및 호출 계층 분석 중..."

                val result = ReadAction.compute<ImpactAnalysisResult, RuntimeException> {
                    val rootNode = buildImpactTree(targetElement, 0, mutableSetOf(), indicator)
                    buildAnalysisResult(targetElement, rootNode)
                }

                ApplicationManager.getApplication().invokeLater {
                    callback(result)
                }
            }
        })
    }

    /**
     * 동기 방식 분석 — BaseAgent의 ProgressManager 태스크 안에서 호출할 때 사용합니다.
     * ReadAction 안에서 호출해야 합니다.
     */
    fun analyzeSync(
        project: Project,
        targetElement: PsiElement
    ): ImpactAnalysisResult {
        return ReadAction.compute<ImpactAnalysisResult, RuntimeException> {
            val rootNode = buildImpactTree(targetElement, 0, mutableSetOf(), null)
            buildAnalysisResult(targetElement, rootNode)
        }
    }

    // ================================================================
    // 4. 분석 전략 결정
    // ================================================================

    private fun determineStrategy(element: PsiElement): AnalysisStrategy {
        return when (element) {
            is PsiMethod -> {
                val containingClass = element.containingClass
                when {
                    containingClass?.isInterface == true -> AnalysisStrategy.INTERFACE
                    element.hasModifierProperty(PsiModifier.ABSTRACT) -> AnalysisStrategy.INTERFACE
                    else -> AnalysisStrategy.METHOD
                }
            }
            is PsiField -> AnalysisStrategy.FIELD
            is PsiClass -> {
                if (element.isInterface) AnalysisStrategy.INTERFACE else AnalysisStrategy.CLASS
            }
            else -> {
                val cn = element.javaClass.simpleName
                when {
                    cn.contains("Function") -> AnalysisStrategy.METHOD
                    cn.contains("Property") -> AnalysisStrategy.FIELD
                    cn.contains("Class") || cn.contains("Object") -> AnalysisStrategy.CLASS
                    else -> AnalysisStrategy.METHOD
                }
            }
        }
    }

    // ================================================================
    // 5. 트리 구축 (핵심)
    // ================================================================

private fun buildImpactTree(
    element: PsiElement,
    depth: Int,
    visited: MutableSet<String>,
    indicator: ProgressIndicator?
): ImpactNode? {
    indicator?.checkCanceled()
    if (depth > MAX_DEPTH) return null

    val key = elementKey(element)
    if (!visited.add(key)) return null

    val name = getElementName(element) ?: return null
    val location = getLocation(element)
    val signature = getDeclarationSignature(element)
    val elementType = getElementType(element)

    val node = ImpactNode(
        name = name,
        location = location,
        signature = signature,
        elementType = elementType
    )

    if (isStandardLibraryElement(element)) return node

    val allCallers = mutableListOf<Pair<PsiElement, RelationType>>()
    val strategy = determineStrategy(element)

    // ★ 각 수집 단계를 try-catch로 감싸서 하나가 실패해도 나머지 진행
    when (strategy) {
        AnalysisStrategy.METHOD -> {
            runSafe { collectDirectReferences(element) }?.forEach {
                allCallers.add(it to RelationType.DIRECT_CALL)
            }
            runSafe { collectPolymorphicReferences(element) }?.forEach {
                allCallers.add(it to RelationType.POLYMORPHIC)
            }
            runSafe { collectSuperMethodReferences(element) }?.forEach {
                allCallers.add(it to RelationType.SUPER_METHOD)
            }
            runSafe { collectFieldDataFlowReferences(element) }?.forEach {
                allCallers.add(it to RelationType.DATA_FLOW)
            }
        }

        AnalysisStrategy.FIELD -> {
            runSafe { collectFieldReadWriteReferences(element) }?.forEach {
                allCallers.add(it)
            }
            runSafe { collectRelatedAccessors(element) }?.forEach {
                allCallers.add(it to RelationType.DIRECT_CALL)
            }
            runSafe { collectSerializationImpact(element) }?.forEach {
                allCallers.add(it to RelationType.DATA_FLOW)
            }
        }

        AnalysisStrategy.CLASS -> {
            runSafe { collectDirectReferences(element) }?.forEach {
                allCallers.add(it to RelationType.DIRECT_CALL)
            }
            runSafe { collectSubclasses(element) }?.forEach {
                allCallers.add(it to RelationType.POLYMORPHIC)
            }
            runSafe { collectConstructorUsages(element) }?.forEach {
                allCallers.add(it to RelationType.DIRECT_CALL)
            }
        }

        AnalysisStrategy.INTERFACE -> {
            runSafe { collectDirectReferences(element) }?.forEach {
                allCallers.add(it to RelationType.DIRECT_CALL)
            }
            runSafe { collectPolymorphicReferences(element) }?.forEach {
                allCallers.add(it to RelationType.POLYMORPHIC)
            }
            runSafe { collectAllImplementations(element) }?.forEach {
                allCallers.add(it to RelationType.POLYMORPHIC)
            }
            if (element is PsiMethod) {
                runSafe { collectSuperMethodReferences(element) }?.forEach {
                    allCallers.add(it to RelationType.SUPER_METHOD)
                }
            }
        }
    }

    val uniqueCallers = deduplicateCallersWithType(allCallers, key)
    val totalCount = uniqueCallers.size

    for ((caller, relType) in uniqueCallers) {
        indicator?.checkCanceled()
        val childNode = buildImpactTree(caller, depth + 1, visited, indicator)
        if (childNode != null) {
            node.children.add(childNode.copy(
                relationType = relType,
                name = formatNameWithRelationType(childNode.name, relType)
            ))
        }
    }

    if (uniqueCallers.isEmpty() && depth == 0) {
        runSafe { collectTextFallback(element, name, node) }
    }

    return node.copy(totalChildCount = totalCount)
}

/**
 * ★ 안전 실행 래퍼 — 예외 발생 시 null 반환
 */
private fun <T> runSafe(block: () -> T): T? {
    return try {
        block()
    } catch (e: Exception) {
        // 로그 출력 (IDE 로그에 기록)
        com.intellij.openapi.diagnostic.Logger.getInstance(ImpactAnalyzer::class.java)
            .warn("영향도 분석 중 오류 발생: ${e.message}", e)
        null
    }
}


    // ================================================================
    // 6. 공통 수집 메서드 (METHOD / INTERFACE 공용)
    // ================================================================

    private fun collectDirectReferences(element: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        val query = ReferencesSearch.search(element, GlobalSearchScope.projectScope(element.project))
        for (reference in query) {
            if (!verifyReference(reference, element)) continue
            val caller = findEnclosingDeclaration(reference.element)
            if (caller != null) result.add(caller)
        }
        return result
    }

    private fun collectPolymorphicReferences(element: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        if (element !is PsiMethod) return result

        val scope = GlobalSearchScope.projectScope(element.project)
        for (overridingMethod in OverridingMethodsSearch.search(element, scope, true)) {
            result.add(overridingMethod)
            for (ref in ReferencesSearch.search(overridingMethod, scope)) {
                val caller = findEnclosingDeclaration(ref.element)
                if (caller != null) result.add(caller)
            }
        }
        return result
    }

    private fun collectSuperMethodReferences(element: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        if (element !is PsiMethod) return result

        for (superMethod in element.findSuperMethods()) {
            for (ref in ReferencesSearch.search(superMethod, GlobalSearchScope.projectScope(element.project))) {
                if (!verifyReferenceForSuper(ref, element, superMethod)) continue
                val caller = findEnclosingDeclaration(ref.element)
                if (caller != null) result.add(caller)
            }
        }
        return result
    }

    private fun collectFieldDataFlowReferences(element: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        val body = when (element) {
            is PsiMethod -> element.body
            else -> element.children.firstOrNull { child ->
                child.javaClass.simpleName.let {
                    it.contains("Body") || it.contains("BlockExpression")
                }
            }
        } ?: return result

        val assignedFields = mutableSetOf<PsiField>()
        PsiTreeUtil.findChildrenOfType(body, PsiAssignmentExpression::class.java).forEach { assignment ->
            val resolved = (assignment.lExpression as? PsiReferenceExpression)?.resolve()
            if (resolved is PsiField) assignedFields.add(resolved)
        }

        for (field in assignedFields) {
            for (ref in ReferencesSearch.search(field, GlobalSearchScope.projectScope(element.project))) {
                if (!isWriteReference(ref)) {
                    val caller = findEnclosingDeclaration(ref.element)
                    if (caller != null) result.add(caller)
                }
            }
        }
        return result
    }

    // ================================================================
    // 7. FIELD 전략 전용 수집 메서드
    // ================================================================
private fun collectFieldReadWriteReferences(element: PsiElement): List<Pair<PsiElement, RelationType>> {
    val result = mutableListOf<Pair<PsiElement, RelationType>>()
    if (element !is PsiField) return result

    // ★ 유효성 검사
    if (!element.isValid) return result
    val project = element.project

    val query = ReferencesSearch.search(element, GlobalSearchScope.projectScope(project))
    for (reference in query) {
        // ★ 참조 요소 유효성 확인
        val refElement = reference.element
        if (!refElement.isValid) continue

        val caller = findEnclosingDeclaration(refElement) ?: continue
        val relType = if (isWriteReference(reference)) {
            RelationType.DATA_FLOW
        } else {
            RelationType.DIRECT_CALL
        }
        result.add(caller to relType)
    }
    return result
}


private fun collectRelatedAccessors(element: PsiElement): List<PsiElement> {
    val result = mutableListOf<PsiElement>()
    if (element !is PsiField) return result

    // ★ 유효성 + null 검사
    if (!element.isValid) return result
    val fieldName = element.name
    if (fieldName.isNullOrBlank()) return result
    val containingClass = element.containingClass ?: return result
    if (!containingClass.isValid) return result

    val capitalized = fieldName.replaceFirstChar { it.uppercaseChar() }
    val expectedNames = listOf("get$capitalized", "set$capitalized", "is$capitalized")

    for (method in containingClass.methods) {
        if (!method.isValid) continue
        if (method.name in expectedNames) {
            result.add(method)
            // getter/setter 호출처도 추적
            try {
                for (ref in ReferencesSearch.search(method, GlobalSearchScope.projectScope(element.project))) {
                    if (!ref.element.isValid) continue
                    val caller = findEnclosingDeclaration(ref.element)
                    if (caller != null) result.add(caller)
                }
            } catch (e: Exception) {
                // 개별 메서드 검색 실패 시 건너뛰기
                continue
            }
        }
    }
    return result
}


private fun collectSerializationImpact(element: PsiElement): List<PsiElement> {
    val result = mutableListOf<PsiElement>()
    if (element !is PsiField) return result

    // ★ 유효성 검사
    if (!element.isValid) return result
    val containingClass = element.containingClass ?: return result
    if (!containingClass.isValid) return result

    val serializationAnnotations = listOf(
        "Entity", "Table", "JsonProperty", "JsonIgnore", "JsonFormat",
        "SerializedName", "Column", "Id", "ManyToOne", "OneToMany",
        "Data", "Getter", "Setter"
    )

    // ★ 어노테이션 접근을 try-catch로 감쌈
    val classAnnotations = try {
        containingClass.annotations.mapNotNull {
            it.qualifiedName?.substringAfterLast(".")
        }
    } catch (e: Exception) {
        emptyList()
    }

    val fieldAnnotations = try {
        element.annotations.mapNotNull {
            it.qualifiedName?.substringAfterLast(".")
        }
    } catch (e: Exception) {
        emptyList()
    }

    val hasSerializationContext = (classAnnotations + fieldAnnotations).any { it in serializationAnnotations }

    if (hasSerializationContext) {
        try {
            for (ref in ReferencesSearch.search(containingClass, GlobalSearchScope.projectScope(element.project))) {
                if (!ref.element.isValid) continue
                val caller = findEnclosingDeclaration(ref.element)
                if (caller != null) result.add(caller)
            }
        } catch (e: Exception) {
            // 검색 실패 시 빈 결과 반환
        }
    }
    return result
}


    // ================================================================
    // 8. CLASS 전략 전용 수집 메서드
    // ================================================================

    private fun collectSubclasses(element: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        if (element !is PsiClass) return result

        ClassInheritorsSearch.search(element, GlobalSearchScope.projectScope(element.project), true).forEach {
            result.add(it)
        }
        return result
    }

    private fun collectConstructorUsages(element: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        if (element !is PsiClass) return result

        for (constructor in element.constructors) {
            for (ref in ReferencesSearch.search(constructor, GlobalSearchScope.projectScope(element.project))) {
                val caller = findEnclosingDeclaration(ref.element)
                if (caller != null) result.add(caller)
            }
        }
        return result
    }

    // ================================================================
    // 9. INTERFACE 전략 전용 수집 메서드
    // ================================================================

    private fun collectAllImplementations(element: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()

        if (element is PsiClass && element.isInterface) {
            val scope = GlobalSearchScope.projectScope(element.project)
            ClassInheritorsSearch.search(element, scope, true).forEach { impl ->
                result.add(impl)
                for (ref in ReferencesSearch.search(impl, scope)) {
                    val caller = findEnclosingDeclaration(ref.element)
                    if (caller != null) result.add(caller)
                }
            }
        }

        if (element is PsiMethod) {
            val containingClass = element.containingClass
            if (containingClass?.isInterface == true || element.hasModifierProperty(PsiModifier.ABSTRACT)) {
                val scope = GlobalSearchScope.projectScope(element.project)
                OverridingMethodsSearch.search(element, scope, true).forEach { impl ->
                    result.add(impl)
                    for (ref in ReferencesSearch.search(impl, scope)) {
                        val caller = findEnclosingDeclaration(ref.element)
                        if (caller != null) result.add(caller)
                    }
                }
            }
        }

        return result
    }

    // ================================================================
    // 10. 텍스트 폴백
    // ================================================================

    private fun collectTextFallback(element: PsiElement, name: String, node: ImpactNode) {
        val searchHelper = PsiSearchHelper.getInstance(element.project)
        val scope = GlobalSearchScope.projectScope(element.project)
        val targetClassQualifiedName = when (element) {
            is PsiMethod -> element.containingClass?.qualifiedName
            is PsiClass -> element.qualifiedName
            is PsiField -> element.containingClass?.qualifiedName
            else -> PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.qualifiedName
        }

        val textResults = mutableListOf<TextFallbackHit>()
        val selfFile = element.containingFile?.virtualFile?.path ?: ""
        val selfOffset = element.textOffset

        searchHelper.processAllFilesWithWord(name, scope, { file ->
            val document = PsiDocumentManager.getInstance(element.project).getDocument(file)
            val text = file.text
            var searchFrom = 0

            while (true) {
                val idx = text.indexOf(name, searchFrom)
                if (idx < 0) break
                searchFrom = idx + name.length

                val filePath = file.virtualFile?.path ?: ""
                if (filePath == selfFile && Math.abs(idx - selfOffset) < name.length + 10) continue

                val elementAtOffset = file.findElementAt(idx) ?: continue
                if (!verifyTextHitTarget(elementAtOffset, element, targetClassQualifiedName)) continue

                val lineNumber = document?.getLineNumber(idx)?.plus(1) ?: -1
                val enclosing = findEnclosingDeclaration(elementAtOffset)
                val enclosingSignature = if (enclosing != null) getDeclarationSignature(enclosing) else "(알 수 없음)"
                val enclosingType = if (enclosing != null) getElementType(enclosing) else "Unknown"

                textResults.add(TextFallbackHit(
                    fileName = file.name,
                    filePath = filePath,
                    lineNumber = lineNumber,
                    enclosingSignature = enclosingSignature,
                    enclosingType = enclosingType
                ))
            }
            true
        }, true)

        val deduplicated = textResults.distinctBy { "${it.enclosingSignature}@${it.lineNumber}" }
        for ((index, hit) in deduplicated.withIndex()) {
            node.children.add(ImpactNode(
                name = "[텍스트폴백] ${hit.enclosingSignature}",
                location = "${hit.fileName}:${hit.lineNumber}",
                signature = hit.enclosingSignature,
                elementType = hit.enclosingType,
                relationType = RelationType.TEXT_FALLBACK,
                totalChildCount = 0
            ))
            if (index >= MAX_TEXT_FALLBACK_DISPLAY - 1 && deduplicated.size > MAX_TEXT_FALLBACK_DISPLAY) {
                node.children.add(ImpactNode(
                    name = "...외 ${deduplicated.size - MAX_TEXT_FALLBACK_DISPLAY}개 텍스트 매칭",
                    location = "",
                    signature = "",
                    elementType = "Overflow",
                    relationType = RelationType.TEXT_FALLBACK,
                    totalChildCount = 0
                ))
                break
            }
        }
    }

    // ================================================================
    // 11. 검증 메서드
    // ================================================================

    private fun verifyReference(reference: PsiReference, targetElement: PsiElement): Boolean {
        return try {
            val resolved = reference.resolve()
            when {
                resolved == null -> true
                resolved == targetElement -> true
                elementKey(resolved) == elementKey(targetElement) -> true
                resolved is PsiMethod && targetElement is PsiMethod -> {
                    val resolvedSupers = resolved.findDeepestSuperMethods().toSet() + resolved
                    val targetSupers = targetElement.findDeepestSuperMethods().toSet() + targetElement
                    resolvedSupers.intersect(targetSupers).isNotEmpty()
                }
                else -> false
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun verifyReferenceForSuper(reference: PsiReference, implMethod: PsiMethod, superMethod: PsiMethod): Boolean {
        return try {
            val resolved = reference.resolve()
            when {
                resolved == null -> true
                resolved == superMethod -> true
                elementKey(resolved) == elementKey(superMethod) -> true
                resolved is PsiMethod -> {
                    val resolvedSupers = resolved.findDeepestSuperMethods().toSet() + resolved
                    resolvedSupers.contains(superMethod) || resolvedSupers.contains(implMethod)
                }
                else -> false
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun verifyTextHitTarget(
        elementAtOffset: PsiElement,
        targetElement: PsiElement,
        targetClassQualifiedName: String?
    ): Boolean {
        val reference = elementAtOffset.reference ?: elementAtOffset.parent?.reference

        if (reference != null) {
            return try {
                val resolved = reference.resolve()
                when {
                    resolved == null -> true
                    resolved == targetElement -> true
                    elementKey(resolved) == elementKey(targetElement) -> true
                    resolved is PsiMethod && targetElement is PsiMethod -> {
                        val resolvedSupers = resolved.findDeepestSuperMethods().toSet() + resolved
                        val targetSupers = targetElement.findDeepestSuperMethods().toSet() + targetElement
                        resolvedSupers.intersect(targetSupers).isNotEmpty()
                    }
                    else -> false
                }
            } catch (e: Exception) {
                true
            }
        }

        if (targetClassQualifiedName != null) {
            val fileText = elementAtOffset.containingFile?.text ?: return true
            val simpleClassName = targetClassQualifiedName.substringAfterLast(".")
            return fileText.contains(targetClassQualifiedName) || fileText.contains(simpleClassName)
        }

        return true
    }

private fun isWriteReference(reference: PsiReference): Boolean {
    return try {
        val element = reference.element
        if (!element.isValid) return false

        val parent = element.parent ?: return false

        if (parent is PsiAssignmentExpression && parent.lExpression == element) return true

        if (parent is PsiUnaryExpression) {
            val tokenType = parent.operationSign.tokenType
            if (tokenType == JavaTokenType.PLUSPLUS || tokenType == JavaTokenType.MINUSMINUS) return true
        }

        false
    } catch (e: Exception) {
        false
    }
}


    // ================================================================
    // 12. 중복 제거
    // ================================================================

    private fun deduplicateCallersWithType(
        callers: List<Pair<PsiElement, RelationType>>,
        selfKey: String
    ): List<Pair<PsiElement, RelationType>> {
        val priority = mapOf(
            RelationType.DIRECT_CALL to 0,
            RelationType.POLYMORPHIC to 1,
            RelationType.SUPER_METHOD to 2,
            RelationType.DATA_FLOW to 3,
            RelationType.TEXT_FALLBACK to 4
        )
        val bestByKey = mutableMapOf<String, Pair<PsiElement, RelationType>>()
        for ((caller, relType) in callers) {
            val callerKey = elementKey(caller)
            if (callerKey == selfKey) continue
            val existing = bestByKey[callerKey]
            if (existing == null || (priority[relType] ?: 99) < (priority[existing.second] ?: 99)) {
                bestByKey[callerKey] = caller to relType
            }
        }
        return bestByKey.values.toList()
    }

    // ================================================================
    // 13. 결과 생성
    // ================================================================

    private fun buildAnalysisResult(targetElement: PsiElement, rootNode: ImpactNode?): ImpactAnalysisResult {
        return ImpactResultFormatter.buildResult(
            targetSignature = getDeclarationSignature(targetElement),
            targetLocation = getLocation(targetElement),
            targetType = getElementType(targetElement),
            strategy = determineStrategy(targetElement),
            rootNode = rootNode
        )
    }

    // ================================================================
    // 15. 유틸리티
    // ================================================================

private fun elementKey(element: PsiElement): String {
    return try {
        val file = element.containingFile?.virtualFile?.path ?: "unknown"
        val offset = if (element.isValid) element.textOffset else -1
        "$file@$offset"
    } catch (e: Exception) {
        "unknown@${System.identityHashCode(element)}"
    }
}


    private fun getElementName(element: PsiElement): String? {
        return when (element) {
            is PsiMethod -> element.name
            is PsiClass -> element.name ?: element.qualifiedName
            is PsiField -> element.name
            is PsiNamedElement -> element.name
            else -> element.text.let { if (it.length > 60) it.substring(0, 60) + "..." else it }
        }
    }

    private fun getElementType(element: PsiElement): String {
        return when (element) {
            is PsiMethod -> if (element.isConstructor) "Constructor" else "Method"
            is PsiClass -> when {
                element.isInterface -> "Interface"
                element.isEnum -> "Enum"
                element.isAnnotationType -> "Annotation"
                else -> "Class"
            }
            is PsiClassInitializer -> "Initializer"
            is PsiField -> "Field"
            is PsiNamedElement -> {
                val cn = element.javaClass.simpleName
                when {
                    cn.contains("Function") -> "KotlinFunction"
                    cn.contains("Property") -> "KotlinProperty"
                    cn.contains("Class") || cn.contains("Object") -> "KotlinClass"
                    else -> "Declaration"
                }
            }
            else -> "Unknown"
        }
    }

private fun getDeclarationSignature(element: PsiElement): String {
    if (!element.isValid) return "(invalid element)"

    return when (element) {
        is PsiMethod -> {
            val className = element.containingClass?.qualifiedName ?: "(anonymous)"
            val params = element.parameterList.parameters.joinToString(", ") {
                try { it.type.canonicalText } catch (e: Exception) { "?" }
            }
            "$className.${element.name}($params)"
        }
        is PsiClass -> element.qualifiedName ?: element.name ?: "(anonymous class)"
        is PsiClassInitializer -> {
            val className = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.qualifiedName ?: "(unknown)"
            if (element.hasModifierProperty(PsiModifier.STATIC)) "$className.<clinit>" else "$className.<init>"
        }
        is PsiField -> {
            // ★ containingClass가 null일 수 있음
            val className = element.containingClass?.qualifiedName
                ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.qualifiedName
                ?: "(unknown)"
            val fieldName = element.name ?: "(unnamed)"
            "$className.$fieldName"
        }
        is PsiNamedElement -> {
            val parentClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
            val prefix = parentClass?.qualifiedName ?: extractKotlinPackage(element) ?: "(unknown)"
            "$prefix.${element.name ?: "(unnamed)"}"
        }
        else -> "${element.containingFile?.virtualFile?.path ?: "unknown"}@${element.textOffset}"
    }
}


    private fun extractKotlinPackage(element: PsiElement): String? {
        val file = element.containingFile ?: return null
        return try {
            val method = file.javaClass.getMethod("getPackageFqName")
            method.invoke(file)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun getLocation(element: PsiElement): String {
        val fileName = element.containingFile?.name ?: "unknown"
        val lineNumber = getLineNumber(element)
        return if (lineNumber > 0) "$fileName:$lineNumber" else fileName
    }

private fun getLineNumber(element: PsiElement): Int {
    return try {
        if (!element.isValid) return -1
        val file = element.containingFile ?: return -1
        val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return -1
        val offset = element.textOffset

        // ★ 오프셋 범위 검증
        if (offset < 0 || offset >= document.textLength) return -1

        document.getLineNumber(offset) + 1
    } catch (e: Exception) {
        -1
    }
}

private fun isStandardLibraryElement(element: PsiElement): Boolean {
    // ★ 유효성 먼저 확인
    if (!element.isValid) return false

    if (element is PsiMethod) {
        val qn = element.containingClass?.qualifiedName ?: return false
        return isStandardPackage(qn)
    }
    // ★ PsiField 추가
    if (element is PsiField) {
        val qn = element.containingClass?.qualifiedName ?: return false
        return isStandardPackage(qn)
    }
    if (element is PsiClass) {
        val qn = element.qualifiedName ?: return false
        return isStandardPackage(qn)
    }
    val filePath = element.containingFile?.virtualFile?.path ?: return false
    return filePath.contains("/kotlin-stdlib/") ||
            filePath.contains("/rt.jar!/") ||
            filePath.contains("/jre/") ||
            filePath.contains("/jdk/")
}


    private fun isStandardPackage(qualifiedName: String): Boolean {
        return listOf("java.", "javax.", "sun.", "com.sun.", "kotlin.", "kotlinx.", "org.jetbrains.annotations")
            .any { qualifiedName.startsWith(it) }
    }

private fun findEnclosingDeclaration(element: PsiElement): PsiElement? {
    var current: PsiElement? = element
    while (current != null) {
        // ★ 유효성 확인
        if (!current.isValid) return null

        if (current is PsiMethod) return current
        if (current is PsiClass && current !is PsiAnonymousClass) return current
        if (current is PsiClassInitializer) return current

        val cn = current.javaClass.simpleName
        if (current is PsiNamedElement) {
            when (cn) {
                "KtNamedFunction", "KtPropertyAccessor", "KtProperty",
                "KtClassOrObject", "KtClass", "KtObjectDeclaration",
                "KtAnonymousInitializer", "KtScript" -> return current
            }
        }
        if (current is PsiLambdaExpression || cn == "KtLambdaExpression" || cn == "KtFunctionLiteral") {
            return findEnclosingDeclaration(current.parent) ?: current
        }
        if (current is PsiAnonymousClass) {
            return findEnclosingDeclaration(current.parent) ?: current
        }
        current = current.parent
    }
    return null
}


    private fun formatNameWithRelationType(name: String, relType: RelationType): String {
        if (name.startsWith("[")) return name
        val prefix = when (relType) {
            RelationType.DIRECT_CALL -> ""
            RelationType.POLYMORPHIC -> "[Override] "
            RelationType.SUPER_METHOD -> "[Super] "
            RelationType.DATA_FLOW -> "[DataFlow] "
            RelationType.TEXT_FALLBACK -> "[텍스트폴백] "
        }
        return "$prefix$name"
    }
}

