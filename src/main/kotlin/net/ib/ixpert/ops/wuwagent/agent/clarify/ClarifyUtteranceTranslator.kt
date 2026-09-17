package net.ib.ixpert.ops.wuwagent.agent.clarify

import com.google.gson.Gson
import com.google.gson.JsonParser
import net.ib.ixpert.ops.wuwagent.agent.clarify.model.*
import net.ib.ixpert.ops.wuwagent.client.LLMClient

/**
 * 사용자 자유 발화 번역 액션 종류
 */
enum class UserActionKind {
    EXCLUDE,         // 특정 후보 파일 제외 요청
    INCLUDE_TOKEN,   // 신규 검색 앵커 토큰 추가 (최소형)
    ADD_CONSTRAINT,  // 제약 조건 추가
    COMPLETE,        // 구체화 완료 및 분석 시작 선언
    UNKNOWN          // 분류 불능 또는 목록 밖 대상 지정
}

/**
 * 자유 발화 번역 결과 구조체
 */
data class TranslatedAction(
    val kind: UserActionKind,
    val targetId: String? = null,
    val targetFilePath: String? = null,
    val tokenValue: String? = null,
    val constraint: IntentConstraint? = null,
    val rawStatement: String,
    val clarificationMessage: String? = null,
    val isNotInCandidates: Boolean = false // (b) 닫힌 선택 강제 위반 플래그
)

/**
 * Clarify 단계 사용자의 자유 발화를 구조화된 액션으로 번역하는 번역기.
 * - 닫힌 후보군(currentItems) 컨텍스트 주입으로 환각 방지
 * - (b-강화) LLM 반환 targetId의 현재 items 존재 여부를 코드로 2차 강제 재검증 (NOT_IN_CANDIDATES 강등)
 * - LLM 장애 시 예외 + [Error] 응답 양방향 Fail-Safe 폴백
 */
object ClarifyUtteranceTranslator {

    private val gson = Gson()

