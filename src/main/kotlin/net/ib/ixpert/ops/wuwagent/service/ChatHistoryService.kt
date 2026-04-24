package net.ib.ixpert.ops.wuwagent.service

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

object ChatHistoryService {
    private val logger = Logger.getInstance(ChatHistoryService::class.java)

    private val chatsDir: File
        get() = File(System.getProperty("user.home"), ".ixpert/chats").also { it.mkdirs() }

    fun saveChat(id: String, title: String, messagesJson: String) {
        try {
            val chatsDir = File(System.getProperty("user.home") + "/.ixpert/chats/")
            chatsDir.mkdirs()
            val file = File(chatsDir, "$id.json")
            val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(Date())
            val json = """{"id":"${id.esc()}","title":"${title.esc()}","date":"$date","messages":$messagesJson}"""
            file.writeText(json, Charsets.UTF_8)
            println("채팅 저장 성공: ${file.absolutePath}")
        } catch (e: Exception) {
            println("채팅 저장 실패: ${e.message}")
            e.printStackTrace()
        }
    }

    fun loadLastChat(): String? {
        return try {
            val file = File(System.getProperty("user.home") + "/.ixpert/chats/")
                .listFiles { f -> f.extension == "json" }
                ?.maxByOrNull { it.lastModified() }
                ?: return null
            println("loadLastChat: ${file.absolutePath}")
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            println("loadLastChat 실패: ${e.message}")
            null
        }
    }

    fun loadChat(id: String): String? {
        return try {
            val file = File(System.getProperty("user.home") + "/.ixpert/chats/", "$id.json")
            println("loadChat 시도: ${file.absolutePath} (exists=${file.exists()})")
            if (!file.exists()) return null
            val content = file.readText(Charsets.UTF_8)
            println("loadChat 읽기 성공: ${content.length}bytes")
            content
        } catch (e: Exception) {
            println("loadChat 실패: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    fun listChats(): String {
        return try {
            val files = chatsDir.listFiles { f -> f.extension == "json" }
                ?.sortedByDescending { it.lastModified() }
                ?.take(10)
                ?: emptyList()
            val items = files.mapNotNull { file ->
                try {
                    val text = file.readText(Charsets.UTF_8)
                    val id    = Regex(""""id"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: return@mapNotNull null
                    val title = Regex(""""title"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: ""
                    val date  = Regex(""""date"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: ""
                    """{"id":"${id.esc()}","title":"${title.esc()}","date":"$date"}"""
                } catch (e: Exception) { null }
            }
            "[${items.joinToString(",")}]"
        } catch (e: Exception) {
            logger.warn("ChatHistoryService: 목록 조회 실패", e)
            "[]"
        }
    }

    fun deleteChat(id: String) {
        try {
            File(chatsDir, "$id.json").takeIf { it.exists() }?.delete()
        } catch (e: Exception) {
            logger.warn("ChatHistoryService: 삭제 실패 id=$id", e)
        }
    }

    private fun String.esc(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
