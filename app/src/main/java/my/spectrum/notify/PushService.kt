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
        // Worker cloud-push registration removed (v0.23.1) — the Worker path is discontinued.
        fun registerWithWorker(ctx: android.content.Context) { /* removed */ }
    }
}