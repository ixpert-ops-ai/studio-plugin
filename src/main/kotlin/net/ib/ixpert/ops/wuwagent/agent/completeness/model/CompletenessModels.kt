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
    abstract fun match(anchorPath: String, ctx: GraphMatchContext): List<MatchResult>
    fun isPrecisionAvailable(ctx: GraphMatchContext): Boolean = ctx.capabilities.containsAll(requires)

    object SameBasenameImpl : MatchStrategy(emptySet()) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): List<MatchResult> {
            val base = ctx.baseName(anchorPath)
            val target = "${base}Impl"
            val hit = ctx.filesUnder(ctx.dirOf(anchorPath)).firstOrNull { ctx.baseName(it) == target }
            return if (hit != null)
                listOf(MatchResult(hit, existsInGraph = true, MatchMode.PRECISION, 1.0, "basename+Impl 규칙 일치"))
            else
                listOf(MatchResult(null, existsInGraph = false, MatchMode.PRECISION, 1.0, "$target 미발견 (생성 필요)"))
        }
    }

    object ImplementedInterfacesMatch : MatchStrategy(emptySet()) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): List<MatchResult> {
            val anchorNode = ctx.getFileNode(anchorPath)
            if (anchorNode == null) return listOf(MatchResult(null, existsInGraph = false, MatchMode.PRECISION, 1.0, "앵커 노드 없음"))
            
            // Search for any node that implements this interface
            val hitNode = ctx.allNodes().firstOrNull { node ->
                node.implementedInterfaces.contains(anchorNode.className) ||
                node.implementedInterfaces.any { it.endsWith(".${anchorNode.className}") }
            }
            
            val hitPath = hitNode?.path
            
            if (hitPath != null)
                return listOf(MatchResult(hitPath, existsInGraph = true, MatchMode.PRECISION, 1.0, "implementedInterfaces 매칭"))

            // Fallback: implementedInterfaces가 비어있는 경우 basename+Impl 컨벤션으로 검색
            return SameBasenameImpl.match(anchorPath, ctx)
        }
    }

    class DomainPrefixVO(val targetSuffix: String) : MatchStrategy(emptySet()) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): List<MatchResult> {
            val base = ctx.baseName(anchorPath)
            // e.g., ACAMTBAPC001DEM -> ACAMTBAPC001, APCMMPsnzInfSVC -> APCMMPsnzInf
            // We assume the prefix is everything before the known suffix like DEM, DQM, SVC, BIZ
            val prefix = base.replace(Regex("(DEM|DQM|SVCImpl|SVC|BIZ)$"), "")
            val target = "$prefix$targetSuffix"
            // Search all files for this exact basename
            val hit = ctx.allFiles().firstOrNull { ctx.baseName(it).equals(target, true) }

            if (hit != null) return listOf(MatchResult(hit, existsInGraph = true, MatchMode.PRECISION, 1.0, "도메인 프리픽스 매칭 ($target)"))

            // Fallback: check dependencies of the anchor or its known delegates
            val anchorNode = ctx.getFileNode(anchorPath)
            if (anchorNode != null) {
                // First check direct dependencies of BIZ
                val directHit = anchorNode.dependsOn.firstOrNull { it.endsWith(targetSuffix + ".java") }
                if (directHit != null) {
                    return listOf(MatchResult(directHit, existsInGraph = true, MatchMode.PRECISION, 1.0, "의존성 그래프 매칭 ($targetSuffix)"))
                }
                
                // Then check dependencies of any DEM/DQM that BIZ calls
                val delegates = anchorNode.dependsOn.filter { it.endsWith("DEM.java") || it.endsWith("DQM.java") }
                for (del in delegates) {
                    val delNode = ctx.getFileNode(del)
                    if (delNode != null) {
                        val delHit = delNode.dependsOn.firstOrNull { it.endsWith(targetSuffix + ".java") }
                        if (delHit != null) {
                            return listOf(MatchResult(delHit, existsInGraph = true, MatchMode.PRECISION, 1.0, "위임 대상 의존성 매칭 ($targetSuffix)"))
                        }
                    }
                }
            }

            return listOf(MatchResult(null, existsInGraph = false, MatchMode.PRECISION, 1.0, "$target 미발견 (생성 필요)"))
        }
    }

    class EntityDtoMatch(val suffix: String) : MatchStrategy(emptySet()) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): List<MatchResult> {
            val base = ctx.baseName(anchorPath)
            val target = "$base$suffix"
            
            val hits = ctx.allFiles().filter { 
                val name = ctx.baseName(it)
                name.startsWith(base) && name.endsWith(suffix)
            }
            
            if (hits.isNotEmpty()) {
                return hits.map { MatchResult(it, existsInGraph = true, MatchMode.PRECISION, 1.0, "Entity DTO 매칭 ($it)") }
            } else {
                return listOf(MatchResult(null, existsInGraph = false, MatchMode.PRECISION, 1.0, "$target 패턴 미발견 (생성 필요)"))
            }
        }
    }

    class CallChainDelegatingMatch(val linkKind: FileKind, val delegate: MatchStrategy) : MatchStrategy(emptySet()) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): List<MatchResult> {
            val anchorNode = ctx.getFileNode(anchorPath) ?: return emptyList()
            
            val linkCandidates = anchorNode.injections.map { it.targetType } + 
                                 anchorNode.injections.mapNotNull { it.resolvedImpl } +
                                 anchorNode.dependsOn // also include path-based dependencies

            val suffix = linkKind.name
            val linkFound = linkCandidates.firstOrNull { candidate ->
                val name = candidate.substringAfterLast("/").substringBeforeLast(".")
                name.endsWith(suffix, ignoreCase = true)
            } ?: return emptyList() // Rule is inapplicable if precondition fails

            // Resolve link to an actual file path in the graph
            val delegatePath = ctx.getFileNode(linkFound)?.path 
                ?: ctx.allFiles().firstOrNull { it.endsWith("/$linkFound.java") || it.endsWith(linkFound) }
                ?: linkFound

            // Delegate to the inner strategy
            return delegate.match(delegatePath, ctx)
        }
    }

    object SameNamespaceXml : MatchStrategy(setOf(GraphCapability.XML_NAMESPACE_INDEX)) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): List<MatchResult> {
            if (!isPrecisionAvailable(ctx)) {
                // Heuristic Fallback
                val base = ctx.baseName(anchorPath)
                val hit = ctx.allFiles().firstOrNull { it.endsWith("$base.xml") }
                return if (hit != null)
                    listOf(MatchResult(hit, true, MatchMode.HEURISTIC, 0.7, "이름 유사성 매칭 (.xml)"))
                else
                    listOf(MatchResult(null, false, MatchMode.HEURISTIC, 0.7, "$base.xml 미발견"))
            }

            // Precision Match
            val fqcn = ctx.fqcnOf(anchorPath) 
                ?: return listOf(MatchResult(null, false, MatchMode.PRECISION, 1.0, "Java FQCN 식별 불가"))
            
            val xmlPaths = ctx.linkedByNamespace(fqcn)
            val hit = xmlPaths.firstOrNull()
            
            return if (hit != null)
                listOf(MatchResult(hit, true, MatchMode.PRECISION, 1.0, "XML namespace 매칭"))
            else
                listOf(MatchResult(null, false, MatchMode.PRECISION, 1.0, "namespace=$fqcn 인 XML 미발견"))
        }
    }

    object SameFeatureJs : MatchStrategy(setOf(GraphCapability.STATIC_RESOURCE_LINK)) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): List<MatchResult> {
            val base = ctx.baseName(anchorPath)
            
            if (isPrecisionAvailable(ctx)) {
                val jspNode = ctx.getResourceNode(anchorPath)
                if (jspNode != null && jspNode.linkedTo.isNotEmpty()) {
                    val hit = jspNode.linkedTo.firstOrNull { it.endsWith(".js") }
                    if (hit != null) return listOf(MatchResult(hit, true, MatchMode.PRECISION, 1.0, "JSP 내부 스크립트 링크 매칭"))
                }
            }

            // Heuristic
            val hit = ctx.allFiles().firstOrNull { it.endsWith("$base.js") }
            return if (hit != null)
                listOf(MatchResult(hit, true, MatchMode.HEURISTIC, 0.8, "이름 유사성 매칭 (.js)"))
            else
                listOf(MatchResult(null, false, MatchMode.HEURISTIC, 0.8, "$base.js 미발견"))
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
        override fun match(anchorPath: String, ctx: GraphMatchContext): List<MatchResult> {
            val controllers = ctx.existingControllers()
            return if (controllers.isNotEmpty())
                listOf(MatchResult(controllers.first(), true, MatchMode.PRECISION, 1.0, "기존 컨트롤러 식별됨"))
            else
                listOf(MatchResult(null, false, MatchMode.PRECISION, 1.0, "프로젝트 내 컨트롤러 없음"))
        }
    }

    class InjectedByMatch(val targetKind: FileKind) : MatchStrategy(emptySet()) {
        override fun match(anchorPath: String, ctx: GraphMatchContext): List<MatchResult> {
            val anchorNode = ctx.getFileNode(anchorPath)
                ?: return emptyList() // No anchor node, can't find dependents

            // dependsOn and dependedBy are lists of file paths in MetaGraphModels
            val dependents = anchorNode.dependedBy
            val matches = dependents.filter { path ->
                // Unfortunately we can't directly call FileKindClassifier from here easily 
                // without passing it, but MatchStrategy is in the same package!
                net.ib.ixpert.ops.wuwagent.agent.completeness.FileKindClassifier.classify(path, ctx) == targetKind
            }

            return if (matches.isNotEmpty()) {
                matches.map { path ->
                    MatchResult(path, true, MatchMode.PRECISION, 1.0, "역방향 주입(dependedBy) 관계 매칭 ($targetKind)")
                }
            } else {
                listOf(MatchResult(null, false, MatchMode.PRECISION, 1.0, "나를 주입하는 $targetKind 미발견"))
            }
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
    val unclassifiedFiles: List<String>,
    val acceptedDebts: List<CompanionFinding> = emptyList()
) {
    val roleViolations get() = roleFindings.filter { it.violated }
    val companionViolations get() = companionFindings.filter { it.pairing == PairingStrength.MANDATORY && !it.satisfied && !acceptedDebts.contains(it) }
    val isBlocked get() = roleViolations.isNotEmpty() || companionViolations.isNotEmpty()
}
