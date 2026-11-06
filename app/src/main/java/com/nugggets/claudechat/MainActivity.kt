package com.nugggets.claudechat

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private const val RQ_FILE = 11
private const val RQ_CAM = 12
private const val RQ_VOICE = 13
private const val RQ_SAVE = 14
private const val RQ_SAVE_ZIP = 15
private const val MAX_PENDING = 10

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var chat: Chat = Store.newChat()
    private var sending = false
    private var req: Req? = null
    private var gen = 0
    private var note = ""
    private var liveStatus = ""
    private var startTimeMs = 0L
    private var draft = ""
    private var built = false

    private val pending = mutableListOf<Att>()
    private var importing = 0
    private var importingStatus = ""
    private var camUri: Uri? = null
    private var saveText = ""
    private var pendingZipOverrides: Map<String, String> = emptyMap()
    private val thumbs = HashMap<String, Bitmap?>()

    @Volatile
    private var uiPending = false

    private lateinit var scroll: ScrollView
    private lateinit var box: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var info: TextView
    private lateinit var titleView: TextView
    private lateinit var pendScroll: HorizontalScrollView
    private lateinit var pendBox: LinearLayout
    private var liveBubble: TextView? = null
    private var liveStatusView: TextView? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (sending) {
                updateLiveStatusView()
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

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
        if (id.isNotEmpty() && chat.id == id && chat.msgs.isNotEmpty()) {
            val loaded = Store.load(this, id)
            if (loaded != null) chat = loaded
            return
        }
        val loaded = if (id.isNotEmpty()) Store.load(this, id) else null
        chat = loaded ?: Store.newChat()
        note = ""
    }

    // ---------- входящие данные из других приложений ----------

    @Suppress("DEPRECATION")
    private fun streamUri(i: Intent): Uri? = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamUris(i: Intent): List<Uri> =
        i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val a = i.action
        if (a != Intent.ACTION_SEND && a != Intent.ACTION_SEND_MULTIPLE) return
        val uris = ArrayList<Uri>()
        if (a == Intent.ACTION_SEND) {
            val u = streamUri(i)
            if (u != null) uris.add(u)
        } else {
            uris.addAll(streamUris(i))
        }
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        if (!text.isNullOrEmpty() && uris.isEmpty()) {
            if (built) appendInput(text) else draft = if (draft.isEmpty()) text else draft + "\n" + text
        }
        if (uris.isNotEmpty()) addUris(uris, null)
    }

    // ---------- интерфейс ----------

    private fun barBtn(t: String, a: () -> Unit): TextView {
        val b = label(t, 22f, Pal.text, true)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(11), dp(8), dp(11), dp(8))
        b.setOnClickListener { a() }
        return b
    }

    private fun inputBtn(t: String, a: () -> Unit): TextView {
        val b = label(t, 22f, Pal.text, false)
        b.gravity = Gravity.CENTER
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
        bar.setPadding(dp(4), dp(4) + dp(Store.topPad(this)), dp(4), dp(0))
        bar.addView(barBtn("☰") { openHistory() })
        titleView = label("", 17f, Pal.text, true)
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        titleView.setOnClickListener { renameChat() }
        bar.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(barBtn("+") { newChat() })
        bar.addView(barBtn("🐞") { showDiag() })
        bar.addView(barBtn("⋮") { chatMenu() })
        bar.addView(barBtn("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        info = label("", 12f, Pal.sub, false)
        info.setPadding(dp(16), dp(2), dp(16), dp(8))
        info.setBackgroundColor(Pal.bar)
        info.maxLines = 1
        info.ellipsize = TextUtils.TruncateAt.END
        info.setOnClickListener { pickModel() }
        root.addView(info, LinearLayout.LayoutParams(MATCH, WRAP))

        scroll = ScrollView(this)
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(10), dp(4), dp(10), dp(12))
        scroll.addView(box, ViewGroup.LayoutParams(MATCH, WRAP))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        pendScroll = HorizontalScrollView(this)
        pendScroll.isHorizontalScrollBarEnabled = false
        pendScroll.setBackgroundColor(Pal.bar)
        pendBox = LinearLayout(this)
        pendBox.orientation = LinearLayout.HORIZONTAL
        pendBox.setPadding(dp(8), dp(8), dp(8), dp(0))
        pendScroll.addView(pendBox)
        root.addView(pendScroll, LinearLayout.LayoutParams(MATCH, WRAP))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.BOTTOM
        row.setBackgroundColor(Pal.bar)
        row.setPadding(dp(4), dp(8), dp(8), dp(8))

        row.addView(inputBtn("📎") { attachMenu() }, LinearLayout.LayoutParams(dp(42), dp(46)))

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

        row.addView(inputBtn("🎤") { voice() }, LinearLayout.LayoutParams(dp(42), dp(46)))

        sendBtn = label("↑", 22f, Pal.white, true)
        sendBtn.gravity = Gravity.CENTER
        val oval = GradientDrawable()
        oval.shape = GradientDrawable.OVAL
        oval.setColor(Pal.user)
        sendBtn.background = oval
        sendBtn.setOnClickListener { if (sending) stop() else send() }
        val slp = LinearLayout.LayoutParams(dp(46), dp(46))
        slp.leftMargin = dp(4)
        row.addView(sendBtn, slp)
        root.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)
        built = true
        input.setText(draft)
        updateTitle()
        updateInfo()
        updateSendBtn()
        renderPending()
        render()
    }

    private fun updateTitle() {
        titleView.text = if (chat.msgs.isEmpty()) "Claude Chat" else chat.title
    }

    private fun fmt(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

    private fun updateInfo() {
        val web = if (Store.web(this)) "  ·  🔎" else ""
        val sys = if (chat.sys.isNotBlank()) "  ·  своя инструкция" else ""
        info.text = Store.model(this) + web + sys + "  ·  токены: " + fmt(Store.tokIn(this)) +
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

    private fun pillBtn(t: String, filled: Boolean, a: () -> Unit): TextView {
        val b = label(t, 13f, if (filled) Pal.white else Pal.text, true)
        b.setPadding(dp(12), dp(7), dp(12), dp(7))
        b.background = rounded(if (filled) Pal.user else Pal.field, dp(10).toFloat())
        b.setOnClickListener { a() }
        return b
    }

    private fun updateLiveStatusView() {
        val sv = liveStatusView ?: return
        val sec = ((System.currentTimeMillis() - startTimeMs) / 1000).coerceAtLeast(0)
        val st = if (liveStatus.isNotEmpty()) liveStatus else "ИИ обрабатывает запрос…"
        sv.text = "$st ($sec с)"
    }

    private fun render() {
        box.removeAllViews()
        liveBubble = null
        liveStatusView = null
        val size = Store.textSize(this).toFloat()

        if (chat.msgs.isEmpty() && note.isEmpty()) {
            val hint = label(
                "Напишите первое сообщение или прикрепите файл (📎): фото, PDF, текст, код, docx, pptx, xlsx, zip.\n\n" +
                    "Встроен сборщик проектов: прикрепите ZIP-архив приложения, попросите ИИ внести изменения, " +
                    "и прямо под ответом появится кнопка «📦 Скачать готовый ZIP» + команда для Termux!",
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
            addBubble(i, m, i == chat.msgs.size - 1, size)
        }

        val lastIsUser = !sending && chat.msgs.isNotEmpty() && chat.msgs[chat.msgs.size - 1].role == "user"
        if (note.isNotEmpty() || lastIsUser) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(14), dp(12), dp(14), dp(12))
            card.background = rounded(Pal.field, dp(12).toFloat())

            val msgText = if (note.isNotEmpty()) {
                note
            } else {
                "Ответ на последнее сообщение ещё не получен."
            }
            val n = label(msgText, 14f, Pal.err, false)
            n.setTextIsSelectable(true)
            card.addView(n)

            if (lastIsUser) {
                val btnRow = LinearLayout(this)
                btnRow.orientation = LinearLayout.HORIZONTAL
                btnRow.setPadding(0, dp(8), 0, 0)

                val retryBtn = label("🔄 Получить ответ", 14f, Pal.white, true)
                retryBtn.setPadding(dp(14), dp(8), dp(14), dp(8))
                retryBtn.background = rounded(Pal.user, dp(10).toFloat())
                retryBtn.setOnClickListener { start() }
                btnRow.addView(retryBtn)

                val modelBtn = label("Модель", 14f, Pal.text, false)
                modelBtn.setPadding(dp(12), dp(8), dp(12), dp(8))
                modelBtn.background = rounded(Pal.bg, dp(10).toFloat())
                val mlp = LinearLayout.LayoutParams(WRAP, WRAP)
                mlp.leftMargin = dp(8)
                modelBtn.setOnClickListener { pickModel() }
                btnRow.addView(modelBtn, mlp)

                val diagBtn = label("🐞", 14f, Pal.text, true)
                diagBtn.maxLines = 1
                diagBtn.setPadding(dp(12), dp(8), dp(12), dp(8))
                diagBtn.background = rounded(Pal.bg, dp(10).toFloat())
                val dlp = LinearLayout.LayoutParams(WRAP, WRAP)
                dlp.leftMargin = dp(8)
                diagBtn.setOnClickListener { showDiag() }
                btnRow.addView(diagBtn, dlp)

                card.addView(btnRow)
            }

            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.topMargin = dp(10)
            box.addView(card, lp)
        }
        scrollDown()
    }

    private fun thumb(a: Att): Bitmap? {
        return thumbs.getOrPut(a.file) {
            try {
                val f = File(Store.attDir(this), a.file)
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeFile(f.path, o)
                var s = 1
                while (o.outWidth / s > 800 || o.outHeight / s > 800) s *= 2
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = s
                BitmapFactory.decodeFile(f.path, o2)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun previewAtt(a: Att) {
        val f = File(Store.attDir(this), a.file)
        if (!f.exists()) {
            toast("Файл вложения не найден")
            return
        }
        if (a.isImage) {
            val bmp = thumb(a)
            if (bmp != null) showImage(bmp)
            return
        }
        if (a.isPdf) {
            toast("PDF-документ (${f.length() / 1024} КБ)")
            return
        }
        val txt = try {
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            "Не удалось прочитать содержимое"
        }
        val sv = ScrollView(this)
        val tv = label(txt.take(15000) + if (txt.length > 15000) "\n\n…(всего ${txt.length} симв.)" else "", 12f, Pal.text, false)
        tv.setPadding(dp(16), dp(12), dp(16), dp(12))
        tv.setTextIsSelectable(true)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(a.name + " (${txt.length} симв.)")
            .setView(sv)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun attView(a: Att): View {
        if (a.isImage) {
            val bmp = thumb(a)
            if (bmp != null) {
                val iv = ImageView(this)
                iv.setImageBitmap(bmp)
                iv.adjustViewBounds = true
                iv.maxHeight = dp(260)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.setOnClickListener { showImage(bmp) }
                val lp = LinearLayout.LayoutParams(dp(220), WRAP)
                lp.bottomMargin = dp(6)
                iv.layoutParams = lp
                return iv
            }
        }
        val icon = when {
            a.isImage -> "🖼 "
            a.name.endsWith(".zip", true) -> "📦 "
            else -> "📄 "
        }
        val f = File(Store.attDir(this), a.file)
        val sizeInfo = if (f.exists()) " (${maxOf(1L, f.length() / 1024)} КБ)" else ""
        val t = label(icon + a.name + sizeInfo, 14f, Pal.text, true)
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END
        t.setPadding(dp(10), dp(6), dp(10), dp(6))
        t.background = rounded(0x22808080, dp(8).toFloat())
        t.setOnClickListener { previewAtt(a) }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.bottomMargin = dp(6)
        t.layoutParams = lp
        return t
    }

    private fun showImage(b: Bitmap) {
        val iv = ImageView(this)
        iv.setImageBitmap(b)
        iv.adjustViewBounds = true
        AlertDialog.Builder(this).setView(iv).setPositiveButton("Закрыть", null).show()
    }

    // ---------- автоматическая сборка ZIP и файлов из ответа ИИ ----------

    private fun saveZipToDownloads(zipName: String, overrides: Map<String, String>) {
        val baseZip = Builder.findBaseZipFile(Store.attDir(this), chat)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = contentResolver
                // Удаляем старый файл с тем же именем в Загрузках, если он уже есть
                try {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        arrayOf(zipName)
                    )
                } catch (e: Exception) {
                }
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, zipName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw Exception("Не удалось создать файл в Загрузках")
                var count = 0
                resolver.openOutputStream(uri)?.use { out ->
                    count = Builder.writeMergedZip(baseZip, overrides, out)
                }
                toastLong("✅ Сохранено в Загрузки: $zipName (файлов в архиве: $count)")
                Diag.log("ZIP сохранён в Загрузки: $zipName (файлов: $count, базовый архив: ${baseZip != null})")
            } else {
                @Suppress("DEPRECATION")
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dlDir.mkdirs()
                val outFile = File(dlDir, zipName)
                var count = 0
                FileOutputStream(outFile).use { out ->
                    count = Builder.writeMergedZip(baseZip, overrides, out)
                }
                toastLong("✅ Сохранено: ${outFile.absolutePath} (файлов: $count)")
                Diag.log("ZIP сохранён: ${outFile.absolutePath} (файлов: $count)")
            }
        } catch (e: Exception) {
            Diag.logErr("saveZipToDownloads", e)
            // Fallback через системный диалог выбора места сохранения
            pendingZipOverrides = overrides
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/zip"
            i.putExtra(Intent.EXTRA_TITLE, zipName)
            try {
                startActivityForResult(i, RQ_SAVE_ZIP)
            } catch (ex: Exception) {
                toastLong("Не удалось сохранить ZIP: ${e.message}")
            }
        }
    }

    private fun showExtractedFilesDialog(files: Map<String, String>, zipName: String) {
        val names = files.keys.toList()
        val labels = names.map { "$it (${files[it]?.length ?: 0} симв.)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Файлы в ответе (${names.size})")
            .setItems(labels) { _, which ->
                val path = names[which]
                val content = files[path] ?: ""
                val sv = ScrollView(this)
                val tv = label(content, 11f, Pal.text, false)
                tv.typeface = android.graphics.Typeface.MONOSPACE
                tv.setPadding(dp(14), dp(12), dp(14), dp(12))
                tv.setTextIsSelectable(true)
                sv.addView(tv)
                AlertDialog.Builder(this)
                    .setTitle(path)
                    .setView(sv)
                    .setPositiveButton("Скопировать код") { _, _ -> copy(content) }
                    .setNeutralButton("Сохранить файл") { _, _ ->
                        saveText = content
                        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        i.addCategory(Intent.CATEGORY_OPENABLE)
                        i.type = "text/plain"
                        i.putExtra(Intent.EXTRA_TITLE, path.substringAfterLast('/'))
                        try {
                            startActivityForResult(i, RQ_SAVE)
                        } catch (e: Exception) {
                            toast("Не удалось открыть сохранение")
                        }
                    }
                    .setNegativeButton("Назад") { _, _ -> showExtractedFilesDialog(files, zipName) }
                    .show()
            }
            .setPositiveButton("📦 Скачать весь ZIP ($zipName)") { _, _ ->
                saveZipToDownloads(zipName, files)
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun addBubble(idx: Int, m: Msg, isLast: Boolean, size: Float) {
        val user = m.role == "user"
        val live = sending && isLast && !user

        val tv = TextView(this)
        tv.textSize = size
        tv.setTextColor(if (user) Pal.userText else Pal.text)
        if (!user) { tv.typeface = android.graphics.Typeface.SERIF; tv.setLineSpacing(0f, 1.15f) }
        tv.setTextIsSelectable(true)
        tv.text = if (user) {
            m.text
        } else if (m.text.isEmpty()) {
            "Ожидание первых токенов от модели…"
        } else {
            Md.render(m.text)
        }
        if (live) liveBubble = tv

        val lp = LinearLayout.LayoutParams(if (live) MATCH else WRAP, WRAP)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = dp(10)
        if (user) lp.leftMargin = dp(40) else lp.rightMargin = dp(20)

        if (user && m.atts.isNotEmpty()) {
            val wrap = LinearLayout(this)
            wrap.orientation = LinearLayout.VERTICAL
            wrap.setPadding(dp(12), dp(8), dp(12), dp(6))
            wrap.background = rounded(Pal.userBubble, dp(18).toFloat())
            for (a in m.atts) wrap.addView(attView(a))
            if (m.text.isNotEmpty()) wrap.addView(tv, LinearLayout.LayoutParams(WRAP, WRAP))
            box.addView(wrap, lp)
        } else if (live) {
            val wrap = LinearLayout(this)
            wrap.orientation = LinearLayout.VERTICAL
            wrap.setPadding(dp(12), dp(10), dp(12), dp(10))
            wrap.background = rounded(Pal.bot, dp(14).toFloat())

            val statusRow = LinearLayout(this)
            statusRow.orientation = LinearLayout.HORIZONTAL
            statusRow.gravity = Gravity.CENTER_VERTICAL
            statusRow.setPadding(dp(8), dp(6), dp(10), dp(6))
            statusRow.background = rounded(Pal.code, dp(8).toFloat())

            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleSmall)
            statusRow.addView(pb, LinearLayout.LayoutParams(dp(18), dp(18)))

            val sv = label("", 12f, Pal.sub, true)
            val slp = LinearLayout.LayoutParams(0, WRAP, 1f)
            slp.leftMargin = dp(8)
            statusRow.addView(sv, slp)
            liveStatusView = sv
            updateLiveStatusView()

            val srlp = LinearLayout.LayoutParams(MATCH, WRAP)
            srlp.bottomMargin = dp(8)
            wrap.addView(statusRow, srlp)
            wrap.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
            box.addView(wrap, lp)
        } else {
            if (user) {
                tv.setPadding(dp(14), dp(10), dp(14), dp(10))
                tv.background = rounded(Pal.userBubble, dp(18).toFloat())
            } else {
                tv.setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            box.addView(tv, lp)
        }

        if (live) return

        // Если это ответ ИИ, проверяем наличие сгенерированных файлов проекта и команд Termux
        if (!user && m.text.isNotEmpty()) {
            val extractedFiles = Builder.extractFiles(m.text)
            val extractedCmds = Builder.extractCommands(m.text)
            if (extractedFiles.isNotEmpty() || extractedCmds.isNotEmpty()) {
                val devScroll = HorizontalScrollView(this)
                devScroll.isHorizontalScrollBarEnabled = false
                val devRow = LinearLayout(this)
                devRow.orientation = LinearLayout.HORIZONTAL
                devRow.setPadding(dp(2), dp(6), dp(2), dp(2))

                if (extractedFiles.isNotEmpty()) {
                    val zipName = Builder.suggestZipName(m.text, chat)
                    val baseExists = Builder.findBaseZipFile(Store.attDir(this), chat) != null
                    val zipLabel = if (baseExists) {
                        "📦 Скачать $zipName (обновлено: ${extractedFiles.size})"
                    } else {
                        "📦 Скачать $zipName (${extractedFiles.size} файл.)"
                    }
                    val zipBtn = pillBtn(zipLabel, true) {
                        saveZipToDownloads(zipName, extractedFiles)
                    }
                    val zlp = LinearLayout.LayoutParams(WRAP, WRAP)
                    zlp.rightMargin = dp(8)
                    devRow.addView(zipBtn, zlp)

                    val filesBtn = pillBtn("📂 Файлы (${extractedFiles.size})", false) {
                        showExtractedFilesDialog(extractedFiles, zipName)
                    }
                    val flp = LinearLayout.LayoutParams(WRAP, WRAP)
                    flp.rightMargin = dp(8)
                    devRow.addView(filesBtn, flp)
                }

                if (extractedCmds.isNotEmpty()) {
                    val bestCmd = extractedCmds.last()
                    val cmdBtn = pillBtn("💻 Скопировать команду Termux", false) {
                        copy(bestCmd)
                    }
                    val clp = LinearLayout.LayoutParams(WRAP, WRAP)
                    clp.rightMargin = dp(8)
                    devRow.addView(cmdBtn, clp)
                }

                devScroll.addView(devRow)
                box.addView(devScroll, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        if (user) {
            row.addView(smallBtn("Править") { editMsg(idx) })
            row.addView(smallBtn("Удалить") { deleteMsg(idx) })
            val rlp = LinearLayout.LayoutParams(WRAP, WRAP)
            rlp.gravity = Gravity.END
            box.addView(row, rlp)
        } else {
            row.addView(smallBtn("Копировать") { copy(m.text) })
            row.addView(smallBtn("Поделиться") { shareText(m.text) })
            if (isLast) row.addView(smallBtn("Повторить") { regenerate() })
            row.addView(smallBtn("Удалить") { deleteMsg(idx) })
            box.addView(row)
        }
    }

    private fun updateLast() {
        updateLiveStatusView()
        val tv = liveBubble ?: return
        if (chat.msgs.isEmpty()) return
        val m = chat.msgs[chat.msgs.size - 1]
        val atBottom = isNearBottom()
        tv.text = if (m.text.isEmpty()) "Ожидание первых токенов от модели…" else Md.render(m.text)
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

    private fun toastLong(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Claude", text))
        toast("Скопировано")
    }

    private fun shareText(text: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, text)
        try {
            startActivity(Intent.createChooser(i, "Поделиться"))
        } catch (e: Exception) {
            toast("Не удалось открыть список приложений")
        }
    }

    private fun appendInput(s: String) {
        val cur = input.text.toString()
        val sep = if (cur.isEmpty() || cur.endsWith(" ") || cur.endsWith("\n")) "" else " "
        input.setText(cur + sep + s)
        input.setSelection(input.text.length)
    }

    // ---------- вложения ----------

    private fun renderPending() {
        pendBox.removeAllViews()
        pendScroll.visibility = if (pending.isEmpty() && importing == 0) View.GONE else View.VISIBLE
        for (a in pending) {
            val icon = when {
                a.isImage -> "🖼 "
                a.name.endsWith(".zip", true) -> "📦 "
                else -> "📄 "
            }
            val c = label(icon + a.name.take(26) + "   ✕", 13f, Pal.text, false)
            c.setPadding(dp(12), dp(7), dp(12), dp(7))
            c.background = rounded(Pal.field, dp(14).toFloat())
            c.setOnClickListener {
                pending.remove(a)
                Files.drop(this, a)
                renderPending()
            }
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.rightMargin = dp(8)
            pendBox.addView(c, lp)
        }
        if (importing > 0) {
            val st = if (importingStatus.isNotEmpty()) importingStatus else "⏳ распаковка и чтение файла…"
            val c = label(st, 13f, Pal.sub, false)
            c.setPadding(dp(12), dp(7), dp(12), dp(7))
            pendBox.addView(c)
        }
    }

    private fun attachMenu() {
        if (pending.size + importing >= MAX_PENDING) {
            toast("Можно прикрепить не больше $MAX_PENDING файлов за раз")
            return
        }
        val items = arrayOf("Фото из галереи", "Сделать фото", "Файл: ZIP, PDF, код, текст, docx, pptx, xlsx")
        AlertDialog.Builder(this).setItems(items) { _, w ->
            if (w == 0) pickFiles(true) else if (w == 1) takePhoto() else pickFiles(false)
        }.show()
    }

    private fun pickFiles(images: Boolean) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = if (images) "image/*" else "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(i, RQ_FILE)
        } catch (e: Exception) {
            toast("Не найдено приложение для выбора файлов")
        }
    }

    private fun takePhoto() {
        try {
            val v = ContentValues()
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "cc_" + System.currentTimeMillis() + ".jpg")
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ClaudeChat")
            }
            val u = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw Exception("insert")
            camUri = u
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            i.putExtra(MediaStore.EXTRA_OUTPUT, u)
            startActivityForResult(i, RQ_CAM)
        } catch (e: Exception) {
            camUri = null
            toastLong("Камера недоступна. Выберите фото из галереи.")
        }
    }

    /** Обрабатывает файлы в фоне и добавляет их к будущему сообщению. */
    private fun addUris(uris: List<Uri>, cleanup: Uri?) {
        for (u in uris) {
            if (pending.size + importing >= MAX_PENDING) {
                toast("Лишние файлы пропущены: максимум $MAX_PENDING")
                break
            }
            val rawName = Files.nameOf(this, u)
            importing++
            importingStatus = "⏳ Чтение «$rawName»…"
            if (built) renderPending()
            Thread {
                var att: Att? = null
                var err: String? = null
                try {
                    att = Files.readAtt(this, u)
                } catch (e: Exception) {
                    err = e.message ?: "Не удалось прочитать файл"
                }
                if (cleanup != null) {
                    try {
                        contentResolver.delete(cleanup, null, null)
                    } catch (e: Exception) {
                    }
                }
                val a2 = att
                val e2 = err
                handler.post {
                    importing--
                    if (importing == 0) importingStatus = ""
                    if (a2 != null) pending.add(a2)
                    if (e2 != null) toastLong(e2)
                    if (built) renderPending()
                }
            }.start()
        }
    }

    private fun voice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите")
        try {
            startActivityForResult(i, RQ_VOICE)
        } catch (e: Exception) {
            toast("Голосовой ввод недоступен на этом телефоне")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RQ_FILE) {
            if (resultCode == RESULT_OK && data != null) {
                val list = ArrayList<Uri>()
                val cd = data.clipData
                if (cd != null) {
                    for (k in 0 until cd.itemCount) {
                        val u = cd.getItemAt(k).uri
                        if (u != null) list.add(u)
                    }
                } else {
                    val u = data.data
                    if (u != null) list.add(u)
                }
                addUris(list, null)
            }
        } else if (requestCode == RQ_CAM) {
            val u = camUri
            camUri = null
            if (u != null) {
                if (resultCode == RESULT_OK) {
                    addUris(listOf(u), u)
                } else {
                    try {
                        contentResolver.delete(u, null, null)
                    } catch (e: Exception) {
                    }
                }
            }
        } else if (requestCode == RQ_VOICE) {
            if (resultCode == RESULT_OK && data != null) {
                val r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val t = if (r != null && r.isNotEmpty()) r[0] else ""
                if (t.isNotEmpty() && built) appendInput(t)
            }
        } else if (requestCode == RQ_SAVE) {
            val u = data?.data
            if (resultCode == RESULT_OK && u != null) {
                try {
                    val o = contentResolver.openOutputStream(u)
                    if (o != null) {
                        o.use { it.write(saveText.toByteArray(Charsets.UTF_8)) }
                        toast("Сохранено")
                    }
                } catch (e: Exception) {
                    toastLong("Не удалось сохранить файл")
                }
            }
            saveText = ""
        } else if (requestCode == RQ_SAVE_ZIP) {
            val u = data?.data
            if (resultCode == RESULT_OK && u != null && pendingZipOverrides.isNotEmpty()) {
                try {
                    val baseZip = Builder.findBaseZipFile(Store.attDir(this), chat)
                    val o = contentResolver.openOutputStream(u)
                    if (o != null) {
                        val count = o.use { Builder.writeMergedZip(baseZip, pendingZipOverrides, it) }
                        toastLong("✅ ZIP сохранён (файлов: $count)")
                    }
                } catch (e: Exception) {
                    toastLong("Не удалось сохранить ZIP: ${e.message}")
                }
            }
            pendingZipOverrides = emptyMap()
        }
    }

    // ---------- меню и диалоги ----------

    private fun askText(title: String, initial: String, multi: Boolean, ok: (String) -> Unit) {
        val e = EditText(this)
        e.setText(initial)
        if (multi) {
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            e.minLines = 3
            e.gravity = Gravity.TOP
        } else {
            e.inputType = InputType.TYPE_CLASS_TEXT
            e.setSingleLine(true)
        }
        val fr = FrameLayout(this)
        fr.setPadding(dp(20), dp(8), dp(20), dp(0))
        fr.addView(e)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(fr)
            .setPositiveButton("OK") { _, _ -> ok(e.text.toString().trim()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renameChat() {
        if (chat.msgs.isEmpty()) return
        askText("Название чата", chat.title, false) { t ->
            if (t.isNotEmpty()) {
                chat.title = t
                Store.save(this, chat)
                updateTitle()
            }
        }
    }

    private fun pickModel() {
        val ids = Store.modelIds(this)
        AlertDialog.Builder(this).setTitle("Модель").setItems(ids.toTypedArray()) { _, w ->
            Store.setModel(this, ids[w])
            updateInfo()
        }.show()
    }

    private fun showDiag() {
        val report = Diag.buildReport(this, chat, pending, note)
        val sv = ScrollView(this)
        val tv = label(report, 11f, Pal.text, false)
        tv.setPadding(dp(16), dp(12), dp(16), dp(12))
        tv.setTextIsSelectable(true)
        tv.typeface = android.graphics.Typeface.MONOSPACE
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("🐞 Диагностика для ИИ")
            .setView(sv)
            .setPositiveButton("Скопировать отчёт") { _, _ ->
                copy(report)
            }
            .setNeutralButton("Очистить лог") { _, _ ->
                Diag.clear()
                toast("Журнал очищен")
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun chatMenu() {
        val items = arrayOf(
            "🐞 Диагностика (скопировать отчёт для ИИ)",
            "Переименовать чат",
            "Инструкция для этого чата",
            "Поделиться чатом",
            "Сохранить чат в файл (.md)",
            "Копировать весь чат",
            "Удалить чат"
        )
        AlertDialog.Builder(this).setItems(items) { _, w ->
            if (w == 0) {
                showDiag()
            } else if (chat.msgs.isEmpty()) {
                toast("Чат пока пуст")
            } else if (w == 1) {
                renameChat()
            } else if (w == 2) {
                askText("Инструкция для этого чата (пусто — как в настройках)", chat.sys, true) { t ->
                    chat.sys = t
                    Store.save(this, chat)
                    updateInfo()
                }
            } else if (w == 3) {
                shareText(Files.chatText(chat))
            } else if (w == 4) {
                saveText = Files.chatText(chat)
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "text/markdown"
                i.putExtra(Intent.EXTRA_TITLE, Files.safeName(chat.title) + ".md")
                try {
                    startActivityForResult(i, RQ_SAVE)
                } catch (e: Exception) {
                    toast("Не найдено приложение для сохранения файлов")
                }
            } else if (w == 5) {
                copy(Files.chatText(chat))
            } else {
                deleteChat()
            }
        }.show()
    }

    private fun deleteChat() {
        if (sending) {
            toast("Дождитесь ответа или нажмите ■")
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Удалить чат «" + chat.title + "»?")
            .setPositiveButton("Удалить") { _, _ ->
                Store.delete(this, chat.id)
                chat = Store.newChat()
                Store.setCur(this, chat.id)
                note = ""
                updateTitle()
                updateInfo()
                render()
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        updateInfo()
        render()
    }

    private fun stop() {
        req?.cancel()
    }

    private fun send() {
        if (sending) {
            toast("ИИ уже отвечает. Дождитесь ответа или нажмите ■")
            return
        }
        val t = input.text.toString().trim()
        if (t.isEmpty() && pending.isEmpty()) return
        if (importing > 0) {
            toast("Файл ещё обрабатывается, подождите секунду")
            return
        }
        if (Store.apiKey(this).isEmpty()) {
            Toast.makeText(this, "Сначала укажите API-ключ в настройках", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        input.setText("")
        val m = Msg("user", t)
        m.atts.addAll(pending)
        pending.clear()
        renderPending()
        chat.msgs.add(m)
        if (chat.msgs.size == 1) {
            val base = if (t.isNotEmpty()) t else m.atts[0].name
            chat.title = base.replace("\n", " ").take(40)
        }
        start()
    }

    private fun editMsg(idx: Int) {
        if (sending) {
            toast("Дождитесь ответа или нажмите ■")
            return
        }
        if (idx !in chat.msgs.indices) return
        val go = {
            val m = chat.msgs[idx]
            val tail = chat.msgs.subList(idx + 1, chat.msgs.size)
            for (x in tail) Files.dropAll(this, x.atts)
            tail.clear()
            chat.msgs.removeAt(idx)
            pending.addAll(m.atts)
            note = ""
            Store.save(this, chat)
            input.setText(m.text)
            input.setSelection(input.text.length)
            input.requestFocus()
            updateTitle()
            renderPending()
            render()
        }
        if (idx < chat.msgs.size - 1) {
            AlertDialog.Builder(this)
                .setMessage("Все сообщения ниже будут удалены, а это сообщение вернётся в поле ввода. Продолжить?")
                .setPositiveButton("Да") { _, _ -> go() }
                .setNegativeButton("Отмена", null)
                .show()
        } else {
            go()
        }
    }

    private fun deleteMsg(idx: Int) {
        if (sending) {
            toast("Дождитесь ответа или нажмите ■")
            return
        }
        if (idx !in chat.msgs.indices) return
        AlertDialog.Builder(this)
            .setMessage("Удалить это сообщение?")
            .setPositiveButton("Удалить") { _, _ ->
                if (idx in chat.msgs.indices) {
                    val m = chat.msgs.removeAt(idx)
                    Files.dropAll(this, m.atts)
                    note = ""
                    Store.save(this, chat)
                    updateTitle()
                    render()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        liveStatus = "Подготовка запроса…"
        startTimeMs = System.currentTimeMillis()
        chat.updated = startTimeMs
        Store.setCur(this, chat.id)
        val history = chat.msgs
            .filter { it.text.isNotEmpty() || it.atts.isNotEmpty() }
            .map { Msg(it.role, it.text, it.atts) }
        val msg = Msg("assistant", "")
        chat.msgs.add(msg)
        Store.save(this, chat)
        sending = true
        gen++
        val my = gen
        val userSys = if (chat.sys.isNotBlank()) chat.sys else Store.system(this)
        val today = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale("ru")).format(java.util.Date())
        val dateLine = "Текущая дата: $today."
        val prof = Store.profile(this).trim()
        val profBlock = if (prof.isEmpty()) "" else "\n\nЧто нужно знать о собеседнике (учитывай это, не пересказывай без необходимости):\n" + prof
        val fullSys = if (userSys.isBlank()) {
            dateLine + profBlock + "\n\n" + Builder.DEV_SYSTEM_ADDON.trim()
        } else {
            dateLine + "\n\n" + userSys + profBlock + "\n\n" + Builder.DEV_SYSTEM_ADDON.trim()
        }
        val temp = Store.temp(this).replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 1.0)
        val cfg = Cfg(
            Store.baseUrl(this), Store.apiKey(this), Store.model(this), Store.maxTokens(this),
            fullSys, Store.bearer(this), Store.stream(this), temp, Store.web(this), Store.think(this)
        )
        val r = Req(cfg, Store.attDir(this))
        req = r
        updateTitle()
        updateSendBtn()
        render()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 500)

        val acc = StringBuilder()
        Thread {
            var res: Res? = null
            var err: String? = null
            try {
                res = r.run(
                    history,
                    onStatus = { st ->
                        handler.post {
                            if (sending && gen == my) {
                                liveStatus = st
                                updateLiveStatusView()
                            }
                        }
                    },
                    onDelta = { d ->
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
                            }, 50)
                        }
                    }
                )
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
                handler.removeCallbacks(ticker)
                sending = false
                req = null
                liveStatus = ""
                msg.text = finalText
                var n = ""
                if (e2 != null) {
                    n = e2
                } else if (finalText.isEmpty()) {
                    n = if (stopped) "Остановлено пользователем." else "Сервер вернул пустой ответ. Проверьте выбранную модель («" + cfg.model + "») или нажмите «Загрузить список моделей с сервера» в настройках."
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
