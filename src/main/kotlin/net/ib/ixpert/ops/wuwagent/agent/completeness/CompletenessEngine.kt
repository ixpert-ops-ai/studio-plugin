package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType

object RoleNecessityDeriver {
    fun derive(sr: SrFacts): List<RoleNecessity> = listOf(
        RoleNecessity(ArchRole.ENTRYPOINT,  sr.hasUserAction,     "사용자 액션 존재"),
        RoleNecessity(ArchRole.PERSISTENCE, sr.readsOrWritesData, "DB 데이터 조회/저장 필요"),
        RoleNecessity(ArchRole.BUSINESS,    sr.hasBusinessLogic,  "비즈니스 가공/변환 로직 필요"),
        RoleNecessity(ArchRole.VIEW,        sr.touchesUi,         "화면 변경 포함")
    ).filter { it.required }
}

class CompletenessEngine(
    private val ctx: GraphMatchContext,
    private val debtRegistry: KnownDebtRegistry = JsonKnownDebtRegistry.empty()
) {

    fun evaluate(
        frameworkType: FrameworkType,
        requiredFiles: Set<String>,
        sr: SrFacts
    ): CompletenessReport {
        val resolution = FrameworkRulesetRegistry.resolve(frameworkType, ctx)
        val ruleset = resolution.ruleset
        val necessities = RoleNecessityDeriver.derive(sr)

        // 1. Role Necessity Check
        val roleFindings = necessities.map { need ->
            val rr = ruleset.roleRealizations.firstOrNull { it.role == need.role }
            if (rr == null) {
                RoleFinding(need.role, anchorKind = null, present = true, suggestion = "", reason = need.reason)
            } else {
                val present = requiredFiles.any { kindOf(it) == rr.anchorKind }
                val suggestion = if (!present) buildAnchorSuggestion(rr, need) else ""
                RoleFinding(need.role, rr.anchorKind, present, suggestion, need.reason)
            }
        }

        // 2. Companion File Check
        val companionFindings = mutableListOf<CompanionFinding>()
        val acceptedDebts = mutableListOf<CompanionFinding>()
        
        for (rr in ruleset.roleRealizations) {
            // [FIX] JPA(1:N) 구조에서는 앵커 확산 시 노이즈가 폭발하므로 requiredFiles로 엄격 제한합니다.
            // Anyframe/ISM 등 레거시 프레임워크(1:1:1)에서는 역방향 동반자 추천을 위해 전체 그래프 앵커 확산이 필요합니다.
            val allAnchors = if (frameworkType == FrameworkType.SPRING_BOOT_JPA) {
                requiredFiles.filter { kindOf(it) == rr.anchorKind }.distinct()
            } else {
                val projectAnchors = ctx.allFiles().filter { kindOf(it) == rr.anchorKind }
                (projectAnchors + requiredFiles.filter { kindOf(it) == rr.anchorKind }).distinct()
            }
            
            for (anchor in allAnchors) {
                val matches = rr.companions.flatMap { c ->
                    c.matchBy.match(anchor, ctx).map { res -> c to res }
                }
                
                // anchor가 requiredFiles에 있거나, 직접 companion(non-delegated)이 requiredFiles에 있을 때만 발동
                val directCompanionPaths = matches
                    .filter { it.first.matchBy !is MatchStrategy.CallChainDelegatingMatch }
                    .mapNotNull { it.second.matchedPath }
                val familyPaths = setOf(anchor) + directCompanionPaths
                val isFamilyTouched = familyPaths.any { requiredFiles.contains(it) }
                
                if (isFamilyTouched) {
                    for ((c, res) in matches) {
                        if (c.trigger == CompanionTrigger.ON_NEW_METHOD && !sr.addsNewMethod) continue

                        val inRequired = res.matchedPath?.let { requiredFiles.contains(it) }
                            ?: run {
                                // matchedPath가 null(그래프에 없음)이더라도,
                                // requiredFiles에 같은 basename의 파일이 있으면 충족으로 간주
                                val targetBaseName = res.note.substringBefore(" ").takeIf { it.isNotBlank() }
                                targetBaseName != null && requiredFiles.any { path ->
                                    path.substringAfterLast("/").removeSuffix(".java") == targetBaseName
                                }
                            }
                        
                        val finding = CompanionFinding(
                            role = rr.role, anchorPath = anchor,
                            companionKind = c.kind, pairing = c.pairing,
                            trigger = c.trigger, result = res,
                            existsInRequiredSet = inRequired
                        )
                        
                        val isDuplicate = companionFindings.any { 
                            it.anchorPath == finding.anchorPath && 
                            (
                                (it.result.matchedPath != null && it.result.matchedPath == finding.result.matchedPath) || 
                                (it.result.matchedPath == null && finding.result.matchedPath == null && it.companionKind == finding.companionKind)
                            )
                        }
                        
                        if (!isDuplicate) {
                            companionFindings += finding
                            
                            val isViolation = !finding.result.existsInGraph || !finding.existsInRequiredSet
                            if (isViolation && debtRegistry.isSuppressed(finding)) {
                                acceptedDebts += finding
                            }
                        }
                    }
                }
            }
        }

        val unclassified = requiredFiles.filter { kindOf(it) == null }.toList()

        return CompletenessReport(
            frameworkType, resolution.degraded,
            roleFindings, companionFindings, unclassified, acceptedDebts
        )
    }

    private fun buildAnchorSuggestion(rr: RoleRealization, need: RoleNecessity): String {
        if (rr.role == ArchRole.ENTRYPOINT && rr.preferModifyExisting) {
            val existing = ctx.existingControllers().firstOrNull()
            return if (existing != null)
                "기존 ${rr.anchorKind}($existing)에 엔드포인트 추가를 REQUIRED로 포함하세요."
            else "사용자 액션을 받을 ${rr.anchorKind}가 없습니다. 신규 생성이 필요합니다."
        }
        return "${need.reason} → ${rr.anchorKind} 가 REQUIRED 집합에 없습니다. 해당 역할 파일을 포함하세요."
    }

    private fun kindOf(path: String): FileKind? = FileKindClassifier.classify(path, ctx)
}
