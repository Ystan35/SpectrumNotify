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

/** What the "Get set up" card and the Occ dialog remember while the Home screen is open. */
internal class SetupState {
    var sig = ""
    /** Setup guide expanded to every step (collapsed: progress and the next step only). */
    var open = false
    var occDialogShown = false
    var occDialog: android.app.AlertDialog? = null
}

// The "Get set up" card and the "Confirm your Occ" dialog of the Home screen.

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** The Occ dialog opened already since the app process started: it comes back at the next start while a course is unconfirmed. */
private var occAskedThisRun = false

private data class OccAsk(val code: String, val cname: String, val occs: List<String>, val predicted: String?)

/** Courses whose Occ the user has not confirmed yet (detected from visible Occ pages). */
private fun MainActivity.pendingOccAsks(): List<OccAsk> {
    val st = State.readOnly(this)   // called on every Home render: no re-parse of state.json
    val seen = st.j.optJSONObject("occ_seen") ?: return emptyList()
    val courses = Courses.all(this).associateBy { it.id }
    val asks = ArrayList<OccAsk>()
    for (cidS in seen.keys()) {
        val course = courses[cidS.toLongOrNull() ?: -1L] ?: continue
        val code = course.code.uppercase()
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
        // Spectrum's short name is only the code, so the name comes from the full course title
        asks.add(OccAsk(code, Timetable.classTitle(course.code, course.name, ""), occs, predictedNum))
    }
    return asks
}

/**
 * After the auto-detection finds courses with visible Occ pages, ask the user to confirm
 * each one (the detection is a good guess, but only the user knows their real Occ).
 * Opens by itself once; after that only from the "Get set up" card ([manual]).
 * A course left on "?" stays unconfirmed and is asked again.
 */
internal fun MainActivity.confirmOccDialog(manual: Boolean = false) {
    if (setup.occDialogShown) return
    val flags = getSharedPreferences("health", android.content.Context.MODE_PRIVATE)
    // asked again each time the app is opened until every course is confirmed (ticket SN-J6ACW): wrong Occ = wrong alerts
    if (!manual && occAskedThisRun) return
    val asks = pendingOccAsks()
    if (asks.isEmpty()) {
        // nothing left to confirm (done elsewhere meanwhile): no dialog to bring back
        flags.edit().putBoolean(semFlag("occDialogOpen"), false).remove(semFlag("occDraft")).apply()
        return
    }
    setup.occDialogShown = true
    // "occDialogOpen" stays set until you answer (Save all / Later / Back), so a rebuilt screen reopens it
    occAskedThisRun = true
    flags.edit().putBoolean(semFlag("occAutoAsked"), true).putBoolean(semFlag("occDialogOpen"), true).apply()
    // your picks so far (kept while you look things up in another app): course code -> Occ
    val draft = try { JSONObject(flags.getString(semFlag("occDraft"), "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    fun saveDraft() = flags.edit().putString(semFlag("occDraft"), draft.toString()).apply()
    fun closeDialog() = flags.edit().putBoolean(semFlag("occDialogOpen"), false).remove(semFlag("occDraft")).apply()

    // one dialog with the My-classes layout: scrollable content (last courses reachable),
    // sized below the app header
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(10), dp(24), 0) }
    val box = ScrollView(this).apply { addView(content) }
    // prefill: only what you already picked (draft), in the full option list
    val choices = IntArray(asks.size) { i ->
        val picked = draft.optString(asks[i].code, "")
        if (picked.isNotEmpty() && picked in asks[i].occs) asks[i].occs.indexOf(picked)
        else -1   // a guess from attendance pages can be another group's: never pre-selected, only shown as a hint
    }
    asks.forEachIndexed { i, a ->
        // single-line row: code·name on the left, occ dropdown on the right
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, if (i == 0) 0 else dp(8), 0, 0)
        }
        // code, with the course name under it, so you can tell the courses apart
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(a.code, Type.LABEL, Ui.TEXT, true))
            if (a.cname.isNotEmpty()) addView(tv(a.cname, Type.CAPTION, Ui.DIM).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            a.predicted?.let { addView(tv("Guess from attendance: Occ $it", Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) }) }
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
    setup.occDialog = android.app.AlertDialog.Builder(this, Ui.dialogTheme)
        .setTitle("Confirm your Occ")
        .setMessage("Pick your Occ for each course. The guess under a name comes from attendance pages, which include other groups, so check it. Unlisted courses keep working without a filter.")
        .setView(box)
        .setPositiveButton("Save all") { _, _ ->
            closeDialog()
            val notInCatalog = ArrayList<String>()
            asks.forEachIndexed { i, a ->
                val v = choices[i]
                // "?" is not an answer: that course stays unconfirmed and is asked again
                if (v >= 0 && a.occs.isNotEmpty()) {
                    // also flags the Occ for a look at the class times on the Courses page
                    if (!Timetable.setOcc(this, a.code, a.occs[v], notify = false)) notInCatalog.add(a.code)
                } else if (a.occs.isEmpty()) Prefs(this).setOccConfirmed(a.code)
            }
            Timetable.changed(this)   // once for all the courses
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
        .setOnDismissListener { setup.occDialogShown = false; setup.occDialog = null; setup.sig = ""; render() }
        .create()
    // a stray tap outside must not throw away your picks
    setup.occDialog?.setCanceledOnTouchOutside(false)
    setup.occDialog?.show()
}

