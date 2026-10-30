package my.spectrum.notify

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Notification history (the bell in the bottom bar): filter chips, tap to expand, swipe to dismiss.
 * Tapping a phone notification opens this page on that record.
 */
class NotificationsActivity : BaseActivity() {
    companion object {
        const val EXTRA_TS = "record_ts"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var p: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var recordsBox: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var filter = ""                 // "" = all
    private var seenBefore = 0L             // records newer than this show a "new" dot
    private var openTs = -1L                // record to open when arriving from a notification
    private var chipSig = ""
    private var recSig = ""
    private var entries: List<NotifyLog.Entry> = emptyList()
    private var logStamp = -1L
    private val cards = HashMap<Long, View>()

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        content = screen("Notifications", navTo = NotificationsActivity::class.java, onRefresh = { Scheduler.pollNow(this) })
        scroll = content.parent as ScrollView

        val recHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), 0, dp(4))
        }
        recHead.addView(tv("Your notifications", Type.BODY, Ui.DIM), lp(0, WRAP, 1f))
        recHead.addView(button("Clear all", Emphasis.TERTIARY) { confirmClear() })
        content.addView(recHead)
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })
        recordsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(recordsBox, lp(MATCH, WRAP))

        seenBefore = p.lastSeen
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        seenBefore = p.lastSeen
        recSig = ""
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        p.lastSeen = System.currentTimeMillis() / 1000   // everything shown so far counts as seen
    }

    /** Opened from a phone notification with nothing behind it: go back to the home screen, not out of the app. */
    override fun finish() {
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        super.finish()
    }

    // ------------------------------------------------------------ small helpers

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun openUrl(url: String) {
        if (url.isEmpty()) return
        AppLink.intent(this, url)?.let { startActivity(it); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
    }


    private fun yIn(v: View, ancestor: View): Int {
        var y = 0
        var c: View? = v
        while (c != null && c !== ancestor) {
            y += c.top
            c = c.parent as? View
        }
        return y
    }

    private fun confirmClear() {
        if (entries.isEmpty()) return
        AlertDialog.Builder(this, Ui.dialogTheme)
            .setTitle("Clear all notifications?")
            .setMessage("This only clears the list in the app. Nothing changes on Spectrum.")
            .setPositiveButton("Clear") { _, _ ->
                NotifyLog.clear(this)
                logStamp = -1L
                recSig = ""
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ rendering
    private fun render() {
        val loggedIn = p.token.isNotEmpty()
        val st = NotifyLog.stamp(this)
        if (st != logStamp) {
            logStamp = st
            val hiddenEvents = getSharedPreferences("health", MODE_PRIVATE).getStringSet("hiddenEvents", emptySet())!!
            entries = NotifyLog.entries(this).filter {
                !NotifyLog.isHidden(this, it.ts) &&
                // every reminder for a hidden task, not only the one that was hidden
                Checker.reminderTaskName(it.title)?.let { n -> Checker.hiddenNameKey(n) in hiddenEvents } != true &&
                !(it.kind == "sys" && it.title == "Check OK") &&
                (p.debugLog || it.kind != "sys" || it.title.startsWith("Check failed") || it.title.startsWith("Last crash") ||
                 it.title.startsWith("Logged in") || it.title.startsWith("Logged out") || it.title.startsWith("Token"))
            }.asReversed()
        }
        renderChips()
        renderRecords(loggedIn)
    }

    // ------------------------------------------------------------ chips and records
    private val filters = listOf("" to "All", Cat.CLASS to "Classes", Cat.DEADLINE to "Deadlines", Cat.MATERIAL to "Materials", Cat.NEWS to "News")

    /** Course page URL from a real /course/view.php link in the entry (never module ids). */
    private fun courseSiteId(anyUrl: String, e: NotifyLog.Entry): String? {
        val site = Regex("""https?://[^/]+""").find(anyUrl)?.value ?: return null
        val re = Regex("""https?://[^\s"'<>)]*/course/view\.php\?id=(\d+)""")
        val id = re.findAll(e.body).map { it.groupValues[1] }.firstOrNull()
            ?: e.buttons.firstOrNull()?.let { re.find(it.second)?.groupValues?.get(1) }
        return id?.let { "$site/course/view.php?id=$it" }
    }

    private fun catOf(e: NotifyLog.Entry) = Cat.of(e.title, e.body)

    private fun renderChips() {
        val notifs = entries.filter { it.kind == "notif" }
        val counts = filters.map { (k, _) -> if (k.isEmpty()) notifs.size else notifs.count { catOf(it) == k } }
        val sig = "$filter:${counts.joinToString(",")}"
        if (sig == chipSig) return
        chipSig = sig
        chipRow.removeAllViews()
        for ((i, f) in filters.withIndex()) {
            val sel = f.first == filter
            val label = if (counts[i] > 0 && f.first.isNotEmpty()) "${f.second}  ${counts[i]}" else f.second
            chipRow.addView(
                tv(label, Type.BODY, if (sel) onAccent() else Ui.TEXT, sel).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(Ui.touch(48))
                    setPadding(dp(16), 0, dp(16), 0)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(Radius.PILL).toFloat()
                        if (sel) setColor(Ui.CYAN) else { setColor(Ui.BAR); setStroke(dp(1), Ui.BORDER) }
                    }
                    setOnClickListener {
                        filter = f.first
                        chipSig = ""
                        recSig = ""
                        render()
                    }
                },
                lp(WRAP, WRAP).apply { rightMargin = dp(8) },
            )
        }
    }

    private fun renderRecords(loggedIn: Boolean) {
        val shown = entries.filter { filter.isEmpty() || (it.kind == "notif" && catOf(it) == filter) }
        val sig = "$loggedIn:$filter:$seenBefore:${shown.size}:${shown.firstOrNull()?.ts}:${shown.firstOrNull()?.title}"
        if (sig == recSig) return
        recSig = sig
        TransitionManager.beginDelayedTransition(recordsBox, AutoTransition().setDuration(180))
        recordsBox.removeAllViews()
        cards.clear()
        if (shown.isEmpty()) {
            recordsBox.addView(emptyState(loggedIn), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        for (e in shown) {
            if (e.kind == "notif") {
                val c = card(e)
                cards[e.ts] = c
                recordsBox.addView(c, lp(MATCH, WRAP).apply { bottomMargin = dp(10) })
            } else {
                val count = if (e.n > 1) " (×${e.n})" else ""
                recordsBox.addView(tv("${ago(NotifyLog.atOf(e))} · ${e.title}$count", Type.CAPTION, Ui.YELLOW).apply { setPadding(dp(4), dp(2), dp(4), dp(12)) })
            }
        }
        if (openTs > 0) {
            val target = cards[openTs]
            if (target != null) scroll.post { scroll.smoothScrollTo(0, maxOf(0, yIn(target, content) - dp(72))) }
            openTs = -1L
        }
    }

    private fun emptyState(loggedIn: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }
        box.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_cat_other)
            setColorFilter(Ui.DIM)
        }, lp(dp(44), dp(44)))
        box.addView(tv(
            if (!loggedIn) "Nothing here yet" else if (filter.isEmpty()) "You're all caught up" else "Nothing in this category",
            Type.TITLE, Ui.TEXT, true,
        ).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(tv(
            if (!loggedIn) "Log in to start receiving class links, deadlines and materials."
            else "New class links, deadlines and materials will show up here.",
            Type.LABEL, Ui.DIM,
        ).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    // ------------------------------------------------------------ one notification record
    private fun card(e: NotifyLog.Entry): View {
        // a deadline reminder is done when its task is (marked here or on Home)
        val task = Checker.reminderTaskName(e.title)
        val done = NotifyLog.isDone(this, e.ts) || (task != null && Checker.isTaskDone(this, task))
        val cat = catOf(e)
        val col = Cat.color(cat)
        var open = e.ts == openTs
        val btns = if (e.buttons.isNotEmpty()) e.buttons else
            Regex("""https?://[^\s"'<>)]+""").findAll(e.body).map { Pair("Open link", it.value) }.distinct().take(3).toList()

        val card = SwipeCard(this) {
            // hidden rather than deleted, so Undo can bring it straight back
            NotifyLog.hide(this, e.ts)
            logStamp = -1L
            recSig = ""
            render()
            undoBar("Notification removed") {
                NotifyLog.unhide(this, e.ts)
                logStamp = -1L
                recSig = ""
                render()
            }
        }
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(14), dp(14), dp(14))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val badge = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.tint(col, 0x2E)) }
        }
        badge.addView(ImageView(this).apply {
            setImageResource(Cat.icon(cat))
            setColorFilter(col)
        }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        row.addView(badge, lp(dp(40), dp(40)))

        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        val meta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        meta.addView(tv(Cat.label(cat), Type.CAPTION, col, true).apply { letterSpacing = 0.06f })
        meta.addView(tv("· ${ago(NotifyLog.atOf(e))}", Type.CAPTION, Ui.DIM).apply { setPadding(dp(6), 0, 0, 0) })
        col2.addView(meta)
        val title = tv(e.title, Type.BODY_L, Ui.TEXT, true).apply {
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }
        if (done) title.paintFlags = title.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        col2.addView(title)
        var body: TextView? = null
        if (e.body.isNotEmpty()) {
            body = tv(e.body.take(3000), Type.LABEL, Ui.DIM).apply {
                ellipsize = TextUtils.TruncateAt.END
                setLinkTextColor(Ui.CYAN)
                setPadding(0, dp(6), 0, 0)
                Linkify.addLinks(this, Linkify.WEB_URLS)       // links inside the text open when tapped
                movementMethod = LinkMovementMethod.getInstance()
            }
            col2.addView(body)
        }
        row.addView(col2, lp(0, WRAP, 1f))
        val unread = NotifyLog.atOf(e) > seenBefore
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.CYAN) }
            visibility = if (unread) View.VISIBLE else View.GONE
        }
        row.addView(dot, lp(dp(10), dp(10)).apply { topMargin = dp(4) })
        card.addView(row)

        // link buttons: the first is always visible, the rest appear when the card is opened
        val chips = ArrayList<TextView>()
        val extraChips = ArrayList<Pair<String, () -> Unit>>()
        if (cat == Cat.NEWS) {
            // announcement: open the course's forum instead of the one post
            val courseUrl = courseSiteId(e.body, e) ?: btns.firstOrNull()?.second?.let { courseSiteId(it, e) }
            val forumUrl = courseUrl?.replaceFirst("/course/view.php?id=", "/mod/forum/index.php?id=")
            if (!forumUrl.isNullOrEmpty()) extraChips.add(Pair("Check forum") { openUrl(forumUrl) })
        }
        if (cat == Cat.MATERIAL) {
            val courseUrl = courseSiteId(e.body, e) ?: btns.firstOrNull()?.second?.let { courseSiteId(it, e) }
            if (!courseUrl.isNullOrEmpty()) extraChips.add(Pair("View course materials") { openUrl(courseUrl) })
        }
        if (cat == Cat.DEADLINE) {
            extraChips.add(Pair(if (done) "Mark as not done" else "Mark as done") {
                if (done) {
                    NotifyLog.unmarkDone(this, e.ts)
                    if (task != null) Checker.setTaskDone(this, task, false)
                    logStamp = -1L; recSig = ""; render()
                } else {
                    AlertDialog.Builder(this, Ui.dialogTheme)
                        .setTitle("Mark as done?")
                        .setMessage("The title stays on the list with a line through it, so you can still find it, and you get no more reminders for this task. Nothing is deleted, and you can undo with \"Mark as not done\".")
                        .setPositiveButton("Mark as done") { _, _ ->
                            NotifyLog.markDone(this, e.ts)
                            if (task != null) Checker.setTaskDone(this, task, true)
                            logStamp = -1L; recSig = ""; render()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
            extraChips.add(Pair("Hide this (wrong?)") {
                // reminders carry only the task name ("Due in ~5h: Quiz 2"): hide that name everywhere
                // (Home, reminders, morning summary), not just this one line
                val keys = listOfNotNull(Checker.reminderTaskName(e.title)?.let { Checker.hiddenNameKey(it) })
                keys.forEach { NotifyLog.hideEvent(this, it) }
                NotifyLog.hide(this, e.ts)
                logStamp = -1L
                recSig = ""
                render()
                undoBar("Deadline hidden") {
                    keys.forEach { NotifyLog.unhideEvent(this, it) }
                    NotifyLog.unhide(this, e.ts)
                    logStamp = -1L
                    recSig = ""
                    render()
                }
            })
        }
        if (btns.isNotEmpty() || extraChips.isNotEmpty()) {
            val chipLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                // lines up with the title, not the category badge (40dp badge + 12dp gap)
                setPadding(dp(52), dp(12), 0, 0)
            }
            for ((i, b) in btns.withIndex()) {
                val chip = button(b.first, if (i == 0) Emphasis.PRIMARY else Emphasis.SECONDARY, col) { openUrl(b.second) }
                chips.add(chip)
                chipLine.addView(chip, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
            for (x in extraChips) {
                val chip = button(x.first, Emphasis.SECONDARY, col) { x.second() }
                chips.add(chip)
                chipLine.addView(chip, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
            card.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipLine)
            })
        }

        fun applyOpen() {
            title.maxLines = if (open) Int.MAX_VALUE else 2
            body?.maxLines = if (open) Int.MAX_VALUE else 2
            body?.setTextColor(if (open) Ui.TEXT else Ui.DIM)
            for ((i, c) in chips.withIndex()) c.visibility = if (i == 0 || open) View.VISIBLE else View.GONE
            card.background = roundBg(Ui.BAR, if (open) col else Ui.BORDER, this, Radius.MD)
            if (open) dot.visibility = View.GONE
        }
        fun toggle() {
            TransitionManager.beginDelayedTransition(recordsBox, AutoTransition().setDuration(180))
            open = !open
            applyOpen()
        }
        applyOpen()
        card.setOnClickListener { toggle() }
        body?.setOnClickListener { toggle() }       // tapping plain text still expands; tapping a link opens it
        return card
    }

    // ------------------------------------------------------------ intent from a phone notification
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val ts = i.getLongExtra(EXTRA_TS, -1L)
        if (ts > 0) {
            // opened from a notification: show that record, opened and scrolled into view
            i.removeExtra(EXTRA_TS)
            openTs = ts
            filter = ""
            chipSig = ""
            recSig = ""
            logStamp = -1L
            if (::recordsBox.isInitialized) render()
        }
    }
}
