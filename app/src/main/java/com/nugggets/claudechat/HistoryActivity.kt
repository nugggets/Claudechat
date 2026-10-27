package com.nugggets.claudechat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val RQ_EXPORT = 21

class HistoryActivity : Activity() {
    private lateinit var list: LinearLayout
    private var query = ""
    private var exportText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.load(this)
        styleWindow()
        build()
    }

    private fun build() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.bg)
        root.addView(topBar("История чатов", "←") { finish() }, LinearLayout.LayoutParams(MATCH, WRAP))

        val search = EditText(this)
        search.hint = "Поиск по чатам"
        search.setHintTextColor(Pal.sub)
        search.setTextColor(Pal.text)
        search.textSize = 15f
        search.setSingleLine(true)
        search.inputType = InputType.TYPE_CLASS_TEXT
        search.background = rounded(Pal.field, dp(18).toFloat())
        search.setPadding(dp(14), dp(8), dp(14), dp(8))
        search.setText(query)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                fill()
            }
        })
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.setMargins(dp(10), dp(8), dp(10), dp(0))
        root.addView(search, slp)

        val sv = ScrollView(this)
        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(10), dp(6), dp(10), dp(20))
        sv.addView(list, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.setBackgroundColor(Pal.bar)
        foot.gravity = Gravity.CENTER
        val b1 = label("Сохранить все чаты в файл", 13f, Pal.text, false)
        b1.setPadding(dp(14), dp(12), dp(14), dp(12))
        b1.setOnClickListener { exportAll() }
        val b2 = label("Удалить все", 13f, Pal.err, false)
        b2.setPadding(dp(14), dp(12), dp(14), dp(12))
        b2.setOnClickListener { deleteAll() }
        foot.addView(b1, LinearLayout.LayoutParams(0, WRAP, 1f))
        foot.addView(b2, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(foot, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)
        fill()
    }

    private fun matches(c: Chat): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (c.title.contains(q, true)) return true
        for (m in c.msgs) if (m.text.contains(q, true)) return true
        return false
    }

    private fun fill() {
        list.removeAllViews()
        val all = Store.list(this).filter { it.msgs.isNotEmpty() }
        val chats = all.filter { matches(it) }
        if (all.isEmpty()) {
            val t = label("Здесь появятся сохранённые чаты. Напишите первое сообщение, и чат сохранится.", 15f, Pal.sub, false)
            t.setPadding(dp(8), dp(24), dp(8), dp(8))
            list.addView(t)
        } else if (chats.isEmpty()) {
            val t = label("Ничего не найдено.", 15f, Pal.sub, false)
            t.setPadding(dp(8), dp(24), dp(8), dp(8))
            list.addView(t)
        }
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        for (c in chats) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(Pal.bot, dp(12).toFloat())

            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.setPadding(dp(14), dp(10), dp(8), dp(10))
            val title = label(c.title, 16f, Pal.text, true)
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            left.addView(title)
            val files = c.msgs.sumOf { it.atts.size }
            val extra = if (files > 0) "  ·  файлов: $files" else ""
            left.addView(
                label(fmt.format(Date(c.updated)) + "  ·  сообщений: " + c.msgs.size + extra, 12f, Pal.sub, false)
            )
            left.setOnClickListener {
                Store.setCur(this, c.id)
                finish()
            }
            row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))

            val del = label("✕", 18f, Pal.sub, true)
            del.setPadding(dp(16), dp(12), dp(16), dp(12))
            del.setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage("Удалить чат «" + c.title + "»?")
                    .setPositiveButton("Удалить") { _, _ ->
                        Store.delete(this, c.id)
                        if (Store.curId(this) == c.id) Store.setCur(this, "")
                        fill()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            row.addView(del)

            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = dp(8)
            list.addView(row, lp)
        }
    }

    private fun exportAll() {
        val all = Store.list(this).filter { it.msgs.isNotEmpty() }
        if (all.isEmpty()) {
            Toast.makeText(this, "Нет чатов для сохранения", Toast.LENGTH_SHORT).show()
            return
        }
        exportText = all.joinToString("\n\n---\n\n") { Files.chatText(it).trimEnd() } + "\n"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "text/markdown"
        i.putExtra(Intent.EXTRA_TITLE, "claudechat-all.md")
        try {
            startActivityForResult(i, RQ_EXPORT)
        } catch (e: Exception) {
            Toast.makeText(this, "Не найдено приложение для сохранения файлов", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RQ_EXPORT) {
            val u = data?.data
            if (resultCode == RESULT_OK && u != null) {
                try {
                    val o = contentResolver.openOutputStream(u)
                    if (o != null) {
                        o.use { it.write(exportText.toByteArray(Charsets.UTF_8)) }
                        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось сохранить файл", Toast.LENGTH_LONG).show()
                }
            }
            exportText = ""
        }
    }

    private fun deleteAll() {
        val all = Store.list(this)
        if (all.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage("Удалить все чаты (" + all.size + ") вместе с вложениями? Это нельзя отменить.")
            .setPositiveButton("Удалить всё") { _, _ ->
                for (c in all) Store.delete(this, c.id)
                Store.setCur(this, "")
                fill()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
