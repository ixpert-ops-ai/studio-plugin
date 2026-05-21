package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * Anyframe BIZ 호출 분석기.
 * BIZ 클래스 내의 메서드 바디에서 DEM/DQM 멤버 변수를 통한 실제 메서드 호출을 정적 해석하고,
 * FD-CALL 마커 및 주석(/*-FD-CALL-START-(번호)-*/)을 파싱하여 관계 메타데이터에 연계합니다.
 */
class AnyframeBizCallAnalyzer {

    private val logger = Logger.getInstance(AnyframeBizCallAnalyzer::class.java)

    fun analyze(sourceClass: PsiClass, projectBasePath: String): List<Relationship> {
        val relationships = mutableListOf<Relationship>()
        val sourceVirtualFile = sourceClass.containingFile?.virtualFile ?: return emptyList()
        val sourcePath = sourceVirtualFile.path.removePrefix(projectBasePath).removePrefix("/")

        val methods = sourceClass.methods
        for (method in methods) {
            val methodCalls = PsiTreeUtil.findChildrenOfType(method, PsiMethodCallExpression::class.java)
            for (call in methodCalls) {
                try {
                    val resolvedMethod = call.resolveMethod() ?: continue
                    val targetClass = resolvedMethod.containingClass ?: continue
                    val targetClassName = targetClass.name ?: ""

                    if (targetClassName.endsWith("DEM") || targetClassName.endsWith("NDEM") || targetClassName.endsWith("DQM")) {
                        val targetVirtualFile = targetClass.containingFile?.virtualFile ?: continue
                        val targetPath = targetVirtualFile.path.removePrefix(projectBasePath).removePrefix("/")

                        val fdCallId = findFdCallId(call)
                        val metadata = mutableMapOf<String, String>()
                        if (fdCallId != null) {
                            metadata["fdCallId"] = fdCallId
                        }

                        relationships.add(
                            Relationship(
                                source = sourcePath,
                                target = targetPath,
                                type = RelationshipType.CALLS_DEM_METHOD,
                                strength = RelationshipStrength.DIRECT,
                                detail = "${resolvedMethod.name}()",
                                metadata = if (metadata.isNotEmpty()) metadata else null
                            )
                        )
                    }
                } catch (e: Exception) {
                    logger.debug("Failed to resolve method call inside BIZ method: ${e.message}")
                }
            }
        }

        return relationships
    }

    /**
     * 호출 코드 전후의 /*-FD-CALL-START-(번호)-*/ 또는 /*-FD-CALL-END-(번호)-*/ 주석 마커를 스캔하여
     * 흐름 설계 정의 ID를 추출합니다.
     */
    private fun findFdCallId(element: PsiElement): String? {
        // 문장(Statement) 레벨로 이동
        var current: PsiElement? = element
        while (current != null && current !is PsiStatement && current !is PsiClass) {
            current = current.parent
        }
        val statement = current as? PsiStatement ?: return null

        // 1. 해당 문장 바로 직전의 형제 노드들(최대 8개) 중 주석 스캔
        var prev = statement.prevSibling
        var count = 0
        while (prev != null && count < 8) {
            if (prev is PsiComment) {
                val text = prev.text
                val id = extractIdFromComment(text)
                if (id != null) return id
            }
            prev = prev.prevSibling
            count++
        }

        // 2. 문장 내부 자체의 주석 스캔 (예: 코드 라인 끝의 주석 또는 중간 주석)
        var fdCallId: String? = null
        statement.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitComment(comment: PsiComment) {
                super.visitComment(comment)
                val id = extractIdFromComment(comment.text)
                if (id != null) {
                    fdCallId = id
                }
            }
        })

        return fdCallId
    }

    private fun extractIdFromComment(text: String): String? {
        // /*-FD-CALL-START-(0021)-*/ 또는 /*-FD-CALL-START-0021-*/ 등 다양한 표기 대응
        val match = Regex("""FD-CALL-START-[\(-]?([A-Za-z0-9_]+)[\)-]?""").find(text)
        return match?.groupValues?.get(1)
    }
}