    fun translate(
        utterance: String,
        currentItems: List<RequirementItem>,
        llmClient: LLMClient?
    ): TranslatedAction {
        val trimmed = utterance.trim()
        if (trimmed.isBlank()) {
            return TranslatedAction(
                kind = UserActionKind.UNKNOWN,
                rawStatement = utterance,
                clarificationMessage = "입력 내용이 비어있습니다."
            )
        }

        // 1. 규칙 기반 빠른 확정 감지
        if (isCompletionUtterance(trimmed)) {
            return TranslatedAction(
                kind = UserActionKind.COMPLETE,
                rawStatement = utterance,
                clarificationMessage = "요구사항 구체화를 완료하고 분석을 시작합니다."
            )
        }

        if (llmClient == null) {
            return TranslatedAction(
                kind = UserActionKind.UNKNOWN,
                rawStatement = utterance,
                clarificationMessage = "LLM 서비스를 사용할 수 없어 발화를 해석하지 못했습니다."
            )
        }

        return try {
            val candidatesContext = buildCandidatesContext(currentItems)

            val systemPrompt = """
                당신은 소프트웨어 요구사항 구체화 대화의 발화 번역 전문가입니다.
                사용자의 자연어 발화를 분석하여, 현재 후보 목록을 바탕으로 아래 5가지 액션 중 정확히 하나로 번역하세요.

                [현재 후보 목록]
                $candidatesContext

                [액션 분류 규칙]
                1. EXCLUDE: 사용자가 위 [현재 후보 목록]에 있는 특정 파일/모듈을 분석에서 제외하거나 빼달라고 요청한 경우.
                   - 반드시 위 목록에 명시된 정확한 "ID"를 "targetId"에 기재해야 합니다.
                   - 만약 사용자가 요구한 파일/모듈이 위 목록에 전혀 존재하지 않는다면, action을 "UNKNOWN"으로 설정하고 "notInCandidates": true 로 설정하세요. 임의의 ID를 지어내면 절대 안 됩니다.
                2. INCLUDE_TOKEN: 사용자가 새로운 업무 키워드/채널/도메인 개념을 추가로 탐색/고려해달라고 발화한 경우. (예: "알림톡도 봐줘", "SMS 채널 추가")
                   - 추가할 핵심 키워드를 "tokenValue"에 기재하세요.
                3. ADD_CONSTRAINT: 사용자가 아키텍처나 기능에 관한 명시적 제약 조건을 언급한 경우. (예: "외부 API는 쓰지 마", "어드민 화면만 수정해")
                   - constraintKind: INCLUDE_CHANNEL | EXCLUDE_EXTERNAL | NEW_MODULE | SCOPE_LIMIT | OTHER 중 하나
                   - constraintValue: 제약 요약 기재
                4. COMPLETE: 구체화를 마치고 분석(/analyze)을 시작하자는 선언인 경우. (예: "이제 분석해줘", "이대로 시작")
                5. UNKNOWN: 위 항목으로 명확히 분류되지 않거나, 단순 감탄사/모호한 발화인 경우.

                [출력 형식]
                반드시 아래 JSON 포맷으로만 응답하세요:
                {
                  "action": "EXCLUDE | INCLUDE_TOKEN | ADD_CONSTRAINT | COMPLETE | UNKNOWN",
                  "targetId": "후보 목록의 ID 또는 null",
                  "tokenValue": "추가 토큰 문자열 또는 null",
                  "constraintKind": "INCLUDE_CHANNEL | EXCLUDE_EXTERNAL | NEW_MODULE | SCOPE_LIMIT | OTHER 또는 null",
                  "constraintValue": "제약 요약 내용 또는 null",
                  "notInCandidates": false,
                  "message": "사용자에게 전달할 안내 또는 되묻기 메시지"
                }
            """.trimIndent()

            val userPrompt = """
                [사용자 발화]
                $trimmed
            """.trimIndent()

            val response = llmClient.chat(
                systemPrompt = systemPrompt,
                userCode = userPrompt,
                maxTokens = 300
            )

            val content = response?.message?.content?.trim()
            if (content.isNullOrBlank() || content.startsWith("[Error]")) {
                return TranslatedAction(
                    kind = UserActionKind.UNKNOWN,
                    rawStatement = utterance,
                    clarificationMessage = "발화 의도를 파악하는 중 오류가 발생했습니다. 다시 말씀해 주세요."
                )
            }

            parseAndValidateResponse(content, utterance, currentItems)
        } catch (e: Exception) {
            TranslatedAction(
                kind = UserActionKind.UNKNOWN,
                rawStatement = utterance,
                clarificationMessage = "발화 번역 처리 중 예외가 발생했습니다: ${e.message}"
            )
        }
    }

    private fun isCompletionUtterance(text: String): Boolean {
        val normalized = text.lowercase().replace(" ", "")
        val completionKeywords = listOf(
            "분석시작", "시작해줘", "시작하자", "분석진행", "진행해줘",
            "완료", "다음으로", "단계진행", "분석해줘"
        )
        return completionKeywords.any { normalized.contains(it) }
    }

    private fun buildCandidatesContext(items: List<RequirementItem>): String {
        if (items.isEmpty()) return "(현재 후보 항목 없음)"
        return items.joinToString("\n") { item ->
            val path = when (val h = item.hint) {
                is LinkHint.ExistingRef -> h.filePath
                is LinkHint.NewCreation -> item.statement
            }
            "- ID: ${item.id} | 파일: $path | 설명: ${item.statement}"
        }
    }

