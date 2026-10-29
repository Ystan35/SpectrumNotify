package my.spectrum.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

object Notifier {
    private const val CH_ALERT = "alerts"
    private const val CH_INFO = "info"

    private fun ensureChannels(nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Class and deadline alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, "Materials and summaries", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(ctx: Context, ts: Long): PendingIntent {
        val i = Intent(ctx, NotificationsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(NotificationsActivity.EXTRA_TS, ts)
        return PendingIntent.getActivity(
            ctx, (System.nanoTime() and 0x7fffffff).toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * buttons = (label, url), max 3. Tapping the notification opens the app on this record; buttons open links.
     * During quiet hours it is only added to the Notifications list (no pop-up), unless [ignoreQuiet].
     */
    fun push(
        ctx: Context,
        title: String,
        body: String,
        alert: Boolean = false,
        buttons: List<Pair<String, String>> = emptyList(),
        ignoreQuiet: Boolean = false,
    ) {
        val logTs = NotifyLog.add(ctx, "notif", title, body, buttons)
        if (!ignoreQuiet && Checker.inQuiet(java.time.ZonedDateTime.now(zone()), Prefs(ctx))) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        ensureChannels(nm)
        val b = Notification.Builder(ctx, if (alert) CH_ALERT else CH_INFO)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.take(100))
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(body.take(3000)))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx, logTs))
        for ((i, bt) in buttons.take(3).withIndex()) {
            if (bt.second.isEmpty()) continue
            val intent = AppLink.intent(ctx, bt.second, (logTs.hashCode() and 0x7fffffff))
                ?: Intent(Intent.ACTION_VIEW, Uri.parse(bt.second)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val code = (logTs.hashCode() and 0x7fffffff) + i
            val pi = PendingIntent.getActivity(
                ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            @Suppress("DEPRECATION")
            b.addAction(0, bt.first.take(30), pi)
        }
        try {
            nm.notify((logTs.hashCode() and 0x7fffffff), b.build())
        } catch (e: SecurityException) {
            // notification permission not granted
        }
    }
}
