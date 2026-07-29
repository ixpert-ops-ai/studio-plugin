package net.ib.ixpert.ops.wuwagent.agent.completeness

import net.ib.ixpert.ops.wuwagent.agent.completeness.model.*

object RootCauseAnalyzer {
    
    fun analyze(finding: CompanionFinding, ctx: GraphMatchContext): RootCause {
        if (finding.companionKind == FileKind.MYBATIS_XML) {
            return analyzeSameNamespaceXml(finding, ctx)
        }
        
        // For DomainPrefixVO and others, if they couldn't find the node by name, it's NOT_IN_GRAPH.
        // If they did find the node (existsInGraph = true), but it's a violation, 
        // it means the node exists and is connected, but was just not included in the SR.
        // The instructions ask to classify into NOT_IN_GRAPH or NO_EDGE for extractor limits.
        // If it's already in the graph and connected, we technically have NO_EDGE = false, NOT_IN_GRAPH = false.
        // But the enum only has these two. We'll return NO_EDGE as a fallback for "it is in the graph".
        return if (finding.result.existsInGraph) RootCause.NO_EDGE else RootCause.NOT_IN_GRAPH
    }

    private fun analyzeSameNamespaceXml(finding: CompanionFinding, ctx: GraphMatchContext): RootCause {
        if (finding.result.existsInGraph) return RootCause.NO_EDGE

        val fqcn = ctx.fqcnOf(finding.anchorPath) ?: return RootCause.NOT_IN_GRAPH
        val baseName = ctx.baseName(finding.anchorPath).replace("DAO", "").replace("Mapper", "")
        
        val hasXmlWithSimilarName = ctx.allFiles().any { 
            it.endsWith(".xml") && ctx.baseName(it).contains(baseName, ignoreCase = true) 
        }
        return if (hasXmlWithSimilarName) RootCause.NO_EDGE else RootCause.NOT_IN_GRAPH
    }
}
