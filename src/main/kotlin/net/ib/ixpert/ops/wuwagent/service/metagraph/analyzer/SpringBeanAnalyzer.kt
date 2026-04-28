package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.psi.PsiClass
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.BeanDefinition
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.TypeResolver

/**
 * Spring @Configuration 내부의 @Bean 선언을 분석하는 분석기.
 */
class SpringBeanAnalyzer {

    fun analyze(psiClass: PsiClass): List<BeanDefinition> {
        val beans = mutableListOf<BeanDefinition>()
        
        // 1. @Bean 메서드 스캔
        for (method in psiClass.methods) {
            val beanAnnotation = method.getAnnotation("org.springframework.context.annotation.Bean")
            if (beanAnnotation != null) {
                // 빈 이름 추출 (@Bean("name") 또는 @Bean(name="name") 없으면 메서드 이름)
                var beanName = method.name
                val valueAttr = beanAnnotation.findAttributeValue("value")
                val nameAttr = beanAnnotation.findAttributeValue("name")
                val targetAttr = valueAttr ?: nameAttr
                
                val attrText = targetAttr?.text?.replace("\"", "")
                if (!attrText.isNullOrBlank() && !attrText.startsWith("{")) {
                    beanName = attrText
                } else if (attrText?.startsWith("{") == true) {
                    val first = attrText.removePrefix("{").removeSuffix("}").split(",").firstOrNull()?.trim()
                    if (!first.isNullOrBlank()) {
                        beanName = first
                    }
                }
                
                val rawReturnType = method.returnType?.canonicalText ?: "java.lang.Object"
                val returnType = TypeResolver.unwrapGenericType(rawReturnType)
                
                // 파라미터는 의존성을 나타냄
                val dependencies = method.parameterList.parameters.map { param ->
                    TypeResolver.unwrapGenericType(param.type.canonicalText)
                }
                
                beans.add(BeanDefinition(
                    beanName = beanName,
                    returnType = returnType,
                    dependencies = dependencies
                ))
            }
        }
        
        return beans
    }
}
