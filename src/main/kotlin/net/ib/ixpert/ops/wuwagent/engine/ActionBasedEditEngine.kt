package net.ib.ixpert.ops.wuwagent.engine

import org.slf4j.LoggerFactory

/**
 * Action-based code editing engine.
 * Supports: INSERT_AFTER, INSERT_BEFORE, REPLACE_METHOD, ADD_IMPORT
 * 
 * LLM outputs action blocks instead of exact SEARCH/REPLACE,
 * eliminating the need to reproduce original code exactly.
 */
class ActionBasedEditEngine {
    private val logger = LoggerFactory.getLogger(ActionBasedEditEngine::class.java)

    companion object {
        private val ACTION_PATTERN = Regex("""\[ACTION:\s*(INSERT_AFTER|INSERT_BEFORE|REPLACE_METHOD|ADD_IMPORT)\s*]""")
        private val ANCHOR_PATTERN = Regex("""\[ANCHOR:\s*(.+?)\s*]""")
        private const val CODE_START = "[CODE]"
        private const val CODE_END = "[/CODE]"
        private val METHOD_SIGNATURE_PATTERN = Regex(
            """(public|protected|private|static|\s)\s+[\w<>,\s\[\]]+\s+(\w+)\s*\([^)]*\)"""
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Data Model
    // ═══════════════════════════════════════════════════════════════

    enum class Action {
        INSERT_AFTER,
        INSERT_BEFORE,
        REPLACE_METHOD,
        ADD_IMPORT
    }

    data class EditAction(
        val action: Action,
        val anchor: String,
        val code: String,
        val index: Int
    )

    data class ApplyResult(
        val success: Boolean,
        val updatedContent: String?,
        val appliedCount: Int,
        val failedActions: List<FailedAction>,
        val message: String
    )

    data class FailedAction(
        val index: Int,
        val action: Action,
        val anchor: String,
        val reason: String
    )

    // ═══════════════════════════════════════════════════════════════
    // Parse LLM Response
    // ═══════════════════════════════════════════════════════════════

    fun parse(llmResponse: String): List<EditAction> {
        val actions = mutableListOf<EditAction>()
        val responseText = llmResponse.replace("\r\n", "\n")
        
        // Find all ACTION blocks
        val actionMatches = ACTION_PATTERN.findAll(responseText).toList()
        
        for (idx in actionMatches.indices) {
            val currentMatch = actionMatches[idx]
            val actionType = Action.valueOf(currentMatch.groupValues[1])
            
            // Define search range for this action (until next action or end of text)
            val startSearch = currentMatch.range.last + 1
            val endSearch = if (idx + 1 < actionMatches.size) actionMatches[idx + 1].range.first else responseText.length
            val blockContext = responseText.substring(startSearch, endSearch)
            
            // Extract ANCHOR
            val anchorMatch = ANCHOR_PATTERN.find(blockContext)
            val anchor = anchorMatch?.groupValues?.get(1)?.trim() ?: ""
            
            // Extract CODE using [CODE] ... [/CODE] markers (flexible placement)
            val codeStartIdx = blockContext.indexOf(CODE_START)
            val codeEndIdx = blockContext.indexOf(CODE_END)
            
            var code = ""
            if (codeStartIdx != -1 && codeEndIdx != -1 && codeEndIdx > codeStartIdx) {
                code = blockContext.substring(codeStartIdx + CODE_START.length, codeEndIdx).trim('\n', '\r')
            } else if (actionType == Action.ADD_IMPORT) {
                // ADD_IMPORT might just have the import line after [CODE] or even without tags in some cases
                // but we prefer tags. Let's try to extract if tags are missing but text exists.
                if (code.isEmpty()) {
                    val lines = blockContext.lines().filter { it.trim().startsWith("import ") }
                    if (lines.isNotEmpty()) code = lines.first().trim()
                }
            }

            if (code.isNotEmpty() || actionType == Action.ADD_IMPORT) {
                actions.add(EditAction(actionType, anchor, code, idx))
            }
        }

        logger.info("[Parse] ${actions.size} edit actions extracted")
        return actions
    }

    // ═══════════════════════════════════════════════════════════════
    // Apply Actions to Source
    // ═══════════════════════════════════════════════════════════════

    fun apply(originalSource: String, actions: List<EditAction>): ApplyResult {
        if (actions.isEmpty()) {
            return ApplyResult(true, originalSource, 0, emptyList(), "No actions to apply")
        }

        var current = originalSource
        var appliedCount = 0
        val failed = mutableListOf<FailedAction>()

        for (editAction in actions) {
            val result = applySingle(current, editAction)
            if (result.first) {
                current = result.second
                appliedCount++
                logger.info("[Apply OK] #${editAction.index} ${editAction.action}: ${editAction.anchor.take(40)}")
            } else {
                failed.add(
                    FailedAction(editAction.index, editAction.action, editAction.anchor, result.second)
                )
                logger.warn("[Apply FAIL] #${editAction.index} ${editAction.action}: ${result.second}")
            }
        }

        val message = if (failed.isEmpty()) {
            "$appliedCount action(s) applied successfully"
        } else {
            "$appliedCount applied, ${failed.size} failed"
        }

        return ApplyResult(
            success = failed.isEmpty(),
            updatedContent = if (appliedCount > 0) current else null,
            appliedCount = appliedCount,
            failedActions = failed,
            message = message
        )
    }

    private fun applySingle(source: String, editAction: EditAction): Pair<Boolean, String> {
        return try {
            when (editAction.action) {
                Action.INSERT_AFTER -> applyInsertAfter(source, editAction)
                Action.INSERT_BEFORE -> applyInsertBefore(source, editAction)
                Action.REPLACE_METHOD -> applyReplaceMethod(source, editAction)
                Action.ADD_IMPORT -> applyAddImport(source, editAction)
            }
        } catch (e: Exception) {
            Pair(false, "Engine error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // INSERT_AFTER: Find anchor method, insert code after its closing brace
    // ═══════════════════════════════════════════════════════════════

    private fun applyInsertAfter(source: String, editAction: EditAction): Pair<Boolean, String> {
        val anchorLocation = findAnchorMethod(source, editAction.anchor)
            ?: return Pair(false, "Anchor not found: ${editAction.anchor}")

        // Find the closing brace of the anchor method
        val methodEnd = findMethodEnd(source, anchorLocation)
            ?: return Pair(false, "Cannot find method end for: ${editAction.anchor}")

        // Insert after the method's closing brace
        val insertPoint = methodEnd + 1
        val before = source.substring(0, insertPoint)
        val after = source.substring(insertPoint)
        
        // Ensure proper newline and indentation
        val insertion = "\n\n${editAction.code}"

        return Pair(true, "$before$insertion$after")
    }

    // ═══════════════════════════════════════════════════════════════
    // INSERT_BEFORE: Find anchor method, insert code before it
    // ═══════════════════════════════════════════════════════════════

    private fun applyInsertBefore(source: String, editAction: EditAction): Pair<Boolean, String> {
        val anchorLocation = findAnchorMethod(source, editAction.anchor)
            ?: return Pair(false, "Anchor not found: ${editAction.anchor}")

        // Find the start of the method (including annotations/javadoc above)
        val methodStart = findMethodStart(source, anchorLocation)

        val before = source.substring(0, methodStart)
        val after = source.substring(methodStart)
        val insertion = "${editAction.code}\n\n"

        return Pair(true, "$before$insertion$after")
    }

    // ═══════════════════════════════════════════════════════════════
    // REPLACE_METHOD: Find method by name, replace entire body
    // ═══════════════════════════════════════════════════════════════

    private fun applyReplaceMethod(source: String, editAction: EditAction): Pair<Boolean, String> {
        val anchorLocation = findAnchorMethod(source, editAction.anchor)
            ?: return Pair(false, "Method not found: ${editAction.anchor}")

        val methodStart = findMethodStart(source, anchorLocation)
        val methodEnd = findMethodEnd(source, anchorLocation)
            ?: return Pair(false, "Cannot find method end for: ${editAction.anchor}")

        val before = source.substring(0, methodStart)
        val after = source.substring(methodEnd + 1)

        return Pair(true, "$before${editAction.code}$after")
    }

    // ═══════════════════════════════════════════════════════════════
    // ADD_IMPORT: Add import statement after existing imports
    // ═══════════════════════════════════════════════════════════════

    private fun applyAddImport(source: String, editAction: EditAction): Pair<Boolean, String> {
        val importToAdd = editAction.code.trim()
        if (importToAdd.isBlank()) return Pair(true, source)

        // Check if already exists
        if (source.contains(importToAdd)) {
            logger.info("[ADD_IMPORT] Already exists, skipping: $importToAdd")
            return Pair(true, source)
        }

        // Find last import line
        val lines = source.lines().toMutableList()
        var lastImportIdx = -1
        for ((idx, line) in lines.withIndex()) {
            if (line.trimStart().startsWith("import ")) {
                lastImportIdx = idx
            }
        }

        if (lastImportIdx == -1) {
            // No imports found, insert after package
            val packageIdx = lines.indexOfFirst { it.trimStart().startsWith("package ") }
            lastImportIdx = if (packageIdx >= 0) packageIdx else -1
        }

        lines.add(lastImportIdx + 1, importToAdd)
        return Pair(true, lines.joinToString("\n"))
    }

    // ═══════════════════════════════════════════════════════════════
    // Anchor Finding (Fuzzy Method Name Matching)
    // ═══════════════════════════════════════════════════════════════

    private fun findAnchorMethod(source: String, anchor: String): Int? {
        val anchorClean = anchor.trim()
        if (anchorClean.isBlank()) return null

        // Strategy 1: Direct substring search (anchor is part of signature)
        val directIdx = source.indexOf(anchorClean)
        if (directIdx != -1) return directIdx

        // Strategy 2: Extract method name from anchor and search
        val methodName = extractMethodName(anchorClean)
        if (methodName != null) {
            val pattern = Regex(
                """(public|protected|private|static|\s)\s+[\w<>,\s\[\]]+\s+${Regex.escape(methodName)}\s*\(""")
            val match = pattern.find(source)
            if (match != null) return match.range.first
        }

        // Strategy 3: Just the method name as a word
        if (methodName != null) {
            val simpleIdx = source.indexOf(" $methodName(")
            if (simpleIdx != -1) return simpleIdx + 1
        }

        return null
    }

    private fun extractMethodName(anchor: String): String? {
        val sigMatch = Regex("""(?:[\w<>,\s]+\s+)?(\w+)\s*\(""").find(anchor)
        if (sigMatch != null) return sigMatch.groupValues[1]

        val nameMatch = Regex("""^[a-zA-Z_]\w+$""").find(anchor.trim())
        if (nameMatch != null) return nameMatch.value

        return null
    }

    // ═══════════════════════════════════════════════════════════════
    // Method Boundary Detection
    // ═══════════════════════════════════════════════════════════════

    private fun findMethodEnd(source: String, signatureStart: Int): Int? {
        val braceStart = source.indexOf('{', signatureStart)
        if (braceStart == -1) {
            val semicolon = source.indexOf(';', signatureStart)
            return if (semicolon != -1) semicolon else null
        }

        var depth = 0
        for (i in braceStart..source.lastIndex) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun findMethodStart(source: String, signatureCharIdx: Int): Int {
        val before = source.substring(0, signatureCharIdx)
        val lines = before.lines()
        val allLines = source.lines()
        val sigLineIdx = lines.size - 1

        var checkIdx = sigLineIdx - 1
        while (checkIdx >= 0) {
            val line = allLines[checkIdx].trim()
            if (line.startsWith("@") || line.startsWith("*") || 
                line.startsWith("/**") || line.startsWith("//") || line.startsWith("*/")) {
                checkIdx--
            } else if (line.isEmpty()) {
                break
            } else {
                break
            }
        }

        val methodStartLine = checkIdx + 1
        var offset = 0
        for (i in 0 until methodStartLine) {
            offset += allLines[i].length + 1
        }
        return offset
    }

    // ═══════════════════════════════════════════════════════════════
    // Validation
    // ═══════════════════════════════════════════════════════════════

    fun validate(originalSource: String, updatedSource: String): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val open = updatedSource.count { it == '{' }
        val close = updatedSource.count { it == '}' }
        if (open != close) {
            errors.add("Brace mismatch: { = $open, } = $close")
        }

        val origLines = originalSource.lines().size
        val updLines = updatedSource.lines().size
        if (updLines < origLines * 0.5) {
            errors.add("Significant content loss detected")
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )

    fun buildRetryContext(source: String, failedActions: List<FailedAction>): String = buildString {
        append("## Previous actions failed. Available methods in the file:\n\n")
        METHOD_SIGNATURE_PATTERN.findAll(source).forEach { match ->
            append("- `${match.value.trim()}`\n")
        }
        append("\n## Failed actions:\n")
        for (fa in failedActions) {
            append("- #${fa.index} ${fa.action}: anchor='${fa.anchor}' reason='${fa.reason}'\n")
        }
        append("\nPlease use one of the existing method names as [ANCHOR].\n")
    }
}
