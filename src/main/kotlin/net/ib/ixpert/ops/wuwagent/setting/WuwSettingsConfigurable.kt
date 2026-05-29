package net.ib.ixpert.ops.wuwagent.setting

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.DefaultComboBoxModel
import javax.swing.SwingUtilities
import com.intellij.openapi.ui.ComboBox
import javax.swing.JButton
import com.intellij.openapi.application.ApplicationManager
import net.ib.ixpert.ops.wuwagent.service.WuwLlmService

class WuwSettingsConfigurable : SearchableConfigurable {

    companion object {
        private const val AIPRO_DEFAULT_URL   = "https://aipro.samsungcard.biz:20443/open/api"
        private const val AIPRO_DEFAULT_MODEL = "GPT-OSS_api"
    }

    private val settings = SettingsState.getInstance()

    /** reset() 중에는 ActionListener 자동입력 방지 */
    private var isResetting = false

    private var apiTypeComboBox: ComboBox<String>? = null
    private var apiKeyField: JBPasswordField? = null

    // API 타입별 URL 입력 필드 + 행
    private var ollamaUrlField: JBTextField? = null
    private var ollamaUrlRow: Row? = null

    private var openaiUrlField: JBTextField? = null
    private var openaiUrlRow: Row? = null
    private var openaiUrlHintRow: Row? = null

    private var aiproUrlField: JBTextField? = null
    private var aiproUrlRow: Row? = null

    // Ollama 전용: 드롭박스 + Fetch Models 버튼
    private var modelComboBox: ComboBox<String>? = null
    private var fetchModelsButton: JButton? = null
    private var ollamaModelRow: Row? = null

    // OpenAI Compatible / aipro 공용: 직접 입력 텍스트 필드
    private var modelTextField: JBTextField? = null
    private var openaiModelRow: Row? = null

    private var temperatureSpinner: JBTextField? = null
    private var timeoutSpinner: JBTextField? = null
    private var contextWindowSpinner: JBTextField? = null
    private var testConnectionButton: JButton? = null
    private var enableLlmDebugCheckBox: javax.swing.JCheckBox? = null
    private var frameworkTypeComboBox: ComboBox<String>? = null

    override fun getId(): String = "net.ib.ixpert.ops.wuwagent.setting.WuwSettingsConfigurable"

    override fun getDisplayName(): String = "iXpert AI Assistant"

