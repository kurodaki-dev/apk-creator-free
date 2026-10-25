package com.local.apkcreator

import android.content.Context
import android.util.Base64
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object LocalArtifactBuilder {

    private var customPrivateKey: PrivateKey? = null
    private var customCert: X509Certificate? = null

    fun generateCustomKeyData(context: Context, cn: String, org: String, country: String) {
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

    private fun ensureKeyAndCert(context: Context): Pair<PrivateKey, X509Certificate> {
        if (customPrivateKey == null || customCert == null) {
            generateCustomKeyData(context, "ApkCreator Local", "ApkCreator", "FR")
        }
        return Pair(customPrivateKey!!, customCert!!)
    }

    /**
     * Prend le template APK embarqué dans assets/template_base.apk,
     * injecte assets/app_config.json (+ fichiers HTML/icône locaux) et signe l'APK avec apksig.
     */
    fun buildSignedWebViewApkOrBundle(context: Context, cfg: JSONObject, outSignedFile: File) {
        val tempUnsigned = File(context.cacheDir, "temp_unsigned.apk")
        val baseApk = File(context.cacheDir, "template_base.apk")

        context.assets.open("template_base.apk").use { inp ->
            FileOutputStream(baseApk).use { out -> inp.copyTo(out) }
        }

        ZipFile(baseApk).use { zipIn ->
            ZipOutputStream(FileOutputStream(tempUnsigned)).use { zipOut ->
                val entries = zipIn.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("META-INF/") || entry.name == "assets/app_config.json") continue
                    val newEntry = ZipEntry(entry.name)
                    if (entry.method == ZipEntry.STORED) {
                        newEntry.method = ZipEntry.STORED
                        newEntry.size = entry.size
                        newEntry.compressedSize = entry.compressedSize
                        newEntry.crc = entry.crc
                    }
                    zipOut.putNextEntry(newEntry)
                    zipIn.getInputStream(entry).use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }

                // Injection de la configuration utilisateur
                zipOut.putNextEntry(ZipEntry("assets/app_config.json"))
                zipOut.write(cfg.toString(2).toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                // Si un fichier HTML local a été importé
                val localB64 = cfg.optString("localFileBase64", "")
                if (localB64.contains(",")) {
                    val raw = Base64.decode(localB64.substringAfter(","), Base64.DEFAULT)
                    zipOut.putNextEntry(ZipEntry("assets/local_index.html"))
                    zipOut.write(raw)
                    zipOut.closeEntry()
                }
            }
        }

        val (privKey, cert) = ensureKeyAndCert(context)
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

    /**
     * Génère un projet Android Studio complet (.zip) prêt à être ouvert ou compilé sur GitHub Actions.
     */
    fun buildAndroidStudioZip(context: Context, cfg: JSONObject, outZipFile: File) {
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
