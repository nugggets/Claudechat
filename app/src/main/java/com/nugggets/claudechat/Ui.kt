package com.nugggets.claudechat

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Палитра в стиле Claude Code: тёмный терминальный фон и акцент «Claude orange». */
object Pal {
    var dark = true
    val bg: Int get() = if (dark) 0xFF1A1A1A.toInt() else 0xFFFAF9F5.toInt()
    val bar: Int get() = if (dark) 0xFF1F1F1F.toInt() else 0xFFF0EEE6.toInt()
    val bot: Int get() = if (dark) 0xFF242424.toInt() else 0xFFFFFFFF.toInt()
    val user: Int get() = 0xFFD77757.toInt()
    val accent: Int get() = user
    val text: Int get() = if (dark) 0xFFE8E6E3.toInt() else 0xFF191919.toInt()
    val sub: Int get() = if (dark) 0xFF9A9A9A.toInt() else 0xFF6B6B6B.toInt()
    val code: Int get() = if (dark) 0xFF101010.toInt() else 0xFFE8E4DA.toInt()
    val field: Int get() = if (dark) 0xFF262626.toInt() else 0xFFFFFFFF.toInt()
    val err: Int get() = if (dark) 0xFFFF6B6B.toInt() else 0xFFB3261E.toInt()
    val ok: Int get() = if (dark) 0xFF8FD88F.toInt() else 0xFF2E7D32.toInt()
    val del: Int get() = if (dark) 0xFFFF9C9C.toInt() else 0xFFB3261E.toInt()
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
    window.statusBarColor = Pal.bar
    window.navigationBarColor = Pal.bar
    val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    val f = window.decorView.systemUiVisibility
    window.decorView.systemUiVisibility = if (Pal.dark) (f and light.inv()) else (f or light)
}

/** Заглушка на высоту статус-бара: верхняя панель больше не уезжает под системную строку. */
fun Activity.statusBarSpacer(): View {
    val v = View(this)
    var h = 0
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    if (id > 0) h = resources.getDimensionPixelSize(id)
    if (h <= 0) h = dp(24)
    v.setBackgroundColor(Pal.bar)
    v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    return v
}

fun Context.label(text: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = sizeSp
    t.setTextColor(color)
    if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
    return t
}

/** Верхняя панель: заглушка под статус-бар, слева кнопка, по центру заголовок, справа кнопки. */
fun Activity.topBar(titleText: String, left: String, onLeft: () -> Unit): LinearLayout {
    val wrap = LinearLayout(this)
    wrap.orientation = LinearLayout.VERTICAL
    wrap.setBackgroundColor(Pal.bar)

    wrap.addView(statusBarSpacer(), LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))

    val bar = LinearLayout(this)
    bar.orientation = LinearLayout.HORIZONTAL
    bar.gravity = Gravity.CENTER_VERTICAL
    bar.setBackgroundColor(Pal.bar)
    bar.setPadding(dp(4), dp(4), dp(4), dp(4))
    val b = label(left, 22f, Pal.text, true)
    b.gravity = Gravity.CENTER
    b.setPadding(dp(14), dp(8), dp(14), dp(8))
    b.setOnClickListener { onLeft() }
    bar.addView(b)
    val t = label(titleText, 18f, Pal.text, true)
    t.maxLines = 1
    bar.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    wrap.addView(bar, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ))
    return wrap
}
