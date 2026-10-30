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
 * Home screen for students: greeting, "up next" class with countdown and Join button, and deadlines coming up (reminders only).
 * Bottom bar: courses, timetable, notifications (bell), settings. The notification history is in NotificationsActivity;
 * the profile (login) is a section of Settings.
 */
class MainActivity : BaseActivity() {
    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var p: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var greetTitle: TextView
    private lateinit var greetSub: TextView
    private lateinit var syncLabel: TextView
    private lateinit var refreshBtn: ImageButton
    private lateinit var setupBox: LinearLayout
    private lateinit var heroBox: LinearLayout
    private lateinit var dueSection: LinearLayout
    private lateinit var dueRow: LinearLayout
    private lateinit var eventsSection: LinearLayout
    private lateinit var eventsList: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var bellDot: View
    private lateinit var themeBtn: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var heroSig = ""
    private var dueSig = ""
    private var ov = Checker.Overview(emptyList(), emptyList())
    private var ovAt = 0L
    private var entries: List<NotifyLog.Entry> = emptyList()
    private var logStamp = -1L
    private var refreshing = false
    private var refreshStart = 0L
    private var refreshBase = 0L
    private var spin: ObjectAnimator? = null
    private var heroClasses: List<Checker.ClassItem> = emptyList()
    private var heroPos = 0
    private var heroDragging = false
    private var onSlide: ((Int) -> Unit)? = null

