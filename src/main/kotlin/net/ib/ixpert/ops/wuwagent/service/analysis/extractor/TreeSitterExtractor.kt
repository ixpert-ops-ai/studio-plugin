// analysis/extractor/TreeSitterExtractor.kt
package net.ib.ixpert.ops.wuwagent.service.analysis.extractor

import com.intellij.openapi.diagnostic.Logger
import net.ib.ixpert.ops.wuwagent.service.analysis.model.*

/**
 * Tree-sitter 기반 구조 추출기.
 *
 * kotlin-tree-sitter(ktreesitter)를 사용하여 정확한 AST 기반 구조 추출을 수행합니다.
 * PSI가 지원하지 않는 프론트엔드 언어(JS, TS, Vue, CSS, HTML 등)를 대상으로 합니다.
 *
 * [중요] Tree-sitter 네이티브 라이브러리 설정
 * ──────────────────────────────────────────────
 * kotlin-tree-sitter는 언어별 네이티브 grammar 라이브러리(.so/.dylib/.dll)를 필요로 합니다.
 * 현재 JavaScript, TypeScript 등의 grammar는 Maven Central에 미리 빌드되어 있지 않으므로,
 * 직접 빌드하여 플러그인에 번들해야 합니다.
 *
 * 설정 방법:
 * 1. tree-sitter CLI 설치: npm install -g tree-sitter-cli
 * 2. grammar 저장소 클론: git clone https://github.com/tree-sitter/tree-sitter-javascript
 * 3. 빌드: cd tree-sitter-javascript && tree-sitter generate && tree-sitter build
 * 4. 생성된 네이티브 파일을 플러그인의 lib/ 디렉토리에 배치
 * 5. build.gradle.kts에 ktreesitter 의존성 추가:
 *
 *    plugins {
 *        id("io.github.tree-sitter.ktreesitter-plugin") version "0.24.1"
 *    }
 *    dependencies {
 *        implementation("io.github.tree-sitter:ktreesitter:0.24.1")
 *    }
 *
 * Tree-sitter가 설정되지 않은 환경에서는 isAvailable()이 false를 반환하며,
 * CodeAnalysisPipeline이 자동으로 RegexExtractor로 fallback합니다.
 */
class TreeSitterExtractor : StructureExtractor {

    private val log = Logger.getInstance(TreeSitterExtractor::class.java)

    companion object {
        private val SUPPORTED_LANGUAGES = setOf(
            "javascript", "javascriptreact",
            "typescript", "typescriptreact",
            "vue", "html", "css", "scss",
            "python", "swift"
        )

        // Tree-sitter 노드 타입 → 언어별 함수/메서드 선언 노드
        private val FUNCTION_NODE_TYPES = mapOf(
            "javascript" to setOf(
                "function_declaration",
                "method_definition",
                "arrow_function",
                "generator_function_declaration"
            ),
            "typescript" to setOf(
                "function_declaration",
                "method_definition",
                "arrow_function",
                "generator_function_declaration",
                "abstract_method_signature"
            ),
            "python" to setOf(
                "function_definition"
            ),
            "swift" to setOf(
                "function_declaration",
                "initializer_declaration"
            )
        )

        private val CLASS_NODE_TYPES = mapOf(
            "javascript" to setOf("class_declaration", "class"),
            "typescript" to setOf("class_declaration", "class", "interface_declaration"),
            "python" to setOf("class_definition"),
            "swift" to setOf("class_declaration", "protocol_declaration")
        )

        private var available: Boolean? = null

        /**
         * Tree-sitter 네이티브 라이브러리가 로드 가능한지 확인합니다.
         * 최초 호출 시 1회 확인 후 결과를 캐싱합니다.
         */
        fun isAvailable(): Boolean {
            if (available != null) return available!!

            available = try {
                // ktreesitter 클래스 존재 여부로 판별
                Class.forName("io.github.treesitter.ktreesitter.Parser")
                true
            } catch (e: ClassNotFoundException) {
                false
            } catch (e: UnsatisfiedLinkError) {
                // 네이티브 라이브러리 로드 실패
                false
            } catch (e: Exception) {
                false
            }

            return available!!
        }
    }

    override fun supports(languageId: String): Boolean {
        return isAvailable() && SUPPORTED_LANGUAGES.contains(languageId.lowercase())
    }

