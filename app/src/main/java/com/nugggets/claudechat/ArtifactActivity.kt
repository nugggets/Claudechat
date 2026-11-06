package com.nugggets.claudechat

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

/** Передача HTML между экранами (Intent не вмещает большие страницы). */
object ArtHolder {
    @Volatile var html: String = ""
}

/** Показывает HTML-страницу из ответа модели (артефакт) во встроенном браузере. */
class ArtifactActivity : Activity() {
    private var web: WebView? = null

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.load(this)
        styleWindow()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Pal.bg)
        root.addView(topBar("Предпросмотр", "←") { finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        w.webViewClient = WebViewClient()
        w.webChromeClient = WebChromeClient()
        w.setBackgroundColor(0xFFFFFFFF.toInt())
        root.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        web = w
        val html = ArtHolder.html
        w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    override fun onDestroy() {
        try {
            web?.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
