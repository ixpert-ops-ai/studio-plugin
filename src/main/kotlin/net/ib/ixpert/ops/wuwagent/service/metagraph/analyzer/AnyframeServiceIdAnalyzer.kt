package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.psi.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * Anyframe SVC 인터페이스 분석기.
 * SVC 인터페이스 내의 @ServiceIdMapping 및 @LocalName 어노테이션을 파싱하여
 * 서비스 진입점(ServiceEndpoint) 메타데이터를 수집합니다.
 */
class AnyframeServiceIdAnalyzer {

    fun analyze(psiClass: PsiClass): List<ServiceEndpoint> {
        if (!psiClass.isInterface) return emptyList()

        val endpoints = mutableListOf<ServiceEndpoint>()

        for (method in psiClass.methods) {
            val serviceIdAnn = method.annotations.firstOrNull { 
                val name = it.qualifiedName ?: it.nameReferenceElement?.referenceName ?: ""
                name == "ServiceIdMapping" || name.endsWith(".ServiceIdMapping")
            } ?: continue // @ServiceIdMapping 어노테이션이 있어야 진입점으로 인정합니다.

            val serviceId = when (val expr = serviceIdAnn.findAttributeValue("value") ?: serviceIdAnn.findAttributeValue(null)) {
                is PsiLiteralExpression -> expr.value?.toString()
                null -> null
                else -> expr.text.trim('"')
            } ?: continue

            val localNameAnn = method.annotations.firstOrNull {
                val name = it.qualifiedName ?: it.nameReferenceElement?.referenceName ?: ""
                name == "LocalName" || name.endsWith(".LocalName")
            }
            val localName = when (val expr = localNameAnn?.findAttributeValue("value") ?: localNameAnn?.findAttributeValue(null)) {
                is PsiLiteralExpression -> expr.value?.toString()
                null -> null
                else -> expr.text.trim('"')
            }

            val inputSvo = method.parameterList.parameters.firstOrNull()?.type?.canonicalText?.let {
                // 제네릭 래핑 해제 및 단순 클래스명 추출
                it.substringAfterLast("<").substringBefore(">").substringAfterLast(".")
            }

            val outputSvo = method.returnType?.canonicalText?.let {
                it.substringAfterLast("<").substringBefore(">").substringAfterLast(".")
            }

            endpoints.add(
                ServiceEndpoint(
                    serviceId = serviceId,
                    methodName = method.name,
                    localName = localName,
                    inputSvo = inputSvo,
                    outputSvo = outputSvo
                )
            )
        }

        return endpoints
    }
}
