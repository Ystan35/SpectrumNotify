package my.spectrum.notify

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime

/**
 * Receives FCM pushes from the cloud poller and shows them as normal app
 * notifications (same channel, same log, quiet hours respected). No-op unless
 * the user enabled cloud push in Settings.
 */
class PushService : FirebaseMessagingService() {

    override fun onMessageReceived(msg: RemoteMessage) {
        val p = Prefs(this)
        if (!p.cloudPush) return
        val now = ZonedDateTime.now()
        val title = msg.notification?.title ?: msg.data["title"] ?: "Spectrum"
        val body = msg.notification?.body ?: msg.data["body"] ?: ""
        if (Checker.inQuiet(now, p)) {
            // still log it so it shows in the app's notification list
            NotifyLog.add(this, "notif", title, body, emptyList())
            return
        }
        Notifier.push(this, title, body)
    }

    override fun onNewToken(token: String) {
        Prefs(this).fcmToken = token
        registerWithWorker(this)
    }

    companion object {
        /** Send site + moodle token + fcm token to the cloud poller. Silent best-effort. */
        fun registerWithWorker(ctx: android.content.Context) {
            val p = Prefs(ctx)
            if (!p.cloudPush) return
            val moodleToken = p.token
            val workerKey = p.workerKey
            if (moodleToken.isEmpty() || workerKey.isEmpty() || p.workerUrl.isEmpty() || p.fcmToken.isEmpty()) return
            try {
                val body = JSONObject().put("site", p.site).put("moodleToken", moodleToken)
                    .put("fcmToken", p.fcmToken)
                val conn = URL(p.workerUrl).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 20000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-Admin-Key", workerKey)
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    conn.responseCode // touch
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w("SpectrumNotify", "push register failed: ${e.message}")
            }
        }
    }
}
