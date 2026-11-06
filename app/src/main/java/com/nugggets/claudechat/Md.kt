package com.nugggets.claudechat

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.content.res.Resources
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StrikethroughSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Мини-разметка: заголовки, **жирный**, `код`, блоки кода, списки. */
object Md {
    private val inlineRe = Regex("\\*\\*(.+?)\\*\\*|`([^`\\n]+)`|(?<![\\w*])\\*([^*\\s][^*\\n]*?)\\*(?![\\w*])|~~(.+?)~~")
    private val numRe = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val sepCellRe = Regex("^:?-{2,}:?$")
    private const val TBL = "\u0001"
    private val quoteRe = Regex("^>\\s?(.*)$")
    private val headRe = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRe = Regex("^(\\s*)[-*]\\s+(.*)$")
    private val ruleRe = Regex("^\\s*[-*_]{3,}\\s*$")
    private const val FLAG = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

    private fun px(dp: Int): Int = (dp * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

    private fun cells(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split("|").map { it.trim() }
    }

    /** Таблицы Markdown превращаем в выровненный моноширинный текст; такие строки помечаем маркером. */
    private fun prepTables(src: List<String>): List<String> {
        val out = mutableListOf<String>()
        var inCode = false
        var i = 0
        while (i < src.size) {
            val raw = src[i]
            if (raw.trimStart().startsWith("```")) {
                inCode = !inCode
                out.add(raw)
                i++
                continue
            }
            if (!inCode && raw.trimStart().startsWith("|")) {
                var j = i
                val rows = mutableListOf<List<String>>()
                while (j < src.size && src[j].trimStart().startsWith("|")) {
                    rows.add(cells(src[j]))
                    j++
                }
                val head = rows.firstOrNull { r -> !r.all { sepCellRe.matches(it) || it.isEmpty() } }
                val body = rows.filter { r -> !r.all { sepCellRe.matches(it) || it.isEmpty() } }
                if (head != null && body.isNotEmpty()) {
                    val cols = body.maxOf { it.size }
                    val w = IntArray(cols)
                    for (r in body) for (k in r.indices) w[k] = maxOf(w[k], r[k].length)
                    for ((n, r) in body.withIndex()) {
                        val sbl = StringBuilder()
                        for (k in 0 until cols) {
                            if (k > 0) sbl.append(" │ ")
                            sbl.append((r.getOrElse(k) { "" }).padEnd(w[k]))
                        }
                        out.add(TBL + sbl.toString().trimEnd())
                        if (n == 0) out.add(TBL + w.joinToString("─┼─") { "─".repeat(it) })
                    }
                    i = j
                    continue
                }
            }
            out.add(raw)
            i++
        }
        return out
    }

    fun render(src: String): CharSequence {
        val sb = SpannableStringBuilder()
        var inCode = false
        val lines = prepTables(src.split("\n"))
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
            if (raw.startsWith(TBL)) {
                val s = sb.length
                sb.append(raw.substring(1))
                sb.setSpan(TypefaceSpan("monospace"), s, sb.length, FLAG)
                sb.setSpan(RelativeSizeSpan(0.85f), s, sb.length, FLAG)
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
                val s = sb.length
                val lvl = b.groupValues[1].replace("\t", "  ").length / 2
                sb.append("• ")
                appendInline(sb, b.groupValues[2])
                sb.append("\n")
                val first = px(4 + lvl * 16)
                sb.setSpan(LeadingMarginSpan.Standard(first, first + px(16)), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            val nm = numRe.find(raw)
            if (nm != null) {
                val s = sb.length
                val lvl = nm.groupValues[1].replace("\t", "  ").length / 2
                sb.append(nm.groupValues[2]).append(". ")
                appendInline(sb, nm.groupValues[3])
                sb.append("\n")
                val first = px(4 + lvl * 16)
                sb.setSpan(LeadingMarginSpan.Standard(first, first + px(22)), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            val strike = m.groups[4]
            val s = sb.length
            if (strike != null) {
                sb.append(strike.value)
                sb.setSpan(StrikethroughSpan(), s, sb.length, FLAG)
            } else if (ital != null) {
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
