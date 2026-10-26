package com.local.apkcreator

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var lastPickedIconDataUri: String = ""

    private val iconPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val rawBytes = input.readBytes()
                    val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                    if (original != null) {
                        val scaled = Bitmap.createScaledBitmap(original, 192, 192, true)
                        val iconFile = File(filesDir, "current_custom_icon.png")
                        val bos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
                        val pngBytes = bos.toByteArray()
                        FileOutputStream(iconFile).use { it.write(pngBytes) }

                        val b64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP)
                        lastPickedIconDataUri = "data:image/png;base64,$b64"
                        runOnUiThread {
                            webView.evaluateJavascript("window.onNativeIconPicked();", null)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val htmlPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val htmlFile = File(filesDir, "current_local_index.html")
                    FileOutputStream(htmlFile).use { out -> input.copyTo(out) }
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "index.html"
                    runOnUiThread {
                        webView.evaluateJavascript("window.onNativeHtmlPicked('$name');", null)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF2B2D31.toInt()
        window.navigationBarColor = 0xFF222327.toInt()

        File(filesDir, "current_custom_icon.png").delete()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(LocalBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/ui/index.html")
    }

    inner class LocalBridge {

        @JavascriptInterface
        fun pickAppIconNative() {
            runOnUiThread {
                iconPickerLauncher.launch("image/*")
            }
        }

        @JavascriptInterface
        fun getCustomIconDataUri(): String {
            return lastPickedIconDataUri
        }

        @JavascriptInterface
        fun pickLocalHtmlNative() {
            runOnUiThread {
                htmlPickerLauncher.launch("*/*")
            }
        }

        @JavascriptInterface
        fun clearCustomIcon() {
            lastPickedIconDataUri = ""
            File(filesDir, "current_custom_icon.png").delete()
            File(filesDir, "current_local_index.html").delete()
        }

        @JavascriptInterface
        fun buildLocalArtifact(configJsonStr: String): String {
            return try {
                val cfg = JSONObject(configJsonStr)
                val outDir = File(filesDir, "outputs").apply { mkdirs() }
                val rawName = cfg.optString("appName", "MonApp").ifBlank { "MonApp" }
                val safeFile = rawName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val fmt = cfg.optString("exportFormat", "apk")

                when (fmt) {
                    "src" -> {
                        val zipFile = File(outDir, "${safeFile}_SourceCode.zip")
                        LocalArtifactBuilder.buildAndroidStudioZip(cfg, zipFile)
                        "Code Source généré : ${zipFile.name}"
                    }
                    "aab" -> {
                        val aabFile = File(outDir, "${safeFile}_release.aab")
                        LocalArtifactBuilder.buildSignedWebViewApkOrBundle(this@MainActivity, cfg, aabFile)
                        "Bundle AAB généré : ${aabFile.name}"
                    }
                    else -> {
                        val apkFile = File(outDir, "${safeFile}.apk")
                        val countReplaced = LocalArtifactBuilder.buildSignedWebViewApkOrBundle(this@MainActivity, cfg, apkFile)
                        val iconInfo = if (countReplaced > 0) "Logo personnalisé appliqué ($countReplaced res)" else "Logo par défaut"
                        "APK '${rawName}' prêt à installer ($iconInfo) : ${apkFile.name}"
                    }
                }
            } catch (e: Exception) {
                "Erreur : ${e.message}"
            }
        }

        @JavascriptInterface
        fun listGeneratedFiles(): String {
            val outDir = File(filesDir, "outputs")
            val arr = JSONArray()
            outDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { f ->
                val obj = JSONObject()
                obj.put("name", f.name)
                obj.put("size", "${f.length() / 1024} Ko")
                arr.put(obj)
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun installOrShareFile(fileName: String) {
            val f = File(File(filesDir, "outputs"), fileName)
            if (!f.exists()) return
            val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.provider", f)
            if (fileName.endsWith(".apk")) {
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(installIntent)
            } else {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Partager"))
            }
        }

        @JavascriptInterface
        fun generateKeystore(cn: String, org: String, country: String, pass: String): String {
            return try {
                LocalArtifactBuilder.generateCustomKeyData(cn, org, country)
                "OK"
            } catch (e: Exception) {
                e.message ?: "Error"
            }
        }
    }
}