    override fun extract(code: String, languageId: String): ExtractedStructure {
        if (!isAvailable()) {
            log.info("Tree-sitter를 사용할 수 없어 빈 구조를 반환합니다.")
            return ExtractedStructure.rawOnly(code)
        }

        return try {
            extractWithTreeSitter(code, languageId)
        } catch (e: Exception) {
            log.warn("Tree-sitter 추출 실패: ${e.message}")
            ExtractedStructure.rawOnly(code)
        }
    }

    /**
     * 실제 Tree-sitter 파싱 및 구조 추출.
     *
     * ktreesitter가 클래스패스에 있을 때만 실행됩니다.
     * 리플렉션을 사용하여 컴파일 타임 의존성 없이도 동작하도록 합니다.
     * (플러그인 빌드 시 ktreesitter가 optional dependency인 경우에 대비)
     */
    private fun extractWithTreeSitter(code: String, languageId: String): ExtractedStructure {
        // ── 리플렉션 기반 호출 ──
        // ktreesitter가 optional dependency일 수 있으므로 리플렉션으로 호출합니다.
        // ktreesitter가 필수 dependency로 확정되면 아래 extractDirect()로 교체하세요.

        val normalizedLang = normalizeLang(languageId)
        val symbols = mutableListOf<SymbolInfo>()
        val imports = mutableListOf<String>()
        val classes = mutableListOf<ClassInfo>()

        try {
            // Language 로드
            val languageClass = Class.forName("io.github.treesitter.ktreesitter.Language")
            val parserClass = Class.forName("io.github.treesitter.ktreesitter.Parser")
            val nodeClass = Class.forName("io.github.treesitter.ktreesitter.Node")

            // 언어별 grammar 클래스 로드
            // 예: TreeSitterJavaScript.language() → Long (포인터)
            val grammarClassName = getGrammarClassName(normalizedLang) ?: return ExtractedStructure.rawOnly(code)
            val grammarClass = Class.forName(grammarClassName)
            val languagePtr = grammarClass.getMethod("language").invoke(null)

            // Parser 생성 및 언어 설정
            val language = languageClass.getConstructor(Long::class.java).newInstance(languagePtr)
            val parser = parserClass.getConstructor(languageClass).newInstance(language)

            // 파싱
            val parseMethod = parserClass.getMethod("parse", String::class.java)
            val tree = parseMethod.invoke(parser, code)

            // 루트 노드 획득
            val rootNodeGetter = tree.javaClass.getMethod("getRootNode")
            val rootNode = rootNodeGetter.invoke(tree)

            // AST 순회하여 구조 추출
            traverseNodeReflective(
                node = rootNode,
                nodeClass = nodeClass,
                code = code,
                languageId = normalizedLang,
                symbols = symbols,
                imports = imports,
                classes = classes
            )

            // 리소스 정리
            try {
                parserClass.getMethod("close").invoke(parser)
            } catch (_: Exception) {}

        } catch (e: ClassNotFoundException) {
            log.info("Tree-sitter grammar을 찾을 수 없습니다: $normalizedLang")
            return ExtractedStructure.rawOnly(code)
        } catch (e: Exception) {
            log.warn("Tree-sitter 파싱 오류: ${e.message}")
            return ExtractedStructure.rawOnly(code)
        }

        return ExtractedStructure(
            symbols = symbols,
            imports = imports,
            classes = classes,
            rawCode = code,
            extractionMethod = ExtractionMethod.TREE_SITTER
        )
    }

