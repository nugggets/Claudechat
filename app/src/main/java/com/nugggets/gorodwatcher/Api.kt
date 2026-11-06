package com.nugggets.gorodwatcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class ApiError(msg: String) : RuntimeException(msg)

class Api(private val delayMs: Long = 1000L, private val cancel: () -> Boolean = { false }) {
    private var last = 0L

    init {
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
        try {  // «прогрев»: получить cookies, как делает браузер
            val c = URL("$BASE/map-messages").openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", UA)
            c.connectTimeout = 30000
            c.readTimeout = 30000
            c.inputStream.use { it.read() }
            c.disconnect()
        } catch (e: Exception) {
            Bus.log("прогрев не удался (не критично): ${e.message}")
        }
    }

    private fun sleepCancelable(ms: Long) {
        var left = ms
        while (left > 0) {
            if (cancel()) throw Cancelled()
            val step = minOf(200L, left)
            Thread.sleep(step)
            left -= step
        }
    }

    fun post(path: String, body: JSONObject, retries: Int = 5): Any? {
        var attempt = 0
        while (true) {
            if (cancel()) throw Cancelled()
            val wait = delayMs - (System.currentTimeMillis() - last)
            if (wait > 0) sleepCancelable(wait)
            last = System.currentTimeMillis()
            try {
                val c = URL(BASE + path).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 30000
                c.readTimeout = 60000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json, text/plain, */*")
                c.setRequestProperty("Origin", BASE)
                c.setRequestProperty("Referer", "$BASE/map-messages")
                c.setRequestProperty("User-Agent", UA)
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                c.disconnect()
                if (code in 200..299) {
                    val j = JSONObject(text)
                    if (j.optString("status") != "success") throw ApiError("$path: ${text.take(300)}")
                    return j.opt("data")
                }
                if (code in listOf(429, 500, 502, 503, 504) && attempt < retries - 1) {
                    val pause = 5000L shl attempt
                    Bus.log("HTTP $code на $path, пауза ${pause / 1000}с")
                    sleepCancelable(pause)
                    attempt++
                    continue
                }
                throw ApiError("HTTP $code $path: ${text.take(300)}")
            } catch (e: IOException) {
                if (attempt < retries - 1) {
                    sleepCancelable(5000L shl attempt)
                    attempt++
                    continue
                }
                throw ApiError("сеть $path: ${e.message}")
            }
        }
    }
}

object Engine {
    val FALLBACK = listOf(Node(11122, 986, "Брошенное транспортное средство во дворе"))

    /** Ищем в дереве тем узлы с «брошен…». */
    fun discover(api: Api): List<Node> {
        val tree: Any? = try {
            api.post("/data/theme/tree", JSONObject().put("map", true))
        } catch (e: Cancelled) {
            throw e
        } catch (e: Exception) {
            Bus.log("дерево тем недоступно, беру известную тему 986: ${e.message}")
            return FALLBACK
        }
        val found = ArrayList<Node>()
        val seen = HashSet<Long>()
        fun walk(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                val title = n.optString("title", "")
                val th = n.lng("theme_id")
                if (th != null && th != 0L && KEYWORDS.any { title.lowercase().contains(it) } && seen.add(th)) {
                    found.add(Node(n.optLong("id"), th, title))
                }
                walk(n.optJSONArray("children"))
            }
        }
        val arr: JSONArray? = when (tree) {
            is JSONArray -> tree
            is JSONObject -> tree.optJSONArray("items") ?: tree.optJSONArray("tree") ?: tree.optJSONArray("children")
            else -> null
        }
        walk(arr)
        return if (found.isEmpty()) FALLBACK else found
    }

    fun fetch(
        api: Api, n: Node, from: Long, to: Long,
        known: Set<Long>? = null, stopOnKnown: Boolean = false
    ): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val seen = HashSet<Long>()
        var total = 0
        var page = 1
        while (page <= 2000) {
            val initial = JSONArray()
            for (s in STATUSES) initial.put(JSONObject().put("value", s.first).put("label", s.second))
            val body = JSONObject()
                .put("page", page)
                .put("status", JSONArray(STATUSES.map { it.first }))
                .put("initialStatus", initial)
                .put("theme", JSONArray().put(n.theme))
                .put("date", JSONObject().put("from", from).put("to", to))
                .put("region", 0).put("district", 0)
                .put("only_my", false)
                .put("ids", JSONArray())
                .put("tree_item_id", n.node)
            val d = api.post("/data/messages/map", body) as? JSONObject ?: return out
            val arr = d.optJSONArray("items") ?: return out
            if (arr.length() == 0) return out
            val items = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
            val fresh = items.filter { seen.add(it.optLong("id", -1)) }
            if (fresh.isEmpty()) return out  // сервер вернул ту же страницу — защита от цикла
            out.addAll(fresh)
            total += items.size
            val count = if (!d.has("count") || d.isNull("count")) null else d.optInt("count")
            if (count != null && total >= count) return out
            if (stopOnKnown && known != null && items.all { known.contains(it.optLong("id", -1)) }) return out
            page++
        }
        return out
    }

    fun sync(api: Api, db: Db, since: String, chunkDays: Int) {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = MSK
        val start = (f.parse(since) ?: throw ApiError("неверная дата: $since")).time / 1000
        val end = System.currentTimeMillis() / 1000 + 86400
        val nodes = discover(api)
        Bus.log("темы: " + nodes.joinToString { "${it.theme} ${it.title.take(30)}" })
        var totalNew = 0
        for (n in nodes) {
            var cur = start
            while (cur < end) {
                val nxt = minOf(cur + chunkDays.coerceAtLeast(1) * 86400L, end)
                val got = fetch(api, n, cur, nxt - 1)
                val new = db.save(got)
                totalNew += new.size
                Bus.log("${n.title.take(30)} | ${fmtDay(cur)}: получено ${got.size}, новых ${new.size}")
                cur = nxt
            }
        }
        Bus.log("готово, новых записей: $totalNew, всего в БД: ${db.count()}")
    }

    fun watchCycle(
        ctx: android.content.Context, api: Api, db: Db, nodes: List<Node>,
        days: Int, notifyFirst: Boolean
    ) {
        val now = System.currentTimeMillis() / 1000
        val f = now - days.coerceAtLeast(1) * 86400L
        val t = now + 86400
        val known = db.knownIds()
        val empty = known.isEmpty()
        val allNew = ArrayList<Row>()
        for (n in nodes) allNew.addAll(db.save(fetch(api, n, f, t, known, true)))
        if (empty && !notifyFirst) {
            Bus.log("первичная загрузка: ${allNew.size} записей сохранено без уведомлений")
            return
        }
        val prefs = Prefs(ctx)
        for (r in allNew.sortedBy { it.published ?: it.created ?: 0L }) Notifier.notify(ctx, r, prefs)
        Bus.log("цикл: новых ${allNew.size}")
    }
}
