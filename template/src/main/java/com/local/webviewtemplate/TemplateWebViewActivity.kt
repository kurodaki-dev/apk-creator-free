package com.local.webviewtemplate

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
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

        title = cfg.optString("appName", title.toString())

        when (cfg.optString("orientation", "unspecified")) {
            "portrait" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        val uiHex = cfg.optString("uiColor", "#222327")
        val themeColor = try { Color.parseColor(uiHex) } catch (_: Exception) { Color.DKGRAY }
        window.statusBarColor = themeColor

        val mainFrame = FrameLayout(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
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

        mainFrame.addView(root)

        if (cfg.optString("splashMode", "enabled") == "enabled") {
            val splashBgHex = cfg.optString("splashBg", "#FFFFFF")
            val splashColor = try { Color.parseColor(splashBgHex) } catch (_: Exception) { Color.WHITE }
            val splashView = FrameLayout(this).apply {
                setBackgroundColor(splashColor)
                val img = ImageView(this@TemplateWebViewActivity).apply {
                    try {
                        assets.open("custom_icon.png").use { inp ->
                            setImageBitmap(BitmapFactory.decodeStream(inp))
                        }
                    } catch (_: Exception) {
                        setImageResource(R.mipmap.app_icon)
                    }
                    layoutParams = FrameLayout.LayoutParams(220, 220, Gravity.CENTER)
                }
                addView(img)
            }
            mainFrame.addView(splashView)
            Handler(Looper.getMainLooper()).postDelayed({
                splashView.visibility = View.GONE
            }, 1400)
        }

        setContentView(mainFrame)

        val sourceMode = cfg.optString("sourceMode", "url")
        if (sourceMode == "local") {
            webView.loadUrl("file:///android_asset/local_index.html")
        } else {
            val rawUrl = cfg.optString("webUrl", "https://example.com").trim()
            val finalUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) "https://$rawUrl" else rawUrl
            webView.loadUrl(finalUrl)
        }
    }
}
