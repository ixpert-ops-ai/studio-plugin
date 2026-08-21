package net.ib.ixpert.ops.wuwagent.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.ib.ixpert.ops.wuwagent.service.WuwLlmService
import net.ib.ixpert.ops.wuwagent.setting.SettingsState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IDE 시작 시, 설정에 저장된 모델명이 실제로 서버에 존재하는지 백그라운드에서 검증합니다.
 *
 * 배경: 서버 쪽에서 모델이 교체/삭제되면 설정에는 예전 모델명이 그대로 남아
 * 요청이 실패하거나 이상한 응답이 나오는데, 사용자는 원인을 알기 어렵다.
 * (기존에는 설정 화면에서 "Fetch Models" 버튼을 직접 눌러야만 서버 목록을 확인 가능했음)
 *
 * - IDE 시작을 절대 블로킹하지 않도록 [backgroundPostStartupActivity]로 등록하고,
 *   서버 조회 자체도 3초 타임아웃을 건 뒤 결과를 기다리지 않고 흘려보낸다.
 * - 서버 조회 실패(다운/네트워크 오류/타임아웃)는 정상적인 오프라인 상황일 수 있으므로
 *   조용히 종료한다 (알림 없음).
 * - 프로젝트를 여러 개 열어도 IDE 프로세스당(세션당) 알림은 1회만 표시한다.
 */
class ModelValidationStartupActivity : ProjectActivity {
    private val logger = Logger.getInstance(ModelValidationStartupActivity::class.java)

    companion object {
        private const val TIMEOUT_MS = 3000L
        private const val MAX_MODELS_IN_MESSAGE = 3
        private const val NOTIFICATION_GROUP_ID = "WhatUWantNotification"

        // JVM(=IDE 세션) 전체에서 공유되는 플래그 — 프로젝트를 여러 개 열어도 알림은 1회만.
        private val hasChecked = AtomicBoolean(false)
    }

    override suspend fun execute(project: Project) {
        // 세션 내 이미 검증(성공/실패 무관)을 시도했다면 스킵. 여러 프로젝트를 동시에/순차로
        // 열어도 중복 조회·중복 알림이 발생하지 않도록 최초 1회만 통과시킨다.
        if (!hasChecked.compareAndSet(false, true)) {
            logger.info("ModelValidationStartupActivity: 세션 내 이미 검증 수행됨 → 스킵")
            return
        }

        val settings = SettingsState.getInstance().state
        val configuredModel = settings.model.trim()
        val baseUrl = when (settings.apiType) {
            SettingsState.ApiType.OLLAMA -> settings.ollamaServerUrl
            SettingsState.ApiType.OPENAI_COMPATIBLE -> settings.openaiServerUrl
            SettingsState.ApiType.AIPRO -> settings.aiproServerUrl
        }

        // WuwLlmService.getClient()로 기존 apiType 분기(Ollama /api/tags vs
        // OpenAI Compatible /v1/models)를 그대로 재사용한다.
        // ※ WuwLlmService.fetchModels(...) 래퍼는 성공/실패 시 항상 Messages 다이얼로그를
        //   띄우는 UI 헬퍼라 백그라운드 자동 검증에는 부적합 — LLMClient.fetchModels()를 직접 호출.
        val models = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    WuwLlmService.getClient().fetchModels(baseUrl, settings.effectiveApiKey())
                }
            }
        } catch (e: Exception) {
            logger.info("ModelValidationStartupActivity: 모델 조회 중 예외 → 조용히 종료 (${e.message})")
            null
        }

        if (models == null) {
            // 타임아웃(3초 초과) 또는 서버 다운/네트워크 오류 — 알림 없이 조용히 종료
            logger.info("ModelValidationStartupActivity: 서버 모델 목록 조회 실패/타임아웃 → 검증 스킵")
            return
        }

        if (configuredModel.isNotBlank() && models.any { it == configuredModel }) {
            logger.info("ModelValidationStartupActivity: 설정된 모델('$configuredModel') 서버 목록에서 확인됨")
            return
        }

        logger.warn(
            "ModelValidationStartupActivity: 설정된 모델('$configuredModel')이 서버 목록에 없음 " +
            "(서버 모델 ${models.size}개) → 알림 표시"
        )
        showModelMismatchNotification(project, configuredModel, models)
    }

    private fun showModelMismatchNotification(
        project: Project,
        configuredModel: String,
        availableModels: List<String>
    ) {
        val displayModel = configuredModel.ifBlank { "(설정 안 됨)" }
        val availableDisplay = availableModels.take(MAX_MODELS_IN_MESSAGE).joinToString(", ")
            .ifBlank { "(서버에 등록된 모델 없음)" }

        val content = "설정된 모델을 서버에서 찾을 수 없습니다.\n" +
            "현재 설정: $displayModel\n" +
            "사용 가능: $availableDisplay"

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification("iXpert AI Assistant", content, NotificationType.WARNING)

        when {
            availableModels.size == 1 -> {
                val onlyModel = availableModels[0]
                notification.addAction(NotificationAction.createSimple("${onlyModel}으로 변경") {
                    SettingsState.getInstance().state.model = onlyModel
                    logger.info("ModelValidationStartupActivity: 사용자가 모델을 '$onlyModel'(으)로 교체")
                    notification.expire()
                })
            }
            availableModels.size >= 2 -> {
                notification.addAction(NotificationAction.createSimple("설정 열기") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "iXpert AI Assistant")
                    notification.expire()
                })
            }
            // availableModels가 비어있는 경우(서버 응답은 왔으나 모델 0개): 액션 버튼 없이 안내만 표시
        }

        notification.notify(project)
    }
}
