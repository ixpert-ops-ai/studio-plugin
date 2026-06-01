package net.ib.ixpert.ops.wuwagent.service.analysis.model

/**
 * PSI, Tree-sitter, 정규식 등 모든 구조 추출 결과를 담는 통일 포맷.
 * 어떤 추출 방식을 사용하든 이 형태로 변환된 뒤 프롬프트 빌더에 전달됩니다.
 */
data class ExtractedStructure(
    val packageName: String? = null,
    val symbols: List<SymbolInfo> = emptyList(),
    val fields: List<FieldInfo> = emptyList(),
    val imports: List<String> = emptyList(),
    val classes: List<ClassInfo> = emptyList(),
    val thymeleafStructure: ThymeleafStructure? = null,
    val rawCode: String? = null,
    val extractionMethod: ExtractionMethod = ExtractionMethod.RAW
) {
    fun hasStructure(): Boolean = symbols.isNotEmpty() || classes.isNotEmpty()

    fun getKeySymbols(maxCount: Int = 10): List<SymbolInfo> {
        return symbols
            .filter { it.bodyText.isNotBlank() }
            .sortedByDescending { it.bodyText.length }
            .take(maxCount)
    }

    fun getExternalDependencies(): List<String> {
        return imports.filter { imp ->
            !imp.contains("net.ib.ixpert") && 
            !imp.contains("kr.co.ib") &&
            !imp.startsWith("java.") &&
            !imp.startsWith("javax.")
        }
    }

    companion object {
        fun rawOnly(code: String) = ExtractedStructure(
            rawCode = code,
            extractionMethod = ExtractionMethod.RAW
        )
    }
}

data class SymbolInfo(
    val name: String,
    val kind: SymbolKind,
    val params: List<ParamInfo> = emptyList(),
    val returnType: String? = null,
    val startLine: Int,
    val endLine: Int,
    val bodyText: String = "",
    val isExported: Boolean = false,
    val isAsync: Boolean = false,
    val isStatic: Boolean = false,
    val parentClass: String? = null,
    val annotations: List<String> = emptyList()
)

data class ParamInfo(
    val name: String,
    val type: String? = null
) {
    override fun toString(): String {
        return if (type != null) "$name: $type" else name
    }
}

data class FieldInfo(
    val name: String,
    val type: String,
    val annotationTexts: List<String> = emptyList(),
    val isStatic: Boolean = false,
    val isFinal: Boolean = false,
    val parentClass: String? = null
)

data class ClassInfo(
    val name: String,
    val kind: ClassKind = ClassKind.CLASS,
    val superClass: String? = null,
    val interfaces: List<String> = emptyList(),
    val annotations: List<String> = emptyList(),
    val line: Int
)

enum class SymbolKind(val displayName: String) {
    FUNCTION("함수"),
    METHOD("메서드"),
    CONSTRUCTOR("생성자"),
    ARROW_FUNCTION("화살표 함수"),
    EVENT_HANDLER("이벤트 핸들러"),
    GETTER("Getter"),
    SETTER("Setter"),
    INTERFACE_METHOD("인터페이스 메서드"),
    TYPE_ALIAS("타입 별칭")
}

enum class ClassKind(val displayName: String) {
    CLASS("클래스"),
    INTERFACE("인터페이스"),
    ENUM("열거형"),
    OBJECT("오브젝트"),
    ABSTRACT_CLASS("추상 클래스"),
    DATA_CLASS("데이터 클래스")
}

enum class ExtractionMethod(val displayName: String) {
    PSI("IntelliJ PSI"),
    TREE_SITTER("Tree-sitter"),
    REGEX("정규식 패턴"),
    RAW("원문 (구조 추출 없음)")
}
