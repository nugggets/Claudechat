package com.nugggets.claudechat

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сбор диагностики: кольцевой журнал событий, HTTP-запросов, SSE-потока, вложений и ошибок.
 * Позволяет скопировать полный технический отчёт одним нажатием для отправки ИИ.
 */
object Diag {
    private const val MAX_EVENTS = 120
    private val events = ArrayList<String>()

    @Volatile var lastEndpoint = ""
    @Volatile var lastHttpCode = 0
    @Volatile var lastContentType = ""
    @Volatile var lastReqBytes = 0
    @Volatile var lastReqPreview = ""
    @Volatile var lastRespPreview = ""
    @Volatile var lastError = ""
    @Volatile var lastDurationMs = 0L
    @Volatile var lastSseEventsCount = 0

    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    @Synchronized
    fun log(msg: String) {
        val line = "[${ts()}] $msg"
        events.add(line)
        if (events.size > MAX_EVENTS) {
            events.removeAt(0)
        }
    }

    @Synchronized
    fun logErr(where: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().trim().take(1200)
        lastError = "$where: ${t.javaClass.simpleName}: ${t.message}\n$trace"
        log("ERROR in $where: ${t.javaClass.simpleName}: ${t.message}")
    }

    @Synchronized
    fun clear() {
        events.clear()
        lastEndpoint = ""
        lastHttpCode = 0
        lastContentType = ""
        lastReqBytes = 0
        lastReqPreview = ""
        lastRespPreview = ""
        lastError = ""
        lastDurationMs = 0L
        lastSseEventsCount = 0
        log("Журнал диагностики очищен")
    }

    private fun maskKey(k: String): String {
        if (k.isEmpty()) return "<ПУСТО>"
        if (k.length <= 10) return "*** (длина ${k.length})"
        return k.take(7) + "…" + k.takeLast(4) + " (длина ${k.length})"
    }

    @Synchronized
    fun buildReport(c: Context, chat: Chat?, pending: List<Att> = emptyList(), note: String = ""): String {
        val sb = StringBuilder()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sb.append("=== CLAUDE CHAT DIAGNOSTIC REPORT ===\n")
        sb.append("Время: ").append(fmt.format(Date())).append("\n")
        sb.append("Версия приложения: 1.7 (versionCode 8)\n")
        sb.append("Устройство: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" (Android ").append(Build.VERSION.RELEASE).append(", SDK ").append(Build.VERSION.SDK_INT).append(")\n\n")

        sb.append("--- НАСТРОЙКИ API ---\n")
        sb.append("Base URL: ").append(Store.baseUrl(c)).append("\n")
        sb.append("API Key: ").append(maskKey(Store.apiKey(c))).append("\n")
        sb.append("Способ авторизации: ").append(if (Store.bearer(c)) "Authorization: Bearer" else "x-api-key").append("\n")
        sb.append("Модель: ").append(Store.model(c)).append("\n")
        val serverModels = Store.modelIds(c)
        sb.append("Модели в списке (").append(serverModels.size).append("): ")
            .append(serverModels.take(15).joinToString(", "))
            .append(if (serverModels.size > 15) " …" else "").append("\n")
        sb.append("Max tokens: ").append(Store.maxTokens(c)).append("\n")
        sb.append("Stream (поток): ").append(Store.stream(c)).append("\n")
        sb.append("Web search: ").append(Store.web(c)).append("\n")
        sb.append("Температура: ").append(Store.temp(c).ifEmpty { "<по умолчанию>" }).append("\n")
        sb.append("Системный промпт (глоб.): ").append(if (Store.system(c).isBlank()) "<нет>" else "${Store.system(c).length} симв.").append("\n")
        sb.append("Счётчик токенов: ").append(Store.tokIn(c)).append(" вх / ").append(Store.tokOut(c)).append(" исх\n\n")

        sb.append("--- ТЕКУЩИЙ ЧАТ ---\n")
        if (chat == null) {
            sb.append("Чат не открыт\n")
        } else {
            sb.append("ID чата: ").append(chat.id).append(", название: «").append(chat.title).append("»\n")
            sb.append("Сообщений: ").append(chat.msgs.size).append(", своя инструкция: ")
                .append(if (chat.sys.isBlank()) "<нет>" else "${chat.sys.length} симв.").append("\n")
            val dir = Store.attDir(c)
            for ((idx, m) in chat.msgs.withIndex()) {
                val attsDesc = if (m.atts.isEmpty()) "" else {
                    " | вложения: " + m.atts.joinToString { a ->
                        val f = File(dir, a.file)
                        "${a.name} (${a.mime}, file=${a.file}, exists=${f.exists()}, size=${if (f.exists()) f.length() else 0} B)"
                    }
                }
                sb.append("  #").append(idx + 1).append(" [").append(m.role).append("]: текст=")
                    .append(m.text.length).append(" симв. (")
                    .append(m.text.replace("\n", " ").take(60))
                    .append(if (m.text.length > 60) "…" else "").append(")")
                    .append(attsDesc).append("\n")
            }
        }
        if (pending.isNotEmpty()) {
            val dir = Store.attDir(c)
            sb.append("Ожидающие вложения (").append(pending.size).append("): ")
                .append(pending.joinToString { a ->
                    val f = File(dir, a.file)
                    "${a.name} (${if (f.exists()) f.length() else 0} B)"
                }).append("\n")
        }
        if (note.isNotEmpty()) {
            sb.append("Текущее уведомление/ошибка на экране (note): ").append(note).append("\n")
        }
        sb.append("\n")

        sb.append("--- ПОСЛЕДНИЙ HTTP-ЗАПРОС ---\n")
        sb.append("Endpoint: ").append(lastEndpoint.ifEmpty { "<не выполнялся>" }).append("\n")
        sb.append("HTTP код: ").append(lastHttpCode).append(" | Content-Type: ").append(lastContentType.ifEmpty { "<нет>" }).append("\n")
        sb.append("Размер запроса: ").append(lastReqBytes).append(" байт | Время: ").append(lastDurationMs).append(" мс | SSE-событий: ").append(lastSseEventsCount).append("\n")
        if (lastError.isNotEmpty()) {
            sb.append("Последняя ошибка: ").append(lastError).append("\n")
        }
        if (lastReqPreview.isNotEmpty()) {
            sb.append("Превью запроса (структура):\n").append(lastReqPreview).append("\n")
        }
        if (lastRespPreview.isNotEmpty()) {
            sb.append("Превью ответа/ошибки сервера:\n").append(lastRespPreview).append("\n")
        }
        sb.append("\n")

        sb.append("--- ЖУРНАЛ СОБЫТИЙ (${events.size}) ---\n")
        if (events.isEmpty()) {
            sb.append("<пусто>\n")
        } else {
            for (e in events) sb.append(e).append("\n")
        }
        sb.append("=== END OF REPORT ===\n")
        return sb.toString()
    }
}
