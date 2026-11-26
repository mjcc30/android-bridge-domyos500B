package com.domyos500b.bridge

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour in-app de l'APK : lit la release GitHub « nightly » (mise à jour à
 * chaque push sur main par la CI), fallback sur « latest », télécharge
 * `app-debug.apk` via DownloadManager puis propose l'installation.
 *
 * N'utilise que le réseau — aucun impact sur le scan BLE / l'advertising du service.
 */
object ApkUpdater {

    private const val REPO = "mjcc30/android-bridge-domyos500B"
    private const val APK_NAME = "app-debug.apk"

    /** URL directe de la release roulante « nightly » (stable, hors rate-limit API). */
    private const val DIRECT_NIGHTLY_URL =
        "https://github.com/$REPO/releases/download/nightly/$APK_NAME"

    /** URL directe de l'APK (release nightly, fallback latest). Null si rien trouvé. */
    suspend fun latestApkUrl(): String? = withContext(Dispatchers.IO) {
        // 1) URL directe nightly d'abord : l'API api.github.com est souvent rate-limitée
        //    en non authentifié (403), ce qui empêcherait toute mise à jour.
        try {
            val conn = URL(DIRECT_NIGHTLY_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode == 200) return@withContext DIRECT_NIGHTLY_URL
            conn.disconnect()
        } catch (_: Exception) {
        }
        // 2) Fallback via l'API GitHub (release nightly puis latest).
        val endpoints = listOf(
            "https://api.github.com/repos/$REPO/releases/tags/nightly",
            "https://api.github.com/repos/$REPO/releases/latest"
        )
        for (ep in endpoints) {
            try {
                val conn = URL(ep).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                if (conn.responseCode == 200) {
                    val assets = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optJSONArray("assets") ?: continue
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name") == APK_NAME) {
                            return@withContext a.optString("browser_download_url")
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
        null
    }

    /** Télécharge l'APK puis lance l'installateur Android. False si échec. */
    suspend fun downloadAndInstall(context: Context): Boolean = withContext(Dispatchers.IO) {
        val url = latestApkUrl()
        if (url == null) {
            BridgeBus.log("Updater: aucune release avec $APK_NAME (nightly/latest)")
            return@withContext false
        }
        BridgeBus.log("Updater: téléchargement $url")
        val dm = context.getSystemService(DownloadManager::class.java)
        if (dm == null) {
            BridgeBus.log("Updater: DownloadManager indisponible")
            return@withContext false
        }
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(APK_NAME)
            .setDescription("Mise à jour Domyos500B Bridge")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, APK_NAME)
        val id = dm.enqueue(req)

        var status = -1
        repeat(90) { // jusqu'à ~3 min
            delay(2_000)
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (c.moveToFirst()) {
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                }
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                return@repeat
            }
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            BridgeBus.log("Updater: téléchargement échoué (status=$status)")
            return@withContext false
        }

        val apk = File(context.getExternalFilesDir(null), APK_NAME)
        if (!apk.exists() || apk.length() < 1_000_000) {
            BridgeBus.log("Updater: APK absent ou trop petit (${if (apk.exists()) apk.length() else -1} o)")
            return@withContext false
        }
        val head = ByteArray(2)
        apk.inputStream().use { it.read(head) }
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            BridgeBus.log("Updater: fichier téléchargé corrompu (pas de signature ZIP 'PK')")
            return@withContext false
        }
        BridgeBus.log("Updater: APK téléchargé (${apk.length()} o) -> lancement de l'installation")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        withContext(Dispatchers.Main) {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                BridgeBus.log("Updater: lancement de l'installation échoué: ${e.message}")
            }
        }
        true
    }
}

/** Export des logs du bridge (ring buffer [BridgeBus]) vers un fichier partageable. */
object LogExporter {

    fun share(context: Context) {
        val f = File(context.cacheDir, "bridge-logs.txt")
        f.writeText(BridgeBus.snapshotLogs() + "\n")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Envoyer les logs du bridge"))
    }
}
