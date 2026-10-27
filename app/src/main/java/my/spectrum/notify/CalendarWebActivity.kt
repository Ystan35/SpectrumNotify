package my.spectrum.notify

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

/** UM academic calendar, in-app, cached so it opens offline once seen. */
class CalendarWebActivity : Activity() {
    companion object {
        // UM academic calendar page; change the URL via the UM-links editor if UM moves it
        const val URL = "https://www.um.edu.my/students/academic-calendar"
    }

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        w.loadUrl(intent.getStringExtra("url") ?: URL)
        web = w
    }

    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }
}
