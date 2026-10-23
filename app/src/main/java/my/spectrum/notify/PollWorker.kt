package my.spectrum.notify

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PollWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        Checker.run(ctx)  // handles its own errors and failure alerts
        Updater.check(ctx)
        return Result.success()
    }
}

/**
 * Update check: compares this build's versionCode against a small version file on GitHub
 * (the repo the catalog already comes from). If a newer version exists, one notification
 * points the user at the latest APK download. Never auto-installs; silent on any failure.
 */
object Updater {
    private const val VERSION_URL =
        "https://raw.githubusercontent.com/Ystan35/SpectrumNotify/main/latest.json"
    private const val DOWNLOAD_URL =
        "https://raw.githubusercontent.com/Ystan35/SpectrumNotify/main/apk/SpectrumNotify.apk"

    fun check(ctx: Context) {
        try {
            val p = Prefs(ctx)
            val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
            val text: String = try {
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                if (conn.responseCode !in 200..299) return
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val o = JSONObject(text)
            val latestCode = o.optInt("versionCode", 0)
            val notes = o.optString("notes")
            if (latestCode <= p.buildVersionCode) return
            // remind at most once per new version
            if (p.updateSeenCode >= latestCode) return
            p.updateSeenCode = latestCode
            val body = buildString {
                append("Version ${o.optString("versionName", latestCode.toString())} is available.")
                if (notes.isNotEmpty()) append("\n\n$notes")
                append("\n\nTap to open the download page.")
            }
            Notifier.push(ctx, "Spectrum Notify update", body, false,
                listOf(Pair("Get the update", DOWNLOAD_URL)))
        } catch (_: Exception) {
        }
    }
}
