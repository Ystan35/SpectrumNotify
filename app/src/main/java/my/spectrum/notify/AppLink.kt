package my.spectrum.notify

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Notification buttons that act inside the app instead of opening a web page, written as "app:<action>?code=…".
 * They open Settings → Online or in person (an explicit intent, so nothing outside the app can trigger them).
 */
object AppLink {
    private const val EXTRA_ACTION = "applink_action"
    private const val EXTRA_CODE = "applink_code"
    private const val EXTRA_NID = "applink_nid"

    fun online(code: String) = "app:online?code=" + Uri.encode(code)
    fun keepInPerson(code: String) = "app:inperson?code=" + Uri.encode(code)

    /** The intent for an "app:" button, or null for an ordinary link. [nid] = the notification to close. */
    fun intent(ctx: Context, url: String, nid: Int = 0): Intent? {
        if (!url.startsWith("app:")) return null
        val u = Uri.parse(url.replaceFirst("app:", "app://"))
        return Intent(ctx, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SettingsActivity.EXTRA_OPEN, "inperson")
            .putExtra(EXTRA_ACTION, u.host ?: "")
            .putExtra(EXTRA_CODE, u.getQueryParameter("code") ?: "")
            .putExtra(EXTRA_NID, nid)
            // a distinct data URI per button, so two buttons of one notification are two PendingIntents
            .setData(Uri.parse("spectrumnotify://applink/${u.host}/${u.getQueryParameter("code")}/$nid"))
    }

    /** Called by Settings when opened from such a button: applies it and says what happened. */
    fun handle(activity: Activity, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val code = intent.getStringExtra(EXTRA_CODE) ?: ""
        intent.removeExtra(EXTRA_ACTION)   // a rotation must not apply it twice
        val nid = intent.getIntExtra(EXTRA_NID, 0)
        if (nid != 0) activity.getSystemService(NotificationManager::class.java)?.cancel(nid)
        if (code.isEmpty()) return
        val name = Shorts.display(activity, code)
        when (action) {
            "online" -> {
                Timetable.setInPerson(activity, listOf(code), false)
                Scheduler.scheduleAll(activity)
                Toast.makeText(activity, "$name is online again: Join class is back.", Toast.LENGTH_LONG).show()
            }
            "inperson" -> Toast.makeText(activity, "$name stays in person.", Toast.LENGTH_SHORT).show()
        }
    }
}
