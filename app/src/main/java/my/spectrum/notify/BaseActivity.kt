package my.spectrum.notify

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

/**
 * Every screen extends this. It makes Android's own parts — dialogs, time/date pickers, dropdowns, the
 * DayNight theme — follow the app's Colour mode (Settings → Appearance) instead of the phone's: with the
 * app on Light and the phone on Dark, dialogs used to draw dark with the app's dark text on top.
 * Tab screens also switch to the neighbouring tab on a sideways swipe ([tabSwipe], set by the bottom nav).
 */
open class BaseActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        // read the raw preference: Prefs() would also open the encrypted token store, too early here.
        // Same default as Prefs.themeMode (1 = light)
        val mode = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("themeMode", 1)
        if (mode == 0) {
            super.attachBaseContext(newBase)
            return
        }
        val cfg = Configuration(newBase.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (mode == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onResume() {
        super.onResume()
        // tabs only: the first-run guide's step for this tab, once the page has been drawn
        if (tabSwipe != null) window.decorView.postDelayed({ if (!isFinishing) Guide.maybeShow(this) }, 500)
        restartBored()
    }

    override fun onPause() {
        super.onPause()
        window.decorView.removeCallbacks(bored)
        window.decorView.removeCallbacks(restart)
    }

    // Oli gets bored: after 20 s without a touch the rose on the header sways from its stem, at most 3 times until
    // the next touch. Not when the phone has animations off (reduced motion).
    private var waves = 0
    private val bored: Runnable = Runnable {
        val oli = window.decorView.findViewWithTag<View>("guide:ask")
        val animOn = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        if (oli == null || !oli.isShown || !animOn || waves >= 3) return@Runnable
        waves++
        oli.pivotX = oli.width / 2f
        oli.pivotY = oli.height * 0.85f   // the stem's foot, so the flower waves rather than spins
        android.animation.ObjectAnimator.ofFloat(oli, View.ROTATION, 0f, -14f, 11f, -7f, 4f, 0f).setDuration(1400).start()
        window.decorView.postDelayed(restart, 20_000)
    }
    private val restart: Runnable = Runnable { bored.run() }

    private fun restartBored() {
        waves = 0
        window.decorView.removeCallbacks(bored)
        window.decorView.removeCallbacks(restart)
        window.decorView.postDelayed(bored, 20_000)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        restartBored()
    }

    /** Opens the tab to the right (+1) or left (-1); null on screens without the bottom nav. */
    var tabSwipe: ((Int) -> Unit)? = null
    private var sx = 0f
    private var sy = 0f
    private var swipeOff = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val go = tabSwipe
        if (go != null) when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = ev.rawX; sy = ev.rawY
                swipeOff = ownsSideways(window.decorView, ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - sx
                // a long, clearly sideways swipe; scrolling the page with a slight drift never switches tabs
                if (!swipeOff && abs(dx) > resources.displayMetrics.widthPixels * 0.3f && abs(dx) > abs(ev.rawY - sy) * 2) {
                    go(if (dx < 0) 1 else -1)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Something under the finger already uses sideways drags (a scroller, the hero / day pager, a card, a slider). */
    private fun ownsSideways(v: View, x: Float, y: Float): Boolean {
        if (!v.isShown) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        if (x < loc[0] || x >= loc[0] + v.width || y < loc[1] || y >= loc[1] + v.height) return false
        if (v.tag == "guideOverlay") return true   // the first-run guide is up: no tab switching under it
        if (v.canScrollHorizontally(1) || v.canScrollHorizontally(-1) || v is HeroPager || v is SwipeCard ||
            v is android.widget.AbsSeekBar || v is android.widget.EditText
        ) return true
        return v is ViewGroup && (0 until v.childCount).any { ownsSideways(v.getChildAt(it), x, y) }
    }
}
