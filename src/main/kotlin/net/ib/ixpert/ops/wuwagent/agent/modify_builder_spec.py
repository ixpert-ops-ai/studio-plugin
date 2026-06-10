import os
builder_path = r"C:\Workspace\DEV-ASSISTANT\IDE-PLUGIN\intelliJ\ai-assistant-plugin\src\main\kotlin\net\ib\ixpert\ops\wuwagent\agent\ImplementContextBuilder.kt"
with open(builder_path, "r", encoding="utf-8") as f:
    content = f.read()

# Add parsers
parsers = """    private fun parseTargetHints(description: String): List<String> {
        val koreanRegex = Regex("[가-힣]+")
        val idRegex = Regex("[a-zA-Z0-9_\\\\-]+")
        val hints = mutableListOf<String>()
        hints.addAll(koreanRegex.findAll(description).map { it.value })
        hints.addAll(idRegex.findAll(description).map { it.value }.filter { it.length > 3 })
        return hints.distinct()
    }

    private fun parseTargetQueryIds(description: String): List<String> {
        val idRegex = Regex("[a-zA-Z0-9_]+")
        return idRegex.findAll(description).map { it.value }.filter { it.length > 3 }.distinct().toList()
    }

    fun buildModifyContext("""
content = content.replace("    fun buildModifyContext(", parsers)

# Update buildModifyContext
old_buildModifyContext = """    fun buildModifyContext(
        path: String, 
        graph: ProjectGraph, 
        mdRoot: Path, 
        sourceRoot: Path,
        similarRefs: List<FileReference>
    ): String {
        val md = ContextBuilderUtil.buildFileContext(path, graph, mdRoot, listOf(1, 3, 5))
        val sourceFile = sourceRoot.resolve(path)
        val sourceContent = if (sourceFile.exists()) sourceFile.readText() else ""
        
        return buildContextWithSource(md, sourceContent, graph, similarRefs, path)
    }"""
new_buildModifyContext = """    fun buildModifyContext(
        spec: TargetFileSpec, 
        graph: ProjectGraph, 
        mdRoot: Path, 
        sourceRoot: Path,
        similarRefs: List<FileReference>
    ): String {
        val md = ContextBuilderUtil.buildFileContext(spec.path, graph, mdRoot, listOf(1, 3, 5))
        val sourceFile = sourceRoot.resolve(spec.path)
        val sourceContent = if (sourceFile.exists()) sourceFile.readText() else ""
        val targetHints = parseTargetHints(spec.description)
        val targetQueryIds = parseTargetQueryIds(spec.description)
        
        return buildContextWithSource(md, sourceContent, graph, similarRefs, spec.path, targetHints, targetQueryIds)
    }"""
content = content.replace(old_buildModifyContext, new_buildModifyContext)

# Update buildCreateContext
old_buildCreateContext = """    fun buildCreateContext(
        path: String,
        graph: ProjectGraph,
        mdRoot: Path,
        sourceRoot: Path,
        allTargetFiles: List<TargetFileSpec>,
        similarRefs: List<FileReference>
    ): String {
        val sb = StringBuilder()"""
new_buildCreateContext = """    fun buildCreateContext(
        spec: TargetFileSpec,
        graph: ProjectGraph,
        mdRoot: Path,
        sourceRoot: Path,
        allTargetFiles: List<TargetFileSpec>,
        similarRefs: List<FileReference>
    ): String {
        val path = spec.path
        val targetHints = parseTargetHints(spec.description)
        val targetQueryIds = parseTargetQueryIds(spec.description)
        // Also call buildContextWithSource at the end?
        // Wait, buildCreateContext currently doesn't call buildContextWithSource.
        // It manually assembles.
        // Let's modify buildCreateContext properly later, just change signature for now.
        val sb = StringBuilder()"""
content = content.replace(old_buildCreateContext, new_buildCreateContext)

with open(builder_path, "w", encoding="utf-8") as f:
    f.write(content)

service_path = r"C:\Workspace\DEV-ASSISTANT\IDE-PLUGIN\intelliJ\ai-assistant-plugin\src\main\kotlin\net\ib\ixpert\ops\wuwagent\agent\ImplementService.kt"
with open(service_path, "r", encoding="utf-8") as f:
    s_content = f.read()

s_content = s_content.replace("ImplementContextBuilder.buildModifyContext(spec.path", "ImplementContextBuilder.buildModifyContext(spec")
s_content = s_content.replace("ImplementContextBuilder.buildCreateContext(spec.path", "ImplementContextBuilder.buildCreateContext(spec")

with open(service_path, "w", encoding="utf-8") as f:
    f.write(s_content)

print("Updated ImplementContextBuilder.kt and ImplementService.kt")