    override fun createComponent(): JComponent {
        return panel {
            group("API 설정") {
                row("API Type:") {
                    apiTypeComboBox = comboBox(
                        DefaultComboBoxModel(arrayOf("Ollama", "OpenAI Compatible", "aipro"))
                    ).component
                    apiTypeComboBox?.addActionListener {
                        if (!isResetting) onApiTypeChanged()
                        else updateRowVisibility()
                    }
                }
            }
            group("LLM Server Connection") {
                // Ollama 전용 URL 행
                ollamaUrlRow = row("Base URL:") {
                    ollamaUrlField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .component
                }

                // OpenAI Compatible 전용 URL 행 + 힌트
                openaiUrlRow = row("Base URL:") {
                    openaiUrlField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .component
                }
                openaiUrlHintRow = row {
                    label("⚠ 경로까지 포함해서 입력하세요 (예: https://host:port/open/api)").applyToComponent {
                        foreground = java.awt.Color(160, 100, 0)
                        font = font.deriveFont(font.size2D - 0.5f)
                    }
                }

                // aipro 전용 URL 행
                aiproUrlRow = row("Base URL:") {
                    aiproUrlField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .component
                }

                row("API Key:") {
                    val pwdField = passwordField()
                        .columns(COLUMNS_MEDIUM)
                        .component
                    apiKeyField = pwdField

                    val defaultEchoChar = pwdField.echoChar
                    var isVisible = false

                    button("Show") {
                        isVisible = !isVisible
                        pwdField.echoChar = if (isVisible) 0.toChar() else defaultEchoChar
                        (it.source as? javax.swing.JButton)?.text = if (isVisible) "Hide" else "Show"
                    }
                }

                // Ollama 전용 행: 드롭박스 + Fetch Models
                ollamaModelRow = row("Model:") {
                    modelComboBox = comboBox(DefaultComboBoxModel<String>(arrayOf(settings.state.model)))
                        .component
                    fetchModelsButton = button("Fetch Models") {
                        fetchModels()
                    }.component
                }

                // OpenAI Compatible / aipro 공용 행: 직접 입력
                openaiModelRow = row("Model:") {
                    modelTextField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .comment("모델명을 직접 입력하세요 (예: GPT-OSS_api, gpt-4o)")
                        .component
                }

                row {
                    testConnectionButton = button("Test Connection") {
                        testConnection()
                    }.component
                }
            }
            group("Generation Parameters") {
                row("Temperature:") {
                    temperatureSpinner = textField()
                        .columns(10)
                        .comment("0.0 (precise) to 1.0 (creative)")
                        .component
                }
                row("Timeout (seconds):") {
                    timeoutSpinner = textField()
                        .columns(10)
                        .component
                }
                row("Context Window:") {
                    contextWindowSpinner = textField()
                        .columns(10)
                        .component
                }
            }
            group("분석기 설정 (Analyzer Settings)") {
                row("대상 프레임워크 (Framework):") {
                    frameworkTypeComboBox = comboBox(
                        DefaultComboBoxModel(net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.values().map { it.displayName }.toTypedArray())
                    ).component
                }
            }
            group("Debug") {
                row {
                    enableLlmDebugCheckBox = checkBox("LLM Debug 패널 활성화 (LLM Debug ToolWindow에서 요청/응답 확인)")
                        .component
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  API 타입 변경 시 처리 (사용자가 직접 드롭박스를 바꿀 때만 호출)
    // ──────────────────────────────────────────────────────────────

    private fun onApiTypeChanged() {
        val type = getCurrentApiType()

        // aipro 선택 시 기본값 자동 입력
        if (type == SettingsState.ApiType.AIPRO) {
            if (aiproUrlField?.text.isNullOrBlank()) {
                aiproUrlField?.setText(AIPRO_DEFAULT_URL)
            }
            if (modelTextField?.text.isNullOrBlank()) {
                modelTextField?.setText(AIPRO_DEFAULT_MODEL)
            } else {
                modelTextField?.setText(AIPRO_DEFAULT_MODEL)
            }
        }

        updateRowVisibility()
    }

    /** API 타입에 따라 URL 행 및 모델 행 표시/숨김 */
    private fun updateRowVisibility() {
        val type = getCurrentApiType()
        val isOllama = type == SettingsState.ApiType.OLLAMA
        val isOpenAI = type == SettingsState.ApiType.OPENAI_COMPATIBLE
        val isAipro  = type == SettingsState.ApiType.AIPRO

        ollamaUrlRow?.visible(isOllama)
        openaiUrlRow?.visible(isOpenAI)
        openaiUrlHintRow?.visible(isOpenAI)
        aiproUrlRow?.visible(isAipro)

        ollamaModelRow?.visible(isOllama)
        openaiModelRow?.visible(!isOllama)
    }

    /** 현재 API 타입에 맞는 URL 필드 반환 */
    private fun getCurrentUrlField(): JBTextField? = when (getCurrentApiType()) {
        SettingsState.ApiType.OLLAMA             -> ollamaUrlField
        SettingsState.ApiType.OPENAI_COMPATIBLE  -> openaiUrlField
        SettingsState.ApiType.AIPRO              -> aiproUrlField
    }

    /** 현재 API 타입에 맞는 모델 입력값 반환 */
    private fun getCurrentModelValue(): String {
        return if (getCurrentApiType() == SettingsState.ApiType.OLLAMA) {
            modelComboBox?.selectedItem as? String ?: ""
        } else {
            modelTextField?.text?.trim() ?: ""
        }
    }

    private fun getCurrentApiType(): SettingsState.ApiType {
        return when (apiTypeComboBox?.selectedItem as? String) {
            "OpenAI Compatible" -> SettingsState.ApiType.OPENAI_COMPATIBLE
            "aipro"             -> SettingsState.ApiType.AIPRO
            else                -> SettingsState.ApiType.OLLAMA
        }
    }

    private fun apiTypeToDisplayName(apiType: SettingsState.ApiType): String = when (apiType) {
        SettingsState.ApiType.OPENAI_COMPATIBLE -> "OpenAI Compatible"
        SettingsState.ApiType.AIPRO             -> "aipro"
        SettingsState.ApiType.OLLAMA            -> "Ollama"
    }

    // ──────────────────────────────────────────────────────────────
    //  버튼 동작
    // ──────────────────────────────────────────────────────────────

    private fun fetchModels() {
        val baseUrl = getCurrentUrlField()?.text ?: return
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        val apiType = getCurrentApiType()
        val parent = ollamaUrlField?.let { SwingUtilities.getWindowAncestor(it) }

        val originalText = fetchModelsButton?.text
        fetchModelsButton?.isEnabled = false
        fetchModelsButton?.text = "Fetching..."

        WuwLlmService.fetchModels(parent, baseUrl, apiKey, apiType, onComplete = {
            fetchModelsButton?.isEnabled = true
            fetchModelsButton?.text = originalText
        }) { models ->
            updateModelComboBox(models)
        }
    }

    private fun autoFetchModels() {
        // Ollama 모드에서만 자동 모델 조회
        if (getCurrentApiType() != SettingsState.ApiType.OLLAMA) return

        val baseUrl = ollamaUrlField?.text ?: return
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        val comboBox = modelComboBox ?: return

        val currentItems = (0 until comboBox.itemCount).map { comboBox.getItemAt(it) }
        val loadingText = "로딩 중..."

        if (!currentItems.contains(loadingText)) comboBox.addItem(loadingText)
        comboBox.selectedItem = loadingText
        comboBox.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val models = net.ib.ixpert.ops.wuwagent.agent.SettingsAgent.fetchModelsSilent(
                baseUrl, apiKey, SettingsState.ApiType.OLLAMA
            )
            ApplicationManager.getApplication().invokeLater {
                comboBox.isEnabled = true
                if (models != null && models.isNotEmpty()) {
                    updateModelComboBox(models)
                } else {
                    comboBox.removeItem(loadingText)
                    comboBox.selectedItem = settings.state.model
                }
            }
        }
    }

    private fun updateModelComboBox(models: List<String>) {
        modelComboBox?.let { comboBox ->
            val loadingText = "로딩 중..."
            val savedModel = settings.state.model
            val selected = comboBox.selectedItem as? String
            val currentModel = if (selected == loadingText || selected.isNullOrBlank()) savedModel else selected

            comboBox.removeAllItems()
            models.forEach { comboBox.addItem(it) }

            when {
                models.contains(currentModel) -> comboBox.selectedItem = currentModel
                models.contains(savedModel)   -> comboBox.selectedItem = savedModel
                models.isNotEmpty()           -> comboBox.selectedIndex = 0
            }
        }
    }

    private fun testConnection() {
        val baseUrl = getCurrentUrlField()?.text ?: return
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        val apiType = getCurrentApiType()
        val model = getCurrentModelValue()
        val parent = getCurrentUrlField()?.let { SwingUtilities.getWindowAncestor(it) }

        val originalText = testConnectionButton?.text
        testConnectionButton?.isEnabled = false
        testConnectionButton?.text = "Testing..."

        WuwLlmService.testConnection(parent, baseUrl, apiKey, apiType, model) {
            testConnectionButton?.isEnabled = true
            testConnectionButton?.text = originalText
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Configurable 생명주기
    // ──────────────────────────────────────────────────────────────

    override fun isModified(): Boolean {
        val state = settings.state
        return apiTypeComboBox?.selectedItem != apiTypeToDisplayName(state.apiType) ||
                ollamaUrlField?.text != state.ollamaServerUrl ||
                openaiUrlField?.text != state.openaiServerUrl ||
                aiproUrlField?.text != state.aiproServerUrl ||
                String(apiKeyField?.password ?: charArrayOf()) != state.apiKey ||
                getCurrentModelValue() != state.model ||
                temperatureSpinner?.text != state.temperature.toString() ||
                timeoutSpinner?.text != state.timeoutSeconds.toString() ||
                contextWindowSpinner?.text != state.contextWindow.toString() ||
                enableLlmDebugCheckBox?.isSelected != state.enableLlmDebug ||
                getCurrentFrameworkType() != state.frameworkType
    }

    override fun apply() {
        val state = settings.state
        state.apiType = getCurrentApiType()
        state.ollamaServerUrl = ollamaUrlField?.text ?: ""
        state.openaiServerUrl = openaiUrlField?.text ?: ""
        state.aiproServerUrl  = aiproUrlField?.text ?: ""
        state.apiKey = String(apiKeyField?.password ?: charArrayOf())
        state.model = getCurrentModelValue()
        state.temperature = temperatureSpinner?.text?.toFloatOrNull() ?: 0.1f
        state.timeoutSeconds = timeoutSpinner?.text?.toIntOrNull() ?: 300
        state.contextWindow = contextWindowSpinner?.text?.toIntOrNull() ?: 32768
        state.enableLlmDebug = enableLlmDebugCheckBox?.isSelected ?: false
        state.frameworkType = getCurrentFrameworkType()

        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
            net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project)
                .sendMessage("selected_model", state.model)
        }
    }

    override fun reset() {
        isResetting = true
        val state = settings.state

        apiTypeComboBox?.selectedItem = apiTypeToDisplayName(state.apiType)
        ollamaUrlField?.setText(state.ollamaServerUrl)
        openaiUrlField?.setText(state.openaiServerUrl)
        aiproUrlField?.setText(state.aiproServerUrl)
        apiKeyField?.setText(state.apiKey)

        // API 타입에 맞는 모델 필드에 저장값 복원
        if (state.apiType == SettingsState.ApiType.OLLAMA) {
            modelComboBox?.selectedItem = state.model
        } else {
            modelTextField?.setText(state.model)
        }

        temperatureSpinner?.setText(state.temperature.toString())
        timeoutSpinner?.setText(state.timeoutSeconds.toString())
        contextWindowSpinner?.setText(state.contextWindow.toString())
        enableLlmDebugCheckBox?.isSelected = state.enableLlmDebug
        frameworkTypeComboBox?.selectedItem = frameworkTypeToDisplayName(state.frameworkType)

        isResetting = false

        // 행 가시성 적용 후 Ollama면 자동 모델 조회
        updateRowVisibility()
        autoFetchModels()
    }

    private fun frameworkTypeToDisplayName(type: net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType): String {
        return type.displayName
    }

    private fun getCurrentFrameworkType(): net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType {
        val selectedDisplayName = frameworkTypeComboBox?.selectedItem as? String ?: return net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_JPA
        return net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.values().find { it.displayName == selectedDisplayName }
            ?: net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.SPRING_BOOT_JPA
    }
}