    /** While a refresh runs, look for its result every second (the 30 s ticker is far too slow). */
    private val refreshWatch = object : Runnable {
        override fun run() {
            if (!refreshing) return
            if (checkRefreshDone()) render() else handler.postDelayed(this, 1000)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30000)  // 30s: only the countdown text needs frequent updates, and now it comes from sig-free rebuilds
        }
    }

    // ------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        applyBars()
        // the notification permission is asked from the "Get set up" card, with the reason shown first
        buildUi()
        handleIntent(intent)
        Scheduler.startupCheck(this)   // auto-detect courses + Occ on open when data is stale
        Scheduler.ensurePolling(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        ovAt = 0L
        handler.post(ticker)
        if (refreshing) handler.post(refreshWatch)
        // the Occ dialog was still open when the screen was rebuilt (you switched apps): bring it back, picks kept
        if (getSharedPreferences("health", MODE_PRIVATE).getBoolean(semFlag("occDialogOpen"), false)) confirmOccDialog(manual = true)
        else confirmOccDialog()
    }

    override fun onDestroy() {
        // the screen is going (e.g. rebuilt while you were in another app): close the Occ dialog quietly, without
        // counting it as answered, so onResume of the new screen reopens it with your picks
        occDialog?.let { it.setOnDismissListener(null); it.dismiss() }
        occDialog = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        setupSig = ""
        render()
    }

    private var occDialogShown = false
    private var occDialog: android.app.AlertDialog? = null

    private data class OccAsk(val code: String, val cname: String, val occs: List<String>, val predicted: String?)

    /** Courses whose Occ the user has not confirmed yet (detected from visible Occ pages). */
    private fun pendingOccAsks(): List<OccAsk> {
        val st = State(this)
        val seen = st.j.optJSONObject("occ_seen") ?: return emptyList()
        val names = st.j.optJSONObject("occ_courses") ?: JSONObject()
        val codes = Courses.all(this).associate { it.id to it.code.uppercase() }
        val asks = ArrayList<OccAsk>()
        for (cidS in seen.keys()) {
            val code = codes[cidS.toLongOrNull() ?: -1L] ?: continue
            if (Prefs(this).occConfirmed(code)) continue
            // prediction = attendance/event-name learning (my_groups); options = ALL catalog occs,
            // falling back to predicted, then to availability-seen. Shown even when empty.
            // dropdown = ALL catalog occs (fallback: availability-seen); prefill = predictedOcc
            val predictedNum = Groups.predictedOcc(this)[code.uppercase()]
            val occs = (Catalog.find(this, code)?.occs() ?: emptyList())
                .ifEmpty {
                    (seen.optJSONArray(cidS) ?: JSONArray()).strs()
                        .mapNotNull { Regex("occ([0-9]+[a-z]?)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                }
                .distinct().sortedBy { it.toIntOrNull() ?: 999 }
            asks.add(OccAsk(code, names.optString(cidS, code), occs, predictedNum))
        }
        return asks
    }

    /**
     * After the auto-detection finds courses with visible Occ pages, ask the user to confirm
     * each one (the detection is a good guess, but only the user knows their real Occ).
     * Opens by itself once; after that only from the "Get set up" card ([manual]).
     * A course left on "?" stays unconfirmed and is asked again.
     */
    private fun confirmOccDialog(manual: Boolean = false) {
        if (occDialogShown) return
        val flags = getSharedPreferences("health", MODE_PRIVATE)
        if (!manual && flags.getBoolean(semFlag("occAutoAsked"), false)) return
        val asks = pendingOccAsks()
        if (asks.isEmpty()) {
            // nothing left to confirm (done elsewhere meanwhile): no dialog to bring back
            flags.edit().putBoolean(semFlag("occDialogOpen"), false).remove(semFlag("occDraft")).apply()
            return
        }
        occDialogShown = true
        // "occDialogOpen" stays set until you answer (Save all / Later / Back), so a rebuilt screen reopens it
        flags.edit().putBoolean(semFlag("occAutoAsked"), true).putBoolean(semFlag("occDialogOpen"), true).apply()
        // your picks so far (kept while you look things up in another app): course code -> Occ
        val draft = try { JSONObject(flags.getString(semFlag("occDraft"), "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        fun saveDraft() = flags.edit().putString(semFlag("occDraft"), draft.toString()).apply()
        fun closeDialog() = flags.edit().putBoolean(semFlag("occDialogOpen"), false).remove(semFlag("occDraft")).apply()

        // one dialog with the My-classes layout: scrollable content (last courses reachable),
        // sized below the app header
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(10), dp(24), 0) }
        val box = ScrollView(this).apply { addView(content) }
        // prefill: what you already picked (draft), else the attendance-learning prediction, in the full option list
        val choices = IntArray(asks.size) { i ->
            val picked = draft.optString(asks[i].code, "")
            if (picked.isNotEmpty() && picked in asks[i].occs) asks[i].occs.indexOf(picked)
            else asks[i].predicted?.let { pnum -> asks[i].occs.indexOf(pnum) } ?: -1
        }
        asks.forEachIndexed { i, a ->
            // single-line row: code·name on the left, occ dropdown on the right
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, if (i == 0) 0 else dp(8), 0, 0)
            }
            row.addView(tv(a.code + if (a.cname != a.code) " · ${a.cname}" else "", Type.LABEL, Ui.TEXT, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            val hasPrediction = choices[i] >= 0
            val opts = when {
                a.occs.isEmpty() -> listOf("N/A")
                hasPrediction -> a.occs.map { it }
                else -> listOf("?") + a.occs.map { it }
            }
            // with a "?" first, position 1 is the first Occ; without it, position 0 is (this used to be off by one)
            val offset = if (opts.firstOrNull() == "?") 1 else 0
            val spinnerSel = if (hasPrediction) choices[i] else 0
            val sp = spinner(opts, spinnerSel) { pos ->
                if (a.occs.isEmpty()) return@spinner
                choices[i] = pos - offset
                if (choices[i] >= 0) draft.put(a.code, a.occs[choices[i]]) else draft.remove(a.code)
                saveDraft()
            }
            sp.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            row.addView(sp, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
            content.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        occDialog = android.app.AlertDialog.Builder(this, Ui.dialogTheme)
            .setTitle("Confirm your Occ")
            .setMessage("Prefilled from your attendance and materials — please confirm each. Unlisted courses keep working without a filter.")
            .setView(box)
            .setPositiveButton("Save all") { _, _ ->
                closeDialog()
                val notInCatalog = ArrayList<String>()
                asks.forEachIndexed { i, a ->
                    val v = choices[i]
                    // "?" is not an answer: that course stays unconfirmed and is asked again
                    if (v >= 0 || a.occs.isEmpty()) Prefs(this).setOccConfirmed(a.code)
                    if (v >= 0 && a.occs.isNotEmpty()) {
                        val keep = p.myGroups.split(",").map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("${a.code}:", true) }
                        p.myGroups = (keep + "${a.code}:occ${a.occs[v]}").joinToString(", ")
                        val setup = Timetable.autoSetup(this, a.code, a.occs[v])
                        if (!setup) notInCatalog.add(a.code)
                    }
                }
                if (notInCatalog.isNotEmpty()) {
                    Toast.makeText(this,
                        "${notInCatalog.joinToString(", ")}: class times will be learned from Spectrum automatically.",
                        Toast.LENGTH_LONG).show()
                }
                ovAt = 0L
            }
            .setNegativeButton("Later") { _, _ -> closeDialog() }
            .setOnCancelListener { closeDialog() }   // Back = Later
            // the next steps (check times, short names) are on the "Get set up" card, not more dialogs
            .setOnDismissListener { occDialogShown = false; occDialog = null; setupSig = ""; render() }
            .create()
        // a stray tap outside must not throw away your picks
        occDialog?.setCanceledOnTouchOutside(false)
        occDialog?.show()
    }

    // ------------------------------------------------------------ "Get set up" card

    private var setupSig = ""
    /** Setup guide expanded to every step (collapsed: progress and the next step only). */
    private var setupOpen = false

    /** A setup flag that starts fresh each semester: new subjects mean a new Occ to confirm and new times to check. */
    private fun semFlag(name: String): String {
        val flags = getSharedPreferences("health", MODE_PRIVATE)
        val key = name + ":" + AcademicCalendar.planningLabel(this)
        // v0.25.1 kept these once per install: count them for the semester you are in now
        if (!flags.contains(key) && flags.getBoolean(name, false)) flags.edit().putBoolean(key, true).remove(name).apply()
        return key
    }

    private class SetupStep(val title: String, val why: String, val done: Boolean, val button: String, val optional: Boolean = false, val go: (() -> Unit)?)

    /** Every step a new install needs, in order; a step's button does that step (or opens the screen for it). */
    private fun setupSteps(loggedIn: Boolean): List<SetupStep> {
        val flags = getSharedPreferences("health", MODE_PRIVATE)
        val notifOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        // some phones (Samsung especially) keep reporting "optimised" after you pick Unrestricted on the app's
        // battery page; after one try you can confirm it yourself instead of being asked forever
        val batteryAsked = flags.getBoolean("batteryAsked", false)
        val batteryOk = batteryUnrestricted() || flags.getBoolean("batteryConfirmed", false)
        val checked = p.lastRunTs > 0L
        val occPending = loggedIn && pendingOccAsks().isNotEmpty()
        return listOf(
            SetupStep("Allow notifications", "Class links, deadlines and new materials arrive as notifications.", notifOk, "Allow") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            },
            SetupStep("Log in to Spectrum", "Uses your normal UM sign-in. Your login stays on this phone.", loggedIn, "Log in") {
                startActivity(Intent(this, LoginWebActivity::class.java))
            },
            SetupStep("Allow exact alarms", "So the class reminder arrives about 10 minutes before class, not late.", Scheduler.canExact(this), "Allow") {
                if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            },
            if (!batteryAsked || batteryOk) SetupStep("Let checks run in the background",
                "Allow Spectrum Notify to run in the background (Unrestricted battery), so Android does not stop the checks.",
                batteryOk, "Allow") {
                flags.edit().putBoolean("batteryAsked", true).apply()
                askBatteryUnrestricted()
            } else SetupStep("Let checks run in the background",
                "Already set the app's battery to Unrestricted? Some phones don't report it — tap Done. Otherwise tap Allow again.",
                false, "Done") {
                flags.edit().putBoolean("batteryConfirmed", true).apply()
                setupSig = ""
                render()
            },
            SetupStep("Confirm your Occ",
                if (!checked) "Your Occ (class group) is detected after the first check." else "Your Occ is your class group, e.g. Occ 2. It decides which links and posts are yours.",
                loggedIn && checked && !occPending, "Confirm", go = if (occPending) ({ confirmOccDialog(manual = true) }) else null),
            SetupStep("Check your class times", "Open the timetable once and tap any class whose time looks wrong to fix it.",
                flags.getBoolean(semFlag("setupReviewed"), false), "Open") {
                flags.edit().putBoolean(semFlag("setupReviewed"), true).apply()
                startActivity(Intent(this, TimetableActivity::class.java))
            },
            SetupStep("Give courses short names", "Optional: \"HCI\" is easier to spot than WIA2010.",
                flags.getBoolean("setupNamed", false), "Open", optional = true) {
                flags.edit().putBoolean("setupNamed", true).apply()
                startActivity(Intent(this, CoursesActivity::class.java))
            },
        )
    }

    private fun renderSetup(loggedIn: Boolean) {
        val flags = getSharedPreferences("health", MODE_PRIVATE)
        val hidden = flags.getBoolean(semFlag("setupHidden"), false)
        if (hidden) {
            if (setupSig != "hidden") { setupSig = "hidden"; setupBox.removeAllViews() }
            return
        }
        val steps = setupSteps(loggedIn)
        val required = steps.filter { !it.optional }
        // done flag, wording and whether there is a button: any of them changing redraws the card
        // (a check finishing changes the Occ step's text and gives it a button without ticking it)
        val sig = steps.joinToString("|") { "${it.done}:${it.button}:${it.go != null}:${it.why}" } + hidden
        if (sig == setupSig) return
        setupSig = sig
        setupBox.removeAllViews()
        if (hidden || steps.all { it.done }) return

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            background = roundBg(Ui.BAR, Ui.BORDER, this@MainActivity, Radius.MD)
        }
        val next = steps.firstOrNull { !it.done && it.go != null }
        val doneCount = required.count { it.done }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(Ui.touch(48))
            isClickable = true
            contentDescription = "Get set up, $doneCount of ${required.size} done. " +
                (if (setupOpen) "Tap to show only the next step" else "Tap to show all steps")
            setOnClickListener { setupOpen = !setupOpen; setupSig = ""; render() }
        }
        head.addView(tv("Get set up", Type.BODY_L, Ui.TEXT, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(tv("$doneCount of ${required.size} done", Type.CAPTION, Ui.DIM, true))
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_expand_more)
            setColorFilter(Ui.DIM)
            rotation = if (setupOpen) 180f else 0f
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(6) })
        card.addView(head)
        // progress bar: the done share of the required steps
        val frac = doneCount.toFloat() / maxOf(1, required.size)
        val bar = LinearLayout(this).apply { background = roundBg(Ui.tint(Ui.DIM, 0x33), 0, this@MainActivity, Radius.SM) }
        bar.addView(View(this).apply { background = roundBg(Ui.GREEN, 0, this@MainActivity, Radius.SM) }, lp(0, dp(4), frac.coerceAtLeast(0.02f)))
        bar.addView(View(this), lp(0, dp(4), (1f - frac).coerceAtLeast(0f)))
        card.addView(bar, lp(MATCH, dp(4)).apply { topMargin = dp(2) })
        for (s in steps) {
            if (!setupOpen && s !== next) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            row.addView(ImageView(this).apply {
                setImageResource(if (s.done) R.drawable.ic_check else R.drawable.ic_circle)
                setColorFilter(if (s.done) Ui.GREEN else Ui.DIM)
                contentDescription = if (s.done) "Done" else "Not done"
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(tv(s.title + if (s.optional) " (optional)" else "", Type.BODY, if (s.done) Ui.DIM else Ui.TEXT, !s.done))
            if (!s.done) text.addView(tv(s.why, Type.CAPTION, Ui.DIM))
            row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (!s.done && s.go != null) {
                row.addView(button(s.button, if (s === next) Emphasis.PRIMARY else Emphasis.SECONDARY) { s.go.invoke(); setupSig = "" }.apply {
                    contentDescription = "${s.button}: ${s.title}"
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
            }
            card.addView(row)
        }
        if (setupOpen) card.addView(tv("Hide this guide", Type.CAPTION, Ui.DIM, true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            minHeight = dp(Ui.touch(48))
            isClickable = true
            setOnClickListener {
                flags.edit().putBoolean(semFlag("setupHidden"), true).apply()
                setupSig = ""
                render()
                undoBar("Setup guide hidden") { flags.edit().putBoolean(semFlag("setupHidden"), false).apply(); setupSig = ""; render() }
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        setupBox.addView(card, lp(MATCH, WRAP))
    }

    override fun onPause() {
        handler.removeCallbacks(refreshWatch)
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------ small helpers

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    private fun openUrl(url: String) {
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
    }


    private fun firstName(): String {
        val n = p.userName.trim().split(" ").firstOrNull().orEmpty()
        return if (n.isEmpty()) "" else n.lowercase().replaceFirstChar { it.uppercase() }
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

    // ------------------------------------------------------------ layout
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(80))   // clear of the floating Aa button
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        attachPullToRefresh(scroll) { startRefresh() }
        // scrollable column, bottom bar pinned under it (weighted heights so scrolling works)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(scroll, lp(MATCH, 0, 1f))

        // app title row: THE shared header (same implementation as every page)
        themeBtn = (headerRow(homeMode = true).getChildAt(2) as ImageButton)
        // same place as on every other tab (screen() uses 8dp top, 16 left, 12 right; content already pads 16)
        content.addView(themeBtn.parent as View, lp(MATCH, WRAP).apply { topMargin = dp(8); rightMargin = dp(-4) })

        // greeting + refresh sit on their own card so they stand out from the BG
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            background = roundBg(Ui.BAR, Ui.BORDER, this@MainActivity, Radius.LG)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        greetTitle = tv("", Type.DISPLAY, Ui.TEXT, true)
        greetSub = tv("", Type.BODY, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) }
        syncLabel = tv("", Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(6), 0, 0) }
        texts.addView(greetTitle)
        texts.addView(greetSub)
        texts.addView(syncLabel)
        header.addView(texts, lp(0, WRAP, 1f))
        refreshBtn = ImageButton(this).apply {
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(Ui.CYAN)
            background = rippleBg(Ui.BG, Ui.BORDER, this@MainActivity, Radius.PILL)
            contentDescription = "Check Spectrum now"
            setOnClickListener { startRefresh() }
        }
        header.addView(refreshBtn, lp(dp(Ui.touch(48)), dp(Ui.touch(48))))
        content.addView(header, lp(MATCH, WRAP).apply { topMargin = dp(14); bottomMargin = dp(12) })

        // the next class leads; the setup guide comes after it (it used to push the class below the fold)
        heroBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(heroBox, lp(MATCH, WRAP))

        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(setupBox, lp(MATCH, WRAP).apply { topMargin = dp(12) })

        // deadlines strip
        dueSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        dueSection.addView(sectionLabel("DUE SOON").apply {
            setPadding(dp(2), dp(26), 0, dp(10))
        })
        dueRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dueSection.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(dueRow)
        })
        content.addView(dueSection, lp(MATCH, WRAP))

        // user-created events, next 7 days: a simple list, tap to edit
        eventsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        eventsSection.addView(sectionLabel("YOUR EVENTS").apply {
            setPadding(dp(2), dp(26), 0, dp(10))
        })
        eventsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        eventsSection.addView(eventsList)
        content.addView(eventsSection, lp(MATCH, WRAP))

        // shared bottom nav; keep a reference to the bell dot for the unread marker
        navBar = bottomNav(MainActivity::class.java)
        bellDot = navBar.navBellDot()
        page.addView(View(this).apply { setBackgroundColor(Ui.BORDER) }, lp(MATCH, 1))
        page.addView(navBar, lp(MATCH, WRAP))
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))

        // floating toolbox group (Aa + back to top); Home used to add a second, older Aa on top of it
        attachToolbox(root, scroll)
        setContentView(root)
    }

    // ------------------------------------------------------------ refresh button
    private fun startRefresh() {
        if (p.token.isEmpty()) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (refreshing) return
        if (Checker.inQuiet(java.time.ZonedDateTime.now(zone()), p)) {
            Toast.makeText(this, "Quiet hours (${p.quietStart}:00–${p.quietEnd}:00): checks are paused until they end.", Toast.LENGTH_LONG).show()
            return
        }
        val online = try { getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork != null } catch (_: Exception) { true }
        if (!online) {
            Toast.makeText(this, "No internet connection. The app checks again once you are online.", Toast.LENGTH_LONG).show()
            return
        }
        refreshing = true
        refreshStart = System.currentTimeMillis()
        refreshBase = lastAttempt()
        spin = ObjectAnimator.ofFloat(refreshBtn, View.ROTATION, 0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        Scheduler.pollNow(this)
        handler.removeCallbacks(refreshWatch)
        handler.postDelayed(refreshWatch, 1000)
    }

    /** When the background check last finished (success or failure), written by Checker.run. */
    private fun lastAttempt(): Long = getSharedPreferences("health", MODE_PRIVATE).getLong("lastAttemptMs", 0L)

    /** True when a running refresh just ended: stops the spinner, reloads the data, says how it went. */
    private fun checkRefreshDone(): Boolean {
        if (!refreshing) return false
        val changed = lastAttempt() != refreshBase
        val timedOut = System.currentTimeMillis() - refreshStart > 45000
        if (!changed && !timedOut) return false
        refreshing = false
        handler.removeCallbacks(refreshWatch)
        spin?.cancel()
        refreshBtn.rotation = 0f
        ovAt = 0L
        heroSig = ""
        dueSig = ""
        try { NextClassWidget.updateAll(this) } catch (_: Exception) { }
        Toast.makeText(
            this,
            when {
                !changed -> "Still checking in the background"
                p.lastError.isEmpty() -> "Up to date"
                else -> "Could not reach Spectrum"
            },
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    /** Dot on the bell while there are notifications you have not opened yet. */
    private fun renderBell() {
        val unread = entries.any { it.kind == "notif" && NotifyLog.atOf(it) > p.lastSeen }
        bellDot.visibility = if (unread) View.VISIBLE else View.GONE
    }

    /** Sun/moon on the title row: shows what a tap switches to. */
    private fun updateThemeBtn() {
        themeBtn.setImageResource(if (Ui.dark) R.drawable.ic_sun else R.drawable.ic_moon)
    }


    // ------------------------------------------------------------ rendering
    private fun render() {
        checkRefreshDone()
        val loggedIn = p.token.isNotEmpty()
        val nowMs = System.currentTimeMillis()
        if (nowMs - ovAt > 15000) {
            ov = try { Checker.overview(this) } catch (e: Exception) { Checker.Overview(emptyList(), emptyList()) }
            ovAt = nowMs
        }
        val st = NotifyLog.stamp(this)
        if (st != logStamp) {
            logStamp = st
            entries = NotifyLog.entries(this).filter { !(it.kind == "sys" && it.title == "Check OK") }.asReversed()
        }
        renderHeader(loggedIn)
        renderSetup(loggedIn)
        renderHero(loggedIn)
        renderDue()
        renderEvents()
        renderBell()
    }

    private fun renderHeader(loggedIn: Boolean) {
        val h = LocalDateTime.now(zone()).hour
        val hello = if (h < 12) "Good morning" else if (h < 18) "Good afternoon" else "Good evening"
        val name = if (loggedIn) firstName() else ""
        greetTitle.text = if (name.isEmpty()) hello else "$hello, $name"
        val today = LocalDateTime.now(zone()).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
        val left = ov.classes.count { isToday(it.start) }
        greetSub.text = if (!loggedIn) today else
            today + " · " + (if (left == 0) "No more classes today" else if (left == 1) "1 class left today" else "$left classes left today")

        val ts = p.lastRunTs
        when {
            !loggedIn -> { syncLabel.text = "● Not logged in"; syncLabel.setTextColor(Ui.RED) }
            p.lastError.isNotEmpty() -> { syncLabel.text = "● Last check failed. Tap refresh to retry."; syncLabel.setTextColor(Ui.RED) }
            ts == 0L -> { syncLabel.text = "● Not checked yet. Tap refresh."; syncLabel.setTextColor(Ui.DIM) }
            else -> { syncLabel.text = "● Synced ${ago(ts)}"; syncLabel.setTextColor(Ui.GREEN) }
        }

    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        tv(label, Type.BODY, if (filled) Ui.HERO_A else 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(Radius.LG).toFloat()
                if (filled) setColor(0xFFFFFFFF.toInt()) else { setColor(0x00000000); setStroke(dp(1), 0xB3FFFFFF.toInt()) }
            }
            setOnClickListener { onClick() }
        }

    private fun renderHero(loggedIn: Boolean) {
        val now = System.currentTimeMillis() / 1000
        val next = ov.classes.firstOrNull()
        val sig = when {
            !loggedIn -> "out"
            next == null -> "none:${ov.due.firstOrNull()?.ts}:${ov.due.firstOrNull()?.let { relShort(it.ts - now) }}"
            // the minute is part of the signature so "Starts in …" and the progress bar stay current
            else -> ov.classes.joinToString("|") { "${it.start}:${it.end}:${it.link}" } + ":" + now / 60
        }
        if (sig == heroSig || heroDragging) return
        heroSig = sig

        // today's classes become swipeable slides, then tomorrow's — capped at TWO days
        val slides: List<Checker.ClassItem> = when {
            !loggedIn || next == null -> if (next != null) listOf(next) else emptyList()
            else -> {
                val firstDay = localDt(next.start).toLocalDate()
                val first = ov.classes.filter { localDt(it.start).toLocalDate() == firstDay }
                val secondDay = ov.classes
                    .filter { localDt(it.start).toLocalDate() != firstDay }
                    .groupBy { localDt(it.start).toLocalDate() }
                    .toSortedMap()
                    .entries.firstOrNull()?.value ?: emptyList()
                first + secondDay
            }
        }
        heroClasses = slides
        if (heroPos >= slides.size) heroPos = 0

        val white = 0xFFFFFFFF.toInt()
        val soft = 0xD9FFFFFF.toInt()
        // per-course gradient: keep the course hue, clamp into a dark band so white text
        // always reads — deeper in light theme, brighter in dark theme (fail-safe: default gradient)
        fun shiftColour(c: Int, amount: Float): Int {
            val rf = ((c shr 16) and 0xFF) / 255f
            val gf = ((c shr 8) and 0xFF) / 255f
            val bf = (c and 0xFF) / 255f
            val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
            val l = (max + min) / 2f
            val sat = if (max == min) 0f else (max - min) / (1f - abs(2f * l - 1f)).coerceAtLeast(0.001f)
            val h = when (max) {
                min -> 0f
                rf -> (((gf - bf) / (max - min)).let { if (it < 0) it + 6f else it }) / 6f
                gf -> ((bf - rf) / (max - min) + 2f) / 6f
                else -> ((rf - gf) / (max - min) + 4f) / 6f
            }
            val lBase = if (Ui.dark) 0.34f else 0.24f
            val l2 = (lBase + amount * 0.08f).coerceIn(if (Ui.dark) 0.24f else 0.16f, if (Ui.dark) 0.42f else 0.30f)
            val s2 = (sat * if (Ui.dark) 0.85f else 1.0f).coerceIn(0.35f, 0.80f)
            fun hue2(p: Float, q: Float, tIn: Float): Float {
                var t = tIn
                if (t < 0) t += 1f
                if (t > 1) t -= 1f
                return when {
                    t < 1f / 6f -> p + (q - p) * 6f * t
                    t < 1f / 2f -> q
                    t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                    else -> p
                }.coerceIn(0f, 1f)
            }
            val q = if (l2 < 0.5f) l2 * (1f + s2) else l2 + s2 - l2 * s2
            val pr = 2f * l2 - q
            val r2 = (hue2(pr, q, h + 1f / 3f) * 255).toInt().coerceIn(0, 255)
            val g2 = (hue2(pr, q, h) * 255).toInt().coerceIn(0, 255)
            val b2 = (hue2(pr, q, h - 1f / 3f) * 255).toInt().coerceIn(0, 255)
            return 0xFF000000.toInt() or (r2 shl 16) or (g2 shl 8) or b2
        }
        fun heroBg(course: String): GradientDrawable {
            val tint = Timetable.colours(this, setOf(course))[course]
            return if (tint == null) {
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.HERO_A, Ui.HERO_B))
            } else {
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(shiftColour(tint, 0f), shiftColour(tint, -0.1f)))
            }.apply { cornerRadius = dp(Radius.LG).toFloat() }
        }
        fun label(t: String) = tv(t, Type.CAPTION, soft, true).apply { letterSpacing = 0.08f }

        if (slides.isEmpty()) {
            heroBox.removeAllViews()
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(20))
                background = heroBg("")
            }
            if (!loggedIn) {
                card.addView(label("WELCOME"))
                card.addView(tv("Never miss a class link", Type.HEADLINE, white, true).apply { setPadding(0, dp(6), 0, 0) })
                card.addView(tv("Log in with your Spectrum account to get class links, deadlines and new materials here.", Type.BODY, soft).apply { setPadding(0, dp(6), 0, dp(14)) })
                card.addView(pill("Log in", true) { startActivity(Intent(this, LoginWebActivity::class.java)) }, lp(WRAP, WRAP))
            } else {
                val due = ov.due.firstOrNull()
                val today = java.time.LocalDate.now(zone())
                val inBreak = !AcademicCalendar.inTeaching(this, today)
                // say which part of the calendar it is (break, revision, exams…), not "semester break" for all of them
                val period = AcademicCalendar.describe(this, today) ?: "No teaching today"
                card.addView(label(if (inBreak) "HOLIDAY MODE" else "ALL CLEAR"))
                card.addView(tv(
                    if (inBreak) "$period: classes are hidden until teaching resumes" else "No classes coming up",
                    Type.HEADLINE, white, true,
                ).apply { setPadding(0, dp(6), 0, 0) })
                card.addView(tv(
                    if (due != null) "Next deadline: ${due.name} ${relIn(due.ts - now)}." else "Enjoy the free time. New updates will appear below.",
                    Type.BODY, soft,
                ).apply { setPadding(0, dp(6), 0, 0); maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
            }
            heroBox.addView(card, lp(MATCH, WRAP))
            return
        }

        // container: one gradient card per class; drag sideways to move between classes
        val pager = HeroPager(this)
        pager.canGo = { dir -> heroPos + dir in slides.indices }
        pager.onDragState = { heroDragging = it }
        pager.onCommit = { dir -> onSlide?.invoke(heroPos + dir) }
        // dir: +1 = the new card comes from the right, -1 from the left, 0 = no animation
        fun show(pos: Int, dir: Int = 0) {
            heroPos = pos.coerceIn(0, slides.size - 1)
            pager.removeAllViews()
            val c = slides[heroPos]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(20))
                background = heroBg(c.course)
            }
            val live = now >= c.start
            val col = Timetable.colours(this, setOf(c.course))[c.course] ?: white
            card.addView(label(if (live) "HAPPENING NOW" else if (isToday(c.start)) "UP NEXT TODAY" else "NEXT CLASS").apply {
            })
            card.addView(tv(Shorts.display(this, c.course), Type.DISPLAY, white, true).apply { setPadding(0, dp(6), 0, 0) })
            val sub = Timetable.classTitle(c.course, c.full, c.name)
            if (sub.isNotEmpty()) {
                card.addView(tv(sub, Type.BODY, soft).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                })
            }
            card.addView(tv("${dayLabel(c.start)} · ${hhmm(c.start)}–${hhmm(c.end)}", Type.BODY_L, white, true).apply {
                setPadding(0, dp(12), 0, 0)
            })
            card.addView(
                tv(if (live) "Ends ${relIn(c.end - now)}" else "Starts ${relIn(c.start - now)}", Type.LABEL, white, true).apply {
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = GradientDrawable().apply { cornerRadius = dp(Radius.MD).toFloat(); setColor(0x33FFFFFF) }
                },
                lp(WRAP, WRAP).apply { topMargin = dp(8) },
            )
            // getting there: the shuttle to catch, or the walk (tap: the Shuttle screen, or walking directions)
            if (!live) {
                val z = localDt(c.start)
                val trip = try { Shuttle.tripFor(this, c.code.ifEmpty { c.course }, z.toLocalDate(), z.hour * 60 + z.minute) } catch (_: Exception) { null }
                val line = trip?.let { Shuttle.line(this, it) }
                if (trip != null && line != null) {
                    val walking = trip.plan is ShuttleModel.Plan.Walk
                    val lineText = line.removePrefix("🚌 ").removePrefix("🚶 ")
                    card.addView(iconText(if (walking) R.drawable.ic_walk else R.drawable.lk_bus, lineText, Type.LABEL, white, true, trailing = R.drawable.ic_chevron_right).apply {
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = rippleBg(0x26FFFFFF, 0, this@MainActivity, Radius.MD)
                        isClickable = true
                        contentDescription = lineText + ". Tap for " + (if (walking) "walking directions" else "shuttle times")
                        setOnClickListener {
                            val walk = Shuttle.walkUrl(trip)
                            if (walk != null) openUrl(walk)
                            else {
                                val ride = (trip.plan as? ShuttleModel.Plan.Bus)?.ride
                                startActivity(Intent(this@MainActivity, ShuttleActivity::class.java)
                                    .putExtra(ShuttleActivity.EXTRA_ROUTE, ride?.route?.id)
                                    .putExtra(ShuttleActivity.EXTRA_FROM, ride?.let { r -> r.route.stops[r.fromIdx] }))
                            }
                        }
                    }, lp(MATCH, WRAP).apply { topMargin = dp(8) })
                }
            }
            if (c.link.isEmpty() || c.manual) {
                val inPerson = Timetable.inPerson(this, c.code.ifEmpty { c.course })
                val room = Timetable.locationOf(this, c.code.ifEmpty { c.course }).takeIf { it.startsWith("room:") }?.removePrefix("room:")?.trim().orEmpty()
                val note = if (c.manual) "Attendance for this course is by Teams recording."
                    else if (inPerson) "In person" + (if (room.isNotEmpty()) " · $room" else "") + ". Online links are ignored for this course."
                    else "No online link yet. It may be in person, or not posted. The app keeps checking."
                card.addView((if (inPerson && !c.manual) iconText(R.drawable.ic_place, note, Type.LABEL, soft) else tv(note, Type.LABEL, soft))
                    .apply { setPadding(0, dp(10), 0, 0) })
            }
            if (c.hiddenOwn.isNotEmpty()) {
                card.addView(tv(
                    "Your own ${Shorts.display(this, c.code.ifEmpty { c.course })} entry (${c.hiddenOwn}) is hidden: Spectrum lists this class at the same time.",
                    Type.CAPTION, soft,
                ).apply { setPadding(0, dp(8), 0, 0) })
            }
            // horizontal scroll so three pills never get squeezed into wrapping vertically
            // its own padded zone: a sideways drag that starts here scrolls the buttons, never the card (HeroPager)
            val actions = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                tag = HeroPager.NO_SWIPE
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = roundBg(0x1FFFFFFF, 0, this@MainActivity, Radius.PILL)
            }
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // a real meeting link exists → Join class is the primary action,
            // whatever kind of slot the class came from
            val hasLink = c.link.isNotEmpty()
            if (hasLink) actionsRow.addView(pill("Join class", true) { openUrl(c.link) }, lp(WRAP, dp(Ui.touch(44))).apply { rightMargin = dp(10) })
            actionsRow.addView(pill("Attendance", !hasLink) {
                if (Timetable.attMode(this@MainActivity, c.course) == "manual") {
                    Toast.makeText(this@MainActivity, "Attendance for ${Shorts.display(this@MainActivity, c.course)} is taken in class by the lecturer.", Toast.LENGTH_LONG).show()
                    return@pill
                }
                // Attendance needs the ATTENDANCE PAGE specifically — c.link often holds the
                // MEETING link (the event description contains it, so it scores 100 in
                // linksForEvent). Resolution order: pasted attendance link, occ-scored
                // attendance page, the event link only if it IS an attendance URL, course page.
                val d1 = localDt(c.start).dayOfWeek.value
                val s1 = localDt(c.start).let { it.hour * 60 + it.minute }
                val e1 = localDt(c.end).let { it.hour * 60 + it.minute }
                val (_, att, coursePage) = Checker.linksForCourse(this@MainActivity, c.course, c.courseId, Checker.groupTokens(c.name), preferLec = Checker.preferLecOf(c.name), day = d1, startMin = s1, endMin = e1)
                val eventIsAtt = c.link.contains("/mod/attendance/", true)
                val target = att.ifEmpty { if (eventIsAtt) c.link else "" }.ifEmpty { coursePage }.ifEmpty { c.courseUrl }
                if (target.isNotEmpty()) openUrl(target) else Toast.makeText(this@MainActivity, "No attendance page found for ${c.course} yet.", Toast.LENGTH_LONG).show()
            }, lp(WRAP, dp(Ui.touch(44))).apply { rightMargin = dp(10) })
            actionsRow.addView(pill("Course page", !hasLink) { openUrl(c.courseUrl) }, lp(WRAP, dp(Ui.touch(44))))
            actions.addView(actionsRow, lp(WRAP, WRAP))
            card.addView(actions, lp(MATCH, WRAP).apply { topMargin = dp(14) })
            // countdown progress: how far we are into the wait (or the class itself), fills to 100%
            val span = if (live) maxOf(1, c.end - c.start) else maxOf(1, (c.start - now) + (c.end - c.start))
            val done = if (live) now - c.start else (c.start - now).coerceAtLeast(0)
            val frac = (done.toFloat() / span).coerceIn(0f, 1f)
            // filled part by weight, so it is a true share of the card width
            val track = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x33FFFFFF) }
            }
            track.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0xFFFFFFFF.toInt()) }
            }, lp(0, dp(4), frac.coerceAtLeast(0.02f)))
            track.addView(View(this), lp(0, dp(4), (1f - frac).coerceAtLeast(0f)))
            card.addView(track, lp(MATCH, dp(4)).apply { topMargin = dp(14) })
            pager.addView(card, lp(MATCH, WRAP))
            // a plain tap opens the course sheet; sideways drags are taken by the pager
            card.isClickable = true
            card.contentDescription = "${c.course}, ${dayLabel(c.start)} ${hhmm(c.start)}. Tap for links and options" +
                if (slides.size > 1) ", swipe for other classes" else ""
            card.setOnClickListener { courseSheet(c) }
            if (dir != 0) {
                card.translationX = dir * pager.width * 0.35f
                card.alpha = 0f
                card.animate().translationX(0f).alpha(1f).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }

        // nav: explicit arrows + counter + dots, theme-colored, refreshed on slide change
        val navHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var renderNav: () -> Unit
        renderNav = {
            navHolder.removeAllViews()
            if (slides.size > 1) {
                val nav = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                }
                val navStrong = Ui.TEXT
                val navDim = Ui.DIM
                fun arrowBtn(icon: Int, desc: String, enabled: Boolean, onClick: () -> Unit): ImageView =
                    ImageView(this).apply {
                        setImageResource(icon)
                        setColorFilter(if (enabled) navStrong else navDim)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        contentDescription = desc
                        isEnabled = enabled
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (enabled) Ui.tint(if (Ui.dark) 0xFFFFFFFF.toInt() else 0xFF26251F.toInt(), 0x14) else 0x00000000)
                            setStroke(dp(1), if (enabled) Ui.CYAN else Ui.tint(Ui.DIM, 0x66))
                        }
                        if (enabled) setOnClickListener { onClick() }
                    }
                nav.addView(arrowBtn(R.drawable.ic_chevron_left, "Previous class", heroPos > 0) { pager.slide(-1) }, lp(dp(Ui.touch(48)), dp(Ui.touch(48))))
                nav.addView(tv("Class ${heroPos + 1} of ${slides.size}", Type.LABEL, Ui.TEXT, true).apply { setPadding(dp(12), 0, dp(12), 0) }, lp(WRAP, WRAP))
                nav.addView(arrowBtn(R.drawable.ic_chevron_right, "Next class", heroPos < slides.size - 1) { pager.slide(1) }, lp(dp(Ui.touch(48)), dp(Ui.touch(48))))
                navHolder.addView(nav, lp(MATCH, WRAP))
                val dots = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)); clipChildren = false }
                slides.forEachIndexed { i, sl ->
                    val wrap = FrameLayout(this).apply {
                        isClickable = true
                        contentDescription = "Go to class ${i + 1} of ${slides.size}: ${sl.course}, ${dayLabel(sl.start)}"
                        setOnClickListener { onSlide?.invoke(i) }
                    }
                    val d = View(this)
                    d.background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(if (i == heroPos) Ui.TEXT else Ui.tint(Ui.TEXT, 0x55)) }
                    wrap.addView(d, FrameLayout.LayoutParams(dp(if (i == heroPos) 22 else 10), dp(4), Gravity.CENTER))
                    dots.addView(wrap, lp(dp(44), dp(28)))
                }
                navHolder.addView(dots, lp(MATCH, WRAP))
            }
        }
        onSlide = { pos -> val from = heroPos; show(pos, Integer.signum(pos - from)); renderNav() }

        heroBox.removeAllViews()
        heroBox.addView(pager, lp(MATCH, WRAP))
        heroBox.addView(navHolder, lp(MATCH, WRAP))
        // FIRST paint: show() must run after the pager is attached, or the card is
        // invisible until an arrow is pressed (the reported v0.21.2 bug)
        show(heroPos)
        renderNav()
    }

    private var sheetTag: android.app.AlertDialog? = null
    private fun sheetDismiss() { sheetTag?.dismiss(); sheetTag = null }

    /** Everything for one course in a bottom sheet: links + edit row. Tapping the hero card opens it. */
    private fun courseSheet(c: Checker.ClassItem) {
        sheetDismiss()
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(Radius.LG).toFloat()
                setColor(Ui.RAISED)
                setStroke(dp(1), Ui.RAISED_BORDER)
            }
        }
        val tint = Timetable.colours(this, setOf(c.course))[c.course] ?: Ui.CYAN
        sheet.addView(tv(Shorts.display(this, c.course), Type.HEADLINE, tint, true))
        val sub = Timetable.classTitle(c.course, c.full, c.name)
        if (sub.isNotEmpty()) sheet.addView(tv(sub, Type.LABEL, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) })
        val nowS = System.currentTimeMillis() / 1000
        val status = if (nowS >= c.start) "happening now, ends ${relIn(c.end - nowS)}" else "starts ${relIn(c.start - nowS)}"
        sheet.addView(tv("${dayLabel(c.start)} · ${hhmm(c.start)}–${hhmm(c.end)} · $status", Type.LABEL, Ui.TEXT, true).apply {
            setPadding(0, dp(6), 0, dp(8))
        })

        val d2 = localDt(c.start).dayOfWeek.value
        val s2 = localDt(c.start).let { it.hour * 60 + it.minute }
        val e2 = localDt(c.end).let { it.hour * 60 + it.minute }
        val (meetingResolved, attResolved, coursePage) = Checker.linksForCourse(this, c.course, c.courseId, Checker.groupTokens(c.name), preferLec = Checker.preferLecOf(c.name), day = d2, startMin = s2, endMin = e2)
        // c.link may hold the MEETING link (event description scores 100 in linksForEvent) —
        // the Attendance row must open the attendance PAGE, the Join row the meeting. Keep separate.
        val meeting = c.link.ifEmpty { meetingResolved }
        val att = if (c.link.contains("/mod/attendance/", true)) c.link else attResolved
        fun row(title: String, subText: String, url: String?) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundBg(Ui.BAR, Ui.BORDER, this@MainActivity, Radius.MD)
            }
            r.addView(tv(title, Type.BODY_L, Ui.TEXT, true))
            r.addView(tv(subText, Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(2), 0, 0); maxLines = 3 })
            r.isClickable = true
            r.contentDescription = title
            r.setOnClickListener {
                sheetDismiss()
                if (url != null) openUrl(url)
            }
            sheet.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        if (coursePage.isNotEmpty()) row("Open course page", coursePage, coursePage)
        if (Timetable.attMode(this, c.course) == "manual") row("Attendance", "Taken in class by the lecturer — no link needed", null)
        else if (att.isNotEmpty()) row("Attendance", att, att)
        if (meeting.isNotEmpty()) row("Join class — meeting link", meeting, meeting)
        // edit this class entry: find the real slot (the editor finish()es silently otherwise)
        val day = localDt(c.start).dayOfWeek.value
        val startMin = localDt(c.start).let { it.hour * 60 + it.minute }
        val editIntent = Intent(this, TimetableEditActivity::class.java)
        val manualSlot = Timetable.manual(this).firstOrNull {
            it.course.equals(c.course, true) && it.start == startMin && it.day == day
        }
        if (manualSlot != null) {
            editIntent.putExtra(TimetableEditActivity.EXTRA_GRP, manualSlot.grp)
        } else {
            // exact time first, else the nearest class of this course that day (a moved or overridden class)
            val sameDay = Timetable.all(this, null).filter { it.course.equals(c.course, true) && it.day == day }
            val learnedSlot = sameDay.firstOrNull { it.start == startMin }
                ?: sameDay.minByOrNull { abs(it.start - startMin) }?.takeIf { abs(it.start - startMin) <= 180 }
            if (learnedSlot != null) {
                if (learnedSlot.manual) editIntent.putExtra(TimetableEditActivity.EXTRA_GRP, learnedSlot.grp)
                else editIntent.putExtra(TimetableEditActivity.EXTRA_KEY, learnedSlot.key)
            }
        }
        // without a matching entry the editor would close at once; open the timetable instead
        val canEdit = editIntent.hasExtra(TimetableEditActivity.EXTRA_GRP) || editIntent.hasExtra(TimetableEditActivity.EXTRA_KEY)
        fun editRow() {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundBg(Ui.BAR, Ui.BORDER, this@MainActivity, Radius.MD)
            }
            r.addView(tv(if (canEdit) "Edit this class" else "Open timetable", Type.BODY_L, Ui.TEXT, true))
            r.addView(tv(
                if (canEdit) "Times, name, attendance mode, colour" else "This class is not in your timetable yet; find or add it there",
                Type.CAPTION, Ui.DIM,
            ).apply { setPadding(0, dp(2), 0, 0); maxLines = 3 })
            r.isClickable = true
            r.setOnClickListener {
                sheetDismiss()
                startActivity(if (canEdit) editIntent else Intent(this, TimetableActivity::class.java))
            }
            sheet.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        editRow()
        if (c.courseId != 0L) {
            fun toolRow(title: String, subText: String, go: () -> Unit) {
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = roundBg(Ui.BAR, Ui.BORDER, this@MainActivity, Radius.MD)
                }
                r.addView(tv(title, Type.BODY_L, Ui.TEXT, true))
                r.addView(tv(subText, Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(2), 0, 0); maxLines = 3 })
                r.isClickable = true
                r.setOnClickListener { sheetDismiss(); go() }
                sheet.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(8) })
            }
            toolRow("Notification pages", "Choose which parts of this course alert you") {
                startActivity(Intent(this, SectionsActivity::class.java)
                    .putExtra(SectionsActivity.EXTRA_ID, c.courseId)
                    .putExtra(SectionsActivity.EXTRA_NAME, sub))
            }
        }

        val dialog = android.app.AlertDialog.Builder(this, Ui.dialogTheme)
            .setView(sheet)
            .create()
        sheetTag = dialog
        dialog.show()
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.setGravity(android.view.Gravity.BOTTOM)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.55f)
    }

    /** One task (deadline) in a bottom sheet, matching the course sheet's look. */
    private fun dueSheet(d: Checker.DueItem) {
        sheetDismiss()
        val isDone = Checker.isTaskDone(this, d.name, d.eventId, d.ts)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(Radius.LG).toFloat()
                setColor(Ui.RAISED)
                setStroke(dp(1), Ui.RAISED_BORDER)
            }
        }
        val left = d.ts - System.currentTimeMillis() / 1000
        val col = if (left < 86400) Ui.RED else if (left < 3 * 86400) Ui.ORANGE else Ui.GREEN
        sheet.addView(tv("Due in " + relShort(left) + " · " + dayLabel(d.ts) + ", " + hhmm(d.ts), Type.LABEL, col, true))
        sheet.addView(tv(d.name, Type.HEADLINE, Ui.TEXT, true).apply { setPadding(0, dp(4), 0, 0); maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        if (d.course.isNotEmpty()) sheet.addView(tv(Shorts.display(this, d.course), Type.LABEL, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) })
        if (isDone) sheet.addView(iconText(R.drawable.ic_check, "Marked as done", Type.CAPTION, Ui.GREEN, true).apply { setPadding(0, dp(8), 0, 0) })

        fun row(title: String, subText: String, go: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundBg(Ui.BAR, Ui.BORDER, this@MainActivity, Radius.MD)
            }
            r.addView(tv(title, Type.BODY_L, Ui.TEXT, true))
            r.addView(tv(subText, Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(2), 0, 0); maxLines = 3 })
            r.isClickable = true
            r.setOnClickListener { sheetDismiss(); go() }
            sheet.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        if (d.url.isNotEmpty()) row("View task", d.url) { openUrl(d.url) }
        row(
            if (isDone) "Mark as not done" else "Mark as done",
            if (isDone) "Puts it back on your active list, with reminders" else "Keeps it on the list, struck through; no more reminders",
        ) {
            Checker.setTaskDone(this, d.name, !isDone, d.eventId, d.ts)
            dueSig = ""
            render()
        }
        row("Hide this (wrong?)", "Removes it from Home, Notifications and reminders — you can undo right after") {
            // by id, and by name so its reminders leave the Notifications list too
            if (d.eventId != 0L) NotifyLog.hideEvent(this, "dl:" + d.eventId)
            NotifyLog.hideEvent(this, Checker.hiddenNameKey(d.name))
            sheetDismiss()
            dueSig = ""
            render()
            undoBar("Deadline hidden") {
                if (d.eventId != 0L) NotifyLog.unhideEvent(this, "dl:" + d.eventId)
                NotifyLog.unhideEvent(this, Checker.hiddenNameKey(d.name))
                dueSig = ""
                render()
            }
        }
        val dialog = android.app.AlertDialog.Builder(this, Ui.dialogTheme)
            .setView(sheet)
            .create()
        sheetTag = dialog
        dialog.show()
        dialog.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.setGravity(android.view.Gravity.BOTTOM)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.55f)
    }

    /** Home "YOUR EVENTS": occurrences in the next 7 days, tap to edit. */
    private fun renderEvents() {
        val today = java.time.LocalDate.now(zone())
        val rows = ArrayList<Triple<Events.Event, java.time.LocalDate, Int>>()
        for (e in Events.all(this)) {
            for ((d, t) in e.upcoming(today, 7)) rows.add(Triple(e, d, t.hour * 60 + t.minute))
        }
        rows.sortWith(compareBy({ it.second }, { it.third }))
        eventsSection.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        if (rows.isEmpty()) return
        eventsList.removeAllViews()
        for ((e, d, min) in rows.take(5)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = roundBg(Ui.BAR, Ui.BORDER, this@MainActivity, Radius.MD)
                isClickable = true
                setOnClickListener { EventsUi.editor(this@MainActivity, e, { dueSig = ""; render() }) }
            }
            row.addView(tv(EventsUi.fmtDate(d) + " · " + EventsUi.fmtMin(min), Type.CAPTION, Ui.CYAN, true))
            row.addView(tv(e.title, Type.BODY, Ui.TEXT, true).apply {
                setPadding(0, dp(4), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            eventsList.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        }
    }

    private fun renderDue() {
        val now = System.currentTimeMillis() / 1000
        fun done(d: Checker.DueItem) = Checker.isTaskDone(this, d.name, d.eventId, d.ts)
        // finished ones move behind the open ones
        val items = ov.due.filter { it.ts - now < 14 * 86400 }.take(8).sortedBy { done(it) }
        // the minute keeps "Due in …" current; done state too (it can change on the Notifications tab)
        val sig = items.joinToString("|") { "${it.ts}${it.name}${done(it)}" } + ":" + now / 60
        if (sig == dueSig) return
        dueSig = sig
        dueSection.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        dueRow.removeAllViews()
        for (d in items) {
            val left = d.ts - now
            val col = if (left < 86400) Ui.RED else if (left < 3 * 86400) Ui.ORANGE else Ui.GREEN
            val isDone = done(d)
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = roundBg(Ui.BAR, if (isDone) Ui.CYAN else Ui.BORDER, this@MainActivity, Radius.MD)
                isClickable = true
                setOnClickListener { dueSheet(d) }
            }
            // urgency stripe across the top edge: readable at a glance without the badge
            c.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(col) }
            }, lp(MATCH, dp(4)).apply { bottomMargin = dp(10) })
            c.addView(tv("Due in ${relShort(left)}", Type.CAPTION, col, true).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply { cornerRadius = dp(Radius.MD).toFloat(); setColor(Ui.tint(col, 0x26)) }
            }, lp(WRAP, WRAP))
            c.addView(tv(d.name, Type.BODY, Ui.TEXT, true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(10), 0, 0)
                if (isDone) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            })
            // "GUL1001 · UMLEADER": the code is already in it, so no second line repeating the code
            if (d.course.isNotEmpty()) c.addView(tv(Shorts.display(this, d.course), Type.CAPTION, Ui.DIM).apply {
                setPadding(0, dp(2), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            c.contentDescription = "${d.name}, due in ${relShort(left)}" + (if (isDone) ", marked done" else "")
            dueRow.addView(c, lp(dp(172), WRAP).apply { rightMargin = dp(10) })
        }
    }

    // ------------------------------------------------------------ intents (login redirect)
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val raw = i.dataString ?: return
        if (!raw.startsWith("spectrumnotify://")) return
        i.data = null
        val parsed = Login.parseRedirect(p, raw)
        val tok = parsed.first
        if (tok == null) {
            NotifyLog.sys(this, parsed.second ?: "Login failed")
            return
        }
        Login.saveAndVerify(this, tok) { msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                heroSig = ""
                ovAt = 0L
                render()
            }
        }
    }
}

/**
 * Holds the one visible hero card and moves it with the finger. A sideways drag (also one that starts on a
 * button) is taken from the children and from the scrolling page; on release the card either slides out and
 * [onCommit] shows the neighbour, or springs back. At the first/last class the card only stretches a little.
 */
private class HeroPager(ctx: android.content.Context) : FrameLayout(ctx) {
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
