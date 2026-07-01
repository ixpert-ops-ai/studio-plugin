package net.ib.ixpert.ops.wuwagent.agent.completeness.model

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FileNode
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ResourceNode

// 1. Graph Match Context & Capabilities
enum class GraphCapability {
    FILE_PATH_ONLY,
    BASENAME_INDEX,
    XML_NAMESPACE_INDEX,
    CONTROLLER_INDEX,
    STATIC_RESOURCE_LINK
}

interface GraphMatchContext {
    val frameworkType: FrameworkType
    val capabilities: Set<GraphCapability>
    fun linkedByNamespace(daoFqcn: String): List<String>
    fun filesUnder(dir: String): List<String>
    fun existingControllers(): List<String>
    fun fqcnOf(filePath: String): String?
    fun dirOf(filePath: String): String
    fun baseName(filePath: String): String
    fun getFileNode(path: String): FileNode?
    fun getResourceNode(path: String): ResourceNode?
    fun allFiles(): List<String>
    fun allNodes(): Collection<FileNode>
}

// 2. Roles, Kinds, Policies
enum class ArchRole {
    ENTRYPOINT, BUSINESS, PERSISTENCE, VIEW, DATA
}

enum class FileKind {
    DAO_INTERFACE, DAO_IMPL, MYBATIS_XML,
    SERVICE_INTERFACE, SERVICE_IMPL,
    CONTROLLER, JSP_VIEW, JS_SCRIPT,
    
    // For 2nd Phase Extensions
    JPA_REPOSITORY, ENTITY, RESPONSE_DTO, REQUEST_DTO,
    POJO_DTO, SVO, BVO, DVO,
    
    // For Anyframe AP
    BIZ, DEM, DQM
}

enum class PairingStrength {
    MANDATORY, RECOMMENDED
}

enum class CreationPolicy {
    PREFER_MODIFY_EXISTING, ALLOW_NEW
}

enum class EntrypointTrigger {
    ALWAYS, USER_ACTION_PRESENT, NEVER
}

enum class CompanionTrigger { 
    ON_NEW_METHOD, ON_ANY_CHANGE 
}

// 3. Ruleset definitions
data class EntrypointRule(
    val realizationKind: FileKind,
    val requiredWhen: EntrypointTrigger,
    val creationPolicy: CreationPolicy
)

data class CompanionRule(
    val kind: FileKind,
    val pairing: PairingStrength,
    val matchBy: MatchStrategy,
    val trigger: CompanionTrigger
)

data class RoleRealization(
    val role: ArchRole,
    val anchorKind: FileKind,
    val companions: List<CompanionRule>,
    val preferModifyExisting: Boolean
)

data class FrameworkRuleset(
    val frameworkType: FrameworkType,
    val roleRealizations: List<RoleRealization>,
    val entrypointRule: EntrypointRule
)

// 4. Match Results and Strategies
enum class MatchMode { PRECISION, HEURISTIC }

data class MatchResult(
    val matchedPath: String?,
    val existsInGraph: Boolean,
    val mode: MatchMode,
    val confidence: Double,
    val note: String
) {
    val matched get() = matchedPath != null
}

sealed class MatchStrategy(val requires: Set<GraphCapability>) {
    abstract fun match(anchorPath: String, ctx: GraphMatchContext): MatchResult
    fun isPrecisionAvailable(ctx: GraphMatchContext): Boolean = ctx.capabilities.containsAll(requires)

