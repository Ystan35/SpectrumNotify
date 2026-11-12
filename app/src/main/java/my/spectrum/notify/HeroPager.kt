package my.spectrum.notify

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Typeface
import android.view.MotionEvent
import kotlin.math.abs
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.Window
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Holds the one visible hero card and moves it with the finger. A sideways drag (also one that starts on a
 * button) is taken from the children and from the scrolling page; on release the card either slides out and
 * [onCommit] shows the neighbour, or springs back. At the first/last class the card only stretches a little.
 */
class HeroPager(ctx: android.content.Context) : FrameLayout(ctx) {
    companion object {
        /** Tag a child with this to keep sideways drags that start on it (e.g. the scrolling button row). */
        const val NO_SWIPE = "heroNoSwipe"
    }
    var canGo: (Int) -> Boolean = { false }
    var onCommit: (Int) -> Unit = {}
    var onDragState: (Boolean) -> Unit = {}

    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private val flingSpeed = 700 * ctx.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    /** This gesture started inside a [NO_SWIPE] child: it belongs to that child, not to the card. */
    private var blocked = false
    private var vt: android.view.VelocityTracker? = null

    private fun inNoSwipe(v: View, rawX: Float, rawY: Float): Boolean {
        if (v.tag == NO_SWIPE && v.isShown) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX < loc[0] + v.width && rawY >= loc[1] && rawY < loc[1] + v.height) return true
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) if (inNoSwipe(v.getChildAt(i), rawX, rawY)) return true
        return false
    }

    private fun card(): View? = if (childCount > 0) getChildAt(0) else null

    private fun sideways(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        return abs(dx) > slop && abs(dx) > abs(ev.y - downY) * 1.2f
    }

    private fun startDrag() {
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        card()?.animate()?.cancel()
        onDragState(true)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dragging = false
                blocked = inNoSwipe(this, ev.rawX, ev.rawY)
                vt?.recycle(); vt = android.view.VelocityTracker.obtain()
                vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (!dragging && !blocked && sideways(ev)) startDrag()
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) { if (sideways(ev)) startDrag() else return true }
                val dx = ev.x - downX
                val shown = if (canGo(if (dx < 0) 1 else -1)) dx else dx * 0.25f
                card()?.let {
                    it.translationX = shown
                    it.alpha = 1f - (abs(shown) / width.coerceAtLeast(1)).coerceAtMost(1f) * 0.5f
                }
            }
            MotionEvent.ACTION_UP -> {
                vt?.computeCurrentVelocity(1000)
                release(ev.x - downX, vt?.xVelocity ?: 0f)
            }
            MotionEvent.ACTION_CANCEL -> release(0f, 0f)
        }
        return true
    }

    private fun release(dx: Float, vx: Float) {
        vt?.recycle(); vt = null
        if (!dragging) return
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        val dir = if (dx < 0) 1 else -1
        val fling = (dx < 0 && vx < -flingSpeed) || (dx > 0 && vx > flingSpeed)
        val c = card()
        if (c != null && dx != 0f && canGo(dir) && (abs(dx) > width * 0.25f || fling)) {
            c.animate().translationX(-dir * width.toFloat()).alpha(0f).setDuration(160)
                .withEndAction { onDragState(false); onCommit(dir) }.start()
        } else if (c != null) {
            c.animate().translationX(0f).alpha(1f).setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { onDragState(false) }.start()
        } else {
            onDragState(false)
        }
    }

    /** The ‹ › buttons: same motion as a swipe, without the finger. */
    fun slide(dir: Int) {
        if (!canGo(dir)) return
        val c = card() ?: return onCommit(dir)
        c.animate().translationX(-dir * width * 0.35f).alpha(0f).setDuration(140)
            .withEndAction { onCommit(dir) }.start()
    }
}
