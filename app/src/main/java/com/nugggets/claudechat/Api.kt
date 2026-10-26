package com.nugggets.claudechat

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class ApiError(msg: String) : Exception(msg)

class Cfg(
    val url: String,
    val key: String,
    val model: String,
    val maxTokens: Int,
    val system: String,
    val bearer: Boolean,
    val stream: Boolean
)

class Res {
    var inTok = 0
    var outTok = 0
    var stop = ""
}

/** Один запрос к /v1/messages. Работает в фоновом потоке. */
class Req(private val cfg: Cfg) {
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
            u.endsWith("/v1") -> "$u/messages"
            else -> "$u/v1/messages"
        }
    }

    fun run(history: List<Msg>, onDelta: (String) -> Unit): Res {
        val body = JSONObject()
        body.put("model", cfg.model)
        body.put("max_tokens", cfg.maxTokens)
        if (cfg.system.isNotBlank()) body.put("system", cfg.system)
        val arr = JSONArray()
        for (m in history) {
            val j = JSONObject()
            j.put("role", m.role)
            j.put("content", m.text)
            arr.put(j)
        }
        body.put("messages", arr)
        if (cfg.stream) body.put("stream", true)

        val res = Res()
        val c = try {
            URL(endpoint()).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiError("Неверный адрес API: " + cfg.url)
        }
        conn = c
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = if (cfg.stream) 90000 else 300000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("anthropic-version", "2023-06-01")
            if (cfg.bearer) {
                c.setRequestProperty("Authorization", "Bearer " + cfg.key)
            } else {
                c.setRequestProperty("x-api-key", cfg.key)
            }
            c.setRequestProperty("accept", if (cfg.stream) "text/event-stream" else "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (code !in 200..299) {
                val raw = readAll(c.errorStream)
                throw ApiError(friendly(code, raw))
            }
            val ct = c.contentType ?: ""
            if (cfg.stream && ct.contains("event-stream")) {
                readSse(c, res, onDelta)
            } else {
                parseFull(readAll(c.inputStream), res, onDelta)
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

    private fun readSse(c: HttpURLConnection, res: Res, onDelta: (String) -> Unit) {
        val r = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
        try {
            while (true) {
                if (cancelled) break
                val line = r.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val o = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    continue
                }
                val type = o.optString("type")
                if (type == "message_start") {
                    val u = o.optJSONObject("message")?.optJSONObject("usage")
                    if (u != null) {
                        res.inTok = u.optInt("input_tokens", res.inTok)
                        res.outTok = u.optInt("output_tokens", res.outTok)
                    }
                } else if (type == "content_block_delta") {
                    val d = o.optJSONObject("delta")
                    if (d != null && d.optString("type") == "text_delta") {
                        onDelta(d.optString("text"))
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
        } finally {
            try {
                r.close()
            } catch (e: Exception) {
            }
        }
    }

    private fun parseFull(raw: String, res: Res, onDelta: (String) -> Unit) {
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw ApiError("Сервер вернул ответ не в формате Anthropic:\n" + raw.take(300))
        }
        if (o.optString("type") == "error") {
            throw ApiError(o.optJSONObject("error")?.optString("message") ?: raw.take(300))
        }
        val content = o.optJSONArray("content")
        if (content != null) {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.optJSONObject(i) ?: continue
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            if (sb.isNotEmpty()) onDelta(sb.toString())
        }
        val u = o.optJSONObject("usage")
        if (u != null) {
            res.inTok = u.optInt("input_tokens", 0)
            res.outTok = u.optInt("output_tokens", 0)
        }
        val s = o.optString("stop_reason", "")
        if (s != "null") res.stop = s
    }

    private fun netMsg(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Cleartext", true) -> "Адрес с http:// заблокирован Android. Укажите адрес с https://"
            e is UnknownHostException -> "Не удалось найти сервер. Проверьте интернет и адрес API"
            e is SocketTimeoutException -> "Тайм-аут: сервер не отвечает"
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
                401 -> "неверный или отозванный ключ"
                403 -> "доступ запрещён: ключ заблокирован или нет прав на эту модель"
                404 -> "не найдено: проверьте адрес API и название модели"
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