    object SameBasenameImpl : MatchStrategy(emptySet()) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): MatchResult {
            val base = ctx.baseName(anchorPath)
            val target = "${base}Impl"
            val hit = ctx.filesUnder(ctx.dirOf(anchorPath)).firstOrNull { ctx.baseName(it) == target }
            return if (hit != null)
                MatchResult(hit, existsInGraph = true, MatchMode.PRECISION, 1.0, "basename+Impl 규칙 일치")
            else
                MatchResult(null, existsInGraph = false, MatchMode.PRECISION, 1.0, "$target 미발견 (생성 필요)")
        }
    }

    object ImplementedInterfacesMatch : MatchStrategy(emptySet()) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): MatchResult {
            val anchorNode = ctx.getFileNode(anchorPath)
            if (anchorNode == null) return MatchResult(null, existsInGraph = false, MatchMode.PRECISION, 1.0, "앵커 노드 없음")
            
            // Search for any node that implements this interface
            val hitNode = ctx.allNodes().firstOrNull { node ->
                node.implementedInterfaces.contains(anchorNode.className) ||
                node.implementedInterfaces.any { it.endsWith(".${anchorNode.className}") }
            }
            
            val hitPath = hitNode?.path
            
            if (hitPath != null)
                return MatchResult(hitPath, existsInGraph = true, MatchMode.PRECISION, 1.0, "implementedInterfaces 매칭")

            // Fallback: implementedInterfaces가 비어있는 경우 basename+Impl 컨벤션으로 검색
            return SameBasenameImpl.match(anchorPath, ctx)
        }
    }

    class DomainPrefixVO(val targetSuffix: String) : MatchStrategy(emptySet()) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): MatchResult {
            val base = ctx.baseName(anchorPath)
            // e.g., ACAMTBAPC001DEM -> ACAMTBAPC001, APCMMPsnzInfSVC -> APCMMPsnzInf
            // We assume the prefix is everything before the known suffix like DEM, DQM, SVC, BIZ
            val prefix = base.replace(Regex("(DEM|DQM|SVCImpl|SVC|BIZ)$"), "")
            val target = "$prefix$targetSuffix"
            // Search all files for this exact basename
            val hit = ctx.allFiles().firstOrNull { ctx.baseName(it).equals(target, true) }
            return if (hit != null)
                MatchResult(hit, existsInGraph = true, MatchMode.PRECISION, 1.0, "도메인 프리픽스 매칭 ($target)")
            else
                MatchResult(null, existsInGraph = false, MatchMode.PRECISION, 1.0, "$target 미발견 (생성 필요)")
        }
    }

    class CallChainDelegatingMatch(val linkKind: FileKind, val delegate: MatchStrategy) : MatchStrategy(emptySet()) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): MatchResult {
            val node = ctx.getFileNode(anchorPath)
            if (node == null) return MatchResult(null, existsInGraph = true, MatchMode.PRECISION, 1.0, "노드 없음 (무시)")
            
            // Find depended nodes that match the linkKind
            val calledPaths = node.dependsOn.filter { dependsPath ->
                val depNode = ctx.getFileNode(dependsPath)
                if (depNode != null) {
                    when (linkKind) {
                        FileKind.DEM -> depNode.className.endsWith("DEM")
                        FileKind.DQM -> depNode.className.endsWith("DQM")
                        FileKind.BIZ -> depNode.className.endsWith("BIZ")
                        else -> false
                    }
                } else false
            }

            if (calledPaths.isEmpty()) {
                // If the target layer is NOT called, then the companion is NOT required.
                // We return anchorPath as matchedPath so that inRequired evaluates to true.
                return MatchResult(anchorPath, existsInGraph = true, MatchMode.PRECISION, 1.0, "호출 체인에 $linkKind 없음 (동반 불필요)")
            }

            // If there are called paths, evaluate the delegate on the FIRST called path.
            // (In a complete implementation, it should evaluate all, but MatchStrategy returns one MatchResult)
            val results = calledPaths.map { depPath ->
                val delegateResult = delegate.match(depPath, ctx)
                // Note: VO(BVO/DVO/SVO)는 메서드 파라미터/반환 타입으로 사용되어
                // dependsOn에 나타나지 않는 경우가 많으므로, delegate 매칭 결과를 그대로 신뢰합니다.
                delegateResult
            }
            val missing = results.firstOrNull { !it.matched }
            
            return missing ?: results.first()
        }
    }

    object SameNamespaceXml : MatchStrategy(setOf(GraphCapability.XML_NAMESPACE_INDEX)) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): MatchResult {
            if (isPrecisionAvailable(ctx)) {
                val fqcn = ctx.fqcnOf(anchorPath)
                if (fqcn != null) {
                    val xmls = ctx.linkedByNamespace(fqcn).filter { it.endsWith(".xml") }
                    if (xmls.isNotEmpty())
                        return MatchResult(xmls.first(), existsInGraph = true, MatchMode.PRECISION, 1.0, "namespace_binding 엣지로 매핑")
                    return MatchResult(null, existsInGraph = false, MatchMode.PRECISION, 1.0, "namespace=$fqcn 인 XML 미발견 (생성/매핑 필요)")
                }
            }
            return degradeToBasenameXml(anchorPath, ctx)
        }

        private fun degradeToBasenameXml(anchorPath: String, ctx: GraphMatchContext): MatchResult {
            val base = ctx.baseName(anchorPath)
            val hit = ctx.filesUnder(ctx.dirOf(anchorPath)).firstOrNull { it.endsWith(".xml") && ctx.baseName(it).equals(base, true) }
            return MatchResult(hit, existsInGraph = hit != null, MatchMode.HEURISTIC, if (hit != null) 0.6 else 0.0, "XML_NAMESPACE_INDEX 미보유 → basename 휴리스틱으로 강등")
        }
    }

    object SameFeatureJs : MatchStrategy(setOf(GraphCapability.STATIC_RESOURCE_LINK)) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): MatchResult {
            val base = ctx.baseName(anchorPath)
            val candidates = featureNameVariants(base)
            val featureSeg = base.substringBefore('_').substringBefore('.')
            val jsFiles = ctx.filesUnder(jsRootFor(featureSeg, ctx)).filter { it.endsWith(".js") }
            val hit = jsFiles.firstOrNull { js ->
                val jb = ctx.baseName(js)
                candidates.any { it.equals(jb, true) }
            }
            return MatchResult(hit, existsInGraph = hit != null, MatchMode.HEURISTIC, if (hit != null) 0.5 else 0.0, "STATIC_RESOURCE_LINK 미보유 → 네이밍/디렉터리 휴리스틱")
        }

        private fun featureNameVariants(base: String): List<String> {
            val parts = base.split('_', '.')
            return listOf(
                parts.joinToString("."),
                parts.joinToString("_"),
                parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercase) }
            )
        }
        private fun jsRootFor(feature: String, ctx: GraphMatchContext): String = "resources/js/$feature"
    }

    object ExistingController : MatchStrategy(setOf(GraphCapability.CONTROLLER_INDEX)) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): MatchResult {
            val controllers = ctx.existingControllers()
            return if (controllers.isNotEmpty())
                MatchResult(controllers.first(), existsInGraph = true, MatchMode.PRECISION, 0.9, "기존 컨트롤러 재사용 권장: ${controllers.first()}")
            else
                MatchResult(null, existsInGraph = false, MatchMode.PRECISION, 1.0, "기존 컨트롤러 없음 → 신규 허용")
        }
    }
}

