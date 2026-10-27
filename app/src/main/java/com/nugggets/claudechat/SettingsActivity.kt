package com.nugggets.claudechat

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class SettingsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var col: LinearLayout
    private lateinit var eUrl: EditText
    private lateinit var eKey: EditText
    private lateinit var eModel: EditText
    private lateinit var eMax: EditText
    private lateinit var eSys: EditText
    private lateinit var rbX: RadioButton
    private lateinit var rbB: RadioButton
    private lateinit var cbStream: CheckBox
    private lateinit var cbDark: CheckBox
    private lateinit var cbWeb: CheckBox
    private lateinit var eTemp: EditText
    private lateinit var seek: SeekBar
    private lateinit var lblSize: TextView
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.load(this)
        styleWindow()
        build()
    }

    private fun add(v: View, top: Int) {
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(top)
        col.addView(v, lp)
    }

    private fun head(t: String) {
        add(label(t, 17f, Pal.text, true), 24)
    }

    private fun small(t: String) {
        add(label(t, 13f, Pal.sub, false), 12)
    }

    private fun field(hint: String, value: String): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setTextColor(Pal.text)
        e.setHintTextColor(Pal.sub)
        e.textSize = 15f
        e.background = rounded(Pal.field, dp(10).toFloat())
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        return e
    }

    private fun check(t: String, on: Boolean): CheckBox {
        val c = CheckBox(this)
        c.text = t
        c.setTextColor(Pal.text)
        c.isChecked = on
        return c
    }

    private fun button(t: String, filled: Boolean, a: () -> Unit): TextView {
        val b = label(t, 16f, if (filled) Pal.white else Pal.text, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(16), dp(13), dp(16), dp(13))
        b.background = rounded(if (filled) Pal.user else Pal.field, dp(12).toFloat())
        b.setOnClickListener { a() }
        return b
    }

    private fun build() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.bg)
        root.addView(topBar("Настройки", "←") { finish() }, LinearLayout.LayoutParams(MATCH, WRAP))

        val sv = ScrollView(this)
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(4), dp(16), dp(32))
        sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // --- Подключение ---
        head("Подключение")
        add(label("Ключ хранится на телефоне в зашифрованном виде и уходит только на адрес ниже.", 13f, Pal.sub, false), 4)

        small("Адрес API")
        eUrl = field(Store.DEFAULT_URL, Store.baseUrl(this))
        eUrl.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        add(eUrl, 4)

        small("API-ключ")
        eKey = field("sk-ant-...", Store.apiKey(this))
        eKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        add(eKey, 4)
        val show = check("Показать ключ", false)
        show.setOnCheckedChangeListener { _, on ->
            val vis = if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            eKey.inputType = InputType.TYPE_CLASS_TEXT or vis or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            eKey.setSelection(eKey.text.length)
        }
        add(show, 0)

        small("Как передавать ключ")
        val rg = RadioGroup(this)
        rbX = RadioButton(this)
        rbX.id = View.generateViewId()
        rbX.text = "Заголовок x-api-key (официальный API, ключи sk-ant-…)"
        rbX.setTextColor(Pal.text)
        rbB = RadioButton(this)
        rbB.id = View.generateViewId()
        rbB.text = "Authorization: Bearer (нужно некоторым прокси)"
        rbB.setTextColor(Pal.text)
        rg.addView(rbX)
        rg.addView(rbB)
        if (Store.bearer(this)) rbB.isChecked = true else rbX.isChecked = true
        add(rg, 4)

        // --- Модель ---
        head("Модель")
        eModel = field(Store.DEFAULT_MODEL, Store.model(this))
        add(eModel, 8)
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        for ((name, id) in Store.MODELS) {
            val c = label(name, 14f, Pal.text, false)
            c.setPadding(dp(14), dp(8), dp(14), dp(8))
            c.background = rounded(Pal.field, dp(16).toFloat())
            c.setOnClickListener { eModel.setText(id) }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = dp(8)
            chips.addView(c, lp)
        }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chips)
        add(hs, 8)

        small("Максимум токенов в ответе")
        eMax = field("4096", Store.maxTokens(this).toString())
        eMax.inputType = InputType.TYPE_CLASS_NUMBER
        add(eMax, 4)

        small("Температура 0–1 (необязательно, пусто — по умолчанию модели)")
        eTemp = field("не задана", Store.temp(this))
        eTemp.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        add(eTemp, 4)

        small("Системная инструкция (необязательно)")
        eSys = field("Например: отвечай кратко и по-русски", Store.system(this))
        eSys.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        eSys.minLines = 3
        eSys.gravity = Gravity.TOP
        add(eSys, 4)

        cbStream = check("Показывать ответ по мере генерации", Store.stream(this))
        add(cbStream, 8)

        cbWeb = check("Веб-поиск Claude (работает только с официальным API Anthropic)", Store.web(this))
        add(cbWeb, 4)

        // --- Вид ---
        head("Вид")
        lblSize = label("", 14f, Pal.sub, false)
        add(lblSize, 8)
        seek = SeekBar(this)
        seek.max = 22
        seek.progress = Store.textSize(this) - 10
        lblSize.text = "Размер текста: " + Store.textSize(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                lblSize.text = "Размер текста: " + (p + 10)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        add(seek, 4)
        cbDark = check("Тёмная тема", Store.dark(this))
        add(cbDark, 8)

        // --- Действия ---
        add(button("Проверить ключ", false) { test() }, 28)
        result = label("", 14f, Pal.sub, false)
        result.setTextIsSelectable(true)
        add(result, 8)
        add(button("Сохранить", true) { save() }, 16)
        add(button("Сбросить счётчик токенов", false) {
            Store.resetTokens(this)
            Toast.makeText(this, "Счётчик сброшен", Toast.LENGTH_SHORT).show()
        }, 12)

        setContentView(root)
    }

    private fun readCfg(stream: Boolean, max: Int): Cfg {
        var url = eUrl.text.toString().trim()
        if (url.isEmpty()) url = Store.DEFAULT_URL
        var model = eModel.text.toString().trim()
        if (model.isEmpty()) model = Store.DEFAULT_MODEL
        return Cfg(url, eKey.text.toString().trim(), model, max, "", rbB.isChecked, stream)
    }

    private fun test() {
        val cfg = readCfg(false, 16)
        if (cfg.key.isEmpty()) {
            result.setTextColor(Pal.err)
            result.text = "Сначала введите ключ."
            return
        }
        result.setTextColor(Pal.sub)
        result.text = "Проверяю…"
        Thread {
            var ok = ""
            var bad = ""
            try {
                val acc = StringBuilder()
                val r = Req(cfg).run(listOf(Msg("user", "Ответь одним словом: ок"))) { acc.append(it) }
                ok = "Ключ работает. Ответ модели: " + acc.toString().trim().take(60) +
                    "\nТокены запроса: " + r.inTok + " вх / " + r.outTok + " исх"
            } catch (e: ApiError) {
                bad = e.message ?: "Неизвестная ошибка"
            } catch (e: Exception) {
                bad = "Ошибка: " + e.message
            }
            handler.post {
                if (bad.isEmpty()) {
                    result.setTextColor(Pal.text)
                    result.text = ok
                } else {
                    result.setTextColor(Pal.err)
                    result.text = bad
                }
            }
        }.start()
    }

    private fun save() {
        val cfg = readCfg(true, 0)
        val max = (eMax.text.toString().trim().toIntOrNull() ?: 4096).coerceIn(1, 128000)
        Store.saveSettings(
            this, cfg.url, cfg.key, cfg.model, max, eSys.text.toString().trim(),
            rbB.isChecked, cbStream.isChecked, seek.progress + 10, cbDark.isChecked,
            eTemp.text.toString().trim(), cbWeb.isChecked
        )
        Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        finish()
    }
}
