package my.spectrum.notify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.util.UUID

/** Spectrum login through the browser (Moodle mobile launch flow) plus token verification. */
object Login {
    fun start(activity: Activity) {
        val p = Prefs(activity)
        p.loginStartedAt = System.currentTimeMillis()
        val passport = UUID.randomUUID().toString().take(12)
        val url = "${p.site}/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=$passport&urlscheme=spectrumnotify"
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Returns (token, error). Only accepts a redirect within 15 minutes of pressing the login button. */
    fun parseRedirect(p: Prefs, raw: String): Pair<String?, String?> {
        if (System.currentTimeMillis() - p.loginStartedAt > 15 * 60 * 1000) {
            return Pair(null, "Ignored a login redirect that was not started from this app")
        }
        val b64 = Uri.decode(raw.substringAfter("token=", "")).trim()
        val decoded = try { String(Base64.decode(b64, Base64.DEFAULT)) } catch (e: Exception) { "" }
        val tok = decoded.split(":::").getOrNull(1)?.trim().orEmpty()
        return if (tok.isEmpty()) {
            Pair(null, "The login redirect did not contain a token. Use the manual token field in Account.")
        } else {
            Pair(tok, null)
        }
    }

    /** Stores the token, checks it against Spectrum and queues a first poll. [done] runs on a background thread. */
    fun saveAndVerify(ctx: Context, tok: String, done: (String) -> Unit) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        if (!p.saveToken(tok)) {
            val m = "Could not store the token securely on this device"
            NotifyLog.sys(app, m)
            done(m)
            return
        }
        p.fails = 0
        p.alerted = false
        p.lastError = ""
        val site = p.site
        Thread {
            val msg = try {
                val o = Moodle(site, tok).obj("core_webservice_get_site_info")
                p.userName = o.str("fullname").ifEmpty { o.str("username") }
                "Logged in as ${o.str("fullname")}"
            } catch (e: Exception) {
                "Token saved, but the check failed: ${e.message}"
            }
            NotifyLog.sys(app, msg)
            done(msg)
        }.start()
        Scheduler.pollNow(app)
    }
}