    /**
     * 리플렉션으로 AST 노드를 순회하며 구조 정보를 추출합니다.
     */
    private fun traverseNodeReflective(
        node: Any,
        nodeClass: Class<*>,
        code: String,
        languageId: String,
        symbols: MutableList<SymbolInfo>,
        imports: MutableList<String>,
        classes: MutableList<ClassInfo>,
        parentClassName: String? = null
    ) {
        val getType = nodeClass.getMethod("getType")
        val getText = nodeClass.getMethod("getText")
        val getChildCount = nodeClass.getMethod("getChildCount")
        val getChild = nodeClass.getMethod("child", Int::class.java)
        val childByFieldName = nodeClass.getMethod("childByFieldName", String::class.java)
        val getStartPoint = nodeClass.getMethod("getStartPoint")
        val getEndPoint = nodeClass.getMethod("getEndPoint")

        val nodeType = getType.invoke(node) as String
        val functionTypes = FUNCTION_NODE_TYPES[languageId] ?: emptySet()
        val classTypes = CLASS_NODE_TYPES[languageId] ?: emptySet()

        // ── Import 추출 ──
        if (nodeType == "import_statement" || nodeType == "import_from_statement") {
            val text = getText.invoke(node) as String
            imports.add(text.trim())
            return // import 문의 자식은 탐색 불필요
        }

        // ── 클래스 추출 ──
        if (nodeType in classTypes) {
            val nameNode = childByFieldName.invoke(node, "name")
            val className = if (nameNode != null) {
                (getText.invoke(nameNode) as String)
            } else "<anonymous>"

            val startPoint = getStartPoint.invoke(node)
            val row = startPoint.javaClass.getField("row").getInt(startPoint)

            val kind = when (nodeType) {
                "interface_declaration" -> ClassKind.INTERFACE
                "protocol_declaration" -> ClassKind.INTERFACE
                else -> ClassKind.CLASS
            }

            classes.add(ClassInfo(
                name = className,
                kind = kind,
                line = row + 1
            ))

            // 클래스 내부 순회 (메서드 추출을 위해)
            val childCount = getChildCount.invoke(node) as Int
            for (i in 0 until childCount) {
                val child = getChild.invoke(node, i) ?: continue
                traverseNodeReflective(
                    child, nodeClass, code, languageId,
                    symbols, imports, classes,
                    parentClassName = className
                )
            }
            return
        }

        // ── 함수/메서드 추출 ──
        if (nodeType in functionTypes) {
            val nameNode = childByFieldName.invoke(node, "name")
            val name = if (nameNode != null) {
                (getText.invoke(nameNode) as String)
            } else {
                // 화살표 함수 등 이름이 없는 경우 → 부모의 variable_declarator에서 이름 추출
                "<anonymous>"
            }

            val paramsNode = childByFieldName.invoke(node, "parameters")
            val params = if (paramsNode != null) {
                extractParamsFromNode(paramsNode, nodeClass)
            } else emptyList()

            val returnTypeNode = childByFieldName.invoke(node, "return_type")
            val returnType = if (returnTypeNode != null) {
                (getText.invoke(returnTypeNode) as String).removePrefix(":").trim()
            } else null

            val startPoint = getStartPoint.invoke(node)
            val endPoint = getEndPoint.invoke(node)
            val startRow = startPoint.javaClass.getField("row").getInt(startPoint)
            val endRow = endPoint.javaClass.getField("row").getInt(endPoint)

            val bodyText = (getText.invoke(node) as String).take(500)

            val kind = when (nodeType) {
                "method_definition", "abstract_method_signature" -> SymbolKind.METHOD
                "arrow_function" -> SymbolKind.ARROW_FUNCTION
                "generator_function_declaration" -> SymbolKind.FUNCTION
                "initializer_declaration" -> SymbolKind.CONSTRUCTOR
                "function_definition" -> { // Python
                    if (parentClassName != null) SymbolKind.METHOD else SymbolKind.FUNCTION
                }
                else -> SymbolKind.FUNCTION
            }

            val nodeText = getText.invoke(node) as String

            symbols.add(SymbolInfo(
                name = name,
                kind = kind,
                params = params,
                returnType = returnType,
                startLine = startRow + 1,
                endLine = endRow + 1,
                bodyText = bodyText,
                isAsync = nodeText.trimStart().startsWith("async"),
                parentClass = parentClassName
            ))
        }

        // ── 변수에 할당된 화살표 함수 추출 ──
        // const myFunc = (...) => { ... }
        if (nodeType in listOf("lexical_declaration", "variable_declaration")) {
            val childCount = getChildCount.invoke(node) as Int
            for (i in 0 until childCount) {
                val declarator = getChild.invoke(node, i) ?: continue
                val declaratorType = getType.invoke(declarator) as String
                if (declaratorType != "variable_declarator") continue

                val valueNode = childByFieldName.invoke(declarator, "value") ?: continue
                val valueType = getType.invoke(valueNode) as String
                if (valueType != "arrow_function") continue

                val nameNode = childByFieldName.invoke(declarator, "name")
                val name = if (nameNode != null) {
                    (getText.invoke(nameNode) as String)
                } else "<anonymous>"

                val paramsNode = childByFieldName.invoke(valueNode, "parameters")
                val params = if (paramsNode != null) {
                    extractParamsFromNode(paramsNode, nodeClass)
                } else emptyList()

                val startPoint = getStartPoint.invoke(node)
                val endPoint = getEndPoint.invoke(node)
                val startRow = startPoint.javaClass.getField("row").getInt(startPoint)
                val endRow = endPoint.javaClass.getField("row").getInt(endPoint)

                val bodyText = (getText.invoke(valueNode) as String).take(500)
                val fullText = getText.invoke(node) as String

                symbols.add(SymbolInfo(
                    name = name,
                    kind = SymbolKind.ARROW_FUNCTION,
                    params = params,
                    startLine = startRow + 1,
                    endLine = endRow + 1,
                    bodyText = bodyText,
                    isExported = fullText.trimStart().startsWith("export"),
                    isAsync = fullText.contains("async"),
                    parentClass = parentClassName
                ))
            }
            // 이 노드는 자식을 이미 처리했으므로 return하지 않고 계속 진행
        }

        // ── 자식 노드 재귀 순회 ──
        val childCount = getChildCount.invoke(node) as Int
        for (i in 0 until childCount) {
            val child = getChild.invoke(node, i) ?: continue
            val childType = getType.invoke(child) as String

            // 이미 처리한 클래스 노드는 건너뜀
            if (childType in classTypes) continue

            traverseNodeReflective(
                child, nodeClass, code, languageId,
                symbols, imports, classes, parentClassName
            )
        }
    }

