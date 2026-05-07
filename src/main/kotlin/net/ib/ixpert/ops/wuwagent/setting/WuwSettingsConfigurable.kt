package net.ib.ixpert.ops.wuwagent.setting

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
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
    private val settings = SettingsState.getInstance()

    private var apiTypeComboBox: ComboBox<String>? = null
    private var baseUrlField: JBTextField? = null
    private var apiKeyField: JBPasswordField? = null
    private var modelComboBox: ComboBox<String>? = null
    private var temperatureSpinner: JBTextField? = null
    private var timeoutSpinner: JBTextField? = null
    private var contextWindowSpinner: JBTextField? = null
    private var fetchModelsButton: JButton? = null
    private var testConnectionButton: JButton? = null

    override fun getId(): String = "net.ib.ixpert.ops.wuwagent.setting.WuwSettingsConfigurable"

    override fun getDisplayName(): String = "iXpert AI Assistant"

    override fun createComponent(): JComponent {
        return panel {
            group("API 설정") {
                row("API Type:") {
                    apiTypeComboBox = comboBox(
                        DefaultComboBoxModel(arrayOf("Ollama", "OpenAI Compatible"))
                    ).component
                }
            }
            group("LLM Server Connection") {
                row("Base URL:") {
                    baseUrlField = textField()
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
                row("Model:") {
                    modelComboBox = comboBox(DefaultComboBoxModel<String>(arrayOf(settings.state.model)))
                        .component
                    fetchModelsButton = button("Fetch Models") {
                        fetchModels()
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
        }
    }

    private fun getCurrentApiType(): SettingsState.ApiType {
        val selected = apiTypeComboBox?.selectedItem as? String ?: return SettingsState.ApiType.OLLAMA
        return if (selected == "OpenAI Compatible") SettingsState.ApiType.OPENAI_COMPATIBLE
               else SettingsState.ApiType.OLLAMA
    }

    private fun apiTypeToDisplayName(apiType: SettingsState.ApiType): String =
        if (apiType == SettingsState.ApiType.OPENAI_COMPATIBLE) "OpenAI Compatible" else "Ollama"

    private fun fetchModels() {
        val baseUrl = baseUrlField?.text ?: return
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        val apiType = getCurrentApiType()
        val parent = baseUrlField?.let { SwingUtilities.getWindowAncestor(it) }

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
        val baseUrl = baseUrlField?.text ?: return
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        val apiType = getCurrentApiType()
        val comboBox = modelComboBox ?: return

        val currentItems = (0 until comboBox.itemCount).map { comboBox.getItemAt(it) }
        val loadingText = "로딩 중..."

        if (!currentItems.contains(loadingText)) {
            comboBox.addItem(loadingText)
        }
        comboBox.selectedItem = loadingText
        comboBox.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val models = net.ib.ixpert.ops.wuwagent.agent.SettingsAgent.fetchModelsSilent(baseUrl, apiKey, apiType)

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
            val currentModel = if (selected == loadingText || selected.isNullOrBlank()) {
                savedModel
            } else {
                selected
            }

            comboBox.removeAllItems()
            models.forEach { comboBox.addItem(it) }

            when {
                models.contains(currentModel) -> comboBox.selectedItem = currentModel
                models.contains(savedModel) -> comboBox.selectedItem = savedModel
                models.isNotEmpty() -> comboBox.selectedIndex = 0
            }
        }
    }

    private fun testConnection() {
        val baseUrl = baseUrlField?.text ?: return
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        val apiType = getCurrentApiType()
        val parent = baseUrlField?.let { SwingUtilities.getWindowAncestor(it) }

        val originalText = testConnectionButton?.text
        testConnectionButton?.isEnabled = false
        testConnectionButton?.text = "Testing..."

        WuwLlmService.testConnection(parent, baseUrl, apiKey, apiType) {
            testConnectionButton?.isEnabled = true
            testConnectionButton?.text = originalText
        }
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return apiTypeComboBox?.selectedItem != apiTypeToDisplayName(state.apiType) ||
                baseUrlField?.text != state.baseUrl ||
                String(apiKeyField?.password ?: charArrayOf()) != state.apiKey ||
                modelComboBox?.selectedItem != state.model ||
                temperatureSpinner?.text != state.temperature.toString() ||
                timeoutSpinner?.text != state.timeoutSeconds.toString() ||
                contextWindowSpinner?.text != state.contextWindow.toString()
    }

    override fun apply() {
        val state = settings.state
        state.apiType = getCurrentApiType()
        state.baseUrl = baseUrlField?.text ?: ""
        state.apiKey = String(apiKeyField?.password ?: charArrayOf())
        state.model = modelComboBox?.selectedItem as? String ?: ""
        state.temperature = temperatureSpinner?.text?.toFloatOrNull() ?: 0.1f
        state.timeoutSeconds = timeoutSpinner?.text?.toIntOrNull() ?: 300
        state.contextWindow = contextWindowSpinner?.text?.toIntOrNull() ?: 32768

        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
            net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project).sendMessage("selected_model", state.model)
        }
    }

    override fun reset() {
        val state = settings.state
        apiTypeComboBox?.selectedItem = apiTypeToDisplayName(state.apiType)
        baseUrlField?.setText(state.baseUrl)
        apiKeyField?.setText(state.apiKey)
        modelComboBox?.selectedItem = state.model
        temperatureSpinner?.setText(state.temperature.toString())
        timeoutSpinner?.setText(state.timeoutSeconds.toString())
        contextWindowSpinner?.setText(state.contextWindow.toString())

        autoFetchModels()
    }
}