// ------------------------------------------------------------ "Get set up" card


/** A setup flag that starts fresh each semester: new subjects mean a new Occ to confirm and new times to check. */
internal fun MainActivity.semFlag(name: String): String {
    val flags = getSharedPreferences("health", android.content.Context.MODE_PRIVATE)
    val key = name + ":" + AcademicCalendar.planningLabel(this)
    // v0.25.1 kept these once per install: count them for the semester you are in now
    if (!flags.contains(key) && flags.getBoolean(name, false)) flags.edit().putBoolean(key, true).remove(name).apply()
    return key
}

private class SetupStep(val title: String, val why: String, val done: Boolean, val button: String, val optional: Boolean = false, val go: (() -> Unit)?)

/** Every step a new install needs, in order; a step's button does that step (or opens the screen for it). */
private fun MainActivity.setupSteps(loggedIn: Boolean): List<SetupStep> {
    val flags = getSharedPreferences("health", android.content.Context.MODE_PRIVATE)
    val notifOk = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    // some phones (Samsung especially) keep reporting "optimised" after you pick Unrestricted on the app's
    // battery page; after one try you can confirm it yourself instead of being asked forever
    val batteryAsked = flags.getBoolean("batteryAsked", false)
    val batteryOk = batteryUnrestricted() || flags.getBoolean("batteryConfirmed", false)
    val checked = p.lastRunTs > 0L
    val occN = if (loggedIn) pendingOccAsks().size else 0
    val occPending = occN > 0
    return listOf(
        SetupStep("Allow notifications", "Class links, deadlines and new materials arrive as notifications.", notifOk, "Allow") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        },
        SetupStep("Log in to Spectrum", "Uses your normal university sign-in. Your login stays on this phone.", loggedIn, "Log in") {
            startActivity(Intent(this, LoginWebActivity::class.java))
        },
        // right after login: it decides which alerts and classes are yours, so the collapsed card shows it first
        SetupStep("Confirm your Occ",
            if (!checked) "Your Occ (class group) is detected after the first check."
            else if (occN > 0) (if (occN == 1) "1 course still needs its Occ confirmed." else "$occN courses still need their Occ confirmed.") + " It decides which links, alerts and classes are yours."
            else "Your Occ is your class group, e.g. Occ 2. It decides which links and posts are yours.",
            loggedIn && checked && !occPending, "Confirm", go = if (occPending) ({ confirmOccDialog(manual = true) }) else null),
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
            setup.sig = ""
            render()
        },
        // after confirming: check each chosen Occ on the Courses page (its class times must match your UM timetable)
        p.occChecksPending().let { pend ->
            SetupStep("Verify Occ selection",
                if (pend.isEmpty()) "Class times match your Occ."
                else "Check on Courses: " + pend.joinToString(", ") { Shorts.display(this, it) },
                loggedIn && checked && !occPending && pend.isEmpty(), "Open",
                go = if (pend.isNotEmpty()) ({ startActivity(Intent(this, CoursesActivity::class.java)) }) else null)
        },
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

