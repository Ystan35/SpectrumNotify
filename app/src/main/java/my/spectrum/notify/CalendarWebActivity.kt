package my.spectrum.notify

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

/** UM academic calendar: a web page in-app (cached so it opens offline once seen), or a PDF in the phone's viewer. */
class CalendarWebActivity : BaseActivity() {
    companion object {
        // UM's Bachelor degree calendar for the 2026/2027 session (AASD). The old page
        // www.um.edu.my/students/academic-calendar is gone (404). Update this each session.
        const val URL = "https://aasd.um.edu.my/website_doc/academic-calendar/2026/" +
            "Attachment%20D%20Academic%20Calendar%20Bachelor%20Degree%202026-2027%20Academic%20Session_July%202026.pdf"
    }

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: URL
        // a WebView cannot show a PDF: hand it to the phone's PDF viewer or browser
        if (url.substringBefore('?').endsWith(".pdf", ignoreCase = true)) {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: Exception) {
                android.widget.Toast.makeText(this, "No app here can open the calendar PDF", android.widget.Toast.LENGTH_LONG).show()
            }
            finish()
            return
        }
        // screen() sets the content view with the shared header + back button;
        // we then add the WebView as the page body
        val col = screen("Academic calendar")
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        // cache-first: the calendar opens without internet once it has been seen once
        w.settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
        w.webViewClient = WebViewClient()
        w.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        col.addView(w, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        w.loadUrl(url)
        web = w
    }

    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }
}
