package com.nugggets.gorodwatcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val START_URL = "https://gorod.mos.ru/map-messages"
        const val TOP_STUB_DP = 64   // заглушка вместо неработающей верхней части экрана
        const val MAX_ENTRIES = 400
    }

    private lateinit var prefs: Prefs
    private lateinit var db: Db

    private val tabs = ArrayList<View>()
    private val tabButtons = ArrayList<Button>()
    private var currentTab = 0
    private val logViews = ArrayList<TextView>()
    private var lastReload = 0L
    @Volatile private var checking = false

    // --- Лента
    private var feedItems: List<Row> = emptyList()
    private lateinit var feedInfo: TextView
    private lateinit var searchEdit: EditText
    private val feedAdapter = object : BaseAdapter() {
        override fun getCount() = feedItems.size
        override fun getItem(position: Int): Any = feedItems[position]
        override fun getItemId(position: Int): Long = feedItems[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                textSize = 13f
            }
            val r = feedItems[position]
            tv.text = "${fmtTs(r.published ?: r.created)} · ${r.statusName ?: "?"}\n" +
                "${r.address ?: r.place ?: ""}\n${r.text.take(120)}"
            return tv
        }
    }

    // --- Монитор
    private lateinit var monitorStatus: TextView
    private lateinit var intervalEdit: EditText
    private lateinit var daysEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var chatEdit: EditText
    private lateinit var notifyFirstBox: CheckBox
    private lateinit var androidNotifyBox: CheckBox

    // --- Данные
    private lateinit var syncStatus: TextView
    private lateinit var statsView: TextView
    private lateinit var sinceEdit: EditText
    private lateinit var chunkEdit: EditText

    // --- Сайт (дамп запросов, диагностика)
    private val entries = ArrayList<String>()
    private var web: WebView? = null
    private lateinit var webHolder: FrameLayout
    private lateinit var counter: TextView

    inner class Bridge {
        @JavascriptInterface
        fun log(json: String) {
            synchronized(entries) {
                if (entries.size >= MAX_ENTRIES) entries.removeAt(0)
                entries.add(json)
            }
            runOnUiThread { updateCounter() }
        }
    }

    private val refresher = Runnable { runOnUiThread { refreshUi() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ жизненный цикл
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = Db(this)
        Notifier.ensureChannels(this)
        buildUi()
        loadSettingsToFields()
        showTab(0)
        askNotifPermission()
        if (prefs.watching && !Bus.watching) WorkService.start(this, WorkService.A_WATCH)
    }

    override fun onResume() {
        super.onResume()
        Bus.onChange = refresher
        refreshUi()
        loadFeed()
    }

    override fun onPause() {
        Bus.onChange = null
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (currentTab == 3 && w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }

    private fun askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // ------------------------------------------------------------ построение UI
    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        textSize = 12f
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (v in views) r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setPadding(dp(6), dp(8), dp(6), 0)
    }

    private fun edit(hint: String, number: Boolean = false) = EditText(this).apply {
        this.hint = hint
        textSize = 14f
        setSingleLine(true)
        if (number) inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun newLogView(): TextView {
        val tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
        }
        logViews.add(tv)
        return tv
    }

    private fun scroll(inner: LinearLayout): ScrollView {
        inner.setPadding(dp(8), dp(4), dp(8), dp(8))
        return ScrollView(this).apply { addView(inner) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Заглушка сверху
        root.addView(TextView(this).apply {
            text = "▲ заглушка (верх экрана не используется)"
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            setTextColor(Color.DKGRAY)
            textSize = 12f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TOP_STUB_DP)))

        // Вкладки
        val names = listOf("Лента", "Монитор", "Данные", "Сайт")
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        names.forEachIndexed { i, n ->
            val b = btn(n) { showTab(i) }
            tabButtons.add(b)
            bar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabs.add(buildFeedTab())
        tabs.add(buildMonitorTab())
        tabs.add(buildDataTab())
        tabs.add(buildWebTab())
        for (t in tabs) content.addView(t, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
    }

    private fun buildFeedTab(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchEdit = edit("поиск по адресу, тексту, номеру")
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { loadFeed() }
        })
        val checkBtn = btn("Проверить сейчас") { checkNow() }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        top.addView(searchEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(checkBtn)
        v.addView(top)
        feedInfo = TextView(this).apply { textSize = 11f; setPadding(dp(8), 0, 0, dp(4)) }
        v.addView(feedInfo)
        val list = ListView(this)
        list.adapter = feedAdapter
        list.setOnItemClickListener { _, _, pos, _ -> if (pos in feedItems.indices) showRow(feedItems[pos]) }
        v.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return v
    }

    private fun buildMonitorTab(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        monitorStatus = TextView(this).apply { textSize = 14f; setPadding(dp(6), dp(8), dp(6), dp(4)) }
        v.addView(monitorStatus)
        v.addView(row(btn("Запустить") { startMonitor() }, btn("Остановить") { stopMonitor() }))
        v.addView(label("Интервал проверки, минут"))
        intervalEdit = edit("10", true); v.addView(intervalEdit)
        v.addView(label("Окно просмотра, дней назад"))
        daysEdit = edit("3", true); v.addView(daysEdit)
        v.addView(label("Telegram: токен бота (необязательно)"))
        tokenEdit = edit("123:ABC"); v.addView(tokenEdit)
        v.addView(label("Telegram: chat id"))
        chatEdit = edit("123456789"); v.addView(chatEdit)
        notifyFirstBox = CheckBox(this).apply { text = "Уведомлять и о записях первой загрузки" }
        androidNotifyBox = CheckBox(this).apply { text = "Уведомления Android" }
        v.addView(notifyFirstBox)
        v.addView(androidNotifyBox)
        v.addView(row(
            btn("Сохранить") { saveSettings(); toast("Сохранено") },
            btn("Тест") { testNotify() },
            btn("Батарея") { openBatterySettings() }
        ))
        v.addView(label("Журнал"))
        v.addView(newLogView())
        return scroll(v)
    }

    private fun buildDataTab(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        syncStatus = TextView(this).apply { textSize = 14f; setPadding(dp(6), dp(8), dp(6), dp(4)) }
        v.addView(syncStatus)
        statsView = TextView(this).apply { textSize = 12f; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        v.addView(statsView)
        v.addView(label("Загрузить историю с даты (ГГГГ-ММ-ДД)"))
        sinceEdit = edit("2025-01-01"); v.addView(sinceEdit)
        v.addView(label("Шаг запроса, дней"))
        chunkEdit = edit("7", true); v.addView(chunkEdit)
        v.addView(row(btn("Загрузить историю") { startSync() }, btn("Стоп") { stopSync() }))
        v.addView(row(btn("Экспорт CSV") { exportCsv() }, btn("Статистика") { loadStats() }))
        v.addView(label("Журнал"))
        v.addView(newLogView())
        return scroll(v)
    }

    private fun buildWebTab(): View {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        bar.addView(btn("Скачать дамп") { saveDump() })
        bar.addView(btn("Очистить") {
            synchronized(entries) { entries.clear() }
            updateCounter()
        })
        bar.addView(btn("Обновить") { web?.reload() })
        counter = TextView(this).apply { setPadding(dp(8), 0, 0, 0) }
        bar.addView(counter)
        v.addView(bar)
        webHolder = FrameLayout(this)
        v.addView(webHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        updateCounter()
        return v
    }

    private fun showTab(i: Int) {
        currentTab = i
        tabs.forEachIndexed { idx, t -> t.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { idx, b -> b.alpha = if (idx == i) 1f else 0.5f }
        when (i) {
            0 -> loadFeed()
            2 -> loadStats()
            3 -> ensureWeb()
        }
    }

    // ------------------------------------------------------------ настройки
    private fun loadSettingsToFields() {
        intervalEdit.setText(prefs.intervalMin.toString())
        daysEdit.setText(prefs.days.toString())
        tokenEdit.setText(prefs.tgToken)
        chatEdit.setText(prefs.tgChat)
        notifyFirstBox.isChecked = prefs.notifyFirst
        androidNotifyBox.isChecked = prefs.androidNotify
        sinceEdit.setText(prefs.since)
        chunkEdit.setText(prefs.chunkDays.toString())
    }

    private fun saveSettings() {
        prefs.intervalMin = (intervalEdit.text.toString().toIntOrNull() ?: 10).coerceIn(1, 1440)
        prefs.days = (daysEdit.text.toString().toIntOrNull() ?: 3).coerceIn(1, 365)
        prefs.tgToken = tokenEdit.text.toString().trim()
        prefs.tgChat = chatEdit.text.toString().trim()
        prefs.notifyFirst = notifyFirstBox.isChecked
        prefs.androidNotify = androidNotifyBox.isChecked
        prefs.chunkDays = (chunkEdit.text.toString().toIntOrNull() ?: 7).coerceIn(1, 60)
        val s = sinceEdit.text.toString().trim()
        if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(s)) prefs.since = s
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ действия
    private fun startMonitor() {
        saveSettings()
        askNotifPermission()
        WorkService.start(this, WorkService.A_WATCH)
    }

    private fun stopMonitor() = WorkService.start(this, WorkService.A_STOP_WATCH)

    private fun startSync() {
        saveSettings()
        val s = sinceEdit.text.toString().trim()
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(s)) {
            toast("Дата в формате ГГГГ-ММ-ДД")
            return
        }
        WorkService.start(this, WorkService.A_SYNC, s)
    }

    private fun stopSync() = WorkService.start(this, WorkService.A_STOP_SYNC)

    private fun checkNow() {
        if (checking) return
        checking = true
        saveSettings()
        Bus.log("проверка вручную…")
        Thread {
            try {
                val api = Api(1000L)
                val nodes = Engine.discover(api)
                Engine.watchCycle(this, api, db, nodes, prefs.days, prefs.notifyFirst)
            } catch (e: Exception) {
                Bus.log("ошибка проверки: ${e.message}")
            } finally {
                checking = false
                runOnUiThread { loadFeed() }
            }
        }.start()
    }

    private fun testNotify() {
        saveSettings()
        Thread {
            val res = try { Notifier.test(this) } catch (e: Exception) { "ошибка: ${e.message}" }
            runOnUiThread { toast(res) }
        }.start()
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            toast("Не удалось открыть настройки: ${e.message}")
        }
    }

    private fun showRow(r: Row) {
        val b = AlertDialog.Builder(this)
            .setTitle("№ ${r.id}")
            .setMessage(Notifier.messageText(r))
            .setNegativeButton("Закрыть", null)
        val map = Notifier.mapUrl(r)
        if (map != null) b.setPositiveButton("На карте") { _, _ -> openUrl(map) }
        if (r.photos.isNotEmpty()) b.setNeutralButton("Фото") { _, _ -> openUrl(r.photos[0]) }
        b.show()
    }

    private fun openUrl(u: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (e: Exception) {
            toast("Не удалось открыть: ${e.message}")
        }
    }

    // ------------------------------------------------------------ обновление UI
    private fun refreshUi() {
        val log = Bus.tail()
        for (v in logViews) v.text = log
        monitorStatus.text = if (Bus.watching)
            "● Мониторинг работает: каждые ${prefs.intervalMin} мин, окно ${prefs.days} дн."
        else "○ Мониторинг остановлен"
        syncStatus.text = if (Bus.syncing) "● Идёт загрузка истории" else "○ Загрузка истории не идёт"
        val now = System.currentTimeMillis()
        if (now - lastReload > 3000) {
            lastReload = now
            if (currentTab == 0) loadFeed()
            if (currentTab == 2) loadStats()
        }
    }

    private fun loadFeed() {
        if (!::searchEdit.isInitialized) return
        val q = searchEdit.text.toString()
        Thread {
            val rows = try { db.query(q, 300) } catch (e: Exception) { emptyList() }
            val total = try { db.count() } catch (e: Exception) { 0 }
            runOnUiThread {
                feedItems = rows
                feedAdapter.notifyDataSetChanged()
                feedInfo.text = "показано ${rows.size} из $total"
            }
        }.start()
    }

    private fun loadStats() {
        Thread {
            val s = try { db.stats() } catch (e: Exception) { "ошибка: ${e.message}" }
            runOnUiThread { statsView.text = s }
        }.start()
    }

    // ------------------------------------------------------------ файлы в Download
    private fun saveToDownloads(name: String, mime: String, write: (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("insert вернул null")
        contentResolver.openOutputStream(uri)!!.use { write(it) }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun exportCsv() {
        val name = "gorod_${stamp()}.csv"
        Thread {
            val msg = try {
                var n = 0
                saveToDownloads(name, "text/csv") { n = db.writeCsv(it) }
                "Сохранено: Download/$name ($n записей)"
            } catch (e: Exception) {
                "Ошибка: ${e.message}"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun saveDump() {
        val joined = synchronized(entries) { entries.joinToString(",") }
        val ts = stamp()
        val body = "{\"saved\":${JSONObject.quote(ts)}," +
            "\"page\":${JSONObject.quote(web?.url ?: "")}," +
            "\"entries\":[$joined]}"
        val name = "gorod_dump_$ts.json"
        try {
            saveToDownloads(name, "application/json") { it.write(body.toByteArray(Charsets.UTF_8)) }
            toast("Сохранено: Download/$name")
        } catch (e: Exception) {
            toast("Ошибка: ${e.message}")
        }
    }

    // ------------------------------------------------------------ вкладка «Сайт»
    private fun updateCounter() {
        val n = synchronized(entries) { entries.size }
        counter.text = "запросов: $n"
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb() {
        if (web != null) return
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.addJavascriptInterface(Bridge(), "GW")
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(HOOK_JS, null)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(HOOK_JS, null)
            }
        }
        webHolder.addView(w, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        w.loadUrl(START_URL)
    }

    // Перехват fetch/XHR: записываем только URL, метод, статус и тело ответа.
    // Заголовки и cookies НЕ сохраняются; параметры вида token/key/auth в URL маскируются.
    private val HOOK_JS = """
(function(){
  if(window.__gw) return; window.__gw=true;
  var MAX=4000;
  function red(u){try{var x=new URL(u,location.href);var ks=[];x.searchParams.forEach(function(v,k){ks.push(k)});
    ks.forEach(function(k){if(/token|auth|key|sess|sid|secret|pass/i.test(k))x.searchParams.set(k,'REDACTED')});return x.toString()}catch(e){return String(u)}}
  function findArr(o,p,d){if(Array.isArray(o))return {path:p,len:o.length,first:o[0]};
    if(o&&typeof o==='object'&&d<4){for(var k in o){var r=findArr(o[k],p+'.'+k,d+1);if(r)return r}}return null}
  function meta(o){var m={};if(o&&typeof o==='object'&&!Array.isArray(o)){for(var k in o){var v=o[k];if(v===null||typeof v!=='object')m[k]=v}}return m}
  function send(kind,method,url,status,ct,text,req){
    try{
      var rec={t:Date.now(),kind:kind,method:method,url:red(url),status:status,ct:ct,len:text.length,req:req,resp:text.slice(0,MAX)};
      if(ct&&ct.indexOf('json')>=0){try{var j=JSON.parse(text);var a=findArr(j,'$',0);
        rec.meta=meta(j);
        if(a){rec.arr={path:a.path,len:a.len,first:JSON.stringify(a.first).slice(0,3000)}}}catch(e){}}
      GW.log(JSON.stringify(rec));
    }catch(e){}
  }
  var of=window.fetch;
  if(of){window.fetch=function(i,init){
    var url=(typeof i==='string')?i:((i&&i.url)||'');
    var m=(init&&init.method)||(i&&i.method)||'GET';
    var rb=(init&&typeof init.body==='string')?init.body.slice(0,1000):null;
    return of.apply(this,arguments).then(function(r){
      try{r.clone().text().then(function(t){send('fetch',m,url,r.status,r.headers.get('content-type'),t,rb)}).catch(function(){})}catch(e){}
      return r;});
  }}
  var xo=XMLHttpRequest.prototype.open, xs=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(m,u){this.__m=m;this.__u=u;return xo.apply(this,arguments)};
  XMLHttpRequest.prototype.send=function(b){
    var self=this;var rb=(typeof b==='string')?b.slice(0,1000):null;
    this.addEventListener('load',function(){try{
      var t=(self.responseType===''||self.responseType==='text')?self.responseText:'';
      send('xhr',self.__m,self.__u,self.status,self.getResponseHeader('content-type'),t,rb)}catch(e){}});
    return xs.apply(this,arguments)};
})();
"""
}
