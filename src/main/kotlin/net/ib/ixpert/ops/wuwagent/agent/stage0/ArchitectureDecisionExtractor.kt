package net.ib.ixpert.ops.wuwagent.agent.stage0

object ArchitectureDecisionExtractor {

    fun extract(input: SrInput): List<ArchDecision> {
        val text = "${input.title} ${input.description} ${input.additionalContext.orEmpty()}"
        
        return ARCHITECTURE_TRIGGERS
            .filter { it.pattern.containsMatchIn(text) }
            .map { trigger ->
                val isResolved = trigger.resolutionPatterns.any { it.containsMatchIn(text) }
                val selectedOption = if (isResolved) {
                    findSelectedOption(text, trigger)
                } else null

                ArchDecision(
                    topic = trigger.topic,
                    options = trigger.options,
                    triggerKeyword = trigger.pattern.find(text)?.value ?: trigger.topic,
                    resolved = isResolved,
                    selectedOption = selectedOption
                )
            }
    }

    private fun findSelectedOption(text: String, trigger: ArchitectureTrigger): String? {
        trigger.resolutionPatterns.forEachIndexed { index, pattern ->
            if (pattern.containsMatchIn(text) && index < trigger.options.size) {
                return trigger.options[index]
            }
        }
        return null
    }

    // ─── 아키텍처 트리거 정의 ───
    private val ARCHITECTURE_TRIGGERS = listOf(
        ArchitectureTrigger(
            topic = "파일 업로드 방식",
            pattern = Regex("""(?:업로드|upload|파일\s*전송|이미지\s*전송)""", RegexOption.IGNORE_CASE),
            options = listOf(
                "REST 선행 업로드 후 URL 전달",
                "WebSocket/메시지에 인라인 포함",
                "멀티파트 별도 API"
            ),
            resolutionPatterns = listOf(
                Regex("""(?:REST|API).*(?:업로드|upload).*(?:URL|경로)|(?:기존|재사용).*(?:업로드|upload)\s*API|/api/.*upload""", RegexOption.IGNORE_CASE),
                Regex("""(?:WebSocket|소켓).*(?:포함|inline|전송)""", RegexOption.IGNORE_CASE),
                Regex("""(?:multipart|멀티파트)""", RegexOption.IGNORE_CASE)
            )
        ),
        ArchitectureTrigger(
            topic = "페이징 방식",
            pattern = Regex("""(?:페이징|paging|pagination|커서|cursor|무한\s*스크롤).*(?:변경|전환|도입|적용)""", RegexOption.IGNORE_CASE),
            options = listOf(
                "Offset 기반 유지",
                "Cursor 기반 (createdAt+id)",
                "Keyset 페이징"
            ),
            resolutionPatterns = listOf(
                Regex("""offset.*(?:유지|기존)""", RegexOption.IGNORE_CASE),
                Regex("""(?:cursor|커서).*(?:createdAt|생성시간|id)|(?:정렬\s*기준|sort).*(?:명시|지정)""", RegexOption.IGNORE_CASE),
                Regex("""keyset""", RegexOption.IGNORE_CASE)
            )
        ),
        ArchitectureTrigger(
            topic = "실시간 통신 방식",
            pattern = Regex("""(?:실시간|WebSocket|SSE|소켓|알림\s*전송|push).*(?:추가|구현|도입|전송)""", RegexOption.IGNORE_CASE),
            options = listOf(
                "WebSocket (STOMP)",
                "SSE (Server-Sent Events)",
                "Polling"
            ),
            resolutionPatterns = listOf(
                Regex("""(?:STOMP|WebSocket|ws://|기존.*소켓)""", RegexOption.IGNORE_CASE),
                Regex("""(?:SSE|EventSource|Server-Sent)""", RegexOption.IGNORE_CASE),
                Regex("""(?:polling|주기적\s*조회)""", RegexOption.IGNORE_CASE)
            )
        ),
        ArchitectureTrigger(
            topic = "인증/권한 검증 위치",
            pattern = Regex("""(?:로그인\s*여부|비로그인|인증|권한\s*검증|Guard|접근\s*제한)""", RegexOption.IGNORE_CASE),
            options = listOf(
                "Spring Security Filter",
                "AOP Interceptor",
                "프론트엔드 Router Guard",
                "메서드 레벨 @PreAuthorize"
            ),
            resolutionPatterns = listOf(
                Regex("""(?:Security|Filter|SecurityConfig)""", RegexOption.IGNORE_CASE),
                Regex("""(?:Interceptor|AOP)""", RegexOption.IGNORE_CASE),
                Regex("""(?:Router\s*Guard|beforeEach|navigation\s*guard)""", RegexOption.IGNORE_CASE),
                Regex("""(?:@PreAuthorize|@Secured)""", RegexOption.IGNORE_CASE)
            )
        ),
        ArchitectureTrigger(
            topic = "상태 변경 트리거 방식",
            pattern = Regex("""(?:상태\s*변경|status.*변경|상태.*전환).*(?:일괄|자동|연동|트리거)""", RegexOption.IGNORE_CASE),
            options = listOf(
                "동기 호출 (같은 트랜잭션)",
                "이벤트 발행 (비동기)",
                "배치/스케줄러"
            ),
            resolutionPatterns = listOf(
                Regex("""(?:같은\s*트랜잭션|동기|직접\s*호출|@Transactional)""", RegexOption.IGNORE_CASE),
                Regex("""(?:이벤트|Event|ApplicationEvent|비동기|@Async)""", RegexOption.IGNORE_CASE),
                Regex("""(?:배치|스케줄러|cron|@Scheduled)""", RegexOption.IGNORE_CASE)
            )
        ),
        ArchitectureTrigger(
            topic = "데이터 저장 위치",
            pattern = Regex("""(?:저장|persist|캐시|Redis|세션).*(?:어디|방식|위치|선택)""", RegexOption.IGNORE_CASE),
            options = listOf(
                "RDB (기존 테이블)",
                "Redis/Cache",
                "파일 시스템/S3"
            ),
            resolutionPatterns = listOf(
                Regex("""(?:테이블|컬럼|Entity|DB|RDB)""", RegexOption.IGNORE_CASE),
                Regex("""(?:Redis|캐시|Cache)""", RegexOption.IGNORE_CASE),
                Regex("""(?:파일|디렉토리|S3|storage)""", RegexOption.IGNORE_CASE)
            )
        )
    )

    private data class ArchitectureTrigger(
        val topic: String,
        val pattern: Regex,
        val options: List<String>,
        val resolutionPatterns: List<Regex>
    )
}