    /**
     * 파라미터 노드에서 파라미터 목록 추출
     */
    private fun extractParamsFromNode(paramsNode: Any, nodeClass: Class<*>): List<ParamInfo> {
        val getChildCount = nodeClass.getMethod("getChildCount")
        val getChild = nodeClass.getMethod("child", Int::class.java)
        val getType = nodeClass.getMethod("getType")
        val getText = nodeClass.getMethod("getText")

        val params = mutableListOf<ParamInfo>()
        val childCount = getChildCount.invoke(paramsNode) as Int

        for (i in 0 until childCount) {
            val child = getChild.invoke(paramsNode, i) ?: continue
            val childType = getType.invoke(child) as String

            // 구분자 건너뜀
            if (childType in listOf(",", "(", ")", "[", "]")) continue

            val text = (getText.invoke(child) as String).trim()
            if (text.isBlank()) continue

            // "name: Type" 형태 분리 시도
            val colonIndex = text.indexOf(':')
            if (colonIndex > 0) {
                params.add(ParamInfo(
                    name = text.substring(0, colonIndex).trim(),
                    type = text.substring(colonIndex + 1).trim()
                ))
            } else {
                params.add(ParamInfo(name = text))
            }
        }

        return params
    }

    /**
     * 언어 ID를 정규화 (react 변형 → 기본 언어)
     */
    private fun normalizeLang(languageId: String): String {
        return when (languageId.lowercase()) {
            "javascriptreact" -> "javascript"
            "typescriptreact" -> "typescript"  // tsx는 별도 grammar가 필요할 수 있음
            else -> languageId.lowercase()
        }
    }

    /**
     * 언어별 grammar 클래스명 매핑
     *
     * ktreesitter-plugin이 생성하는 클래스명 규칙:
     * tree-sitter-javascript → TreeSitterJavaScript
     * tree-sitter-typescript → TreeSitterTypeScript
     */
    private fun getGrammarClassName(languageId: String): String? {
        return when (languageId) {
            "javascript" -> "io.github.treesitter.javascript.TreeSitterJavaScript"
            "typescript" -> "io.github.treesitter.typescript.TreeSitterTypeScript"
            "python" -> "io.github.treesitter.python.TreeSitterPython"
            "html" -> "io.github.treesitter.html.TreeSitterHtml"
            "css" -> "io.github.treesitter.css.TreeSitterCss"
            "swift" -> "io.github.treesitter.swift.TreeSitterSwift"
            "vue" -> "io.github.treesitter.vue.TreeSitterVue"
            else -> null
        }
    }
}

