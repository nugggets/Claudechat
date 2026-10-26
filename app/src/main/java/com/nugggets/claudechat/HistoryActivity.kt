package com.nugggets.claudechat

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class HistoryActivity : Activity() {

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

        val sv = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(10), dp(6), dp(10), dp(20))
        sv.addView(box, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val chats = Store.list(this)
        if (chats.isEmpty()) {
            val t = label("Здесь появятся сохранённые чаты. Напишите первое сообщение, и чат сохранится.", 15f, Pal.sub, false)
            t.setPadding(dp(8), dp(24), dp(8), dp(8))
            box.addView(t)
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
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            left.addView(title)
            left.addView(label(fmt.format(Date(c.updated)) + "  ·  сообщений: " + c.msgs.size, 12f, Pal.sub, false))
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
                        build()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            row.addView(del)

            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = dp(8)
            box.addView(row, lp)
        }
        setContentView(root)
    }
}
