package my.spectrum.notify

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The first-run guide (fresh installs only): dims the screen except one control, with a short note beside it.
 * First it points at "Log in" on Home; after a successful login it walks through the tabs, switching to each one
 * so you see the real control: Home, Courses, Timetable, Notifications, Settings.
 * Skip ends it with a note that it can be replayed from Settings → App & maintenance → Show the guide again.
 * Each tab calls [maybeShow] when it is shown ([BaseActivity.onResume]); a step whose control is not there is left out.
 */
object Guide {
    private class Step(val screen: Class<*>, val tag: String, val title: String, val text: String, val tapThrough: Boolean = false)

    private val login = Step(MainActivity::class.java, "guide:login", "Start here: log in",
        "Log in with your university account so the app can bring in your classes, deadlines and notices. " +
            "Your login stays on this phone. The guide carries on after you log in.", tapThrough = true)

    private val tour = listOf(
        Step(MainActivity::class.java, "guide:hero", "Your next class",
            "Join the class, open its attendance page or its course page from here. Swipe the card to see the classes after it."),
        Step(MainActivity::class.java, "guide:due", "Due soon",
            "Your next deadlines. Tap one to mark it done or hide it. Hidden tasks are listed in Settings, where you can bring each one back."),
        Step(MainActivity::class.java, "guide:refresh", "Check now",
            "The app checks Spectrum on its own. Tap here, or pull the page down, to check right away."),
        Step(MainActivity::class.java, "toolboxBtn", "Text size and your links",
            "Make the text bigger or smaller, and open your shortcuts such as Spectrum and MySiswa."),
        Step(CoursesActivity::class.java, "guide:course", "Everything for one course",
            "Tap a course for its classes and rooms, deadlines, materials and notifications, all on one page. Set your Occ (group) there: it decides which classes are yours."),
        Step(TimetableActivity::class.java, "guide:ttViews", "Day, week or month",
            "See your classes by day, week or month. In Month, tap a day, then swipe its timeline or use ‹ › for the days around it."),
        Step(TimetableActivity::class.java, "guide:attPencil", "Wrong attendance page?",
            "The pencil chooses the right attendance page for this class. Lectures and tutorials can each have their own."),
        Step(NotificationsActivity::class.java, "guide:notifFilter", "Filter",
            "Show everything, or only classes, deadlines, materials or news."),
        Step(NotificationsActivity::class.java, "notifCard", "Clear a notice",
            "Swipe a card sideways to clear it. You can undo right after."),
        Step(SettingsActivity::class.java, "guide:settingsSearch", "Find a setting",
            "Search reminders, quiet hours, your links, hidden tasks, and the UM shuttle with your own places."),
        Step(SettingsActivity::class.java, "navBar", "Swipe between tabs",
            "Swipe left or right on any page to go to the next tab, or tap a tab here."),
    )

    private val end = Step(Activity::class.java, "nav:Settings", "You can come back to this",
        "Replay this guide any time in Settings → App & maintenance → Show the guide again.")

    /** The bottom-bar label of each tab, to switch to it like a tap would. */
    private val tabLabel = mapOf(
        MainActivity::class.java to "Home", CoursesActivity::class.java to "Courses", TimetableActivity::class.java to "Timetable",
        NotificationsActivity::class.java to "Notifications", SettingsActivity::class.java to "Settings",
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("guide", Context.MODE_PRIVATE)
    /**
     * "" = never run, "replay" = asked for again in Settings, "login" = the log-in step was shown once (the tour
     * starts after your first login), "tour", "end" (the replay note), "done".
     */
    private fun stage(ctx: Context) = sp(ctx).getString("stage", "") ?: ""
    private fun set(ctx: Context, stage: String, step: Int = 0) = sp(ctx).edit().putString("stage", stage).putInt("step", step).apply()

    /** Settings → Show the guide again. */
    fun restart(ctx: Context) = set(ctx, "replay")

    /** Installed fresh, not updated from an older version (people updating already know the app). */
    private fun freshInstall(ctx: Context): Boolean = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        pi.firstInstallTime == pi.lastUpdateTime
    } catch (_: Exception) { false }

    /** A tab was shown: put up the guide's step if it is on this tab. */
    fun maybeShow(a: Activity) {
        val root = a.findViewById<FrameLayout>(android.R.id.content) ?: return
        root.findViewWithTag<View>(OVERLAY)?.let { root.removeView(it) }
        val home = a is MainActivity
        val loggedIn = Prefs(a).token.isNotEmpty()
        when (stage(a)) {
            "" -> if (!freshInstall(a)) set(a, "done") else if (home) startOrLogin(a, root, loggedIn)
            "replay" -> if (home) startOrLogin(a, root, loggedIn)
            // the log-in step is shown once only; opening the app logged out later does not bring it back
            "login" -> if (home && loggedIn) { set(a, "tour"); showTour(a, root, 0, switchTabs = false) }
            // Back out of a tab mid-tour does not drag you back: the tour waits until you are on its tab again
            "tour" -> showTour(a, root, sp(a).getInt("step", 0), switchTabs = false)
            "end" -> showEnd(a, root)
        }
    }

