package com.local.apkcreator

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            fileUploadCallback?.onReceiveValue(arrayOf(uri))
        } else {
            fileUploadCallback?.onReceiveValue(null)
        }
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF2B2D31.toInt()
        window.navigationBarColor = 0xFF222327.toInt()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                vw: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                }
                filePickerLauncher.launch(intent)
                return true
            }
        }

        webView.addJavascriptInterface(LocalBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/ui/index.html")
    }

    inner class LocalBridge {

        @JavascriptInterface
        fun buildLocalArtifact(configJsonStr: String): String {
            return try {
                val cfg = JSONObject(configJsonStr)
                val outDir = File(filesDir, "outputs").apply { mkdirs() }
                val safeName = cfg.optString("appName", "MyApp").replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val fmt = cfg.optString("exportFormat", "apk")

                when (fmt) {
                    "src" -> {
                        val zipFile = File(outDir, "${safeName}_SourceCode.zip")
                        LocalArtifactBuilder.buildAndroidStudioZip(this@MainActivity, cfg, zipFile)
                        "Archive Code Source générée en local : ${zipFile.name}"
                    }
                    "aab" -> {
                        val aabFile = File(outDir, "${safeName}_release.aab")
                        LocalArtifactBuilder.buildSignedWebViewApkOrBundle(this@MainActivity, cfg, aabFile)
                        "Bundle AAB généré en local : ${aabFile.name}"
                    }
                    else -> {
                        val apkFile = File(outDir, "${safeName}_signed.apk")
                        LocalArtifactBuilder.buildSignedWebViewApkOrBundle(this@MainActivity, cfg, apkFile)
                        "APK signé généré 100% en local : ${apkFile.name}"
                    }
                }
            } catch (e: Exception) {
                "Erreur lors de la génération : ${e.message}"
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
            val intent = if (fileName.endsWith(".apk")) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(Intent.createChooser(intent, "Ouvrir ou partager"))
        }

        @JavascriptInterface
        fun generateKeystore(cn: String, org: String, country: String, pass: String): String {
            return try {
                LocalArtifactBuilder.generateCustomKeyData(this@MainActivity, cn, org, country)
                "OK"
            } catch (e: Exception) {
                e.message ?: "Error"
            }
        }
    }
}
