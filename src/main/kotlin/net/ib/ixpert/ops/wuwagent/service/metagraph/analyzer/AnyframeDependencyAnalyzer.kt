package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * Anyframe 통합 의존성 분석기.
 * Spring DI 대신 Anyframe 특유의 정적 Singleton 및 new 팩토리 패턴을 단일 컴포넌트에서 완전히 통합 추적합니다:
 * 1. SINGLETON 의존성 추적: BIZ 내 필드 선언문 중 XXXDEM.getInstance() 또는 XXXDQM.getInstance() 호출 BIZ -> DEM/DQM
 * 2. NEW_INSTANCE 의존성 추적: SVCImpl 내에서 new XxxBIZ() 호출 패턴 감지 SVCImpl -> BIZ
 */
class AnyframeDependencyAnalyzer {

    private val logger = Logger.getInstance(AnyframeDependencyAnalyzer::class.java)

    fun analyze(sourceClass: PsiClass, projectBasePath: String): List<Relationship> {
        val relationships = mutableListOf<Relationship>()
        val sourceVirtualFile = sourceClass.containingFile?.virtualFile ?: return emptyList()
        val sourcePath = sourceVirtualFile.path.removePrefix(projectBasePath).removePrefix("/")

        // 1. SINGLETON (getInstance) 의존성 추적: BIZ -> DEM/DQM
        val methodCalls = PsiTreeUtil.findChildrenOfType(sourceClass, PsiMethodCallExpression::class.java)
        for (call in methodCalls) {
            val methodName = call.methodExpression.referenceName
            if (methodName == "getInstance") {
                val qualifier = call.methodExpression.qualifierExpression
                if (qualifier != null) {
                    val targetClass = (qualifier.reference as? PsiReference)?.resolve() as? PsiClass
                    if (targetClass != null) {
                        val className = targetClass.name ?: ""
                        if (className.endsWith("DEM") || className.endsWith("NDEM") || className.endsWith("DQM")) {
                            val targetVirtualFile = targetClass.containingFile?.virtualFile
                            if (targetVirtualFile != null) {
                                val targetPath = targetVirtualFile.path.removePrefix(projectBasePath).removePrefix("/")
                                relationships.add(
                                    Relationship(
                                        source = sourcePath,
                                        target = targetPath,
                                        type = RelationshipType.CALLS_DEM_METHOD,
                                        strength = RelationshipStrength.DIRECT,
                                        detail = "getInstance()",
                                        metadata = mapOf("bindingType" to "SINGLETON")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. NEW_INSTANCE 의존성 추적: SVCImpl -> BIZ
        val newExpressions = PsiTreeUtil.findChildrenOfType(sourceClass, PsiNewExpression::class.java)
        for (newExpr in newExpressions) {
            val classRef = newExpr.classReference
            if (classRef != null) {
                val targetClass = classRef.resolve() as? PsiClass
                if (targetClass != null) {
                    val className = targetClass.name ?: ""
                    if (className.endsWith("BIZ")) {
                        val targetVirtualFile = targetClass.containingFile?.virtualFile
                        if (targetVirtualFile != null) {
                            val targetPath = targetVirtualFile.path.removePrefix(projectBasePath).removePrefix("/")
                            relationships.add(
                                Relationship(
                                    source = sourcePath,
                                    target = targetPath,
                                    type = RelationshipType.CALLS_BIZ,
                                    strength = RelationshipStrength.DIRECT,
                                    detail = "new BIZ()",
                                    metadata = mapOf("bindingType" to "NEW_INSTANCE")
                                )
                            )
                        }
                    }
                }
            }
        }

        return relationships
    }
}
