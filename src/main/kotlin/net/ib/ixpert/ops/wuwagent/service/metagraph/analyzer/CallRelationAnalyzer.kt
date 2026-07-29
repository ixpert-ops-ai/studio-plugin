package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeVisitor
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.PsiTreeUtil
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.Relationship
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.RelationshipType

/**
 * 프로젝트 내부 클래스 간의 메서드 호출(CALLS) 관계를 추출하는 분석기.
 */
class CallRelationAnalyzer {
    private val logger = Logger.getInstance(CallRelationAnalyzer::class.java)

    /**
     * 특정 클래스 내부의 모든 메서드를 순회하며 메서드 호출 관계를 수집합니다.
     * 외부 라이브러리(Java 표준, Spring 프레임워크 등) 호출은 제외합니다.
     *
     * @param sourceClass 분석 대상 PsiClass
     * @param projectBasePath 프로젝트의 루트 경로 (상대 경로 계산용)
     * @return 수집된 Relationship 리스트 (source, target 모두 상대 경로)
     */
    fun analyze(sourceClass: PsiClass, projectBasePath: String): List<Relationship> {
        val relationships = mutableSetOf<Relationship>() // 중복 방지를 위해 Set 사용
        
        val sourceVirtualFile = sourceClass.containingFile?.virtualFile ?: return emptyList()
        val sourcePath = sourceVirtualFile.path.removePrefix(projectBasePath).removePrefix("/")
        val sourceClassName = sourceClass.qualifiedName ?: sourceClass.name ?: return emptyList()

        var unresolvedCount = 0

        val methods = sourceClass.methods
        for (method in methods) {
            val methodCalls = PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression::class.java)
            for (call in methodCalls) {
                try {
                    val resolvedMethod = call.resolveMethod()
                    if (resolvedMethod == null) {
                        unresolvedCount++
                        continue
                    }
                    
                    val targetClass = resolvedMethod.containingClass ?: continue
                    val targetClassName = targetClass.qualifiedName ?: targetClass.name ?: continue

                    // 필터링: 자기 자신 호출 제외
                    if (targetClassName == sourceClassName) continue
                    
                    // 외부 라이브러리 제외
                    if (targetClassName.startsWith("java.") ||
                        targetClassName.startsWith("javax.") ||
                        targetClassName.startsWith("org.springframework.") ||
                        targetClassName.startsWith("org.apache.") ||
                        targetClassName.startsWith("org.slf4j.") ||
                        targetClassName.startsWith("lombok.") ||
                        targetClassName.startsWith("kotlin.")) {
                        continue
                    }

                    // 대상 파일의 상대 경로 계산
                    val targetVirtualFile = targetClass.containingFile?.virtualFile ?: continue
                    val targetPath = targetVirtualFile.path.removePrefix(projectBasePath).removePrefix("/")

                    if (targetPath == sourcePath) continue

                    // STATIC vs INSTANCE 판별
                    val callType = if (resolvedMethod.hasModifierProperty(PsiModifier.STATIC)) {
                        "STATIC"
                    } else {
                        "INSTANCE"
                    }

                    val methodName = resolvedMethod.name
                    relationships.add(
                        Relationship(
                            source = sourcePath,
                            target = targetPath,
                            type = RelationshipType.CALLS,
                            detail = "$methodName()",
                            callType = callType
                        )
                    )
                } catch (e: Exception) {
                    logger.debug("Failed to resolve method call in ${sourceClassName}.${method.name}: ${e.message}")
                }
            }
        }

        for (method in methods) {
            val signatureTypes = mutableSetOf<PsiType>()
            method.returnType?.let { signatureTypes.add(it) }
            for (param in method.parameterList.parameters) {
                signatureTypes.add(param.type)
            }
            
            val visitor = object : PsiTypeVisitor<Unit>() {
                override fun visitClassType(classType: PsiClassType) {
                    val className = classType.className
                    val resolvedClass = classType.resolve()
                    if (resolvedClass != null) {
                        val targetClassName = resolvedClass.qualifiedName ?: resolvedClass.name
                        if (targetClassName != null && targetClassName != sourceClassName) {
                            val targetVirtualFile = resolvedClass.containingFile?.virtualFile
                            if (targetVirtualFile != null && targetVirtualFile.path.startsWith(projectBasePath)) {
                                val targetPath = targetVirtualFile.path.removePrefix(projectBasePath).removePrefix("/")
                                if (targetPath != sourcePath) {
                                    relationships.add(
                                        Relationship(
                                            source = sourcePath,
                                            target = targetPath,
                                            type = RelationshipType.USES_TYPE,
                                            detail = "Signature type"
                                        )
                                    )
                                }
                            }
                        }
                    }
                    for (param in classType.parameters) {
                        param.accept(this)
                    }
                }
                
                override fun visitArrayType(arrayType: PsiArrayType) {
                    arrayType.componentType.accept(this)
                }
                
                override fun visitWildcardType(wildcardType: PsiWildcardType) {
                    wildcardType.bound?.accept(this)
                }
            }
            
            for (type in signatureTypes) {
                type.accept(visitor)
            }
        }

        if (unresolvedCount > 0) {
            logger.debug("[$sourcePath] CallRelationAnalyzer: $unresolvedCount method calls could not be resolved (e.g. Lombok, generics).")
        }

        val callTargets = relationships.filter { it.type == RelationshipType.CALLS }.map { it.target }.toSet()
        val finalRelationships = relationships.filter { 
            it.type != RelationshipType.USES_TYPE || it.target !in callTargets 
        }

        return finalRelationships.toList()
    }
}
