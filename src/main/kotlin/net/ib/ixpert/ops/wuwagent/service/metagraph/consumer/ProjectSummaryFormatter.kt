package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer

import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ArchitectureLayer

/**
 * 프로젝트 전체 파일 목록을 경량화된 형태(1줄 요약)로 포맷팅하는 유틸리티 클래스입니다.
 * LLM이 요구사항을 분석하여 수정/신규 파일을 도출할 때 참조할 전체 컨텍스트 맵을 제공합니다.
 */
object ProjectSummaryFormatter {

    /**
     * 그래프 내의 모든 파일을 계층(Layer) 순서로 정렬하여 마크다운 테이블 형태로 반환합니다.
     * 정렬 순서: PERSISTENCE → BUSINESS → PRESENTATION → COMMON → 기타
     */
    fun format(graph: ProjectGraph): String {
        if (graph.files.isEmpty()) {
            return "분석 대상 파일이 없습니다."
        }

        val sb = StringBuilder()
        sb.append("- 프레임워크: Spring Boot / 총 ${graph.files.size}개 파일\n\n")
        sb.append("| 클래스명 | 타입 | 계층 | 위험도 | 파일경로 |\n")
        sb.append("|:---|:---:|:---:|:---:|:---|\n")

        // 계층별 정렬 가중치 부여
        val sortedFiles = graph.files.entries.sortedBy { (_, node) ->
            when (node.layer) {
                ArchitectureLayer.PERSISTENCE -> 1
                ArchitectureLayer.BUSINESS -> 2
                ArchitectureLayer.PRESENTATION -> 3
                ArchitectureLayer.COMMON -> 4
                else -> 5
            }
        }

        for ((path, node) in sortedFiles) {
            sb.append("| ${node.className} | ${node.fileType} | ${node.layer} | ${node.changeRisk} | $path |\n")
        }

        return sb.toString()
    }
}
