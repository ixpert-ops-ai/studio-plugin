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
        private const val AIPRO_DEFAULT_URL     = "https://aipro.samsungcard.biz:20443/open/api"
        private const val AIPRO_DEFAULT_MODEL   = "GPT-OSS_api"
        private const val AIPRO_DEFAULT_API_KEY = "25dc93cc-75c2-4c47-a83e-7e35555cf65a"
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

    // OpenAI Compatible 전용: 드롭박스 + Fetch Models 버튼 (조회 실패 시 직접 입력 가능하도록 editable)
    private var openaiModelComboBox: ComboBox<String>? = null
    private var openaiModelFetchButton: JButton? = null
    private var openaiModelComboRow: Row? = null

    // aipro 전용: editable 드롭박스 + Fetch Models 버튼 (조회 실패 시 직접 입력 가능하도록 editable)
    private var aiproModelComboBox: ComboBox<String>? = null
    private var aiproModelFetchButton: JButton? = null
    private var aiproModelRow: Row? = null

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
                        DefaultComboBoxModel(arrayOf("OpenAI Compatible", "AIPro", "Ollama"))
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
                    label("서버 주소를 입력하세요. (예: http://vllm.ixpertops.cloud)").applyToComponent {
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
                // API 타입 변경 시 API Key 필드에 해당 타입의 저장값을 표시
                // (apiTypeComboBox ActionListener에서 swapApiKeyField() 호출)

                // Ollama 전용 행: 드롭박스 + Fetch Models
                ollamaModelRow = row("Model:") {
                    modelComboBox = comboBox(DefaultComboBoxModel<String>(arrayOf(settings.state.model)))
                        .component
                    fetchModelsButton = button("Fetch Models") {
                        fetchModels()
                    }.component
                }

                // OpenAI Compatible 전용 행: editable 드롭박스 + Fetch Models
                // editable=true 이므로 조회 실패 시에도 직접 입력 가능
                openaiModelComboRow = row("Model:") {
                    openaiModelComboBox = comboBox(DefaultComboBoxModel<String>(arrayOf(settings.state.model)))
                        .component
                    openaiModelComboBox?.isEditable = true
                    openaiModelFetchButton = button("Fetch Models") {
                        fetchOpenaiModels()
                    }.component
                }

                // aipro 전용 행: editable 드롭박스 + Fetch Models
                // editable=true 이므로 조회 실패 시에도 직접 입력 가능
                aiproModelRow = row("Model:") {
                    aiproModelComboBox = comboBox(DefaultComboBoxModel<String>(arrayOf(settings.state.model)))
                        .component
                    aiproModelComboBox?.isEditable = true
                    aiproModelFetchButton = button("Fetch Models") {
                        fetchAiproModels()
                    }.component
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
                        DefaultComboBoxModel(net.ib.ixpert.ops.wuwagent.service.metagraph.model.FrameworkType.values()
                            .filter { !it.displayName.contains("Legacy") }
                            .map { it.displayName }.toTypedArray())
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

        // API 타입 전환 시 API Key 필드에 해당 타입의 저장값 표시
        swapApiKeyField(type)

        // aipro 선택 시 기본값 자동 입력
        if (type == SettingsState.ApiType.AIPRO) {
            if (aiproUrlField?.text.isNullOrBlank()) {
                aiproUrlField?.setText(AIPRO_DEFAULT_URL)
            }
            val currentModel = (aiproModelComboBox?.editor?.item as? String)?.trim()
                ?: (aiproModelComboBox?.selectedItem as? String)?.trim()
            if (currentModel.isNullOrBlank()) {
                aiproModelComboBox?.selectedItem = AIPRO_DEFAULT_MODEL
            }
            if (getCurrentApiKey().isBlank()) {
                apiKeyField?.setText(AIPRO_DEFAULT_API_KEY)
            }
        }

        updateRowVisibility()
    }

    /**
     * API 타입 전환 시 API Key 필드를 해당 타입의 저장값으로 교체합니다.
     */
    private fun swapApiKeyField(type: SettingsState.ApiType) {
        val state = settings.state
        val key = when (type) {
            SettingsState.ApiType.OLLAMA            -> state.ollamaApiKey
            SettingsState.ApiType.OPENAI_COMPATIBLE -> state.openaiApiKey
            SettingsState.ApiType.AIPRO             -> state.aiproApiKey
        }
        apiKeyField?.setText(key)
    }

    /** API 타입에 따라 URL 행 및 모델 행 표시/숨김 */
    private fun updateRowVisibility() {
        val type = getCurrentApiType()
        val isOllama  = type == SettingsState.ApiType.OLLAMA
        val isOpenAI  = type == SettingsState.ApiType.OPENAI_COMPATIBLE
        val isAipro   = type == SettingsState.ApiType.AIPRO

        ollamaUrlRow?.visible(isOllama)
        openaiUrlRow?.visible(isOpenAI)
        openaiUrlHintRow?.visible(isOpenAI)
        aiproUrlRow?.visible(isAipro)

        ollamaModelRow?.visible(isOllama)
        openaiModelComboRow?.visible(isOpenAI)
        aiproModelRow?.visible(isAipro)
    }

    /** 현재 API 타입에 맞는 URL 필드 반환 */
    private fun getCurrentUrlField(): JBTextField? = when (getCurrentApiType()) {
        SettingsState.ApiType.OLLAMA             -> ollamaUrlField
        SettingsState.ApiType.OPENAI_COMPATIBLE  -> openaiUrlField
        SettingsState.ApiType.AIPRO              -> aiproUrlField
    }

    /** 현재 API 타입에 맞는 모델 입력값 반환 */
    private fun getCurrentModelValue(): String {
        return when (getCurrentApiType()) {
            SettingsState.ApiType.OLLAMA            -> modelComboBox?.selectedItem as? String ?: ""
            SettingsState.ApiType.OPENAI_COMPATIBLE -> {
                // editable ComboBox: editor에 직접 입력한 값도 반영
                val editor = openaiModelComboBox?.editor?.item as? String
                editor?.trim()?.takeIf { it.isNotBlank() }
                    ?: (openaiModelComboBox?.selectedItem as? String)?.trim()
                    ?: ""
            }
            SettingsState.ApiType.AIPRO             -> {
                // editable ComboBox: editor에 직접 입력한 값도 반영
                val editor = aiproModelComboBox?.editor?.item as? String
                editor?.trim()?.takeIf { it.isNotBlank() }
                    ?: (aiproModelComboBox?.selectedItem as? String)?.trim()
                    ?: ""
            }
        }
    }

    /** API Key 필드의 현재 입력값 (타입 무관하게 화면에 보이는 값) */
    private fun getCurrentApiKey(): String = String(apiKeyField?.password ?: charArrayOf())

    private fun getCurrentApiType(): SettingsState.ApiType {
        return when (apiTypeComboBox?.selectedItem as? String) {
            "OpenAI Compatible" -> SettingsState.ApiType.OPENAI_COMPATIBLE
            "AIPro"             -> SettingsState.ApiType.AIPRO
            else                -> SettingsState.ApiType.OLLAMA
        }
    }

    private fun apiTypeToDisplayName(apiType: SettingsState.ApiType): String = when (apiType) {
        SettingsState.ApiType.OPENAI_COMPATIBLE -> "OpenAI Compatible"
        SettingsState.ApiType.AIPRO             -> "AIPro"
        SettingsState.ApiType.OLLAMA            -> "Ollama"
    }

    // ──────────────────────────────────────────────────────────────
    //  버튼 동작
    // ──────────────────────────────────────────────────────────────

    /** Ollama 모델 조회 */
    private fun fetchModels() {
        val baseUrl = getCurrentUrlField()?.text ?: return
        val apiKey = getCurrentApiKey()
        val parent = ollamaUrlField?.let { SwingUtilities.getWindowAncestor(it) }

        val originalText = fetchModelsButton?.text
        fetchModelsButton?.isEnabled = false
        fetchModelsButton?.text = "Fetching..."

        WuwLlmService.fetchModels(parent, baseUrl, apiKey, SettingsState.ApiType.OLLAMA, onComplete = {
            fetchModelsButton?.isEnabled = true
            fetchModelsButton?.text = originalText
        }) { models ->
            updateModelComboBox(models)
        }
    }

    /** OpenAI Compatible 모델 조회 (GET /v1/models) */
    private fun fetchOpenaiModels() {
        val baseUrl = getCurrentUrlField()?.text ?: return
        val apiKey = getCurrentApiKey()
        val parent = openaiUrlField?.let { SwingUtilities.getWindowAncestor(it) }

        val originalText = openaiModelFetchButton?.text
        openaiModelFetchButton?.isEnabled = false
        openaiModelFetchButton?.text = "Fetching..."

        WuwLlmService.fetchModels(parent, baseUrl, apiKey, SettingsState.ApiType.OPENAI_COMPATIBLE, onComplete = {
            openaiModelFetchButton?.isEnabled = true
            openaiModelFetchButton?.text = originalText
        }) { models ->
            updateOpenaiModelComboBox(models)
        }
    }

    /** aipro 모델 조회 (GET /v1/models, 표준 OpenAI 형식 우선 → AIPro modelList[].name 폴백) */
    private fun fetchAiproModels() {
        val baseUrl = getCurrentUrlField()?.text ?: return
        val apiKey = getCurrentApiKey()
        val parent = aiproUrlField?.let { SwingUtilities.getWindowAncestor(it) }

        val originalText = aiproModelFetchButton?.text
        aiproModelFetchButton?.isEnabled = false
        aiproModelFetchButton?.text = "Fetching..."

        WuwLlmService.fetchModels(parent, baseUrl, apiKey, SettingsState.ApiType.AIPRO, onComplete = {
            aiproModelFetchButton?.isEnabled = true
            aiproModelFetchButton?.text = originalText
        }) { models ->
            updateAiproModelComboBox(models)
        }
    }

    private fun autoFetchModels() {
        // Ollama 모드에서만 자동 모델 조회
        if (getCurrentApiType() != SettingsState.ApiType.OLLAMA) return

        val baseUrl = ollamaUrlField?.text ?: return
        val apiKey = getCurrentApiKey()
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

    /** Ollama 모델 콤보박스 업데이트 */
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

    /** OpenAI Compatible 모델 콤보박스 업데이트 */
    private fun updateOpenaiModelComboBox(models: List<String>) {
        openaiModelComboBox?.let { comboBox ->
            val savedModel = settings.state.model
            val currentTyped = (comboBox.editor?.item as? String)?.trim()
                ?: (comboBox.selectedItem as? String)?.trim()
                ?: ""

            comboBox.removeAllItems()
            models.forEach { comboBox.addItem(it) }

            val preferred = currentTyped.takeIf { it.isNotBlank() } ?: savedModel
            when {
                models.contains(preferred)  -> comboBox.selectedItem = preferred
                models.contains(savedModel) -> comboBox.selectedItem = savedModel
                models.isNotEmpty()         -> comboBox.selectedIndex = 0
            }
        }
    }

    /** aipro 모델 콤보박스 업데이트 */
    private fun updateAiproModelComboBox(models: List<String>) {
        aiproModelComboBox?.let { comboBox ->
            val savedModel = settings.state.model
            val currentTyped = (comboBox.editor?.item as? String)?.trim()
                ?: (comboBox.selectedItem as? String)?.trim()
                ?: ""

            comboBox.removeAllItems()
            models.forEach { comboBox.addItem(it) }

            val preferred = currentTyped.takeIf { it.isNotBlank() } ?: savedModel
            when {
                models.contains(preferred)  -> comboBox.selectedItem = preferred
                models.contains(savedModel) -> comboBox.selectedItem = savedModel
                models.isNotEmpty()         -> comboBox.selectedIndex = 0
            }
        }
    }

    private fun testConnection() {
        val baseUrl = getCurrentUrlField()?.text ?: return
        val apiKey = getCurrentApiKey()
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
        val currentApiKey = getCurrentApiKey()
        val apiKeyChanged = when (getCurrentApiType()) {
            SettingsState.ApiType.OLLAMA            -> currentApiKey != state.ollamaApiKey
            SettingsState.ApiType.OPENAI_COMPATIBLE -> currentApiKey != state.openaiApiKey
            SettingsState.ApiType.AIPRO             -> currentApiKey != state.aiproApiKey
        }
        return apiTypeComboBox?.selectedItem != apiTypeToDisplayName(state.apiType) ||
                ollamaUrlField?.text != state.ollamaServerUrl ||
                openaiUrlField?.text != state.openaiServerUrl ||
                aiproUrlField?.text != state.aiproServerUrl ||
                apiKeyChanged ||
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
        // 타입별 독립 필드에 저장
        val currentApiKey = getCurrentApiKey()
        when (state.apiType) {
            SettingsState.ApiType.OLLAMA            -> state.ollamaApiKey = currentApiKey
            SettingsState.ApiType.OPENAI_COMPATIBLE -> state.openaiApiKey = currentApiKey
            SettingsState.ApiType.AIPRO             -> state.aiproApiKey = currentApiKey
        }
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
        // API 타입에 맞는 키를 API Key 필드에 복원
        val keyToRestore = when (state.apiType) {
            SettingsState.ApiType.OLLAMA            -> state.ollamaApiKey
            SettingsState.ApiType.OPENAI_COMPATIBLE -> state.openaiApiKey
            SettingsState.ApiType.AIPRO             -> state.aiproApiKey
        }
        apiKeyField?.setText(keyToRestore)

        // API 타입에 맞는 모델 필드에 저장값 복원
        when (state.apiType) {
            SettingsState.ApiType.OLLAMA -> {
                modelComboBox?.selectedItem = state.model
            }
            SettingsState.ApiType.OPENAI_COMPATIBLE -> {
                // editable ComboBox: 저장된 모델이 목록에 없으면 editor에 직접 세팅
                val comboBox = openaiModelComboBox
                if (comboBox != null) {
                    val items = (0 until comboBox.itemCount).map { comboBox.getItemAt(it) }
                    if (items.contains(state.model)) {
                        comboBox.selectedItem = state.model
                    } else {
                        comboBox.removeAllItems()
                        comboBox.addItem(state.model)
                        comboBox.selectedItem = state.model
                    }
                }
            }
            SettingsState.ApiType.AIPRO -> {
                // editable ComboBox: 저장된 모델이 목록에 없으면 editor에 직접 세팅
                val comboBox = aiproModelComboBox
                if (comboBox != null) {
                    val items = (0 until comboBox.itemCount).map { comboBox.getItemAt(it) }
                    if (items.contains(state.model)) {
                        comboBox.selectedItem = state.model
                    } else {
                        comboBox.removeAllItems()
                        comboBox.addItem(state.model)
                        comboBox.selectedItem = state.model
                    }
                }
            }
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
