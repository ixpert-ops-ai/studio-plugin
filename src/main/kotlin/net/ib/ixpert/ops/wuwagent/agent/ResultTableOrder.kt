package net.ib.ixpert.ops.wuwagent.agent

/**
 * 결과 표(분석된 대상 파일 목록)의 **표시 순서**(B-84 c). 필수 파일이 먼저 보이도록 묶고, 묶음 안에서는 업무 계층 순서로 정렬한다.
 * - 묶음: 필수 → 판정 없음 → 선택(경로 통과)
 * - 묶음 안: service → dao → dto → mapper(XML) → 그 밖(컨트롤러, 화면 등)
 * - 같은 묶음, 같은 계층이면 받은 순서를 그대로 둔다(안정 정렬).
 * 화면에 보이는 순서와 번호만 바꾼다. 분석 결과의 `TargetFileSpec.order`(의존성 정렬, 구현 단계가 쓰는 값)는 바꾸지 않는다.
 * 호출하는 쪽은 정렬된 목록에 1부터 번호를 다시 매긴다(@로 파일을 더 붙이면 그 표는 N+1부터 이어진다).
 */
object ResultTableOrder {
    private val SERVICE = Regex("(Service(Impl)?|SVC(Impl)?|Biz(Impl)?)\\.(java|kt)$")
    private val DAO = Regex("(Dao(Impl)?|Repository|Mapper)\\.(java|kt)$")
    private val DTO = Regex("(Dto|VO|Vo|Entity|Model)\\.(java|kt)$")

    fun groupRank(role: PathRoles.Role?): Int = when (role) {
        PathRoles.Role.REQUIRED -> 0
        null -> 1
        PathRoles.Role.OPTIONAL -> 2
    }

    fun layerRank(path: String): Int {
        val name = path.replace('\\', '/').substringAfterLast('/')
        return when {
            SERVICE.containsMatchIn(name) -> 0
            DAO.containsMatchIn(name) -> 1
            DTO.containsMatchIn(name) -> 2
            name.endsWith(".xml") -> 3
            else -> 4
        }
    }

    fun <T> sort(items: List<T>, path: (T) -> String, role: (T) -> PathRoles.Role?): List<T> =
        items.sortedWith(compareBy<T>({ groupRank(role(it)) }, { layerRank(path(it)) }))
}
