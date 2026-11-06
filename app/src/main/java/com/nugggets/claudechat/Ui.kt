package com.nugggets.claudechat

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

object Pal {
    var dark = true
    // Палитра как в приложении Claude: тёплые нейтральные тона и терракотовый акцент
    val bg: Int get() = if (dark) 0xFF262624.toInt() else 0xFFFAF9F5.toInt()
    val bar: Int get() = bg
    /** Ответ ассистента: без пузыря, прямо на фоне. */
    val bot: Int get() = bg
    /** Акцент (кнопка отправки, главные кнопки). */
    val user: Int get() = 0xFFD97757.toInt()
    /** Пузырь сообщения пользователя. */
    val userBubble: Int get() = if (dark) 0xFF3A3938.toInt() else 0xFFF0EEE6.toInt()
    val userText: Int get() = text
    val text: Int get() = if (dark) 0xFFF2F0E8.toInt() else 0xFF141413.toInt()
    val sub: Int get() = if (dark) 0xFFA8A69E.toInt() else 0xFF73726C.toInt()
    val code: Int get() = if (dark) 0xFF1F1E1D.toInt() else 0xFFF0EEE6.toInt()
    val field: Int get() = if (dark) 0xFF30302E.toInt() else 0xFFFFFFFF.toInt()
    val err: Int get() = if (dark) 0xFFFF8A80.toInt() else 0xFFB3261E.toInt()
    val white: Int = 0xFFFFFFFF.toInt()

    fun load(c: Context) {
        dark = Store.dark(c)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radius: Float): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = radius
    return g
}

fun Activity.styleWindow() {
    window.setBackgroundDrawable(ColorDrawable(Pal.bg))
    window.statusBarColor = Pal.bg
    window.navigationBarColor = Pal.bg
    val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val f = window.decorView.systemUiVisibility
    window.decorView.systemUiVisibility = if (Pal.dark) (f and light.inv()) else (f or light)
}

fun Context.label(text: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = sizeSp
    t.setTextColor(color)
    if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
    return t
}

/** Верхняя панель: слева кнопка, по центру заголовок, справа кнопки. */
fun Activity.topBar(titleText: String, left: String, onLeft: () -> Unit): LinearLayout {
    val bar = LinearLayout(this)
    bar.orientation = LinearLayout.HORIZONTAL
    bar.gravity = Gravity.CENTER_VERTICAL
    bar.setBackgroundColor(Pal.bar)
    bar.setPadding(dp(4), dp(4) + dp(Store.topPad(this)), dp(4), dp(4))
    val b = label(left, 22f, Pal.text, true)
    b.gravity = Gravity.CENTER
    b.setPadding(dp(14), dp(8), dp(14), dp(8))
    b.setOnClickListener { onLeft() }
    bar.addView(b)
    val t = label(titleText, 18f, Pal.text, true)
    t.maxLines = 1
    bar.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    return bar
}
