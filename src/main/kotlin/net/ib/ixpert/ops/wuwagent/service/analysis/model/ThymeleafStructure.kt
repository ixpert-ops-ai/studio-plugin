package net.ib.ixpert.ops.wuwagent.service.analysis.model

data class ThymeleafStructure(
    val fragments: List<FragmentInfo> = emptyList(),
    val bindings: List<ThymeleafBinding> = emptyList(),
    val conditionals: List<ThymeleafBinding> = emptyList(),
    val iterations: List<ThymeleafBinding> = emptyList(),
    val formBindings: List<ThymeleafBinding> = emptyList(),
    val includes: List<FragmentRef> = emptyList()
) {
    fun isEmpty(): Boolean = fragments.isEmpty() && bindings.isEmpty()
        && conditionals.isEmpty() && iterations.isEmpty()
        && formBindings.isEmpty() && includes.isEmpty()
}

data class ThymeleafBinding(
    val attribute: String,
    val expression: String,
    val line: Int,
    val tagContext: String
)

data class FragmentInfo(
    val name: String,
    val parameters: List<String> = emptyList(),
    val line: Int
)

data class FragmentRef(
    val templateName: String,
    val fragmentName: String,
    val line: Int
)
