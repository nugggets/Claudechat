package com.nugggets.claudechat

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class ApiError(msg: String, val code: Int = 0) : Exception(msg)

class Cfg(
    val url: String,
    val key: String,
    val model: String,
    val maxTokens: Int,
    val system: String,
    val bearer: Boolean,
    val stream: Boolean,
    val temp: Double? = null,
    val web: Boolean = false
)

class Res {
    var inTok = 0
    var outTok = 0
    var stop = ""
}

/** Один запрос к /v1/messages. Работает в фоновом потоке. */
class Req(private val cfg: Cfg, private val attDir: File? = null) {
    @Volatile
    var cancelled = false

    @Volatile
    private var conn: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        val c = conn
        if (c != null) {
            Thread {
                try {
                    c.disconnect()
                } catch (e: Exception) {
                }
            }.start()
        }
    }

    private fun endpoint(): String {
        val u = cfg.url.trim().trimEnd('/')
        return when {
            u.endsWith("/v1/messages") -> u
            u.endsWith("/chat/completions") -> u
            u.endsWith("/v1") -> "$u/messages"
            else -> "$u/v1/messages"
        }
    }

    private fun textBlock(t: String): JSONObject {
        val j = JSONObject()
        j.put("type", "text")
        j.put("text", t)
        return j
    }

    private fun b64Source(mime: String, f: File): JSONObject {
        val src = JSONObject()
        src.put("type", "base64")
        src.put("media_type", mime)
        src.put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        return src
    }

    /** Содержимое сообщения: массив блоков (файлы, затем текст). */
    private fun contentBlocks(m: Msg): JSONArray {
        val dir = attDir
        val arr = JSONArray()
        if (dir != null) {
            for (a in m.atts) {
                val f = File(dir, a.file)
                if (!f.exists()) {
                    arr.put(textBlock("[Файл «" + a.name + "» больше недоступен]"))
                    continue
                }
                if (a.isImage) {
                    val b = JSONObject()
                    b.put("type", "image")
                    b.put("source", b64Source(a.mime, f))
                    arr.put(b)
                } else if (a.isPdf) {
                    val b = JSONObject()
                    b.put("type", "document")
                    b.put("source", b64Source("application/pdf", f))
                    b.put("title", a.name)
                    arr.put(b)
                } else {
                    arr.put(textBlock("Файл «" + a.name + "»:\n\n" + f.readText(Charsets.UTF_8)))
                }
            }
        }
        if (m.text.isNotEmpty() || arr.length() == 0) {
            arr.put(textBlock(if (m.text.isNotEmpty()) m.text else "…"))
        }
        return arr
    }

    /**
     * Формирует тело запроса.
     * Склеивает подряд идущие сообщения одной роли (например, два user подряд после ошибки),
     * так как Anthropic API строго требует чередования user / assistant.
     */
    private fun buildBody(history: List<Msg>): ByteArray {
        val body = JSONObject()
        body.put("model", cfg.model)
        body.put("max_tokens", cfg.maxTokens)
        if (cfg.system.isNotBlank()) body.put("system", cfg.system)
        if (cfg.temp != null) body.put("temperature", cfg.temp)

        val merged = mutableListOf<Pair<String, JSONArray>>()
        for (m in history) {
            val blocks = contentBlocks(m)
            if (merged.isNotEmpty() && merged.last().first == m.role) {
                val prevArr = merged.last().second
                for (i in 0 until blocks.length()) {
                    prevArr.put(blocks.get(i))
                }
            } else {
                merged.add(m.role to blocks)
            }
        }
        // Первое сообщение обязательно должно быть от user
        while (merged.isNotEmpty() && merged.first().first != "user") {
            merged.removeAt(0)
        }

        val arr = JSONArray()
        for ((role, blocks) in merged) {
            val j = JSONObject()
            j.put("role", role)
            if (blocks.length() == 1 && blocks.optJSONObject(0)?.optString("type") == "text") {
                j.put("content", blocks.getJSONObject(0).optString("text"))
            } else {
                j.put("content", blocks)
            }
            arr.put(j)
        }
        body.put("messages", arr)
        if (cfg.stream) body.put("stream", true)
        if (cfg.web) {
            val t = JSONObject()
            t.put("type", "web_search_20250305")
            t.put("name", "web_search")
            t.put("max_uses", 5)
            body.put("tools", JSONArray().put(t))
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    /** Запрос с двумя повторами при перегрузке сервера или лимите (только до начала ответа). */
    fun run(
        history: List<Msg>,
        onStatus: (String) -> Unit = {},
        onDelta: (String) -> Unit
    ): Res {
        onStatus("Подготовка запроса и вложений…")
        val bytes = buildBody(history)
        val kb = bytes.size / 1024
        var attempt = 0
        while (true) {
            try {
                return once(bytes, kb, onStatus, onDelta)
            } catch (e: ApiError) {
                val c = e.code
                val retry = c == 429 || c == 500 || c == 502 || c == 503 || c == 504 || c == 529
                if (!retry || attempt >= 2 || cancelled) throw e
                attempt++
                onStatus("Сервер перегружен (код $c), повтор $attempt/2…")
                var waited = 0
                while (waited < 2500 * attempt && !cancelled) {
                    Thread.sleep(100)
                    waited += 100
                }
                if (cancelled) throw e
            }
        }
    }

    private fun once(
        bytes: ByteArray,
        kb: Int,
        onStatus: (String) -> Unit,
        onDelta: (String) -> Unit
    ): Res {
        val res = Res()
        val urlStr = endpoint()
        val c = try {
            URL(urlStr).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiError("Неверный адрес API: " + cfg.url)
        }
        conn = c
        try {
            onStatus("Подключение к серверу ($kb КБ)…")
            c.requestMethod = "POST"
            c.connectTimeout = 30000
            c.readTimeout = if (cfg.stream) 180000 else 300000
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes.size)
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("anthropic-version", "2023-06-01")
            if (cfg.bearer) {
                c.setRequestProperty("Authorization", "Bearer " + cfg.key)
            } else {
                c.setRequestProperty("x-api-key", cfg.key)
            }
            c.setRequestProperty("accept", if (cfg.stream) "text/event-stream" else "application/json")
            c.outputStream.use { it.write(bytes) }

            onStatus("Ожидание ответа от ${cfg.model}…")
            val code = c.responseCode
            if (code !in 200..299) {
                val raw = readAll(c.errorStream)
                throw ApiError(friendly(code, raw), code)
            }
            val ct = (c.contentType ?: "").lowercase()
            if (cfg.stream && ct.contains("event-stream")) {
                readSse(c, res, onStatus, onDelta)
            } else {
                onStatus("Чтение полного ответа…")
                parseFull(readAll(c.inputStream), res, onStatus, onDelta)
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: Exception) {
            if (!cancelled) throw ApiError(netMsg(e))
        } finally {
            try {
                c.disconnect()
            } catch (e: Exception) {
            }
            conn = null
        }
        return res
    }

    private fun readAll(s: InputStream?): String {
        if (s == null) return ""
        return s.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun readSse(
        c: HttpURLConnection,
        res: Res,
        onStatus: (String) -> Unit,
        onDelta: (String) -> Unit
    ) {
        val r = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
        var emitted = 0
        var inThinking = false
        var nonSseLines = StringBuilder()
        try {
            while (true) {
                if (cancelled) break
                val line = r.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                if (!trimmed.startsWith("data:")) {
                    if (emitted == 0 && nonSseLines.length < 32000) {
                        nonSseLines.append(line).append("\n")
                    }
                    continue
                }
                val data = trimmed.substring(5).trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val o = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    continue
                }

                // Поддержка OpenAI-совместимых прокси (choices[0].delta)
                val choices = o.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val ch = choices.optJSONObject(0)
                    val delta = ch?.optJSONObject("delta") ?: ch?.optJSONObject("message")
                    if (delta != null) {
                        val reasoning = delta.optString("reasoning_content", "")
                            .ifEmpty { delta.optString("thinking", "") }
                        if (reasoning.isNotEmpty()) {
                            onStatus("🧠 Модель размышляет…")
                            if (!inThinking) {
                                inThinking = true
                                onDelta("💭 *Размышления:*\n> ")
                            }
                            onDelta(reasoning.replace("\n", "\n> "))
                            emitted += reasoning.length
                        }
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) {
                            if (inThinking) {
                                inThinking = false
                                onDelta("\n\n---\n\n")
                            }
                            onStatus("✍ Генерация ответа…")
                            emitted += content.length
                            onDelta(content)
                        }
                    }
                    val finish = ch?.optString("finish_reason", "") ?: ""
                    if (finish.isNotEmpty() && finish != "null") res.stop = finish
                    val u = o.optJSONObject("usage")
                    if (u != null) {
                        res.inTok = u.optInt("prompt_tokens", u.optInt("input_tokens", res.inTok))
                        res.outTok = u.optInt("completion_tokens", u.optInt("output_tokens", res.outTok))
                    }
                    continue
                }

                val type = o.optString("type")
                if (type == "message_start") {
                    onStatus("Поток открыт, ИИ анализирует контекст…")
                    val u = o.optJSONObject("message")?.optJSONObject("usage")
                    if (u != null) {
                        res.inTok = u.optInt("input_tokens", res.inTok)
                        res.outTok = u.optInt("output_tokens", res.outTok)
                    }
                } else if (type == "content_block_start") {
                    val b = o.optJSONObject("content_block")
                    val bt = b?.optString("type") ?: ""
                    if (bt == "thinking") {
                        onStatus("🧠 ИИ размышляет…")
                        inThinking = true
                        if (emitted > 0) onDelta("\n\n")
                        onDelta("💭 *Размышления:*\n> ")
                        emitted += 20
                    } else if (bt == "server_tool_use" || bt == "tool_use") {
                        val toolName = b?.optString("name", "инструмент") ?: "инструмент"
                        onStatus("🔎 Вызов инструмента: $toolName…")
                    } else if (bt == "web_search_tool_result") {
                        onStatus("🌐 Обработка результатов веб-поиска…")
                    } else if (bt == "text") {
                        if (inThinking) {
                            inThinking = false
                            onDelta("\n\n---\n\n")
                        } else if (emitted > 0) {
                            onDelta("\n\n")
                            emitted += 2
                        }
                        onStatus("✍ ИИ печатает ответ…")
                    }
                } else if (type == "content_block_delta") {
                    val d = o.optJSONObject("delta")
                    val dt = d?.optString("type") ?: ""
                    if (dt == "thinking_delta") {
                        onStatus("🧠 ИИ размышляет…")
                        val t = d?.optString("thinking") ?: ""
                        if (t.isNotEmpty()) {
                            if (!inThinking) {
                                inThinking = true
                                onDelta("💭 *Размышления:*\n> ")
                            }
                            emitted += t.length
                            onDelta(t.replace("\n", "\n> "))
                        }
                    } else if (dt == "text_delta") {
                        if (inThinking) {
                            inThinking = false
                            onDelta("\n\n---\n\n")
                        }
                        onStatus("✍ ИИ печатает ответ…")
                        val t = d?.optString("text") ?: ""
                        emitted += t.length
                        onDelta(t)
                    } else if (dt == "input_json_delta") {
                        onStatus("⚙ Формирование параметров инструмента…")
                    }
                } else if (type == "content_block_stop") {
                    if (inThinking) {
                        inThinking = false
                        onDelta("\n\n---\n\n")
                    }
                } else if (type == "message_delta") {
                    val d = o.optJSONObject("delta")
                    if (d != null) {
                        val s = d.optString("stop_reason", "")
                        if (s != "null") res.stop = s
                    }
                    val u = o.optJSONObject("usage")
                    if (u != null) {
                        res.outTok = u.optInt("output_tokens", res.outTok)
                        if (u.has("input_tokens")) res.inTok = u.optInt("input_tokens", res.inTok)
                    }
                } else if (type == "error") {
                    val m = o.optJSONObject("error")?.optString("message") ?: data
                    throw ApiError(m)
                }
            }
            // Если прокси прислал обычный JSON с заголовком text/event-stream
            if (emitted == 0 && nonSseLines.isNotEmpty()) {
                parseFull(nonSseLines.toString(), res, onStatus, onDelta)
            }
        } finally {
            try {
                r.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun parseFull(
        raw: String,
        res: Res,
        onStatus: (String) -> Unit,
        onDelta: (String) -> Unit
    ) {
        val trimmed = raw.trim()
        // Если прокси наоборот вернул SSE-поток с заголовком application/json
        if (trimmed.startsWith("event:") || trimmed.startsWith("data:")) {
            val sb = StringBuilder()
            for (line in trimmed.split("\n")) {
                val l = line.trim()
                if (!l.startsWith("data:")) continue
                val data = l.substring(5).trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val o = try { JSONObject(data) } catch (e: Exception) { continue }
                val d = o.optJSONObject("delta")
                if (d != null && d.optString("type") == "text_delta") {
                    sb.append(d.optString("text"))
                }
                val ch = o.optJSONArray("choices")?.optJSONObject(0)
                val cd = ch?.optJSONObject("delta") ?: ch?.optJSONObject("message")
                if (cd != null) sb.append(cd.optString("content", ""))
            }
            if (sb.isNotEmpty()) {
                onStatus("Готово")
                onDelta(sb.toString())
                return
            }
        }

        val o = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            throw ApiError("Сервер вернул ответ не в формате JSON:\n" + trimmed.take(300))
        }
        if (o.optString("type") == "error" || o.has("error")) {
            val errObj = o.optJSONObject("error")
            val errMsg = errObj?.optString("message") ?: o.optString("error", trimmed.take(300))
            throw ApiError(errMsg)
        }

        val sb = StringBuilder()
        val content = o.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val b = content.optJSONObject(i) ?: continue
                val bt = b.optString("type")
                if (bt == "thinking") {
                    val th = b.optString("thinking", "")
                    if (th.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append("\n\n")
                        sb.append("💭 *Размышления:*\n> ").append(th.replace("\n", "\n> ")).append("\n\n---\n\n")
                    }
                } else if (bt == "text") {
                    if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n\n")
                    sb.append(b.optString("text"))
                }
            }
        } else if (o.optString("content").isNotEmpty()) {
            sb.append(o.optString("content"))
        } else {
            // OpenAI-совместимый формат
            val choices = o.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val msg = choices.optJSONObject(0)?.optJSONObject("message")
                if (msg != null) {
                    val reasoning = msg.optString("reasoning_content", "")
                    if (reasoning.isNotEmpty()) {
                        sb.append("💭 *Размышления:*\n> ").append(reasoning.replace("\n", "\n> ")).append("\n\n---\n\n")
                    }
                    sb.append(msg.optString("content", ""))
                }
            }
        }

        if (sb.isNotEmpty()) {
            onStatus("Готово")
            onDelta(sb.toString())
        }
        val u = o.optJSONObject("usage")
        if (u != null) {
            res.inTok = u.optInt("input_tokens", u.optInt("prompt_tokens", 0))
            res.outTok = u.optInt("output_tokens", u.optInt("completion_tokens", 0))
        }
        val s = o.optString("stop_reason", "")
        if (s != "null" && s.isNotEmpty()) res.stop = s
    }

    private fun netMsg(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Cleartext", true) -> "Адрес с http:// заблокирован Android. Укажите адрес с https://"
            e is UnknownHostException -> "Не удалось найти сервер. Проверьте интернет и адрес API"
            e is SocketTimeoutException -> "Тайм-аут: сервер слишком долго думает над большим запросом"
            else -> "Ошибка сети: $m"
        }
    }

    companion object {
        fun friendly(code: Int, raw: String): String {
            var msg = ""
            try {
                val o = JSONObject(raw)
                val e = o.optJSONObject("error")
                msg = e?.optString("message") ?: o.optString("message", "")
            } catch (x: Exception) {
            }
            if (msg.isBlank()) {
                msg = if (raw.contains("<html", true)) {
                    "сервер вернул HTML-страницу, проверьте адрес API"
                } else {
                    raw.take(300)
                }
            }
            val hint = when (code) {
                400 -> "некорректный запрос (проверьте название модели или размер контекста)"
                401 -> "неверный или отозванный ключ"
                403 -> "доступ запрещён: ключ заблокирован или нет прав на эту модель"
                404 -> "не найдено: проверьте адрес API и название модели"
                413 -> "слишком большой запрос (архив или файлы превышают лимит сервера)"
                429 -> "лимит запросов или токены закончились"
                503, 529 -> "сервер перегружен, повторите позже"
                else -> ""
            }
            val sb = StringBuilder("Ошибка $code")
            if (hint.isNotEmpty()) sb.append(": ").append(hint)
            if (msg.isNotBlank()) sb.append("\n").append(msg)
            return sb.toString()
        }
    }
}

