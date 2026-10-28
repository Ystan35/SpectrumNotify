package my.spectrum.notify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.util.UUID

/** Spectrum login through the browser (Moodle mobile launch flow) plus token verification. */
object Login {
    fun start(activity: Activity) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(launchUrl(Prefs(activity)))))
    }

    /**
     * Starts a login attempt: a fresh passport is stored so the redirect can be checked against it.
     * Alphanumeric only, so Moodle signs exactly the value stored here.
     */
    fun launchUrl(p: Prefs): String {
        val passport = UUID.randomUUID().toString().replace("-", "").take(16)
        p.loginPassport = passport
        p.loginStartedAt = System.currentTimeMillis()
        return "${p.site}/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=$passport&urlscheme=spectrumnotify"
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Returns (token, error). Only accepts a redirect within 15 minutes of pressing the login button,
     * signed by Moodle for this attempt's passport (md5(wwwroot + passport)), and only once.
     */
    fun parseRedirect(p: Prefs, raw: String): Pair<String?, String?> {
        val passport = p.loginPassport
        if (passport.isEmpty() || System.currentTimeMillis() - p.loginStartedAt > 15 * 60 * 1000) {
            return Pair(null, "Ignored a login redirect that was not started from this app")
        }
        val b64 = Uri.decode(raw.substringAfter("token=", "")).trim()
        val decoded = try { String(Base64.decode(b64, Base64.DEFAULT)) } catch (e: Exception) { "" }
        val parts = decoded.split(":::")
        val sig = parts.getOrNull(0)?.trim()?.lowercase().orEmpty()
        val tok = parts.getOrNull(1)?.trim().orEmpty()
        if (tok.isEmpty()) {
            return Pair(null, "The login redirect did not contain a token. Use the manual token field in Account.")
        }
        // Moodle signs with its own wwwroot, which may differ from the saved site only in http/https
        val host = p.site.substringAfter("://")
        if (sig !in listOf("https://$host", "http://$host").map { md5(it + passport) }) {
            return Pair(null, "Ignored a login redirect that does not match this login attempt. Check the site URL, or use the manual token field in Account.")
        }
        p.loginPassport = ""
        p.loginStartedAt = 0L
        return Pair(tok, null)
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
