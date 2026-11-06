package com.nugggets.claudechat

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Вложение: имя для показа, тип и имя файла в папке вложений приложения. */
class Att(val name: String, val mime: String, val file: String) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isPdf: Boolean get() = mime == "application/pdf"
}

class Msg(val role: String, var text: String, val atts: MutableList<Att> = mutableListOf())

class Chat(
    val id: String,
    var title: String,
    val msgs: MutableList<Msg>,
    var updated: Long,
    var sys: String = ""
)

/** Шифрование ключа через Android Keystore (AES-GCM). */
object Crypto {
    private const val ALIAS = "claudechat_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    fun enc(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            val ct = c.doFinal(s.toByteArray(Charsets.UTF_8))
            "e:" + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            "p:" + Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    fun dec(s: String): String {
        if (s.length < 3) return ""
        return try {
            val raw = Base64.decode(s.substring(2), Base64.NO_WRAP)
            if (s.startsWith("p:")) {
                String(raw, Charsets.UTF_8)
            } else {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }
}

object Store {
    const val DEFAULT_URL = "https://api.anthropic.com"
    const val DEFAULT_MODEL = "claude-opus-5-5"

    /** Инструкция по умолчанию: тон и манера ответов как в приложении Claude. */
    const val DEFAULT_SYSTEM_V1 = """Ты Claude, ассистент от Anthropic. Отвечай на том языке, на котором пишет человек.
Тон: тёплый, прямой и честный; относись к собеседнику как к взрослому, способному человеку. Не льсти, не начинай с похвалы вопросу, не извиняйся без причины. Если не согласен, скажи об этом доброжелательно и по делу.
Форма: в обычном разговоре и на простые вопросы отвечай связным текстом, коротко. Списки, заголовки и жирный шрифт используй только когда содержимое действительно многосоставное или об этом просят. Не задавай больше одного уточняющего вопроса за раз и сначала старайся ответить, даже если запрос неоднозначен.
Если чего-то не знаешь или не уверен, так и скажи. Для данных, которые могли устареть, используй веб-поиск, если он включён.
Код оформляй блоками, объяснения держи краткими."""

    const val DEFAULT_SYSTEM = """Ты Claude, ИИ-ассистент от Anthropic. Ты общаешься с человеком в чате на телефоне.

Кто ты. Ты не человек и не притворяешься им. Если спросят, какая ты модель, скажи, что ты Claude от Anthropic; точную версию называй, только если она указана в этой инструкции или ты в ней уверен, иначе так и скажи, ничего не выдумывай.

Характер. Ты любознательный, тёплый и прямой. Относись к собеседнику как к умному взрослому человеку: не поучай, не опекай, не делай поспешных выводов о его намерениях. Если не согласен, скажи об этом доброжелательно и по существу, с аргументами; не поддакивай ради приятности. Не льсти и не хвали вопрос («отличный вопрос» и подобное), не начинай ответ с вводных фраз, сразу переходи к сути. Извиняйся только когда действительно ошибся: признай ошибку просто, исправь и иди дальше, без самобичевания.

Язык. Отвечай на том языке, на котором пишет человек, и естественным разговорным слогом, без канцелярита и без кальки с английского.

Длина и форма. Подстраивай длину под вопрос: на короткий или бытовой вопрос короткий ответ в несколько предложений, на сложный подробный. В обычной беседе пиши связным текстом, а не списком. Маркированные списки, заголовки и жирный шрифт используй только когда содержимое действительно многосоставное (шаги, сравнение, справочный материал) или об этом просят. Не используй таблицы: в приложении они плохо отображаются. Эмодзи не добавляй, если человек сам их не использует. Не повторяй вопрос и не пересказывай ответ в конце.

Вопросы. Старайся сначала ответить или сделать, даже если запрос неоднозначен, опираясь на разумное допущение (назови его одной фразой). Уточняющий вопрос задавай, только если без него никак, и не больше одного за раз.

Честность. Говори, что знаешь, и отделяй это от догадок. Не выдумывай факты, цитаты, ссылки, имена, цифры и источники. Если не знаешь или не уверен, скажи прямо. Для всего, что могло устареть (новости, цены, версии, текущие должности), используй веб-поиск, если он включён; если нет, предупреди, что данные могут быть неактуальны. Ты не всеведущ: не изображай уверенность там, где её нет.

Эмоциональные темы. Если человеку тяжело, будь живым и тёплым собеседником, а не справочником: сначала пойми, что с ним, не заваливай советами и списками. Не поддерживай самоповреждающее поведение и не соглашайся с неправдой ради утешения, но делай это бережно. Если есть признаки угрозы жизни, мягко и спокойно предложи обратиться за помощью к близким и в экстренные службы.

Медицина, право, финансы. Давай реальную полезную информацию, как знающий друг, и честно отмечай, где нужен врач, юрист или консультант, без пустых отговорок.

Спорные темы. По острым политическим и ценностным вопросам не навязывай своё мнение: излагай сильнейшие аргументы разных сторон честно и ровно; фактические вопросы излагай прямо, без ложного баланса.

Код и техника. Код давай в блоках кода, полный и рабочий, с коротким объяснением ключевого. Если видишь ошибку или риск в подходе человека, скажи об этом. Не пиши вредоносный код.

Творчество. Пиши с увлечением и своим голосом, не штампами. Не воспроизводи защищённые авторским правом тексты (тексты песен, большие отрывки книг).

Границы. От вредных просьб (оружие, насилие, сексуальный контент с участием детей и подобное) отказывайся спокойно, без нотаций и без списков, кратко объясни и, если возможно, предложи безопасную альтернативу. Без нужды не придирайся: большинство просьб нормальные, помогай по-настоящему."""

    val MODELS = listOf(
        "Opus 5.5" to "claude-opus-5-5",
        "Fable 5.1" to "claude-fable-5-1",
        "Sonnet 5.5" to "claude-sonnet-5-5",
        "Haiku 5.5" to "claude-haiku-5-5"
    )

    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String = p(c).getString("url", DEFAULT_URL) ?: DEFAULT_URL
    fun apiKey(c: Context): String = Crypto.dec(p(c).getString("key", "") ?: "")
    fun model(c: Context): String = p(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun maxTokens(c: Context): Int {
        val v = p(c).getInt("max", 16384)
        // Прежний лимит 4096 обрывал длинные ответы: поднимаем, пока человек сам не сохранил новое значение.
        return if (p(c).getInt("sysv", 0) < 3 && v < 16384) 16384 else v
    }
    fun system(c: Context): String {
        val v = p(c).getString("system", "") ?: ""
        // Старые установки хранят пустую строку, хотя инструкцию никто не стирал: даём им стандартную.
        if (v.trim() == DEFAULT_SYSTEM_V1.trim()) return DEFAULT_SYSTEM
        return if (p(c).getInt("sysv", 0) >= 2 || v.isNotBlank()) v else DEFAULT_SYSTEM
    }
    fun bearer(c: Context): Boolean = p(c).getBoolean("bearer", false)
    fun stream(c: Context): Boolean = p(c).getBoolean("stream", true)
    fun textSize(c: Context): Int = p(c).getInt("size", 16)
    fun dark(c: Context): Boolean = p(c).getBoolean("dark", true)
    fun temp(c: Context): String = p(c).getString("temp", "") ?: ""
    fun web(c: Context): Boolean = p(c).getBoolean("web", false)

    /** «Память»: что Claude должен знать о человеке (отправляется вместе с инструкцией). */
    fun profile(c: Context): String = p(c).getString("profile", "") ?: ""
    /** Автопамять: заметки о человеке, которые модель ведёт сама после каждого ответа. */
    fun mem(c: Context): String = p(c).getString("mem", "") ?: ""
    fun autoMem(c: Context): Boolean = p(c).getBoolean("automem", true)
    fun setMem(c: Context, text: String, auto: Boolean) {
        p(c).edit().putString("mem", text).putBoolean("automem", auto).apply()
    }
    fun think(c: Context): Boolean = p(c).getBoolean("think", false)
    fun setProfileThink(c: Context, profile: String, think: Boolean) {
        p(c).edit().putString("profile", profile).putBoolean("think", think).apply()
    }

    /** Отступ сверху (dp): «заглушка» для экрана, у которого не работает верхняя часть сенсора. */
    fun topPad(c: Context): Int = p(c).getInt("toppad", 72)
    fun setTopPad(c: Context, v: Int) {
        p(c).edit().putInt("toppad", v.coerceIn(0, 240)).apply()
    }

    /** Модели, полученные с сервера; пока не загружены — встроенный набор. */
    fun modelIds(c: Context): List<String> {
        val raw = p(c).getString("mlist", "") ?: ""
        val l = raw.split("\n").filter { it.isNotBlank() }
        return if (l.isEmpty()) MODELS.map { it.second } else l
    }

    fun setModelIds(c: Context, ids: List<String>) {
        p(c).edit().putString("mlist", ids.joinToString("\n")).apply()
    }

    fun setModel(c: Context, m: String) {
        p(c).edit().putString("model", m).apply()
    }

    fun tokIn(c: Context): Long = p(c).getLong("tin", 0L)
    fun tokOut(c: Context): Long = p(c).getLong("tout", 0L)

    fun addTokens(c: Context, i: Int, o: Int) {
        p(c).edit().putLong("tin", tokIn(c) + i).putLong("tout", tokOut(c) + o).apply()
    }

    fun resetTokens(c: Context) {
        p(c).edit().putLong("tin", 0L).putLong("tout", 0L).apply()
    }

    fun saveSettings(
        c: Context, url: String, key: String, model: String, max: Int, system: String,
        bearer: Boolean, stream: Boolean, size: Int, dark: Boolean, temp: String, web: Boolean
    ) {
        p(c).edit()
            .putString("url", url)
            .putString("key", Crypto.enc(key))
            .putString("model", model)
            .putInt("max", max)
            .putString("system", system)
            .putInt("sysv", 3)
            .putBoolean("bearer", bearer)
            .putBoolean("stream", stream)
            .putInt("size", size)
            .putBoolean("dark", dark)
            .putString("temp", temp)
            .putBoolean("web", web)
            .apply()
    }

    fun curId(c: Context): String = p(c).getString("cur", "") ?: ""
    fun setCur(c: Context, id: String) {
        p(c).edit().putString("cur", id).apply()
    }

    // ---- чаты ----
    private fun dir(c: Context): File {
        val d = File(c.filesDir, "chats")
        d.mkdirs()
        return d
    }

    fun newChat(): Chat {
        val now = System.currentTimeMillis()
        return Chat(now.toString(), "Новый чат", mutableListOf(), now)
    }

    fun attDir(c: Context): File {
        val d = File(c.filesDir, "att")
        d.mkdirs()
        return d
    }

    fun save(c: Context, chat: Chat) {
        val real = chat.msgs.filter { it.text.isNotEmpty() || it.atts.isNotEmpty() }
        if (real.isEmpty()) {
            try {
                File(dir(c), chat.id + ".json").delete()
            } catch (e: Exception) {
            }
            return
        }
        val o = JSONObject()
        o.put("id", chat.id)
        o.put("title", chat.title)
        o.put("updated", chat.updated)
        o.put("sys", chat.sys)
        val a = JSONArray()
        for (m in real) {
            val j = JSONObject()
            j.put("r", m.role)
            j.put("t", m.text)
            if (m.atts.isNotEmpty()) {
                val aa = JSONArray()
                for (t in m.atts) {
                    val x = JSONObject()
                    x.put("n", t.name)
                    x.put("m", t.mime)
                    x.put("f", t.file)
                    aa.put(x)
                }
                j.put("a", aa)
            }
            a.put(j)
        }
        o.put("msgs", a)
        try {
            val f = File(dir(c), chat.id + ".json")
            val tmp = File(dir(c), chat.id + ".tmp")
            tmp.writeText(o.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(o.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun parse(s: String): Chat {
        val o = JSONObject(s)
        val a = o.getJSONArray("msgs")
        val list = mutableListOf<Msg>()
        for (i in 0 until a.length()) {
            val m = a.getJSONObject(i)
            val msg = Msg(m.optString("r", "user"), m.optString("t", ""))
            val aa = m.optJSONArray("a")
            if (aa != null) {
                for (k in 0 until aa.length()) {
                    val x = aa.getJSONObject(k)
                    msg.atts.add(Att(x.optString("n", "файл"), x.optString("m", ""), x.optString("f", "")))
                }
            }
            list.add(msg)
        }
        return Chat(
            o.optString("id"), o.optString("title", "Чат"), list,
            o.optLong("updated", 0L), o.optString("sys", "")
        )
    }

    fun load(c: Context, id: String): Chat? {
        return try {
            val f = File(dir(c), "$id.json")
            if (f.exists()) parse(f.readText(Charsets.UTF_8)) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Сколько файлов чатов не удалось прочитать при последнем показе списка. */
    @Volatile var unreadable = 0

    fun list(c: Context): List<Chat> {
        val files = dir(c).listFiles { x -> x.name.endsWith(".json") } ?: return emptyList()
        val out = mutableListOf<Chat>()
        var bad = 0
        for (f in files) {
            try {
                val ch = parse(f.readText(Charsets.UTF_8))
                if (ch.id.isEmpty()) throw IllegalStateException("no id")
                out.add(ch)
            } catch (e: Exception) {
                bad++
            }
        }
        unreadable = bad
        out.sortByDescending { it.updated }
        return out
    }

    fun delete(c: Context, id: String) {
        val ch = load(c, id)
        if (ch != null) {
            for (m in ch.msgs) Files.dropAll(c, m.atts)
        }
        File(dir(c), "$id.json").delete()
    }

    // ---- копия чатов в один JSON-файл (без вложений) ----
    fun exportJson(c: Context): String {
        val arr = JSONArray()
        for (f in dir(c).listFiles { x -> x.name.endsWith(".json") } ?: emptyArray()) {
            try {
                arr.put(JSONObject(f.readText(Charsets.UTF_8)))
            } catch (e: Exception) {
            }
        }
        val o = JSONObject()
        o.put("app", "claudechat")
        o.put("version", 1)
        o.put("chats", arr)
        return o.toString()
    }

    /** Возвращает число восстановленных чатов; существующие, более новые чаты не трогает. */
    fun importJson(c: Context, text: String): Int {
        var n = 0
        val arr = JSONObject(text).getJSONArray("chats")
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val f = File(dir(c), "$id.json")
                if (f.exists()) {
                    val old = try { JSONObject(f.readText(Charsets.UTF_8)).optLong("updated", 0L) } catch (e: Exception) { -1L }
                    if (old >= o.optLong("updated", 0L)) continue
                }
                f.writeText(o.toString(), Charsets.UTF_8)
                n++
            } catch (e: Exception) {
            }
        }
        return n
    }
}
