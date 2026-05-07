package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiAnnotation
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ApiEndpoint

/**
 * Spring Controller의 엔드포인트를 분석하는 분석기.
 */
class SpringEndpointAnalyzer {

    companion object {
        private val MAPPING_ANNOTATIONS = mapOf(
            "org.springframework.web.bind.annotation.GetMapping" to "GET",
            "org.springframework.web.bind.annotation.PostMapping" to "POST",
            "org.springframework.web.bind.annotation.PutMapping" to "PUT",
            "org.springframework.web.bind.annotation.DeleteMapping" to "DELETE",
            "org.springframework.web.bind.annotation.PatchMapping" to "PATCH",
            "org.springframework.web.bind.annotation.RequestMapping" to "ALL"
        )
    }

    /**
     * PsiClass 내부의 모든 엔드포인트 메서드를 분석하여 ApiEndpoint 리스트를 반환합니다.
     */
    fun analyze(psiClass: PsiClass): List<ApiEndpoint> {
        val endpoints = mutableListOf<ApiEndpoint>()

        // 1. 클래스 레벨의 RequestMapping 경로 추출
        val classLevelPath = getClassLevelPath(psiClass)

        // 2. 메서드 순회하며 매핑 어노테이션 확인
        for (method in psiClass.methods) {
            val endpoint = extractEndpointFromMethod(method, classLevelPath)
            if (endpoint != null) {
                endpoints.add(endpoint)
            }
        }

        return endpoints
    }

    private fun getClassLevelPath(psiClass: PsiClass): String {
        val requestMapping = psiClass.getAnnotation("org.springframework.web.bind.annotation.RequestMapping")
        return extractPathFromAnnotation(requestMapping)
    }

    private fun extractEndpointFromMethod(method: PsiMethod, classLevelPath: String): ApiEndpoint? {
        for ((annotationFqn, httpMethod) in MAPPING_ANNOTATIONS) {
            val annotation = method.getAnnotation(annotationFqn)
            if (annotation != null) {
                val methodPath = extractPathFromAnnotation(annotation)
                val fullPath = combinePaths(classLevelPath, methodPath)
                
                // ReturnType 추출 (선택적)
                val returnType = method.returnType?.presentableText ?: "void"
                
                return ApiEndpoint(
                    httpMethod = httpMethod,
                    path = fullPath,
                    handlerMethod = method.name,
                    returnType = returnType
                )
            }
        }
        return null
    }

    private fun extractPathFromAnnotation(annotation: PsiAnnotation?): String {
        if (annotation == null) return ""
        
        // "value" 속성이나 "path" 속성 추출
        val valueAttr = annotation.findAttributeValue("value")
        val pathAttr = annotation.findAttributeValue("path")
        
        val targetAttr = valueAttr ?: pathAttr
        
        // PSI에서 텍스트로 추출하면 큰따옴표가 포함되므로 제거
        var pathText = targetAttr?.text?.replace("\"", "") ?: ""
        
        // {} 나 배열 형태일 수 있으므로 단순화 (예: {"/api", "/v1"} -> /api)
        if (pathText.startsWith("{")) {
            pathText = pathText.removePrefix("{").removeSuffix("}").split(",").firstOrNull()?.trim() ?: ""
        }
        
        return pathText
    }

    private fun combinePaths(classPath: String, methodPath: String): String {
        val cPath = if (classPath.endsWith("/")) classPath.dropLast(1) else classPath
        val mPath = if (methodPath.startsWith("/")) methodPath else "/$methodPath"
        
        if (cPath.isEmpty()) return mPath
        if (mPath == "/") return cPath
        
        return cPath + mPath
    }
}