/** Запрашивает у сервера список доступных моделей (GET /v1/models). */
fun fetchModels(cfg: Cfg): List<String> {
    var u = cfg.url.trim().trimEnd('/')
    if (u.endsWith("/messages")) u = u.removeSuffix("/messages")
    if (u.endsWith("/chat/completions")) u = u.removeSuffix("/chat/completions")
    if (u.endsWith("/v1")) u = u.removeSuffix("/v1")
    val c = try {
        URL("$u/v1/models?limit=1000").openConnection() as HttpURLConnection
    } catch (e: Exception) {
        throw ApiError("Неверный адрес API: " + cfg.url)
    }
    try {
        c.requestMethod = "GET"
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("anthropic-version", "2023-06-01")
        if (cfg.bearer) {
            c.setRequestProperty("Authorization", "Bearer " + cfg.key)
        } else {
            c.setRequestProperty("x-api-key", cfg.key)
        }
        val code = c.responseCode
        if (code !in 200..299) {
            val raw = c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            throw ApiError(Req.friendly(code, raw), code)
        }
        val raw = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(raw).optJSONArray("data")
            ?: throw ApiError("Сервер не вернул список моделей")
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id", "") ?: ""
            if (id.isNotEmpty()) out.add(id)
        }
        if (out.isEmpty()) throw ApiError("Список моделей пуст")
        return out
    } catch (e: ApiError) {
        throw e
    } catch (e: Exception) {
        throw ApiError("Ошибка: " + (e.message ?: e.javaClass.simpleName))
    } finally {
        try {
            c.disconnect()
        } catch (e: Exception) {
        }
    }
}