// 5. Findings and Report
data class SrFacts(
    val hasUserAction: Boolean,
    val readsOrWritesData: Boolean,
    val hasBusinessLogic: Boolean,
    val touchesUi: Boolean,
    val addsNewMethod: Boolean
)

data class RoleNecessity(val role: ArchRole, val required: Boolean, val reason: String)

data class DegradeNote(
    val role: ArchRole, val companionKind: FileKind,
    val strategy: String, val missing: Set<GraphCapability>
)

data class RulesetResolution(val ruleset: FrameworkRuleset, val degraded: List<DegradeNote>) {
    val isFullyPrecise get() = degraded.isEmpty()
}

data class RoleFinding(
    val role: ArchRole, val anchorKind: FileKind?,
    val present: Boolean, val suggestion: String, val reason: String
) {
    val violated get() = !present
}

data class CompanionFinding(
    val role: ArchRole, val anchorPath: String,
    val companionKind: FileKind, val pairing: PairingStrength,
    val trigger: CompanionTrigger, val result: MatchResult,
    val existsInRequiredSet: Boolean
) {
    val satisfied get() = existsInRequiredSet
    val isNewFileNeeded get() = !result.existsInGraph
    fun overrideKey() = "$anchorPath::$companionKind"
}

data class CompletenessReport(
    val frameworkType: FrameworkType,
    val degradedStrategies: List<DegradeNote>,
    val roleFindings: List<RoleFinding>,
    val companionFindings: List<CompanionFinding>,
    val unclassifiedFiles: List<String>
) {
    val roleViolations get() = roleFindings.filter { it.violated }
    val companionViolations get() = companionFindings.filter { it.pairing == PairingStrength.MANDATORY && !it.satisfied }
    val isBlocked get() = roleViolations.isNotEmpty() || companionViolations.isNotEmpty()
}
