package com.nugggets.claudechat

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Мини-разметка: заголовки, **жирный**, `код`, блоки кода, списки. */
object Md {
    private val inlineRe = Regex("\\*\\*(.+?)\\*\\*|`([^`\\n]+)`|(?<![\\w*])\\*([^*\\s][^*\\n]*?)\\*(?![\\w*])")
    private val quoteRe = Regex("^>\\s?(.*)$")
    private val headRe = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRe = Regex("^(\\s*)[-*]\\s+(.*)$")
    private val ruleRe = Regex("^\\s*[-*_]{3,}\\s*$")
    private const val FLAG = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

    fun render(src: String): CharSequence {
        val sb = SpannableStringBuilder()
        var inCode = false
        val lines = src.split("\n")
        for (raw in lines) {
            if (raw.trimStart().startsWith("```")) {
                inCode = !inCode
                continue
            }
            if (inCode) {
                val s = sb.length
                sb.append(if (raw.isEmpty()) " " else raw)
                sb.setSpan(TypefaceSpan("monospace"), s, sb.length, FLAG)
                sb.setSpan(BackgroundColorSpan(Pal.code), s, sb.length, FLAG)
                sb.setSpan(RelativeSizeSpan(0.9f), s, sb.length, FLAG)
                sb.append("\n")
                continue
            }
            if (ruleRe.matches(raw)) {
                sb.append("────────────\n")
                continue
            }
            val h = headRe.find(raw)
            if (h != null) {
                val s = sb.length
                appendInline(sb, h.groupValues[2])
                sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, FLAG)
                val k = h.groupValues[1].length
                val scale = if (k == 1) 1.3f else if (k == 2) 1.2f else 1.08f
                sb.setSpan(RelativeSizeSpan(scale), s, sb.length, FLAG)
                sb.append("\n")
                continue
            }
            val b = bulletRe.find(raw)
            if (b != null) {
                sb.append(b.groupValues[1]).append("• ")
                appendInline(sb, b.groupValues[2])
                sb.append("\n")
                continue
            }
            val q = quoteRe.find(raw)
            if (q != null) {
                val s = sb.length
                sb.append("▎ ")
                appendInline(sb, q.groupValues[1])
                sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, FLAG)
                sb.append("\n")
                continue
            }
            if (raw.trimStart().startsWith("|")) {
                val s = sb.length
                sb.append(raw)
                sb.setSpan(TypefaceSpan("monospace"), s, sb.length, FLAG)
                sb.setSpan(RelativeSizeSpan(0.9f), s, sb.length, FLAG)
                sb.append("\n")
                continue
            }
            appendInline(sb, raw)
            sb.append("\n")
        }
        var n = sb.length
        while (n > 0 && sb[n - 1] == '\n') n--
        if (n < sb.length) sb.delete(n, sb.length)
        return sb
    }

    private fun appendInline(sb: SpannableStringBuilder, line: String) {
        var last = 0
        for (m in inlineRe.findAll(line)) {
            sb.append(line.substring(last, m.range.first))
            val bold = m.groups[1]
            val code = m.groups[2]
            val ital = m.groups[3]
            val s = sb.length
            if (ital != null) {
                sb.append(ital.value)
                sb.setSpan(StyleSpan(Typeface.ITALIC), s, sb.length, FLAG)
            } else if (bold != null) {
                sb.append(bold.value)
                sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, FLAG)
            } else if (code != null) {
                sb.append(code.value)
                sb.setSpan(TypefaceSpan("monospace"), s, sb.length, FLAG)
                sb.setSpan(BackgroundColorSpan(Pal.code), s, sb.length, FLAG)
            }
            last = m.range.last + 1
        }
        sb.append(line.substring(last))
    }
}
