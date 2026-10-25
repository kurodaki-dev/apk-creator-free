package com.local.webviewtemplate

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

class TemplateWebViewActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfgText = try {
            assets.open("app_config.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "{}"
        }
        val cfg = JSONObject(cfgText)

        when (cfg.optString("orientation", "unspecified")) {
            "portrait" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        val uiHex = cfg.optString("uiColor", "#222327")
        val themeColor = try { Color.parseColor(uiHex) } catch (_: Exception) { Color.DKGRAY }
        window.statusBarColor = themeColor

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(cfg.optString("zoomMode") == "true")
            settings.builtInZoomControls = cfg.optString("zoomMode") == "true"
            settings.displayZoomControls = false
            settings.cacheMode = if (cfg.optString("cacheMode") == "no_cache")
                WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        }

        // Menu Supérieur ou Inférieur configuré dans l'onglet Menu
        val menuType = cfg.optString("menuType", "")
        val menuItems = cfg.optJSONArray("menuItems")
        val menuBar = if (menuItems != null && menuItems.length() > 0) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(themeColor)
                gravity = Gravity.CENTER
                for (i in 0 until menuItems.length()) {
                    val item = menuItems.getJSONObject(i)
                    val btn = Button(this@TemplateWebViewActivity).apply {
                        text = item.optString("label", "Lien")
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { webView.loadUrl(item.optString("url")) }
                    }
                    addView(btn)
                }
            }
        } else null

        if (menuType == "top" && menuBar != null) root.addView(menuBar)
        root.addView(webView)
        if (menuType == "bottom" && menuBar != null) root.addView(menuBar)

        setContentView(root)

        val sourceMode = cfg.optString("sourceMode", "url")
        if (sourceMode == "local") {
            webView.loadUrl("file:///android_asset/local_index.html")
        } else {
            webView.loadUrl(cfg.optString("webUrl", "https://example.com"))
        }
    }
}