    private fun startOrLogin(a: Activity, root: FrameLayout, loggedIn: Boolean) {
        if (loggedIn) { set(a, "tour"); showTour(a, root, 0, switchTabs = false) }
        else { set(a, "login"); show(a, root, login, "", null) }
    }

    /** From step [from]: the first one there is on this tab, or (after Next) switch to the tab of the next one. */
    private fun showTour(a: Activity, root: FrameLayout, from: Int, switchTabs: Boolean) {
        for (k in from until tour.size) {
            val s = tour[k]
            if (s.screen != a.javaClass) {
                if (!switchTabs) return
                set(a, "tour", k)
                root.findViewWithTag<View>("nav:" + tabLabel[s.screen])?.performClick()
                return
            }
            if (find(root, s.tag) == null) continue
            set(a, "tour", k)
            val last = k == tour.size - 1
            show(a, root, s, "${k + 1} of ${tour.size}", if (last) "Done" else "Next") {
                if (last) set(a, "done") else showTour(a, root, k + 1, switchTabs = true)
            }
            return
        }
        set(a, "done")
    }

    private fun showEnd(a: Activity, root: FrameLayout) {
        set(a, "end")
        show(a, root, end, "", "Got it", skip = false) { set(a, "done") }
    }

    private const val OVERLAY = "guideOverlay"

    /** The control for [tag], when it is on screen. "navBar": the bottom bar; "notifCard": the first notification card. */
    private fun find(root: View, tag: String): View? = when (tag) {
        "navBar" -> root.findViewWithTag<View>("nav:Home")?.parent as? View
        "notifCard" -> firstOf(root) { it is SwipeCard }
        else -> root.findViewWithTag(tag)
    }?.takeIf { it.isShown }

    private fun firstOf(v: View, test: (View) -> Boolean): View? {
        if (!v.isShown) return null
        if (test(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstOf(v.getChildAt(i), test)?.let { return it }
        return null
    }

    private fun show(
        a: Activity, root: FrameLayout, step: Step, count: String, nextLabel: String?, skip: Boolean = true, onNext: () -> Unit = {},
    ) {
        val target = find(root, step.tag) ?: return
        // bring it into view in the scrolling page first, then measure where it is
        target.requestRectangleOnScreen(Rect(0, 0, target.width, target.height), true)
        val o = Overlay(a, step.tapThrough).apply { tag = OVERLAY }
        root.addView(o, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(18), a.dp(14), a.dp(14), a.dp(10))
            background = roundBg(Ui.BAR, Ui.BORDER, a, Radius.LG)
            elevation = a.dp(6).toFloat()
            alpha = 0f
            isClickable = true
        }
        if (count.isNotEmpty()) card.addView(a.tv(count, Type.CAPTION, Ui.DIM))
        card.addView(a.tv(step.title, Type.BODY_L, Ui.TEXT, true))
        card.addView(a.tv(step.text, Type.BODY, Ui.TEXT).apply { setPadding(0, a.dp(4), 0, 0) })
        val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(8), 0, 0) }
        if (skip) row.addView(a.button("Skip guide", Emphasis.TERTIARY) {
            root.removeView(o)
            showEnd(a, root)
        })
        row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        if (nextLabel != null) row.addView(a.button(nextLabel, Emphasis.PRIMARY, Ui.SIGNAL) {
            root.removeView(o)
            onNext()
        })
        card.addView(row)
        o.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = a.dp(16); rightMargin = a.dp(16)
        })

        // after the scroll settles: cut the hole round the control, the note below it (or above when no room)
        o.postDelayed({
            if (o.parent == null) return@postDelayed
            val t = IntArray(2); target.getLocationInWindow(t)
            val w = IntArray(2); o.getLocationInWindow(w)
            val pad = a.dp(6).toFloat()
            val hole = RectF(t[0] - w[0] - pad, t[1] - w[1] - pad, t[0] - w[0] + target.width + pad, t[1] - w[1] + target.height + pad)
            o.hole = hole
            o.invalidate()
            val gap = a.dp(12)
            val below = hole.bottom + gap + card.height <= o.height - a.dp(8)
            card.y = if (below) hole.bottom + gap else maxOf(a.dp(16).toFloat(), hole.top - gap - card.height)
            card.animate().alpha(1f).setDuration(180).start()
            card.announceForAccessibility(step.title + ". " + step.text)
        }, 300)
    }

    /** The dim layer with a clear rounded hole and a ring round the control; [tapThrough] lets taps in the hole reach it. */
    private class Overlay(ctx: Context, private val tapThrough: Boolean) : FrameLayout(ctx) {
        var hole: RectF? = null
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(3).toFloat(); color = Ui.SIGNAL }
        private val r = ctx.dp(16).toFloat()

        init {
            setWillNotDraw(false)
            // its own layer, so the hole clears only the dim and shows the page under it
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isClickable = true
        }

        override fun onDraw(c: Canvas) {
            c.drawColor(0xB3000000.toInt())
            hole?.let { c.drawRoundRect(it, r, r, clear); c.drawRoundRect(it, r, r, ring) }
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            // the control itself can be used (Log in); every other touch stays on the dim
            if (tapThrough && hole?.contains(e.x, e.y) == true) return false
            return true
        }
    }
}
