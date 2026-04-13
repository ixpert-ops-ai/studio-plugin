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
import net.ib.ixpert.ops.wuwagent.service.WuwLlmService

class WuwSettingsConfigurable : SearchableConfigurable {
    private val settings = SettingsState.getInstance()

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
            group("LLM Server Connection") {
                row("Base URL:") {
                    baseUrlField = textField()
                        .columns(COLUMNS_MEDIUM)
                        .component
                }
                row("API Key:") {
                    apiKeyField = passwordField()
                        .columns(COLUMNS_MEDIUM)
                        .component
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

    private fun fetchModels() {
        val baseUrl = baseUrlField?.text ?: return
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        val parent = baseUrlField?.let { SwingUtilities.getWindowAncestor(it) }

        val originalText = fetchModelsButton?.text
        fetchModelsButton?.isEnabled = false
        fetchModelsButton?.text = "Fetching..."

        WuwLlmService.fetchModels(parent, baseUrl, apiKey, onComplete = {
            fetchModelsButton?.isEnabled = true
            fetchModelsButton?.text = originalText
        }) { models ->
            modelComboBox?.let { comboBox ->
                val currentModel = modelComboBox?.selectedItem as? String ?: settings.state.model
                comboBox.removeAllItems()
                models.forEach { comboBox.addItem(it) }
                
                if (models.contains(currentModel)) {
                    comboBox.selectedItem = currentModel
                } else if (models.isNotEmpty()) {
                    comboBox.selectedIndex = 0
                }
            }
        }
    }

    private fun testConnection() {
        val baseUrl = baseUrlField?.text ?: return
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        val parent = baseUrlField?.let { SwingUtilities.getWindowAncestor(it) }

        val originalText = testConnectionButton?.text
        testConnectionButton?.isEnabled = false
        testConnectionButton?.text = "Testing..."

        WuwLlmService.testConnection(parent, baseUrl, apiKey) {
            testConnectionButton?.isEnabled = true
            testConnectionButton?.text = originalText
        }
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return baseUrlField?.text != state.baseUrl ||
                String(apiKeyField?.password ?: charArrayOf()) != state.apiKey ||
                modelComboBox?.selectedItem != state.model ||
                temperatureSpinner?.text != state.temperature.toString() ||
                timeoutSpinner?.text != state.timeoutSeconds.toString() ||
                contextWindowSpinner?.text != state.contextWindow.toString()
    }

    override fun apply() {
        val state = settings.state
        state.baseUrl = baseUrlField?.text ?: ""
        state.apiKey = String(apiKeyField?.password ?: charArrayOf())
        state.model = modelComboBox?.selectedItem as? String ?: ""
        state.temperature = temperatureSpinner?.text?.toFloatOrNull() ?: 0.1f
        state.timeoutSeconds = timeoutSpinner?.text?.toIntOrNull() ?: 300
        state.contextWindow = contextWindowSpinner?.text?.toIntOrNull() ?: 32768

        // 변경된 모델 정보를 모든 열린 프로젝트의 WebView로 동기화
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
            net.ib.ixpert.ops.wuwagent.ui.bridge.JcefBridge.getInstance(project).sendMessage("selected_model", state.model)
        }
    }

    override fun reset() {
        val state = settings.state
        baseUrlField?.setText(state.baseUrl)
        apiKeyField?.setText(state.apiKey)
        modelComboBox?.selectedItem = state.model
        temperatureSpinner?.setText(state.temperature.toString())
        timeoutSpinner?.setText(state.timeoutSeconds.toString())
        contextWindowSpinner?.setText(state.contextWindow.toString())
    }
}
