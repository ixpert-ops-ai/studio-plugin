package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.psi.PsiClass
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.EntityRelation
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.TypeResolver

/**
 * JPA @Entity 클래스 내부의 연관관계를 분석하는 분석기.
 */
class JpaEntityAnalyzer {

    companion object {
        private val RELATION_ANNOTATIONS = mapOf(
            "javax.persistence.OneToOne" to "OneToOne",
            "jakarta.persistence.OneToOne" to "OneToOne",
            "javax.persistence.OneToMany" to "OneToMany",
            "jakarta.persistence.OneToMany" to "OneToMany",
            "javax.persistence.ManyToOne" to "ManyToOne",
            "jakarta.persistence.ManyToOne" to "ManyToOne",
            "javax.persistence.ManyToMany" to "ManyToMany",
            "jakarta.persistence.ManyToMany" to "ManyToMany"
        )
    }

    fun analyze(psiClass: PsiClass): List<EntityRelation> {
        val relations = mutableListOf<EntityRelation>()
        
        // 필드 단위 분석
        for (field in psiClass.fields) {
            for ((annotationFqn, relationType) in RELATION_ANNOTATIONS) {
                if (field.hasAnnotation(annotationFqn)) {
                    val rawType = field.type.canonicalText
                    val targetEntity = TypeResolver.unwrapGenericType(rawType)
                    
                    relations.add(EntityRelation(
                        type = relationType,
                        targetEntity = targetEntity,
                        fieldName = field.name
                    ))
                    break // 하나의 필드에 두 개 이상의 매핑 어노테이션이 올 수 없음
                }
            }
        }
        
        return relations
    }
}
