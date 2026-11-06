package com.nugggets.gorodwatcher

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val BASE = "https://gorod.mos.ru"
const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/124.0 Mobile Safari/537.36"
val STATUSES = listOf(
    "affirmed" to "Проблема в работе",
    "resolved" to "Проблема решена",
    "actions_taken" to "Приняты меры по решению проблемы",
    "not_found" to "Проблема не обнаружена"
)
val KEYWORDS = listOf("брошен", "разукомплект")
val MSK: TimeZone = TimeZone.getTimeZone("GMT+03:00")

data class Node(val node: Long, val theme: Long, val title: String)

class Row(
    val id: Long, val themeId: Long?, val themeName: String?,
    val created: Long?, val published: Long?,
    val statusCode: String?, val statusName: String?,
    val text: String, val place: String?, val address: String?,
    val lat: Double?, val lon: Double?, val userName: String?,
    val photos: List<String>
)

class Cancelled : RuntimeException("остановлено")

fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k)
fun JSONObject.lng(k: String): Long? = if (!has(k) || isNull(k)) null else optLong(k)

fun fmtTs(ts: Long?): String {
    if (ts == null || ts == 0L) return "?"
    val f = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)
    f.timeZone = MSK
    return f.format(Date(ts * 1000))
}

fun fmtDay(ts: Long): String {
    val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    f.timeZone = MSK
    return f.format(Date(ts * 1000))
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("gw", Context.MODE_PRIVATE)

    var intervalMin: Int
        get() = sp.getInt("interval", 10)
        set(v) { sp.edit().putInt("interval", v).apply() }
    var days: Int
        get() = sp.getInt("days", 3)
        set(v) { sp.edit().putInt("days", v).apply() }
    var chunkDays: Int
        get() = sp.getInt("chunk", 7)
        set(v) { sp.edit().putInt("chunk", v).apply() }
    var since: String
        get() = sp.getString("since", "2025-01-01") ?: "2025-01-01"
        set(v) { sp.edit().putString("since", v).apply() }
    var tgToken: String
        get() = sp.getString("tg_token", "") ?: ""
        set(v) { sp.edit().putString("tg_token", v).apply() }
    var tgChat: String
        get() = sp.getString("tg_chat", "") ?: ""
        set(v) { sp.edit().putString("tg_chat", v).apply() }
    var notifyFirst: Boolean
        get() = sp.getBoolean("notify_first", false)
        set(v) { sp.edit().putBoolean("notify_first", v).apply() }
    var androidNotify: Boolean
        get() = sp.getBoolean("android_notify", true)
        set(v) { sp.edit().putBoolean("android_notify", v).apply() }
    var watching: Boolean
        get() = sp.getBoolean("watching", false)
        set(v) { sp.edit().putBoolean("watching", v).apply() }
}

/** Общий журнал и флаги состояния (в пределах процесса). */
object Bus {
    private val lines = ArrayList<String>()
    @Volatile var onChange: Runnable? = null
    @Volatile var watching = false
    @Volatile var syncing = false

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lines) {
            lines.add("$t $msg")
            if (lines.size > 300) lines.removeAt(0)
        }
        onChange?.run()
    }

    fun tail(n: Int = 60): String = synchronized(lines) { lines.takeLast(n).joinToString("\n") }
}