internal fun MainActivity.renderSetup(loggedIn: Boolean) {
    val flags = getSharedPreferences("health", android.content.Context.MODE_PRIVATE)
    val hidden = flags.getBoolean(semFlag("setupHidden"), false)
    val allSteps = setupSteps(loggedIn)
    // a hidden guide still shows the Occ step while any course is unconfirmed
    val occStep = allSteps.firstOrNull { it.title == "Confirm your Occ" && !it.done && it.go != null }
    if (hidden && occStep == null) {
        if (setup.sig != "hidden") { setup.sig = "hidden"; setupBox.removeAllViews() }
        return
    }
    val steps = if (hidden) listOf(occStep!!) else allSteps
    val required = steps.filter { !it.optional }
    // done flag, wording and whether there is a button: any of them changing redraws the card
    // (a check finishing changes the Occ step's text and gives it a button without ticking it)
    val sig = steps.joinToString("|") { "${it.done}:${it.button}:${it.go != null}:${it.why}" } + hidden
    if (sig == setup.sig) return
    setup.sig = sig
    setupBox.removeAllViews()
    if (steps.all { it.done }) return

    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(10))
        background = roundBg(Ui.BAR, Ui.BORDER, this@renderSetup, Radius.MD)
    }
    val next = steps.firstOrNull { !it.done && it.go != null }
    val doneCount = required.count { it.done }
    val head = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Ui.touch(48))
        isClickable = true
        contentDescription = "Get set up, $doneCount of ${required.size} done. " +
            (if (setup.open) "Tap to show only the next step" else "Tap to show all steps")
        setOnClickListener { setup.open = !setup.open; setup.sig = ""; render() }
    }
    head.addView(tv("Get set up", Type.BODY_L, Ui.TEXT, true), LinearLayout.LayoutParams(0, WRAP, 1f))
    head.addView(tv("$doneCount of ${required.size} done", Type.CAPTION, Ui.DIM, true))
    head.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_expand_more)
        setColorFilter(Ui.DIM)
        rotation = if (setup.open) 180f else 0f
    }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(6) })
    card.addView(head)
    // progress bar: the done share of the required steps
    val frac = doneCount.toFloat() / maxOf(1, required.size)
    val bar = LinearLayout(this).apply { background = roundBg(Ui.tint(Ui.DIM, 0x33), 0, this@renderSetup, Radius.SM) }
    bar.addView(View(this).apply { background = roundBg(Ui.GREEN, 0, this@renderSetup, Radius.SM) }, lp(0, dp(4), frac.coerceAtLeast(0.02f)))
    bar.addView(View(this), lp(0, dp(4), (1f - frac).coerceAtLeast(0f)))
    card.addView(bar, lp(MATCH, dp(4)).apply { topMargin = dp(2) })
    for (s in steps) {
        if (!setup.open && s !== next) continue
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
            row.addView(button(s.button, if (s === next) Emphasis.PRIMARY else Emphasis.SECONDARY) { s.go.invoke(); setup.sig = "" }.apply {
                contentDescription = "${s.button}: ${s.title}"
                if (s.title == "Log in to Spectrum") tag = "guide:login"
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        }
        card.addView(row)
    }
    if (setup.open && !hidden) card.addView(tv("Hide this guide", Type.CAPTION, Ui.DIM, true).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        minHeight = dp(Ui.touch(48))
        isClickable = true
        setOnClickListener {
            flags.edit().putBoolean(semFlag("setupHidden"), true).apply()
            setup.sig = ""
            render()
            undoBar("Setup guide hidden") { flags.edit().putBoolean(semFlag("setupHidden"), false).apply(); setup.sig = ""; render() }
        }
    }, LinearLayout.LayoutParams(MATCH, WRAP))
    setupBox.addView(card, lp(MATCH, WRAP).apply { topMargin = dp(12) })
}

