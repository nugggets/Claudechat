package com.nugggets.gorodwatcher

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream

class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "gorod.db", null, 1) {

    companion object {
        private val DROP_KEYS = listOf("attrsSource", "timeline", "noticeForLK", "attachments")
        private const val COLS = "id,theme_id,theme_name,created,published,status_code,status_name," +
            "text,place,address,lat,lon,user_name,photos"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS messages(" +
                "id INTEGER PRIMARY KEY, theme_id INT, theme_name TEXT, created INT, published INT, " +
                "status_code TEXT, status_name TEXT, text TEXT, place TEXT, address TEXT, " +
                "lat REAL, lon REAL, user_name TEXT, photos TEXT, first_seen INT, raw TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_pub ON messages(published)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun rowOf(item: JSONObject): Row? {
        val id = item.optLong("id", -1)
        if (id < 0) return null
        val co = item.optJSONArray("coordinates")
        val lon = if (co != null && co.length() >= 2 && !co.isNull(0)) co.optDouble(0) else null
        val lat = if (co != null && co.length() >= 2 && !co.isNull(1)) co.optDouble(1) else null
        val photos = ArrayList<String>()
        val att = item.optJSONArray("attachments")
        if (att != null) {
            for (i in 0 until att.length()) {
                val a = att.optJSONObject(i) ?: continue
                val p = a.str("thumb_lg_w") ?: a.str("path_jpg_w")
                if (!p.isNullOrEmpty()) photos.add(p)
            }
        }
        return Row(
            id, item.lng("themeId"), item.str("themeName"),
            item.lng("createdDate"), item.lng("publishDate"),
            item.str("statusCode"), item.str("statusName"),
            item.str("text") ?: "", item.str("name"), item.str("address"),
            lat, lon, item.str("userName"), photos
        )
    }

    private fun rawOf(item: JSONObject): String {
        val c = JSONObject(item.toString())
        for (k in DROP_KEYS) c.remove(k)
        return c.toString()
    }

    /** Возвращает список НОВЫХ записей. Для известных обновляет статус. */
    fun save(items: List<JSONObject>): List<Row> {
        synchronized(this) {
            val db = writableDatabase
            val fresh = ArrayList<Row>()
            db.beginTransaction()
            try {
                for (item in items) {
                    val r = rowOf(item) ?: continue
                    val oldStatus: String?
                    var exists = false
                    var tmp: String? = null
                    db.rawQuery("SELECT status_code FROM messages WHERE id=?", arrayOf(r.id.toString())).use { c ->
                        if (c.moveToFirst()) { exists = true; tmp = c.getString(0) }
                    }
                    oldStatus = tmp
                    if (!exists) {
                        val cv = ContentValues()
                        cv.put("id", r.id); cv.put("theme_id", r.themeId); cv.put("theme_name", r.themeName)
                        cv.put("created", r.created); cv.put("published", r.published)
                        cv.put("status_code", r.statusCode); cv.put("status_name", r.statusName)
                        cv.put("text", r.text); cv.put("place", r.place); cv.put("address", r.address)
                        cv.put("lat", r.lat); cv.put("lon", r.lon); cv.put("user_name", r.userName)
                        cv.put("photos", JSONArray(r.photos).toString())
                        cv.put("first_seen", System.currentTimeMillis() / 1000)
                        cv.put("raw", rawOf(item))
                        db.insert("messages", null, cv)
                        fresh.add(r)
                    } else if (oldStatus != r.statusCode) {
                        val cv = ContentValues()
                        cv.put("status_code", r.statusCode); cv.put("status_name", r.statusName)
                        cv.put("raw", rawOf(item))
                        db.update("messages", cv, "id=?", arrayOf(r.id.toString()))
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return fresh
        }
    }

    fun knownIds(): HashSet<Long> {
        val s = HashSet<Long>()
        readableDatabase.rawQuery("SELECT id FROM messages", null).use { c ->
            while (c.moveToNext()) s.add(c.getLong(0))
        }
        return s
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM messages", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun stats(): String {
        val sb = StringBuilder()
        readableDatabase.rawQuery("SELECT COUNT(*),MIN(published),MAX(published) FROM messages", null).use { c ->
            if (c.moveToFirst()) {
                val lo = if (c.isNull(1)) null else c.getLong(1)
                val hi = if (c.isNull(2)) null else c.getLong(2)
                sb.append("записей: ${c.getInt(0)}; с ${fmtTs(lo)} по ${fmtTs(hi)}")
            }
        }
        readableDatabase.rawQuery(
            "SELECT status_name,COUNT(*) FROM messages GROUP BY 1 ORDER BY 2 DESC", null
        ).use { c ->
            while (c.moveToNext()) sb.append("\n  ${c.getString(0)}: ${c.getInt(1)}")
        }
        return sb.toString()
    }

    private fun readRow(c: Cursor): Row {
        fun l(i: Int): Long? = if (c.isNull(i)) null else c.getLong(i)
        fun d(i: Int): Double? = if (c.isNull(i)) null else c.getDouble(i)
        val photos = ArrayList<String>()
        try {
            val arr = JSONArray(c.getString(13) ?: "[]")
            for (i in 0 until arr.length()) photos.add(arr.getString(i))
        } catch (e: Exception) { }
        return Row(
            c.getLong(0), l(1), c.getString(2), l(3), l(4), c.getString(5), c.getString(6),
            c.getString(7) ?: "", c.getString(8), c.getString(9), d(10), d(11), c.getString(12), photos
        )
    }

    fun query(search: String, limit: Int): List<Row> {
        val s = search.trim()
        val sql = "SELECT $COLS FROM messages" +
            (if (s.isNotEmpty()) " WHERE text LIKE ? OR address LIKE ? OR place LIKE ? OR CAST(id AS TEXT) LIKE ?" else "") +
            " ORDER BY published DESC LIMIT $limit"
        val args = if (s.isNotEmpty()) arrayOf("%$s%", "%$s%", "%$s%", "%$s%") else null
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(readRow(c))
        }
        return out
    }

    private fun q(s: String?): String = "\"" + (s ?: "").replace("\"", "\"\"") + "\""

    fun writeCsv(out: OutputStream): Int {
        var n = 0
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("\uFEFF")
        w.write("id,theme_name,created_msk,published_msk,status_name,text,place,address,lat,lon,user_name,photos\n")
        readableDatabase.rawQuery("SELECT $COLS FROM messages ORDER BY published DESC", null).use { c ->
            while (c.moveToNext()) {
                val r = readRow(c)
                w.write(
                    listOf(
                        r.id.toString(), q(r.themeName), q(fmtTs(r.created)), q(fmtTs(r.published)),
                        q(r.statusName), q(r.text), q(r.place), q(r.address),
                        r.lat?.toString() ?: "", r.lon?.toString() ?: "", q(r.userName),
                        q(JSONArray(r.photos).toString())
                    ).joinToString(",")
                )
                w.write("\n")
                n++
            }
        }
        w.flush()
        return n
    }
}
