package com.nugggets.claudechat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var chat: Chat = Store.newChat()
    private var sending = false
    private var req: Req? = null
    private var gen = 0
    private var note = ""
    private var draft = ""
    private var built = false

    @Volatile
    private var uiPending = false

    private lateinit var scroll: ScrollView
    private lateinit var box: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var info: TextView
    private lateinit var titleView: TextView
    private var liveBubble: TextView? = null

    override fun onResume() {
        super.onResume()
        Pal.load(this)
        if (!sending) loadCurrent()
        buildUi()
    }

    override fun onPause() {
        super.onPause()
        if (built) {
            draft = input.text.toString()
            Store.save(this, chat)
        }
    }

    private fun loadCurrent() {
        val id = Store.curId(this)
        val loaded = if (id.isNotEmpty()) Store.load(this, id) else null
        chat = loaded ?: Store.newChat()
        note = ""
    }

    // ---------- интерфейс ----------

    private fun barBtn(t: String, a: () -> Unit): TextView {
        val b = label(t, 22f, Pal.text, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(14), dp(8), dp(14), dp(8))
        b.setOnClickListener { a() }
        return b
    }

    private fun buildUi() {
        if (built) draft = input.text.toString()
        styleWindow()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.bg)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Pal.bar)
        bar.setPadding(dp(4), dp(4), dp(4), dp(0))
        bar.addView(barBtn("☰") { openHistory() })
        titleView = label("", 17f, Pal.text, true)
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        bar.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(barBtn("+") { newChat() })
        bar.addView(barBtn("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        info = label("", 12f, Pal.sub, false)
        info.setPadding(dp(16), dp(2), dp(16), dp(8))
        info.setBackgroundColor(Pal.bar)
        info.maxLines = 1
        info.ellipsize = TextUtils.TruncateAt.END
        root.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))

        scroll = ScrollView(this)
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(10), dp(4), dp(10), dp(12))
        scroll.addView(box, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.BOTTOM
        row.setBackgroundColor(Pal.bar)
        row.setPadding(dp(8), dp(8), dp(8), dp(8))
        input = EditText(this)
        input.hint = "Сообщение"
        input.setHintTextColor(Pal.sub)
        input.setTextColor(Pal.text)
        input.textSize = Store.textSize(this).toFloat()
        input.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.maxLines = 6
        input.background = rounded(Pal.field, dp(22).toFloat())
        input.setPadding(dp(16), dp(10), dp(16), dp(10))
        row.addView(input, LinearLayout.LayoutParams(0, WRAP, 1f))

        sendBtn = label("↑", 22f, Pal.white, true)
        sendBtn.gravity = Gravity.CENTER
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(Pal.user)
        sendBtn.background = oval
        sendBtn.setOnClickListener { if (sending) stop() else send() }
        val slp = LinearLayout.LayoutParams(dp(46), dp(46))
        slp.leftMargin = dp(8)
        row.addView(sendBtn, slp)
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)
        built = true
        input.setText(draft)
        updateTitle()
        updateInfo()
        updateSendBtn()
        render()
    }

    private fun updateTitle() {
        titleView.text = if (chat.msgs.isEmpty()) "Claude Chat" else chat.title
    }

    private fun fmt(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

    private fun updateInfo() {
        info.text = Store.model(this) + "  ·  токены: " + fmt(Store.tokIn(this)) +
            " вх / " + fmt(Store.tokOut(this)) + " исх"
    }

    private fun updateSendBtn() {
        sendBtn.text = if (sending) "■" else "↑"
    }

    private fun smallBtn(t: String, a: () -> Unit): TextView {
        val b = label(t, 13f, Pal.sub, false)
        b.setPadding(dp(10), dp(6), dp(10), dp(6))
        b.setOnClickListener { a() }
        return b
    }

    private fun render() {
        box.removeAllViews()
        liveBubble = null
        val size = Store.textSize(this).toFloat()

        if (chat.msgs.isEmpty() && note.isEmpty()) {
            val hint = label(
                "Напишите первое сообщение.\n\nЕсли ключ ещё не указан, откройте настройки (⚙), " +
                    "введите API-ключ и проверьте его кнопкой «Проверить ключ».",
                15f, Pal.sub, false
            )
            hint.setPadding(dp(8), dp(24), dp(8), dp(8))
            box.addView(hint)
            if (Store.apiKey(this).isEmpty()) {
                val b = label("Открыть настройки", 15f, Pal.white, true)
                b.gravity = Gravity.CENTER
                b.setPadding(dp(16), dp(12), dp(16), dp(12))
                b.background = rounded(Pal.user, dp(12).toFloat())
                b.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
                val lp = LinearLayout.LayoutParams(WRAP, WRAP)
                lp.leftMargin = dp(8)
                lp.topMargin = dp(8)
                box.addView(b, lp)
            }
        }

        for ((i, m) in chat.msgs.withIndex()) {
            addBubble(m, i == chat.msgs.size - 1, size)
        }

        if (note.isNotEmpty()) {
            val n = label(note, 14f, Pal.err, false)
            n.setPadding(dp(12), dp(10), dp(12), dp(10))
            n.setTextIsSelectable(true)
            n.background = rounded(Pal.bar, dp(12).toFloat())
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = dp(10)
            box.addView(n, lp)
            if (!sending && chat.msgs.isNotEmpty() && chat.msgs[chat.msgs.size - 1].role == "user") {
                box.addView(smallBtn("Повторить запрос") { start() })
            }
        }
        scrollDown()
    }

    private fun addBubble(m: Msg, isLast: Boolean, size: Float) {
        val user = m.role == "user"
        val live = sending && isLast && !user
        val tv = TextView(this)
        tv.textSize = size
        tv.setTextColor(if (user) Pal.white else Pal.text)
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        tv.background = rounded(if (user) Pal.user else Pal.bot, dp(14).toFloat())
        tv.setTextIsSelectable(true)
        tv.text = if (user) m.text else if (m.text.isEmpty()) "…" else Md.render(m.text)
        if (live) liveBubble = tv
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = dp(10)
        if (user) lp.leftMargin = dp(40) else lp.rightMargin = dp(20)
        box.addView(tv, lp)

        if (!user && !live) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(smallBtn("Копировать") { copy(m.text) })
            if (isLast) row.addView(smallBtn("Повторить") { regenerate() })
            box.addView(row)
        }
    }

    private fun updateLast() {
        val tv = liveBubble ?: return
        if (chat.msgs.isEmpty()) return
        val m = chat.msgs[chat.msgs.size - 1]
        val atBottom = isNearBottom()
        tv.text = if (m.text.isEmpty()) "…" else Md.render(m.text)
        if (atBottom) scrollDown()
    }

    private fun isNearBottom(): Boolean {
        val c = scroll.getChildAt(0) ?: return true
        return c.bottom - (scroll.height + scroll.scrollY) < dp(150)
    }

    private fun scrollDown() {
        scroll.post {
            val c = scroll.getChildAt(0)
            if (c != null) scroll.scrollTo(0, c.height)
        }
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Claude", text))
        toast("Скопировано")
    }

    // ---------- действия ----------

    private fun openHistory() {
        if (sending) {
            toast("Дождитесь ответа или нажмите ■")
            return
        }
        startActivity(Intent(this, HistoryActivity::class.java))
    }

    private fun newChat() {
        if (sending) {
            toast("Дождитесь ответа или нажмите ■")
            return
        }
        Store.save(this, chat)
        chat = Store.newChat()
        Store.setCur(this, chat.id)
        note = ""
        updateTitle()
        render()
    }

    private fun stop() {
        req?.cancel()
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        if (Store.apiKey(this).isEmpty()) {
            Toast.makeText(this, "Сначала укажите API-ключ в настройках", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        input.setText("")
        chat.msgs.add(Msg("user", t))
        if (chat.msgs.size == 1) chat.title = t.replace("\n", " ").take(40)
        start()
    }

    private fun regenerate() {
        if (sending) return
        if (chat.msgs.isNotEmpty() && chat.msgs[chat.msgs.size - 1].role == "assistant") {
            chat.msgs.removeAt(chat.msgs.size - 1)
        }
        if (chat.msgs.isEmpty()) return
        start()
    }

    /** Отправляет текущую историю и принимает ответ в новое сообщение ассистента. */
    private fun start() {
        if (sending) return
        note = ""
        chat.updated = System.currentTimeMillis()
        Store.setCur(this, chat.id)
        val history = chat.msgs.filter { it.text.isNotEmpty() }.map { Msg(it.role, it.text) }
        val msg = Msg("assistant", "")
        chat.msgs.add(msg)
        Store.save(this, chat)
        sending = true
        gen++
        val my = gen
        val cfg = Cfg(
            Store.baseUrl(this), Store.apiKey(this), Store.model(this), Store.maxTokens(this),
            Store.system(this), Store.bearer(this), Store.stream(this)
        )
        val r = Req(cfg)
        req = r
        updateTitle()
        updateSendBtn()
        render()

        val acc = StringBuilder()
        Thread {
            var res: Res? = null
            var err: String? = null
            try {
                res = r.run(history) { d ->
                    acc.append(d)
                    if (!uiPending) {
                        uiPending = true
                        val snap = acc.toString()
                        handler.postDelayed({
                            uiPending = false
                            if (sending && gen == my) {
                                msg.text = snap
                                updateLast()
                            }
                        }, 60)
                    }
                }
            } catch (e: ApiError) {
                err = e.message
            } catch (e: Exception) {
                err = "Ошибка: " + e.message
            }
            val finalText = acc.toString()
            val e2 = err
            val r2 = res
            val stopped = r.cancelled
            handler.post {
                if (gen != my) return@post
                sending = false
                req = null
                msg.text = finalText
                var n = ""
                if (e2 != null) {
                    n = e2
                } else if (finalText.isEmpty()) {
                    n = if (stopped) "Остановлено." else "Сервер вернул пустой ответ."
                } else if (r2 != null && r2.stop == "max_tokens") {
                    n = "Ответ обрезан: достигнут лимит токенов ответа. " +
                        "Увеличьте «Макс. токенов ответа» в настройках."
                }
                if (finalText.isEmpty()) chat.msgs.remove(msg)
                note = n
                if (r2 != null) Store.addTokens(this, r2.inTok, r2.outTok)
                chat.updated = System.currentTimeMillis()
                Store.save(this, chat)
                updateSendBtn()
                updateInfo()
                render()
            }
        }.start()
    }
}