    private fun parseAndValidateResponse(
        content: String,
        rawUtterance: String,
        currentItems: List<RequirementItem>
    ): TranslatedAction {
        val cleanJson = if (content.contains("```json")) {
            content.substringAfter("```json").substringBefore("```").trim()
        } else if (content.contains("```")) {
            content.substringAfter("```").substringBefore("```").trim()
        } else {
            content
        }

        val jsonObj = try {
            JsonParser.parseString(cleanJson).asJsonObject
        } catch (e: Exception) {
            return TranslatedAction(
                kind = UserActionKind.UNKNOWN,
                rawStatement = rawUtterance,
                clarificationMessage = "응답 파싱에 실패했습니다."
            )
        }

        val actionStr = jsonObj.get("action")?.asString?.trim()?.uppercase() ?: "UNKNOWN"
        val notInCandidates = jsonObj.get("notInCandidates")?.asBoolean ?: false
        val message = jsonObj.get("message")?.asString

        return when (actionStr) {
            "EXCLUDE" -> {
                val rawTargetId = jsonObj.get("targetId")?.asString?.trim()
                
                // [2층 코드 재검증]: LLM이 반환한 targetId가 실제 currentItems에 존재하는지 결정론적으로 검증
                val matchedItem = if (!rawTargetId.isNullOrBlank()) {
                    currentItems.find { it.id == rawTargetId }
                } else {
                    null
                }

                if (matchedItem != null) {
                    // 1층(프롬프트) & 2층(코드검증) 모두 통과 -> 정상 EXCLUDE
                    val filePath = when (val h = matchedItem.hint) {
                        is LinkHint.ExistingRef -> h.filePath
                        is LinkHint.NewCreation -> matchedItem.statement
                    }
                    TranslatedAction(
                        kind = UserActionKind.EXCLUDE,
                        targetId = matchedItem.id,
                        targetFilePath = filePath,
                        rawStatement = rawUtterance,
                        clarificationMessage = message ?: "'$filePath' 파일을 분석 대상에서 제외합니다."
                    )
                } else {
                    // (b-강화) LLM이 목록에 없는 헛것을 반환했거나 targetId가 null인 경우 -> NOT_IN_CANDIDATES로 강등
                    TranslatedAction(
                        kind = UserActionKind.UNKNOWN,
                        isNotInCandidates = true,
                        rawStatement = rawUtterance,
                        clarificationMessage = message ?: "지정하신 파일은 현재 후보 목록에 존재하지 않습니다."
                    )
                }
            }
            "INCLUDE_TOKEN" -> {
                val token = jsonObj.get("tokenValue")?.asString?.trim()
                if (!token.isNullOrBlank()) {
                    TranslatedAction(
                        kind = UserActionKind.INCLUDE_TOKEN,
                        tokenValue = token,
                        rawStatement = rawUtterance,
                        clarificationMessage = message ?: "'$token' 토큰을 검색 시드에 추가합니다."
                    )
                } else {
                    TranslatedAction(
                        kind = UserActionKind.UNKNOWN,
                        rawStatement = rawUtterance,
                        clarificationMessage = message ?: "추가할 검색 토큰을 파악하지 못했습니다."
                    )
                }
            }
            "ADD_CONSTRAINT" -> {
                val kindStr = jsonObj.get("constraintKind")?.asString?.trim()?.uppercase()
                val constraintKind = try {
                    ConstraintKind.valueOf(kindStr ?: "OTHER")
                } catch (e: Exception) {
                    ConstraintKind.OTHER
                }
                val constraintVal = jsonObj.get("constraintValue")?.asString?.trim() ?: rawUtterance
                val constraint = IntentConstraint(
                    kind = constraintKind,
                    value = constraintVal,
                    rawStatement = rawUtterance
                )
                TranslatedAction(
                    kind = UserActionKind.ADD_CONSTRAINT,
                    constraint = constraint,
                    rawStatement = rawUtterance,
                    clarificationMessage = message ?: "제약 조건이 추가되었습니다: $constraintVal"
                )
            }
            "COMPLETE" -> {
                TranslatedAction(
                    kind = UserActionKind.COMPLETE,
                    rawStatement = rawUtterance,
                    clarificationMessage = message ?: "요구사항 구체화를 완료하고 영향도 분석을 시작합니다."
                )
            }
            else -> {
                TranslatedAction(
                    kind = UserActionKind.UNKNOWN,
                    isNotInCandidates = notInCandidates,
                    rawStatement = rawUtterance,
                    clarificationMessage = message ?: "말씀하신 내용을 정확히 이해하지 못했습니다. 다시 설명해 주세요."
                )
            }
        }
    }
}
