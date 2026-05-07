package net.ib.ixpert.ops.wuwagent.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import net.ib.ixpert.ops.wuwagent.service.DebugManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.TitledBorder

class LlmDebugToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = buildDebugPanel()
        val contentManager = toolWindow.contentManager
        contentManager.addContent(contentManager.factory.createContent(panel, "", false))
    }

    private fun buildDebugPanel(): JPanel {
        val gson = GsonBuilder().setPrettyPrinting().create()

        val requestArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(12f)
        }
        val responseArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(12f)
        }

        val listModel = DefaultListModel<String>()
        val entryList = JList(listModel)
        entryList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        var currentEntries: List<DebugManager.DebugEntry> = emptyList()

        fun refreshList() {
            currentEntries = DebugManager.getInstance().getLogs()
            listModel.clear()
            currentEntries.forEach { entry ->
                val icon = if (entry.isSuccess) "✅" else "❌"
                listModel.addElement("$icon [${entry.timestamp}] ${entry.requestId} | ${entry.durationMs}ms | ${entry.requestUrl}")
            }
            requestArea.text = ""
            responseArea.text = ""
        }

        fun formatRequestPanel(entry: DebugManager.DebugEntry): String {
            val sb = StringBuilder()
            sb.appendLine("── 메타 정보 ──────────────────────────────")
            sb.appendLine("requestId  : ${entry.requestId}")
            sb.appendLine("model      : ${entry.model}")
            sb.appendLine("streaming  : ${entry.isStreaming}")
            sb.appendLine("messages   : ${entry.messageCount}개")
            sb.appendLine()

            // messages 배열을 role별로 분리 표시
            try {
                val json = JsonParser.parseString(entry.requestPayload).asJsonObject
                val messages = json.getAsJsonArray("messages")
                if (messages != null) {
                    messages.forEachIndexed { i, el ->
                        val msg = el.asJsonObject
                        val role = msg.get("role")?.asString ?: "unknown"
                        val content = msg.get("content")?.asString ?: ""
                        sb.appendLine("── [${i + 1}] $role ──────────────────────────")
                        sb.appendLine(content)
                        sb.appendLine()
                    }
                }

                // messages 제외한 나머지 파라미터
                val otherParams = json.asMap().filterKeys { it != "messages" }
                if (otherParams.isNotEmpty()) {
                    sb.appendLine("── 요청 파라미터 ──────────────────────────")
                    val paramJson = com.google.gson.JsonObject()
                    otherParams.forEach { (k, v) -> paramJson.add(k, v) }
                    sb.append(gson.toJson(paramJson))
                }
            } catch (_: Exception) {
                sb.appendLine("── Raw Payload ────────────────────────────")
                sb.append(entry.requestPayload)
            }
            return sb.toString()
        }

        fun formatResponsePanel(entry: DebugManager.DebugEntry): String {
            val sb = StringBuilder()
            sb.appendLine("── 응답 정보 ──────────────────────────────")
            sb.appendLine("성공 여부  : ${if (entry.isSuccess) "성공 ✅" else "실패 ❌"}")
            sb.appendLine("소요 시간  : ${entry.durationMs}ms")
            sb.appendLine("스트리밍   : ${entry.isStreaming}")
            sb.appendLine("응답 길이  : ${entry.responseLength}자")
            val lineCount = entry.responseText.lines().size
            sb.appendLine("응답 줄 수 : ${lineCount}줄")
            sb.appendLine()
            if (entry.errorMessage.isNotEmpty()) {
                sb.appendLine("── 에러 메시지 ────────────────────────────")
                sb.appendLine(entry.errorMessage)
            } else {
                sb.appendLine("── 응답 내용 ──────────────────────────────")
                sb.append(entry.responseText)
            }
            return sb.toString()
        }

        entryList.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val idx = entryList.selectedIndex
            if (idx < 0 || idx >= currentEntries.size) return@addListSelectionListener
            val entry = currentEntries[idx]
            requestArea.text = formatRequestPanel(entry)
            requestArea.caretPosition = 0
            responseArea.text = formatResponsePanel(entry)
            responseArea.caretPosition = 0
        }

        fun copyToClipboard(text: String) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }

        val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(JButton("Refresh").apply {
                addActionListener { refreshList() }
            })
            add(JButton("Clear").apply {
                addActionListener {
                    DebugManager.getInstance().clear()
                    refreshList()
                }
            })
        }

        val requestPanel = JPanel(BorderLayout()).apply {
            border = TitledBorder("Request")
            val copyBtn = JButton("Copy").apply {
                addActionListener { copyToClipboard(requestArea.text) }
            }
            add(JScrollPane(requestArea), BorderLayout.CENTER)
            add(copyBtn, BorderLayout.SOUTH)
        }

        val responsePanel = JPanel(BorderLayout()).apply {
            border = TitledBorder("Response")
            val copyBtn = JButton("Copy").apply {
                addActionListener { copyToClipboard(responseArea.text) }
            }
            add(JScrollPane(responseArea), BorderLayout.CENTER)
            add(copyBtn, BorderLayout.SOUTH)
        }

        val rightSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, requestPanel, responsePanel).apply {
            resizeWeight = 0.5
            dividerSize = 5
        }

        val leftPanel = JPanel(BorderLayout()).apply {
            border = TitledBorder("Requests (최근 50개)")
            preferredSize = Dimension(320, 0)
            add(JScrollPane(entryList), BorderLayout.CENTER)
        }

        val mainSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightSplit).apply {
            resizeWeight = 0.3
            dividerSize = 5
        }

        return JPanel(BorderLayout()).apply {
            add(topBar, BorderLayout.NORTH)
            add(mainSplit, BorderLayout.CENTER)
        }
    }
}
