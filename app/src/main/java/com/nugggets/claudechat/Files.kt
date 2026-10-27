package com.nugggets.claudechat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Приём файлов: превращает выбранный файл в вложение, которое можно отправить в API. */
object Files {
    const val MAX_PDF = 25L * 1024 * 1024
    const val MAX_TEXT_CHARS = 300000
    const val MAX_SIDE = 1568

    fun nameOf(c: Context, uri: Uri): String {
        var n = ""
        try {
            val cur = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) n = cur.getString(0) ?: ""
                } finally {
                    cur.close()
                }
            }
        } catch (e: Exception) {
        }
        if (n.isBlank()) n = uri.lastPathSegment?.substringAfterLast('/') ?: "файл"
        return n
    }

    /** Читает файл и сохраняет в папку вложений. Бросает Exception с понятным текстом. */
    fun readAtt(c: Context, uri: Uri): Att {
        val name = nameOf(c, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        var mime = c.contentResolver.getType(uri) ?: ""
        if (mime.isBlank() || mime == "application/octet-stream") {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: mime
        }
        val id = System.nanoTime().toString()
        val dir = Store.attDir(c)
        return when {
            mime.startsWith("image/") -> importImage(c, uri, name, id, dir)
            mime == "application/pdf" || ext == "pdf" -> importPdf(c, uri, name, id, dir)
            ext == "docx" -> importOffice(c, uri, name, id, dir, true)
            ext == "pptx" -> importOffice(c, uri, name, id, dir, false)
            ext == "zip" -> importZip(c, uri, name, id, dir)
            else -> importText(c, uri, name, id, dir)
        }
    }

    private fun open(c: Context, uri: Uri, name: String): InputStream {
        return c.contentResolver.openInputStream(uri) ?: throw Exception("Не удалось открыть файл: $name")
    }

    // ---------- изображения ----------

    private fun exifMatrix(c: Context, uri: Uri): Matrix? {
        var o = ExifInterface.ORIENTATION_NORMAL
        try {
            val s = c.contentResolver.openInputStream(uri)
            if (s != null) {
                try {
                    o = ExifInterface(s).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } finally {
                    s.close()
                }
            }
        } catch (e: Exception) {
        }
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            else -> return null
        }
        return m
    }

    private fun importImage(c: Context, uri: Uri, name: String, id: String, dir: File): Att {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        open(c, uri, name).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw Exception("Не удалось прочитать изображение: $name")
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE * 2 || bounds.outHeight / sample > MAX_SIDE * 2) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        var bmp: Bitmap = open(c, uri, name).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw Exception("Не удалось прочитать изображение: $name")

        val mx = exifMatrix(c, uri)
        if (mx != null) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        val big = maxOf(bmp.width, bmp.height)
        if (big > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / big
            val r = Bitmap.createScaledBitmap(
                bmp, maxOf(1, (bmp.width * k).toInt()), maxOf(1, (bmp.height * k).toInt()), true
            )
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        // JPEG не умеет прозрачность: подкладываем белый фон
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.WHITE)
        cv.drawBitmap(bmp, 0f, 0f, null)
        bmp.recycle()

        val f = File(dir, "$id.jpg")
        try {
            f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } finally {
            out.recycle()
        }
        return Att(name, "image/jpeg", f.name)
    }

    // ---------- PDF ----------

    private fun importPdf(c: Context, uri: Uri, name: String, id: String, dir: File): Att {
        val f = File(dir, "$id.pdf")
        try {
            var total = 0L
            open(c, uri, name).use { ins ->
                f.outputStream().use { o ->
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_PDF) throw Exception("PDF больше 25 МБ: $name")
                        o.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) throw Exception("Файл пустой: $name")
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        return Att(name, "application/pdf", f.name)
    }

    // ---------- текст ----------

    private fun readUpTo(s: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var total = 0
        while (total < max) {
            val n = s.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun saveText(dir: File, id: String, name: String, text: String): Att {
        var t = text
        if (t.length > MAX_TEXT_CHARS) {
            t = t.substring(0, MAX_TEXT_CHARS) + "\n\n[…файл обрезан: показаны первые " + MAX_TEXT_CHARS + " символов]"
        }
        if (t.isBlank()) throw Exception("В файле нет текста: $name")
        val f = File(dir, "$id.txt")
        f.writeText(t, Charsets.UTF_8)
        return Att(name, "text/plain", f.name)
    }

    private fun importText(c: Context, uri: Uri, name: String, id: String, dir: File): Att {
        val bytes = open(c, uri, name).use { readUpTo(it, 4 * 1024 * 1024) }
        val head = minOf(bytes.size, 8000)
        for (i in 0 until head) {
            if (bytes[i].toInt() == 0) {
                throw Exception(
                    "Формат не поддерживается: $name. Можно прикреплять изображения, PDF, " +
                        "текст и код, docx, pptx, zip (таблицы xlsx сохраните как CSV)."
                )
            }
        }
        return saveText(dir, id, name, String(bytes, Charsets.UTF_8))
    }

    // ---------- zip ----------

    private fun importZip(c: Context, uri: Uri, name: String, id: String, dir: File): Att {
        val skipDirs = listOf(".git/", "build/", ".gradle/", "node_modules/", "__pycache__/", ".idea/")
        val index = StringBuilder()
        val body = StringBuilder()
        var skipped = 0
        open(c, uri, name).use { raw ->
            val z = ZipInputStream(raw)
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val en = e.name
                if (skipDirs.any { en.contains(it) }) {
                    skipped++
                    continue
                }
                val bytes = readUpTo(z, 1024 * 1024)
                var binary = false
                for (k in 0 until minOf(bytes.size, 8000)) {
                    if (bytes[k].toInt() == 0) {
                        binary = true
                        break
                    }
                }
                if (binary) {
                    index.append(en).append("  (бинарный файл, пропущен)\n")
                    continue
                }
                val txt = String(bytes, Charsets.UTF_8)
                if (body.length + txt.length > MAX_TEXT_CHARS) {
                    index.append(en).append("  (не включён: лимит размера)\n")
                    continue
                }
                index.append(en).append("\n")
                body.append("===== ").append(en).append(" =====\n").append(txt).append("\n\n")
            }
        }
        if (body.isEmpty()) throw Exception("В архиве нет текстовых файлов: $name")
        val sb = StringBuilder()
        sb.append("Архив: ").append(name).append("\n\nСписок файлов:\n").append(index)
        if (skipped > 0) sb.append("Пропущено служебных файлов (.git, build и т.п.): ").append(skipped).append("\n")
        sb.append("\n").append(body)
        return saveText(dir, id, name, sb.toString())
    }

    // ---------- docx / pptx ----------

    private fun xmlText(xml: String, paraEnd: String, tabTag: String, brTag: String): String {
        var x = xml.replace(paraEnd, "\n").replace(tabTag, "\t").replace(brTag, "\n")
        x = x.replace(Regex("<[^>]+>"), "")
        x = x.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
        return x.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun importOffice(c: Context, uri: Uri, name: String, id: String, dir: File, docx: Boolean): Att {
        val parts = java.util.TreeMap<Int, String>()
        val slideRe = Regex("ppt/slides/slide(\\d+)\\.xml")
        open(c, uri, name).use { raw ->
            val z = ZipInputStream(raw)
            while (true) {
                val e = z.nextEntry ?: break
                val en = e.name
                if (docx && en == "word/document.xml") {
                    parts[0] = String(readUpTo(z, 20 * 1024 * 1024), Charsets.UTF_8)
                } else if (!docx) {
                    val m = slideRe.matchEntire(en)
                    if (m != null) {
                        parts[m.groupValues[1].toInt()] = String(readUpTo(z, 5 * 1024 * 1024), Charsets.UTF_8)
                    }
                }
            }
        }
        if (parts.isEmpty()) throw Exception("Не удалось прочитать файл: $name")
        val sb = StringBuilder()
        for ((k, v) in parts) {
            if (docx) {
                sb.append(xmlText(v, "</w:p>", "<w:tab/>", "<w:br/>"))
            } else {
                sb.append("## Слайд ").append(k).append("\n")
                sb.append(xmlText(v, "</a:p>", "<a:tab/>", "<a:br/>")).append("\n\n")
            }
        }
        return saveText(dir, id, name, sb.toString())
    }

    // ---------- обслуживание ----------

    fun drop(c: Context, a: Att) {
        try {
            File(Store.attDir(c), a.file).delete()
        } catch (e: Exception) {
        }
    }

    fun dropAll(c: Context, list: List<Att>) {
        for (a in list) drop(c, a)
    }

    fun safeName(title: String): String {
        val s = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(40)
        return if (s.isEmpty()) "chat" else s
    }

    fun chatText(chat: Chat): String {
        val sb = StringBuilder()
        sb.append("# ").append(chat.title).append("\n\n")
        for (m in chat.msgs) {
            if (m.text.isEmpty() && m.atts.isEmpty()) continue
            sb.append(if (m.role == "user") "## Вы\n\n" else "## Claude\n\n")
            if (m.atts.isNotEmpty()) {
                sb.append("Вложения: ").append(m.atts.joinToString(", ") { it.name }).append("\n\n")
            }
            if (m.text.isNotEmpty()) sb.append(m.text).append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }
}
