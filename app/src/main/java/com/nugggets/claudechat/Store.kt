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

class Msg(val role: String, var text: String)

class Chat(val id: String, var title: String, val msgs: MutableList<Msg>, var updated: Long)

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

    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String = p(c).getString("url", DEFAULT_URL) ?: DEFAULT_URL
    fun apiKey(c: Context): String = Crypto.dec(p(c).getString("key", "") ?: "")
    fun model(c: Context): String = p(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun maxTokens(c: Context): Int = p(c).getInt("max", 4096)
    fun system(c: Context): String = p(c).getString("system", "") ?: ""
    fun bearer(c: Context): Boolean = p(c).getBoolean("bearer", false)
    fun stream(c: Context): Boolean = p(c).getBoolean("stream", true)
    fun textSize(c: Context): Int = p(c).getInt("size", 16)
    fun dark(c: Context): Boolean = p(c).getBoolean("dark", true)
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
        bearer: Boolean, stream: Boolean, size: Int, dark: Boolean
    ) {
        p(c).edit()
            .putString("url", url)
            .putString("key", Crypto.enc(key))
            .putString("model", model)
            .putInt("max", max)
            .putString("system", system)
            .putBoolean("bearer", bearer)
            .putBoolean("stream", stream)
            .putInt("size", size)
            .putBoolean("dark", dark)
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

    fun save(c: Context, chat: Chat) {
        val real = chat.msgs.filter { it.text.isNotEmpty() }
        if (real.isEmpty()) return
        val o = JSONObject()
        o.put("id", chat.id)
        o.put("title", chat.title)
        o.put("updated", chat.updated)
        val a = JSONArray()
        for (m in real) {
            val j = JSONObject()
            j.put("r", m.role)
            j.put("t", m.text)
            a.put(j)
        }
        o.put("msgs", a)
        try {
            File(dir(c), chat.id + ".json").writeText(o.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
        }
    }

    private fun parse(s: String): Chat {
        val o = JSONObject(s)
        val a = o.getJSONArray("msgs")
        val list = mutableListOf<Msg>()
        for (i in 0 until a.length()) {
            val m = a.getJSONObject(i)
            list.add(Msg(m.getString("r"), m.getString("t")))
        }
        return Chat(o.getString("id"), o.optString("title", "Чат"), list, o.optLong("updated", 0L))
    }

    fun load(c: Context, id: String): Chat? {
        return try {
            val f = File(dir(c), "$id.json")
            if (f.exists()) parse(f.readText(Charsets.UTF_8)) else null
        } catch (e: Exception) {
            null
        }
    }

    fun list(c: Context): List<Chat> {
        val files = dir(c).listFiles() ?: return emptyList()
        val out = mutableListOf<Chat>()
        for (f in files) {
            try {
                out.add(parse(f.readText(Charsets.UTF_8)))
            } catch (e: Exception) {
            }
        }
        out.sortByDescending { it.updated }
        return out
    }

    fun delete(c: Context, id: String) {
        File(dir(c), "$id.json").delete()
    }
}
