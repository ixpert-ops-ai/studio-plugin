package net.ib.ixpert.ops.wuwagent.service.metagraph.builder

data class ScanPattern(
    val category: String,       // "api_url", "namespace", "sql_id", "include", "field", "anyframe_query_id"
    val regex: Regex,
    val groupIndex: Int = 1     // 캡처 그룹 번호
)

object ScanProfiles {

    // ═══════════════════════════════════════════
    // Phase 1: Spring MVC + MyBatis + JSP + jQuery + Anyframe + Thymeleaf
    // ═══════════════════════════════════════════

    val MYBATIS_XML = listOf(
        ScanPattern("namespace",
            Regex("""namespace\s*=\s*["']([^"']+)["']""")),
        ScanPattern("sql_id",
            Regex("""<(?:select|insert|update|delete)\s+id\s*=\s*["']([^"']+)["']""")),
        ScanPattern("parameter_type",
            Regex("""parameterType\s*=\s*["']([^"']+)["']""")),
        ScanPattern("result_type",
            Regex("""resultType\s*=\s*["']([^"']+)["']""")),
        ScanPattern("table_name",
            Regex("""(?:FROM|INTO|UPDATE|JOIN)\s+([A-Z][A-Z0-9_]+)""", RegexOption.IGNORE_CASE)),
        ScanPattern("include_ref",
            Regex("""<include\s+refid\s*=\s*["']([^"']+)["']""")),
            
        // Anyframe QueryService 활성 패턴
        ScanPattern("anyframe_query_id",
            Regex("""<query\s+id\s*=\s*["']([^"']+)["']""")),
        ScanPattern("anyframe_table",
            Regex("""<table\s+name\s*=\s*["']([^"']+)["']"""))
    )

    val JQUERY_JS = listOf(
        ScanPattern("api_url",
            Regex("""\$\.(?:ajax|get|post|getJSON)\s*\(\s*(?:\{[^}]*?url\s*:\s*|)["'`]([^"'`]+)["'`]""")),
        ScanPattern("api_url",
            Regex("""url\s*:\s*["'`]([^"'`]+\.do)["'`]""")),
        ScanPattern("api_url",
            Regex("""url\s*:\s*["'`]([^"'`]*?/api/[^"'`]+)["'`]""")),
        ScanPattern("field",
            Regex("""(?:data|result|response)\s*\.\s*([a-zA-Z_]\w+)""")),
        ScanPattern("field",
            Regex("""\$\s*\(\s*["']#([a-zA-Z_]\w+)["']\s*\)\s*\.val\s*\(""")),
            
        // Anyframe Call
        ScanPattern("anyframe_call",
            Regex("""queryService\.(?:find|create|update|remove)\s*\(\s*["']([^"']+)["']"""))
    )

    val JSP_VIEW = listOf(
        ScanPattern("script_src",
            Regex("""<script[^>]+src\s*=\s*["']([^"']+\.js)["']""")),
        ScanPattern("form_action",
            Regex("""<form[^>]+action\s*=\s*["']([^"']+)["']""")),
        ScanPattern("include",
            Regex("""<%@\s*include\s+file\s*=\s*["']([^"']+)["']""")),
        ScanPattern("jsp_include",
            Regex("""<jsp:include\s+page\s*=\s*["']([^"']+)["']""")),
        ScanPattern("input_field",
            Regex("""<input[^>]+name\s*=\s*["']([^"']+)["']"""))
    )

    val CONFIG_PROPERTIES = listOf(
        ScanPattern("config_key",
            Regex("""^([a-zA-Z][a-zA-Z0-9._-]+)\s*=""", RegexOption.MULTILINE))
    )

    val THYMELEAF_VIEW = listOf(
        ScanPattern("api_url",
            Regex("""th:(?:action|href|src)\s*=\s*["']@\{([^}]+)\}["']""")),
        ScanPattern("include",
            Regex("""th:(?:replace|insert|include)\s*=\s*["']~?\{([^}]+)\}["']""")),
        ScanPattern("field",
            Regex("""th:field\s*=\s*["']\*\{([^}]+)\}["']""")),
        ScanPattern("field",
            Regex("""th:(?:text|value)\s*=\s*["']\$\{([^}]+)\}["']"""))
    )

    val MODERN_JS = listOf(
        ScanPattern("base_url",
            Regex("""baseURL\s*:\s*["'`]([^"'`]+)["'`]""")),
        ScanPattern("api_url",
            Regex("""fetch\s*\(\s*["'`]([^"'`]+)["'`]""")),
        ScanPattern("api_url",
            Regex("""axios\s*\.?\s*(?:get|post|put|delete|patch)\s*\(\s*["'`]([^"'`]+)["'`]""")),
        ScanPattern("import",
            Regex("""import\s+.*?\s+from\s+["']([^"']+)["']"""))
    )

    val VUE_SFC = listOf(
        ScanPattern("base_url",
            Regex("""baseURL\s*:\s*["'`]([^"'`]+)["'`]""")),
        ScanPattern("api_url",
            Regex("""(?:api|http|axios)\s*\.?\s*(?:get|post|put|delete)\s*\(\s*["'`]([^"'`]+)["'`]""")),
        ScanPattern("component_import",
            Regex("""import\s+\w+\s+from\s+["'](@/[^"']+)["']""")),
        ScanPattern("emit",
            Regex("""emit\s*\(\s*["']([^"']+)["']"""))
    )

    // ═══════════════════════════════════════════
    // 프레임워크 감지 → 프로필 매핑
    // ═══════════════════════════════════════════

    fun getProfileForExtension(extension: String): List<ScanPattern> {
        return when (extension.lowercase()) {
            "xml" -> MYBATIS_XML
            "js" -> JQUERY_JS + MODERN_JS
            "ts", "tsx" -> MODERN_JS
            "jsp" -> JSP_VIEW
            "html" -> THYMELEAF_VIEW + JSP_VIEW  // 겸용 시도
            "vue" -> VUE_SFC + MODERN_JS
            "properties" -> CONFIG_PROPERTIES
            "yml", "yaml" -> CONFIG_PROPERTIES   // yml도 key 추출 가능
            else -> emptyList()
        }
    }
}
