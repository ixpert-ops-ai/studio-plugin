package net.ib.ixpert.ops.wuwagent.service.analysis.extractor

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import net.ib.ixpert.ops.wuwagent.service.analysis.model.*

/**
 * IntelliJ PSI 기반 구조 추출기.
 * Java, Kotlin 등 IntelliJ가 완전한 PSI 트리를 제공하는 언어에 사용합니다.
 */
class PsiStructureExtractor(
    private val psiFile: PsiFile,
    private val document: com.intellij.openapi.editor.Document
) : StructureExtractor {

    companion object {
        private val SUPPORTED_LANGUAGES = setOf(
            "java", "kotlin", "groovy"
        )

        fun supports(languageId: String): Boolean {
            return SUPPORTED_LANGUAGES.contains(languageId.lowercase())
        }
    }

    override fun supports(languageId: String): Boolean = Companion.supports(languageId)

    override fun extract(code: String, languageId: String): ExtractedStructure {
        val symbols = mutableListOf<SymbolInfo>()
        val fields = mutableListOf<FieldInfo>()
        val classes = mutableListOf<ClassInfo>()
        val imports = mutableListOf<String>()

        PsiTreeUtil.findChildrenOfType(psiFile, PsiImportStatement::class.java).forEach { imp ->
            imp.qualifiedName?.let { imports.add(it) }
        }

        PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java).forEach { psiClass ->
            classes.add(extractClassInfo(psiClass))
            psiClass.methods.forEach { method ->
                symbols.add(extractMethodInfo(method, psiClass.name))
            }
            psiClass.fields.forEach { field ->
                fields.add(extractFieldInfo(field, psiClass.name))
            }
        }

        PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
            .filter { method ->
                PsiTreeUtil.getParentOfType(method, PsiClass::class.java) == null
            }
            .forEach { method ->
                symbols.add(extractMethodInfo(method, parentClassName = null))
            }

        return ExtractedStructure(
            symbols = symbols,
            fields = fields,
            imports = imports,
            classes = classes,
            rawCode = code,
            extractionMethod = ExtractionMethod.PSI
        )
    }

    private fun extractClassInfo(psiClass: PsiClass): ClassInfo {
        val kind = when {
            psiClass.isInterface -> ClassKind.INTERFACE
            psiClass.isEnum -> ClassKind.ENUM
            psiClass.hasModifierProperty(PsiModifier.ABSTRACT) -> ClassKind.ABSTRACT_CLASS
            else -> ClassKind.CLASS
        }

        val superClass = psiClass.superClass?.let { sup ->
            if (sup.qualifiedName != "java.lang.Object") sup.name else null
        }

        val interfaces = psiClass.interfaces.mapNotNull { it.name }

        val annotations = psiClass.annotations.mapNotNull { ann ->
            ann.qualifiedName?.substringAfterLast(".")
        }

        return ClassInfo(
            name = psiClass.name ?: "<anonymous>",
            kind = kind,
            superClass = superClass,
            interfaces = interfaces,
            annotations = annotations,
            line = getLineNumber(psiClass)
        )
    }

    private fun extractFieldInfo(field: PsiField, parentClassName: String?): FieldInfo {
        val annotationTexts = field.annotations.map { it.text }
        return FieldInfo(
            name = field.name,
            type = field.type.presentableText,
            annotationTexts = annotationTexts,
            isStatic = field.hasModifierProperty(PsiModifier.STATIC),
            isFinal = field.hasModifierProperty(PsiModifier.FINAL),
            parentClass = parentClassName
        )
    }

    private fun extractMethodInfo(method: PsiMethod, parentClassName: String?): SymbolInfo {
        val params = method.parameterList.parameters.map { param ->
            ParamInfo(
                name = param.name,
                type = param.type.presentableText
            )
        }

        val kind = when {
            method.isConstructor -> SymbolKind.CONSTRUCTOR
            method.name.startsWith("get") && method.parameterList.isEmpty ->
                SymbolKind.GETTER
            method.name.startsWith("set") && method.parameterList.parametersCount == 1 ->
                SymbolKind.SETTER
            else -> SymbolKind.METHOD
        }

        val body = method.body
        val isSimpleAccessor = kind in listOf(SymbolKind.GETTER, SymbolKind.SETTER)
            && body != null
            && body.statements.size <= 1

        val annotations = method.annotations.mapNotNull { ann ->
            ann.qualifiedName?.substringAfterLast(".")
        }

        val bodyText = if (!isSimpleAccessor) {
            body?.text?.take(500) ?: ""
        } else ""

        return SymbolInfo(
            name = method.name,
            kind = if (isSimpleAccessor) kind else SymbolKind.METHOD,
            params = params,
            returnType = method.returnType?.presentableText,
            startLine = getLineNumber(method),
            endLine = getEndLineNumber(method),
            bodyText = bodyText,
            isExported = method.hasModifierProperty(PsiModifier.PUBLIC),
            isStatic = method.hasModifierProperty(PsiModifier.STATIC),
            parentClass = parentClassName,
            annotations = annotations
        )
    }

    private fun getLineNumber(element: PsiElement): Int {
        return document.getLineNumber(element.textOffset) + 1
    }

    private fun getEndLineNumber(element: PsiElement): Int {
        return document.getLineNumber(element.textRange.endOffset) + 1
    }
}
