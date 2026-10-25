package com.local.apkcreator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object LocalArtifactBuilder {

    private const val TPL_PKG  = "com.apkcreator.tpl.pkg0000000000000000000000000000"
    private const val TPL_NAME = "AppNamePlaceholder0000000000000000000000"
    private const val TPL_VER  = "1.0.0.00000000000000"

    private var customPrivateKey: PrivateKey? = null
    private var customCert: X509Certificate? = null

    fun generateCustomKeyData(cn: String, org: String, country: String) {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.genKeyPair()
        val issuer = X500Name("CN=$cn, O=$org, C=$country")
        val now = System.currentTimeMillis()
        val certBuilder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now),
            Date(now - 86400000L),
            Date(now + 25L * 365 * 86400000L),
            issuer,
            kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(kp.private)
        customCert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
        customPrivateKey = kp.private
    }

    private fun ensureKeyAndCert(): Pair<PrivateKey, X509Certificate> {
        if (customPrivateKey == null || customCert == null) {
            generateCustomKeyData("ApkCreator Local", "ApkCreator", "FR")
        }
        return Pair(customPrivateKey!!, customCert!!)
    }

    /**
     * Remplace dans le binaire AXML AndroidManifest.xml une chaîne UTF-16LE ou UTF-8
     * en mettant à jour sa longueur exacte et en complétant le reste avec des octets nuls (0x00)
     * afin de préserver l'alignement du StringPool binaire Android.
     */
    private fun patchBinaryManifestString(data: ByteArray, placeholder: String, newValue: String) {
        val safeVal = newValue.take(placeholder.length)

        // 1. Recherche et patch UTF-16LE (format standard de AndroidManifest.xml compilé par AAPT2)
        val searchUtf16 = placeholder.toByteArray(Charsets.UTF_16LE)
        var idx = indexOfBytes(data, searchUtf16)
        while (idx != -1) {
            // Les 2 octets précédant la chaîne dans le StringPool UTF-16LE contiennent la longueur en caractères (uint16 LE)
            if (idx >= 2) {
                val oldLen = (data[idx - 2].toInt() and 0xFF) or ((data[idx - 1].toInt() and 0xFF) shl 8)
                if (oldLen == placeholder.length) {
                    data[idx - 2] = (safeVal.length and 0xFF).toByte()
                    data[idx - 1] = ((safeVal.length shr 8) and 0xFF).toByte()
                }
            }
            val repUtf16 = safeVal.toByteArray(Charsets.UTF_16LE)
            for (i in searchUtf16.indices) {
                data[idx + i] = if (i < repUtf16.size) repUtf16[i] else 0
            }
            idx = indexOfBytes(data, searchUtf16, idx + searchUtf16.size)
        }

        // 2. Recherche et patch UTF-8 (au cas où AAPT2 encode en UTF-8)
        val searchUtf8 = placeholder.toByteArray(Charsets.UTF_8)
        var idx8 = indexOfBytes(data, searchUtf8)
        while (idx8 != -1) {
            val repUtf8 = safeVal.toByteArray(Charsets.UTF_8)
            if (idx8 >= 2 && data[idx8 - 1].toInt() == placeholder.length) {
                data[idx8 - 2] = safeVal.length.toByte()
                data[idx8 - 1] = repUtf8.size.toByte()
            }
            for (i in searchUtf8.indices) {
                data[idx8 + i] = if (i < repUtf8.size) repUtf8[i] else 0
            }
            idx8 = indexOfBytes(data, searchUtf8, idx8 + searchUtf8.size)
        }
    }

    private fun indexOfBytes(source: ByteArray, target: ByteArray, start: Int = 0): Int {
        if (target.isEmpty() || source.size < target.size) return -1
        for (i in start..(source.size - target.size)) {
            var match = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    /**
     * Convertit l'icône importée par l'utilisateur en PNG 192x192 valide.
     */
    private fun decodeUserIconToPng(iconBase64: String): ByteArray? {
        if (!iconBase64.contains(",")) return null
        return try {
            val raw = Base64.decode(iconBase64.substringAfter(","), Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
            val scaled = Bitmap.createScaledBitmap(bmp, 192, 192, true)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizePackageName(rawPkg: String, appName: String): String {
        val cleaned = rawPkg.trim().lowercase().replace(Regex("[^a-z0-9_.]"), "")
        if (cleaned.contains(".") && cleaned.first().isLetter()) {
            return cleaned
        }
        val slug = appName.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "monapp" }
        return "com.webview.$slug"
    }

    private fun writeStoredEntry(zipOut: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }.value
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc
        }
        zipOut.putNextEntry(entry)
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    fun buildSignedWebViewApkOrBundle(context: Context, cfg: JSONObject, outSignedFile: File) {
        val tempUnsigned = File(context.cacheDir, "temp_unsigned.apk")
        val baseApk = File(context.cacheDir, "template_base.apk")

        context.assets.open("template_base.apk").use { inp ->
            FileOutputStream(baseApk).use { out -> inp.copyTo(out) }
        }

        val userAppName = cfg.optString("appName", "MonApp").ifBlank { "MonApp" }
        val userPkgName = sanitizePackageName(cfg.optString("packageName", ""), userAppName)
        val userVerName = cfg.optString("appVersion", "1.0.0").ifBlank { "1.0.0" }
        val customIconPng = decodeUserIconToPng(cfg.optString("iconBase64", ""))

        ZipFile(baseApk).use { zipIn ->
            ZipOutputStream(FileOutputStream(tempUnsigned)).use { zipOut ->
                val entries = zipIn.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("META-INF/") || entry.name == "assets/app_config.json") continue

                    val entryBytes = zipIn.getInputStream(entry).use { it.readBytes() }

                    // 1. Patch binaire de AndroidManifest.xml (Nom de l'app + Package + Version)
                    if (entry.name == "AndroidManifest.xml") {
                        patchBinaryManifestString(entryBytes, TPL_NAME, userAppName)
                        patchBinaryManifestString(entryBytes, TPL_PKG, userPkgName)
                        patchBinaryManifestString(entryBytes, TPL_VER, userVerName)
                        zipOut.putNextEntry(ZipEntry(entry.name))
                        zipOut.write(entryBytes)
                        zipOut.closeEntry()
                        continue
                    }

                    // 2. Remplacement de l'icône de l'application si l'utilisateur a importé une icône
                    if (customIconPng != null && entry.name.endsWith("app_icon.png")) {
                        writeStoredEntry(zipOut, entry.name, customIconPng)
                        continue
                    }

                    // 3. Copie à l'identique (en conservant STORED pour resources.arsc et les libs)
                    if (entry.method == ZipEntry.STORED) {
                        writeStoredEntry(zipOut, entry.name, entryBytes)
                    } else {
                        zipOut.putNextEntry(ZipEntry(entry.name))
                        zipOut.write(entryBytes)
                        zipOut.closeEntry()
                    }
                }

                // Injection de la configuration JSON complète
                zipOut.putNextEntry(ZipEntry("assets/app_config.json"))
                zipOut.write(cfg.toString(2).toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                // Injection de l'icône aussi dans assets pour le Splash Screen
                if (customIconPng != null) {
                    zipOut.putNextEntry(ZipEntry("assets/custom_icon.png"))
                    zipOut.write(customIconPng)
                    zipOut.closeEntry()
                }

                // Injection du fichier HTML local si sélectionné
                val localB64 = cfg.optString("localFileBase64", "")
                if (localB64.contains(",")) {
                    val raw = Base64.decode(localB64.substringAfter(","), Base64.DEFAULT)
                    zipOut.putNextEntry(ZipEntry("assets/local_index.html"))
                    zipOut.write(raw)
                    zipOut.closeEntry()
                }
            }
        }

        val (privKey, cert) = ensureKeyAndCert()
        val signerConfig = ApkSigner.SignerConfig.Builder("LOCAL_KEY", privKey, listOf(cert)).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(tempUnsigned)
            .setOutputApk(outSignedFile)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .build()
            .sign()

        tempUnsigned.delete()
    }

    fun buildAndroidStudioZip(cfg: JSONObject, outZipFile: File) {
        val pkg = cfg.optString("packageName", "com.webview.monapp")
        val appName = cfg.optString("appName", "MonAppWebView")
        val ver = cfg.optString("appVersion", "1.0.0")

        ZipOutputStream(FileOutputStream(outZipFile)).use { zos ->
            fun putText(path: String, content: String) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            putText("app/src/main/assets/app_config.json", cfg.toString(2))
            putText("README.md", "# $appName ($pkg) - v$ver\nProjet généré localement par ApkCreator.")
        }
    }
}
