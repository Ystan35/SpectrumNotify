package my.spectrum.notify

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

/**
 * In-app Spectrum sign-in. Spectrum forgets the pending token request while you sign in through
 * Microsoft, so after sign-in lands on the dashboard this page asks for the token again, now that
 * a session exists, and catches the spectrumnotify:// redirect itself.
 */
class LoginWebActivity : Activity() {
    private lateinit var p: Prefs
    private lateinit var web: WebView
    private lateinit var launchUrl: String
    private var retries = 0
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.setup(this)
        p = Prefs(this)
        p.loginStartedAt = System.currentTimeMillis()
        val passport = UUID.randomUUID().toString().take(12)
        launchUrl = "${p.site}/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=$passport&urlscheme=spectrumnotify"

        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.BAR
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.BAR)
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        top.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Ui.TEXT)
            setPadding(dp(12), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = "Sign in to Spectrum"
            textSize = 18f
            setTextColor(Ui.TEXT)
        })
        root.addView(top)

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("spectrumnotify://")) {
                    gotToken(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (done || retries >= 2) return
                val u = Uri.parse(url)
                if (u.host != Uri.parse(p.site).host) return
                val path = u.path ?: ""
                val landed = path == "/" || path.startsWith("/my") || path.startsWith("/course") || path.startsWith("/index")
                if (landed) {
                    retries++
                    view.loadUrl(launchUrl)  // signed in now: ask for the token again
                }
            }
        }
        web.loadUrl(launchUrl)
    }

    private fun gotToken(url: String) {
        if (done) return
        done = true
        web.loadUrl("about:blank")
        val parsed = Login.parseRedirect(p, url)
        val tok = parsed.first
        if (tok == null) {
            NotifyLog.sys(this, parsed.second ?: "Login failed")
            Toast.makeText(this, parsed.second ?: "Login failed", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Login.saveAndVerify(this, tok) { msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
