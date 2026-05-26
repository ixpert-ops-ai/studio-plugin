package net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer

import com.intellij.psi.*
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

/**
 * Anyframe DEM/DQM 클래스 정적 분석기.
 * 클래스 내의 개별 메서드를 PSI 단위로 파싱하여 SQL 본문, 대상 테이블, CRUD 유형,
 * DVO 모델 매핑 및 로컬 이름 속성을 추출합니다.
 */
class AnyframeDemAnalyzer {

    fun analyze(psiClass: PsiClass): List<DemMethodInfo> {
        val methodsInfo = mutableListOf<DemMethodInfo>()
        val className = psiClass.name ?: ""

        for (method in psiClass.methods) {
            // private 또는 생성자 제외 (public 비즈니스 처리 메서드 위주로 분석)
            if (method.isConstructor || !method.hasModifierProperty(PsiModifier.PUBLIC)) continue

            val sql = extractSqlFromMethod(method)
            val sqlId = extractSqlId(method)
            val operationType = determineOperationType(sql, method.name)
            val tables = extractTables(sql, className)
            
            val inputDvoClass = method.parameterList.parameters.firstOrNull()?.type?.canonicalText?.let { canonical ->
                canonical.substringAfterLast("<").substringBefore(">").substringAfterLast(".")
            }

            var returnDvoClass = method.returnType?.canonicalText?.let { canonical ->
                val simpleName = canonical.substringAfterLast("<").substringBefore(">").substringAfterLast(".")
                if (simpleName.endsWith("DVO")) simpleName else null
            }

            // 리턴 타입에서 DVO를 못 찾은 경우 메서드 바디의 RowMapper나 캐스팅 등에서 DVO 검색
            if (returnDvoClass == null) {
                method.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        super.visitElement(element)
                        if (element is PsiTypeElement) {
                            val text = element.type.canonicalText
                            if (text.endsWith("DVO") || text.contains("DVO>")) {
                                val dvo = text.substringAfterLast("<").substringBefore(">").substringAfterLast(".")
                                if (dvo.endsWith("DVO")) {
                                    returnDvoClass = dvo
                                }
                            }
                        }
                    }
                })
            }

            val localNameAnn = method.annotations.firstOrNull {
                val name = it.qualifiedName ?: it.nameReferenceElement?.referenceName ?: ""
                name == "LocalName" || name.endsWith(".LocalName")
            }
            val localName = when (val expr = localNameAnn?.findAttributeValue("value") ?: localNameAnn?.findAttributeValue(null)) {
                is PsiLiteralExpression -> expr.value?.toString()
                null -> null
                else -> expr.text.trim('"')
            }

            methodsInfo.add(
                DemMethodInfo(
                    methodName = method.name,
                    sqlId = sqlId,
                    inputDvoClass = inputDvoClass,
                    returnDvoClass = returnDvoClass,
                    tables = tables,
                    operationType = operationType,
                    localName = localName
                )
            )
        }

        return methodsInfo
    }

    private fun extractSqlFromMethod(method: PsiMethod): String {
        val sqlBuilder = java.lang.StringBuilder()
        method.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element is PsiLiteralExpression) {
                    val value = element.value
                    if (value is String) {
                        sqlBuilder.append(value).append(" ")
                    }
                }
            }
        })
        return sqlBuilder.toString()
    }

    private fun extractSqlId(method: PsiMethod): String? {
        var sqlId: String? = null
        method.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitComment(comment: PsiComment) {
                super.visitComment(comment)
                val text = comment.text
                if (text.contains("SQL_ID", ignoreCase = true)) {
                    val match = Regex("""(?i)SQL_ID\s*:\s*([A-Za-z0-9_]+)""").find(text)
                    if (match != null) {
                        sqlId = match.groupValues[1]
                    } else {
                        val simpleMatch = Regex("""(?i)SQL_ID\s+([A-Za-z0-9_]+)""").find(text)
                        sqlId = simpleMatch?.groupValues[1]
                    }
                }
            }
        })
        return sqlId
    }

    private fun determineOperationType(sql: String, methodName: String): SqlOpType {
        val cleanSql = sql.uppercase().replace(Regex("\\s+"), " ").trim()
        val keywords = listOf("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE")
        
        // 1순위: SQL 본문 첫 키워드 우선
        val firstKeyword = keywords
            .map { it to cleanSql.indexOf(it) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }?.first

        if (firstKeyword != null) {
            return when (firstKeyword) {
                "SELECT" -> SqlOpType.SELECT
                "INSERT" -> SqlOpType.INSERT
                "UPDATE" -> SqlOpType.UPDATE
                "DELETE" -> SqlOpType.DELETE
                "MERGE" -> SqlOpType.UPDATE // MERGE는 보통 UPDATE/INSERT 혼합이나 분석상 UPDATE 계통 처리
                else -> SqlOpType.UNKNOWN
            }
        }

        // 2순위: 메서드명 접두사 보조 판별
        return when {
            methodName.startsWith("sel") || methodName.startsWith("get") || methodName.startsWith("find") -> SqlOpType.SELECT
            methodName.startsWith("ins") || methodName.startsWith("add") || methodName.startsWith("create") -> SqlOpType.INSERT
            methodName.startsWith("upd") || methodName.startsWith("set") || methodName.startsWith("modify") -> SqlOpType.UPDATE
            methodName.startsWith("del") || methodName.startsWith("remove") -> SqlOpType.DELETE
            else -> SqlOpType.UNKNOWN
        }
    }

    private fun extractTables(sql: String, className: String): List<String> {
        val tables = mutableSetOf<String>()

        // 1순위: SQL 본문 정규식 스캔
        val fromRegex = Regex("""(?i)\bFROM\s+([A-Za-z0-9_]+)""")
        val joinRegex = Regex("""(?i)\bJOIN\s+([A-Za-z0-9_]+)""")

        fromRegex.findAll(sql).forEach { match ->
            val tableName = match.groupValues[1]
            if (!isSqlKeyword(tableName)) {
                tables.add(tableName.uppercase())
            }
        }

        joinRegex.findAll(sql).forEach { match ->
            val tableName = match.groupValues[1]
            if (!isSqlKeyword(tableName)) {
                tables.add(tableName.uppercase())
            }
        }

        if (tables.isNotEmpty()) {
            return tables.toList()
        }

        // 2순위: 클래스명 접두사 기반 물리 테이블명 추론 (예: ACAMTBAPC001DEM -> ACAMTBAPC001)
        val cleanedClassName = className.removeSuffix("DEM").removeSuffix("NDEM").removeSuffix("DQM")
        if (cleanedClassName.length >= 5) {
            return listOf(cleanedClassName.uppercase())
        }

        // 3순위: emptyList() 폴백
        return emptyList()
    }

    private fun isSqlKeyword(word: String): Boolean {
        val keywords = setOf(
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "FROM", "WHERE", "ORDER", "GROUP", "HAVING", 
            "LIMIT", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "AND", "OR", "ON", "AS", "IN", "BY"
        )
        return keywords.contains(word.uppercase())
    }
}
