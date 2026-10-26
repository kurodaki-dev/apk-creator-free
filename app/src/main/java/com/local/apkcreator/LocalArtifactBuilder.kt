package com.local.apkcreator

import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
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
     * Modifie proprement une chaîne dans le StringPool binaire AXML d'AndroidManifest.xml.
     * Met à jour la taille exacte et place le terminateur nul (0x0000 ou 0x00) immédiatement après
     * la nouvelle valeur pour que le PackageParser d'Android accepte l'APK à 100%.
     */
    private fun patchBinaryManifestString(data: ByteArray, placeholder: String, newValue: String) {
        val safeVal = newValue.take(placeholder.length)

        // 1. Format UTF-16LE (Standard Android AXML)
        val searchUtf16 = placeholder.toByteArray(Charsets.UTF_16LE)
        var idx = indexOfBytes(data, searchUtf16)
        while (idx != -1) {
            if (idx >= 2) {
                // Met à jour la longueur uint16 juste avant la chaîne
                data[idx - 2] = (safeVal.length and 0xFF).toByte()
                data[idx - 1] = ((safeVal.length shr 8) and 0xFF).toByte()
            }
            val repUtf16 = safeVal.toByteArray(Charsets.UTF_16LE)
            // Efface toute la zone du placeholder + son terminateur nul avec des 0x00
            for (i in 0 until searchUtf16.size) {
                data[idx + i] = if (i < repUtf16.size) repUtf16[i] else 0
            }
            idx = indexOfBytes(data, searchUtf16, idx + searchUtf16.size)
        }

        // 2. Format UTF-8 (si AAPT2 utilise le flag UTF8_FLAG dans le StringPool)
        val searchUtf8 = placeholder.toByteArray(Charsets.UTF_8)
        var idx8 = indexOfBytes(data, searchUtf8)
        while (idx8 != -1) {
            val repUtf8 = safeVal.toByteArray(Charsets.UTF_8)
            if (idx8 >= 2) {
                // Dans un StringPool UTF-8 AXML (< 128 octets), les 2 octets précédents sont [charLen, byteLen]
                data[idx8 - 2] = safeVal.length.toByte()
                data[idx8 - 1] = repUtf8.size.toByte()
            }
            for (i in 0 until searchUtf8.size) {
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
     * Garantit un nom de package Android 100% valide (ex: com.webview.monapp).
     * Android refuse d'installer un APK si un segment commence par un chiffre ou contient des caractères spéciaux.
     */
    private fun sanitizePackageName(rawPkg: String, appName: String): String {
        val rawSegments = rawPkg.trim().lowercase().split(".")
            .map { it.replace(Regex("[^a-z0-9_]"), "") }
            .filter { it.isNotEmpty() }
            .map { seg -> if (seg.first().isDigit()) "app_$seg" else seg }

        if (rawSegments.size >= 2) {
            return rawSegments.joinToString(".")
        }
        val slug = appName.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "monapp" }
        val safeSlug = if (slug.first().isDigit()) "app$slug" else slug
        return "com.webview.$safeSlug"
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

    fun buildSignedWebViewApkOrBundle(context: Context, cfg: JSONObject, outSignedFile: File): Int {
        val tempUnsigned = File(context.cacheDir, "temp_unsigned.apk")
        val baseApk = File(context.cacheDir, "template_base.apk")

        context.assets.open("template_base.apk").use { inp ->
            FileOutputStream(baseApk).use { out -> inp.copyTo(out) }
        }

        val userAppName = cfg.optString("appName", "MonApp").ifBlank { "MonApp" }
        val userPkgName = sanitizePackageName(cfg.optString("packageName", ""), userAppName)
        val userVerName = cfg.optString("appVersion", "1.0.0").ifBlank { "1.0.0" }

        val nativeIconFile = File(context.filesDir, "current_custom_icon.png")
        val customIconPng: ByteArray? = if (nativeIconFile.exists() && nativeIconFile.length() > 0) {
            nativeIconFile.readBytes()
        } else null

        var replacedIconCount = 0

        ZipFile(baseApk).use { zipIn ->
            ZipOutputStream(FileOutputStream(tempUnsigned)).use { zipOut ->
                val entries = zipIn.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("META-INF/") || entry.name == "assets/app_config.json") continue

                    val entryBytes = zipIn.getInputStream(entry).use { it.readBytes() }

                    // 1. Patch binaire AndroidManifest.xml
                    if (entry.name == "AndroidManifest.xml") {
                        patchBinaryManifestString(entryBytes, TPL_NAME, userAppName)
                        patchBinaryManifestString(entryBytes, TPL_PKG, userPkgName)
                        patchBinaryManifestString(entryBytes, TPL_VER, userVerName)
                        zipOut.putNextEntry(ZipEntry(entry.name))
                        zipOut.write(entryBytes)
                        zipOut.closeEntry()
                        continue
                    }

                    // 2. Remplacement de TOUTES les icônes PNG dans res/ (quel que soit le nom généré par Gradle/AAPT2)
                    val isTemplateIcon = entry.name.endsWith("app_icon.png") ||
                            (entry.name.startsWith("res/") && entry.name.endsWith(".png") && !entry.name.contains(".9.png"))

                    if (customIconPng != null && isTemplateIcon) {
                        writeStoredEntry(zipOut, entry.name, customIconPng)
                        replacedIconCount++
                        continue
                    }

                    // 3. Copie des autres fichiers
                    if (entry.method == ZipEntry.STORED) {
                        writeStoredEntry(zipOut, entry.name, entryBytes)
                    } else {
                        zipOut.putNextEntry(ZipEntry(entry.name))
                        zipOut.write(entryBytes)
                        zipOut.closeEntry()
                    }
                }

                // Injection de app_config.json
                zipOut.putNextEntry(ZipEntry("assets/app_config.json"))
                zipOut.write(cfg.toString(2).toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                // Injection de l'icône personnalisée dans assets/custom_icon.png
                if (customIconPng != null) {
                    zipOut.putNextEntry(ZipEntry("assets/custom_icon.png"))
                    zipOut.write(customIconPng)
                    zipOut.closeEntry()
                }

                // Injection du fichier HTML local si sélectionné
                val nativeHtmlFile = File(context.filesDir, "current_local_index.html")
                if (nativeHtmlFile.exists()) {
                    zipOut.putNextEntry(ZipEntry("assets/local_index.html"))
                    zipOut.write(nativeHtmlFile.readBytes())
                    zipOut.closeEntry()
                }
            }
        }

        // Signature V1 + V2 + V3 complète compatible avec toutes les versions d'Android (7 à 15)
        val (privKey, cert) = ensureKeyAndCert()
        val signerConfig = ApkSigner.SignerConfig.Builder("LOCAL_KEY", privKey, listOf(cert)).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(tempUnsigned)
            .setOutputApk(outSignedFile)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()

        tempUnsigned.delete()
        return replacedIconCount
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
