package net.ib.ixpert.ops.wuwagent.agent.eval

/**
 * required GT를 네 갈래로 나눈다(B-49). 평가에서는 project가 null이라 Stage 3 확인 카드가 동작하지 않고(Stage3ConfirmationBridge.kt:50),
 * Stage 3 뒤에는 정렬만 있어서(RequirementAnalysisPipeline.kt:294) "후보에는 있었는데 최종 목록에 없음"은 Stage 3 판정 결과로만 생긴다.
 *  - 유지: 최종 목록에 있음(Recall 계산과 같은 hit 판정)
 *  - Stage 3 제거: Stage 3 후보에는 있었지만 최종 목록에는 없음
 *  - 후보 밖(제외 지시): 사용자 제외 확정(excludedFiles)에 들어 있음(Stage 3 이전에 걸러짐, RequirementAnalysisPipeline.kt:227-231)
 *  - 후보 밖(Stage 1~2 손실): 그 밖의 경우
 * Stage 3 호출이 관찰되지 않으면 유지가 아닌 항목은 "미관찰"로 따로 둔다(제거와 후보 밖을 가를 수 없다).
 */
object GtSplit {

    data class Split(
        val kept: List<String>,
        val stage3Removed: List<String>,
        val outsideExcluded: List<String>,
        val outsideLost: List<String>,
        val unobserved: List<String>
    ) {
        fun total(): Int = kept.size + stage3Removed.size + outsideExcluded.size + outsideLost.size + unobserved.size
    }

    /** Stage 3 사용자 메시지의 후보 줄 `- [n] 경로`(Stage3OrderReplayTest의 복원 규칙과 같은 형식). */
    private val CANDIDATE_LINE = Regex("""^- \[(\d+)] (\S+)[ \t\r]*$""", RegexOption.MULTILINE)

    /** 모든 Stage 3 호출의 후보 경로 합집합. */
    fun candidatePaths(stage3UserContents: List<String>): Set<String> =
        stage3UserContents.flatMap { c -> CANDIDATE_LINE.findAll(c).map { it.groupValues[2] }.toList() }.toSet()

    fun classify(
        required: List<EvalGt>,
        kept: List<EvalGt>,
        stage3Observed: Boolean,
        candidates: Set<String>,
        excluded: List<String>,
        matches: (EvalGt, String) -> Boolean
    ): Split {
        val keptLabels = required.filter { it in kept }.map { it.label() }
        val removed = ArrayList<String>()
        val excl = ArrayList<String>()
        val lost = ArrayList<String>()
        val unobs = ArrayList<String>()
        for (g in required.filter { it !in kept }) {
            when {
                stage3Observed && candidates.any { matches(g, it) } -> removed.add(g.label())
                excluded.any { matches(g, it) } -> excl.add(g.label())
                stage3Observed -> lost.add(g.label())
                else -> unobs.add(g.label())
            }
        }
        return Split(keptLabels, removed, excl, lost, unobs)
    }
}
