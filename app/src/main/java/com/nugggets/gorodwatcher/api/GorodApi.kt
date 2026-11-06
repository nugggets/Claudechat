package com.nugggets.gorodwatcher.api

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

class GorodApi(private val delay: Long = 1000) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var lastCall = 0L

    suspend fun discoverThemes(): List<Theme> {
        val body = JSONObject(mapOf("map" to true)).toString()
        val response = post("/data/theme/tree", body)
        val themes = mutableListOf<Theme>()
        parseThemes(response.optJSONArray("children"), themes)
        return if (themes.isEmpty()) listOf(Theme(11122, 986, "Брошенное транспортное средство")) else themes
    }

    private fun parseThemes(arr: JSONArray?, themes: MutableList<Theme>) {
        arr?.let {
            for (i in 0 until it.length()) {
                val obj = it.optJSONObject(i) ?: continue
                val id = obj.optInt("id")
                val themeId = obj.optInt("theme_id")
                val title = obj.optString("title", "")
                if (themeId > 0) themes.add(Theme(id, themeId, title))
                parseThemes(obj.optJSONArray("children"), themes)
            }
        }
    }

    suspend fun fetchMessages(node: Int, theme: Int, fromTs: Long, toTs: Long, page: Int = 1): FetchResult {
        val body = JSONObject().apply {
            put("page", page)
            put("status", JSONArray(listOf("affirmed", "resolved", "actions_taken", "not_found")))
            put("theme", JSONArray(listOf(theme)))
            put("date", JSONObject().apply {
                put("from", fromTs)
                put("to", toTs)
            })
            put("region", 0)
            put("district", 0)
            put("only_my", false)
            put("ids", JSONArray())
            put("tree_item_id", node)
        }.toString()
        
        val response = post("/data/messages/map", body)
        val items = response.optJSONArray("items") ?: JSONArray()
        return FetchResult(items, response.optInt("count"))
    }

    private suspend fun post(path: String, body: String): JSONObject {
        val now = System.currentTimeMillis()
        val wait = delay - (now - lastCall)
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()

        val req = Request.Builder()
            .url("https://gorod.mos.ru" + path)
            .post(body.toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", "GorodWatcher/1.0")
            .build()

        return client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
            JSONObject(res.body!!.string()).also {
                if (it.optString("status") != "success") throw Exception(it.toString())
            }
        }.optJSONObject("data") ?: JSONObject()
    }

    data class Theme(val id: Int, val themeId: Int, val title: String)
    data class FetchResult(val items: JSONArray, val count: Int)
}
