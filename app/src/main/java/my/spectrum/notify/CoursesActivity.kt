package my.spectrum.notify

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** All your courses: class times, and a button to each course's Spectrum home page. */
class CoursesActivity : BaseActivity() {
    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** A course code: this screen becomes that course's page, with everything about the course in one place. */
        const val EXTRA_CODE = "code"
    }

    private lateinit var box: LinearLayout
    private val only: String? by lazy { intent.getStringExtra(EXTRA_CODE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = only?.let { screen(Shorts.display(this, it)) }
            ?: screen("Courses", navTo = CoursesActivity::class.java, onRefresh = { Scheduler.pollNow(this) })
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        render()
    }


    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
    }

    /** Card order while this screen is open: a card stays put when its Occ changes, instead of jumping out of view. */
    private val order = LinkedHashMap<String, Int>()

    private fun render() {
        val fresh = Courses.all(this)
        only?.let { code -> renderCourse(code, fresh); return }
        for (c in fresh) if (c.code !in order) order[c.code] = order.size
        val list = fresh.sortedBy { order[it.code] }
        // read once for the whole page: state.json is large, and every read parses all of it
        val st = State(this)
        val learned = Groups.learnedPerCourse(this, st)
        box.removeAllViews()
        if (list.isEmpty()) {
            val loggedIn = Prefs(this).token.isNotEmpty()
            box.label("No courses yet", Type.HEADLINE, Ui.TEXT, 24).setTypeface(null, Typeface.BOLD)
            box.label(
                if (loggedIn) "Your courses appear after the next check of Spectrum."
                else "Log in first, then your courses are listed here.",
                Type.BODY, Ui.DIM, 8,
            )
            if (loggedIn) {
                box.btn("Check Spectrum now") {
                    Scheduler.pollNow(this)
                    Toast.makeText(this, "Checking Spectrum...", Toast.LENGTH_SHORT).show()
                }
            } else {
                box.btn("Log in") { startActivity(Intent(this, AccountActivity::class.java)) }
            }
            return
        }
        val colours = Timetable.colours(this, list.map { it.code }.toSet())
        val ov = try { Checker.overview(this) } catch (_: Exception) { Checker.Overview(emptyList(), emptyList()) }
        box.label("${list.size} course" + (if (list.size == 1) "" else "s"), Type.BODY, Ui.DIM, 8)
        // page actions: two equal SECONDARY buttons, then a quiet text action (none outshouts the courses)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        actions.addView(button("Refresh classes", Emphasis.SECONDARY) { refreshClasses() }.apply {
            contentDescription = "Refresh my classes from Spectrum"
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.addView(button("Short names", Emphasis.SECONDARY) { batchShorts(list) }.apply {
            contentDescription = "Set short names for all courses"
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        box.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        if (Prefs.isUm(this)) box.addView(button("+  Add a course from the UM timetable", Emphasis.TERTIARY) {
            startActivity(Intent(this, CatalogActivity::class.java))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        for (c in list) {
            box.addView(card(c, colours[c.code] ?: Ui.CYAN, learned[c.code.uppercase()] ?: emptyList(), st, ov, full = false).apply {
                if (c == list.first()) tag = "guide:course"
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
    }

    /**
     * One course. [full] = the top of its course page (short name, rich class times, attendance, the Occ check);
     * otherwise the compact card on the list, which opens that page when tapped.
     */
    private fun card(c: Courses.Course, colour: Int, learned: List<String>, st: State, ov: Checker.Overview, full: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundBg(Ui.BAR, Ui.BORDER, this@CoursesActivity, Radius.MD)
        }
        val r = dp(Radius.MD).toFloat()
        card.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(colour)
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
        }, LinearLayout.LayoutParams(dp(5), MATCH))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), if (full) dp(10) else dp(14))
        }

        // the Occ is worked out first: its pill sits on the code line
        val pp = Prefs(this)
        val catOccs = Catalog.find(this, c.code)?.occs() ?: emptyList()
        val occs = if (catOccs.isNotEmpty()) catOccs else learned
        val current = Regex(Regex.escape(c.code) + ":occ([0-9]+[a-z]?)", RegexOption.IGNORE_CASE)
            .find(pp.myGroups)?.groupValues?.get(1) ?: ""
        val occMissing = current.isEmpty() && occs.isNotEmpty()

        // header: code (and your short form) in the course colour, the Occ pill at the end of the same line
        val short = Shorts.get(this, c.code)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(c.code.uppercase() + (if (short.isNotEmpty()) "  ($short)" else "") +
                (if (!full && current.isNotEmpty()) "  ·  Occ $current" else ""), Type.LABEL, colour, true).apply {
            minHeight = dp(Ui.touch(48))
            gravity = Gravity.CENTER_VERTICAL
            // on the course page, tapping the code changes the short name; on the list the whole card opens the page
            if (full) {
                isClickable = true
                contentDescription = c.code + (if (short.isNotEmpty()) ", short name $short" else "") + ". Tap to change the short name"
                pressRipple(Radius.SM, colour)
                setOnClickListener { editShortName(c.code, short) }
            }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        // the Occ is picked on the course page only; the list just names it
        if (full && occs.isNotEmpty()) {
            // not set: a mint "Set Occ" you cannot miss; set: a quiet outlined "Occ 3"
            head.addView(chip(if (occMissing) "Set Occ" else "Occ $current", occMissing, Ui.SIGNAL, Type.LABEL, 14) {
                pickOcc(c.code, occs, current, catOccs.isEmpty())
            }.apply {
                tag = "guide:occ"   // the first-run guide points at the first one
                if (occMissing) setTextColor(onFill(Ui.SIGNAL))
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_expand_more, 0)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(if (occMissing) onFill(Ui.SIGNAL) else Ui.TEXT)
                contentDescription = "Occ for ${c.code}: " + (if (current.isEmpty()) "not set" else current) + ". Tap to change"
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        col.addView(head)
        val title = titleCase(Timetable.classTitle(c.code, c.name, "").ifEmpty { c.code })
        col.addView(tv(title, Type.TITLE, Ui.TEXT, true).apply {
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            if (!full) { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        })

        // ONE notice when the Occ needs a look: just picked (check it against MAYA), or Spectrum has
        // weekly classes this Occ does not (the Occ may be wrong). "Looks right" settles both.
        val fromList = c.slots.filter { it.grp.startsWith("cat-") }
        val notInOcc = c.slots.filter { s -> !s.manual && fromList.none { it.day == s.day && it.start < s.end && s.start < it.end } }
        val okKey = "${c.code.uppercase()}|$current"
        val mismatch = current.isNotEmpty() && fromList.isNotEmpty() && notInOcc.isNotEmpty() && okKey !in occOk()
        val occCheck = current.isNotEmpty() && (pp.occNeedsCheck(c.code) || mismatch)

        if (!full) {
            // the next class, or the weekly times when none is coming up in the next two weeks
            val next = ov.classes.firstOrNull { it.code.equals(c.code, true) }
            val whenText = when {
                next != null -> {
                    val venue = Timetable.venueAt(this, c.code, next.start)
                    "Next: " + dayLabel(next.start) + " " + hhmm(next.start) + if (venue.isNotEmpty()) " · $venue" else ""
                }
                c.slots.isNotEmpty() -> c.slots.take(3).joinToString(", ") { "${Timetable.DAYS[it.day - 1]} ${Timetable.fmtMin(it.start)}" }
                else -> "No class times known yet"
            }
            col.addView(iconText(R.drawable.ic_cat_deadline, whenText, Type.BODY, Ui.TEXT).apply { setPadding(0, dp(8), 0, 0) })
            if (occMissing || occCheck) col.addView(tv(if (occMissing) "Set your Occ" else "Check your Occ", Type.LABEL, Ui.TEXT, true).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = roundBg(Ui.tint(Ui.YELLOW, 0x2A), Ui.tint(Ui.YELLOW, 0x2A), this@CoursesActivity, Radius.PILL)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
            card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            card.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(Ui.DIM)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER_VERTICAL; rightMargin = dp(10) })
            card.isClickable = true
            card.pressRipple()
            card.contentDescription = "${c.code}, $title. $whenText. Tap for its classes, deadlines and materials"
            card.setOnClickListener { startActivity(Intent(this, CoursesActivity::class.java).putExtra(EXTRA_CODE, c.code)) }
            return card
        }

        // class times: each one with where it is and who teaches it; tap for the class popup in Timetable
        if (c.slots.isEmpty()) {
            // without an Occ the UM times are not in the timetable yet, though Spectrum may still show a next class
            val nh = tv(if (occMissing) "Set your Occ to add the class times from the UM timetable." else "No class times known yet",
                Type.BODY, Ui.DIM).apply { setPadding(0, dp(10), 0, 0) }
            col.addView(nh)
            col.addView(button("Refresh class times", Emphasis.TERTIARY) {
                Scheduler.pollNow(this)
                nh.text = "Checking Spectrum…"
                Toast.makeText(this, "Checking Spectrum — class times update when the check finishes.", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(-12) })
        } else {
            val times = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
            for (s in c.slots) {
                val kind = if (s.kind == SlotKind.CLASS) "" else "  ·  ${s.kind.label}"
                val venue = Timetable.venueOf(this, s)
                val online = venue == "Online"
                val lecturer = Timetable.choiceOf(this, s)?.detail().orEmpty()
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = rippleBg(Ui.BAR, 0, this@CoursesActivity, Radius.SM)
                    contentDescription = "${Timetable.DAY_FULL[s.day - 1]} ${Timetable.fmtMin(s.start)} to ${Timetable.fmtMin(s.end)}$kind, " +
                        venue.ifEmpty { "place not known" } + (if (lecturer.isNotEmpty()) ", $lecturer" else "") + ". Tap for details"
                    setOnClickListener {
                        startActivity(Intent(this@CoursesActivity, TimetableActivity::class.java).putExtra(TimetableActivity.EXTRA_SLOT, s.key))
                    }
                }
                row.addView(iconText(R.drawable.ic_cat_deadline,
                    "${Timetable.DAY_FULL[s.day - 1]}  ${Timetable.fmtMin(s.start)} – ${Timetable.fmtMin(s.end)}$kind",
                    Type.BODY, Ui.TEXT, trailing = R.drawable.ic_chevron_right))
                if (venue.isNotEmpty()) row.addView(iconText(if (online) R.drawable.ic_laptop else R.drawable.ic_place, venue,
                    Type.LABEL, if (online) Ui.CYAN else Ui.TEXT, true).apply { setPadding(0, dp(4), 0, 0) })
                if (lecturer.isNotEmpty()) row.addView(tv(lecturer, Type.LABEL, Ui.DIM).apply { setPadding(dp(28), dp(2), 0, 0) })
                times.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
            }
            col.addView(times)
        }

        // which attendance page the classes use; tap to choose (lectures and tutorials can differ)
        if (c.id > 0L && c.slots.isNotEmpty()) {
            val both = AttendancePicker.hasBothKinds(this, c.code)
            val text = if (both) "Attendance: lectures " + AttendancePicker.label(this, c.code, c.id, "lec") +
                    ", tutorials " + AttendancePicker.label(this, c.code, c.id, "tut")
                else "Attendance: " + AttendancePicker.label(this, c.code, c.id, "")
            col.addView(iconText(R.drawable.ic_check, text, Type.LABEL, Ui.TEXT, trailing = R.drawable.ic_chevron_right).apply {
                minHeight = dp(Ui.touch(48))
                setPadding(0, dp(4), 0, dp(4))
                background = rippleBg(Ui.BAR, 0, this@CoursesActivity, Radius.SM)
                contentDescription = "$text. Tap to choose the attendance page"
                setOnClickListener { AttendancePicker.openForCourse(this@CoursesActivity, c.code, c.id) { render() } }
            })
        }

        if (occCheck) {
            val note = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundBg(Ui.tint(Ui.YELLOW, 0x2A), Ui.tint(Ui.YELLOW, 0x2A), this@CoursesActivity, Radius.MD)
            }
            note.addView(tv("Check your Occ", Type.BODY, Ui.TEXT, true))
            val whenText = notInOcc.take(2).joinToString(", ") { "${Timetable.DAYS[it.day - 1]} ${Timetable.fmtMin(it.start)}" }
            note.addView(tv(
                (if (mismatch) "Spectrum also has ${c.code} on $whenText, which Occ $current doesn't. " else "") +
                    "Do the times above match MAYA?",
                Type.LABEL, Ui.TEXT,
            ).apply { setPadding(0, dp(4), 0, dp(10)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Looks right", Emphasis.SECONDARY, Ui.CYAN) {
                pp.setOccNeedsCheck(c.code, false)
                occOkAdd(okKey)
                render()
            }.apply { contentDescription = "Occ $current for ${c.code} looks right" }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            row.addView(button("Change Occ", Emphasis.SECONDARY, Ui.CYAN) {
                pickOcc(c.code, occs, current, catOccs.isEmpty())
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            note.addView(row)
            col.addView(note, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }

        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return card
    }

    /** The course page: its card at the top, then next class, online or in person, deadlines, materials and notifications. */
    private fun renderCourse(code: String, all: List<Courses.Course>) {
        box.removeAllViews()
        val c = all.firstOrNull { it.code.equals(code, true) }
        if (c == null) {
            box.label("$code is not in your courses any more.", Type.BODY, Ui.DIM, 8)
            return
        }
        val st = State(this)
        val colour = Timetable.colours(this, all.map { it.code }.toSet())[c.code] ?: Ui.CYAN
        val ov = try { Checker.overview(this) } catch (_: Exception) { Checker.Overview(emptyList(), emptyList()) }
        box.addView(card(c, colour, Groups.learnedPerCourse(this, st)[c.code.uppercase()] ?: emptyList(), st, ov, full = true),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        fun section(t: String) = box.addView(sectionLabel(t).apply { setPadding(dp(2), dp(24), 0, dp(8)) })
        fun navRow(title: String, sub: String, go: () -> Unit) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(10), dp(12))
                background = roundBg(Ui.BAR, Ui.BORDER, this@CoursesActivity, Radius.MD)
                pressRipple()
                isClickable = true
                contentDescription = "$title. $sub"
                setOnClickListener { go() }
                addView(LinearLayout(this@CoursesActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(tv(title, Type.BODY_L, Ui.TEXT, true))
                    if (sub.isNotEmpty()) addView(tv(sub, Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(2), 0, 0); maxLines = 2 })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(android.widget.ImageView(this@CoursesActivity).apply {
                    setImageResource(R.drawable.ic_chevron_right); setColorFilter(Ui.DIM)
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }

        // next class, with Join when it has a meeting link (the same rule as Home)
        ov.classes.firstOrNull { it.code.equals(c.code, true) }?.let { n ->
            section("Next class")
            // the same rule as the class rows above and Home: trusted data only, never "a link was found"
            val venue = Timetable.venueAt(this, c.code, n.start)
            val online = venue == "Online"
            box.addView(tv(dayLabel(n.start) + ", " + hhmm(n.start) + "–" + hhmm(n.end) + " · " + relIn(n.start - System.currentTimeMillis() / 1000),
                Type.BODY_L, Ui.TEXT, true))
            if (venue.isNotEmpty()) box.addView(iconText(if (online) R.drawable.ic_laptop else R.drawable.ic_place, venue, Type.BODY, if (online) Ui.CYAN else Ui.TEXT, true)
                .apply { setPadding(0, dp(4), 0, 0) })
            if (n.link.isNotEmpty() && (online || venue.isEmpty())) box.addView(button("Join class", Emphasis.PRIMARY, Ui.SIGNAL) { open(n.link) },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }

        // the long-term setting for the whole course, the same as the row in Settings → Online or in person
        section("Online or in person")
        val code = c.code.uppercase()
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        modeRow.addView(tv("Usually", Type.BODY, Ui.TEXT, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        val modes = Timetable.COURSE_MODES
        modeRow.addView(spinner(modes.map { it.second }, modes.indexOfFirst { it.first == Timetable.courseMode(this, code) }.coerceAtLeast(0)) { pos ->
            val m = modes[pos].first
            if (m != Timetable.courseMode(this, code)) {
                Timetable.setCourseMode(this, code, m)
                Scheduler.scheduleAll(this)
                render()
                if (m == "online") askMissingMeetingLinks(code) { render() }
            }
        }.apply { contentDescription = "$code: usually as timetable, online or in person" }, LinearLayout.LayoutParams(dp(176), WRAP))
        box.addView(modeRow)
        box.addView(tv(Timetable.COURSE_MODES_HELP + " For one class, or just one week, tap the class above.", Type.CAPTION, Ui.DIM)
            .apply { setPadding(0, dp(4), 0, 0) })

        // the links Join class opens: yours, else the one found on Spectrum; lectures and tutorials apart when both exist
        section("Meeting links")
        for (k in meetingKinds(code)) {
            val own = Timetable.meetingLinkOf(this, code, when (k) { "lec" -> true; "tut" -> false; else -> null })
            val host = try { android.net.Uri.parse(own).host ?: own } catch (_: Exception) { own }
            // no Spectrum lookup here: it parses the whole sync state, too slow to do per row on every render
            navRow(meetingKindName(k), if (own.isNotEmpty()) "Your link · $host"
                else "The one found on Spectrum, if any. Tap to paste your own.") { editMeetingLink(code, k) { render() } }
        }

        // this course's deadlines, each opening the same sheet as on Home
        section("Due")
        val due = ov.due.filter { it.course.contains(c.code, true) }
        if (due.isEmpty()) box.addView(tv("Nothing due for ${c.code} in what the app has synced.", Type.BODY, Ui.DIM))
        for (d in due.take(10)) {
            val left = d.ts - System.currentTimeMillis() / 1000
            navRow(d.name, "Due in " + relShort(left) + " · " + dayLabel(d.ts) + ", " + hhmm(d.ts)) { showDueSheet(d) { render() } }
        }

        section("Materials and notifications")
        if (c.id > 0L) {
            navRow("Materials", "Files, folders and links, by Spectrum page") {
                startActivity(Intent(this, MaterialsActivity::class.java)
                    .putExtra(MaterialsActivity.EXTRA_ID, c.id)
                    .putExtra(MaterialsActivity.EXTRA_CODE, c.code + Timetable.classTitle(c.code, c.name, "").let { if (it.isNotEmpty()) ", " + titleCase(it) else "" }))
            }
            navRow("Notification pages", Sections.summary(this, c.id, st)) {
                startActivity(Intent(this, SectionsActivity::class.java)
                    .putExtra(SectionsActivity.EXTRA_ID, c.id)
                    .putExtra(SectionsActivity.EXTRA_NAME, c.code + Timetable.classTitle(c.code, c.name, "").let { if (it.isNotEmpty()) " · $it" else "" }))
            }
        }
        if (c.url.isNotEmpty()) navRow("Open on Spectrum", "The course's own page") { open(c.url) }

        // the latest notifications that mention this course, newest first
        val recent = NotifyLog.entries(this).filter { it.kind == "notif" && (it.title.contains(c.code, true) || it.body.contains(c.code, true)) }
            .asReversed().take(5)
        if (recent.isNotEmpty()) {
            section("Recent notifications")
            for (e in recent) navRow(e.title, ago(NotifyLog.atOf(e))) { startActivity(Intent(this, NotificationsActivity::class.java)) }
        }
    }

    /** The Occ picker (from the pill or "Change Occ"): same saving and Undo as the old dropdown. */
    private fun pickOcc(code: String, occs: List<String>, current: String, learnedOnly: Boolean) {
        val names = (listOf("Not set") + occs.map { "Occ $it" }).toTypedArray()
        val sel = occs.indexOf(current) + 1
        android.app.AlertDialog.Builder(this, Ui.dialogTheme)
            .setTitle("Your Occ for $code")
            .apply { if (learnedOnly) setMessage("These Occs were learned from Spectrum (the course is not in the UM list).") }
            .setSingleChoiceItems(names, sel) { d, pos ->
                d.dismiss()
                val picked = if (pos <= 0) "" else occs[pos - 1]
                if (picked == current) return@setSingleChoiceItems
                setOcc(code, picked)
                render()
                // a slip of the finger is one tap to put back: the old Occ and its classes return
                val what = if (picked.isEmpty()) "Occ cleared for $code" else "$code set to Occ $picked"
                undoBar(if (current.isEmpty()) what else "$what (was Occ $current)") { setOcc(code, current); render() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Courses whose Occ you said looks right despite Spectrum's extra times ("CODE|occ"). */
    private fun occOk(): Set<String> = getSharedPreferences("courses", MODE_PRIVATE).getStringSet("occOk", emptySet())!!
    private fun occOkAdd(k: String) = getSharedPreferences("courses", MODE_PRIVATE).edit().putStringSet("occOk", occOk() + k).apply()

    /** Saves the Occ for [code] ("" = not set) and swaps its UM-list classes to that Occ's (none when not set). */
    private fun setOcc(code: String, occ: String) {
        val pp = Prefs(this)
        val keep = pp.myGroups.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("$code:", true) }
        pp.myGroups = (if (occ.isEmpty()) keep else keep + "$code:occ$occ").joinToString(", ")
        // a new Occ has new class times: ask for a look at them; none set, nothing to check
        pp.setOccNeedsCheck(code, occ.isNotEmpty())
        // the confirmed mark follows the Occ: cleared here, Confirm your Occ asks about this course again
        pp.setOccConfirmed(code, occ.isNotEmpty())
        if (occ.isEmpty()) Timetable.clearCatalog(this, code) else Timetable.autoSetup(this, code, occ)
    }

    /** Short name for one course, e.g. "ENT": shown with the code everywhere (Home, timetable, alerts). Empty = none. */
    private fun editShortName(code: String, current: String) {
        val field = EditText(this).apply {
            setText(current)
            setSelection(current.length)
            hint = "e.g. ENT, HCI"
            textSize = Type.BODY * Ui.fontScale; setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM); setSingleLine()
            minHeight = dp(Ui.touch(48))
            background = roundBg(Ui.BAR, Ui.BORDER_STRONG, this@CoursesActivity, Radius.SM)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field, LinearLayout.LayoutParams(MATCH, WRAP)) }
        val d = AlertDialog.Builder(this, Ui.dialogTheme)
            .setTitle("Short name for $code")
            .setMessage("Shown next to $code everywhere in the app.")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                Shorts.set(this, code, field.text.toString())
                render()
            }
            .setNegativeButton("Cancel", null)
        if (current.isNotEmpty()) d.setNeutralButton("Remove") { _, _ -> Shorts.set(this, code, ""); render() }
        d.show()
    }

    /** Rebuilds timetable entries for every course with a set Occ. */
    private fun refreshClasses() {
        val p = Prefs(this)
        val rebuilt = ArrayList<String>()
        val failed = ArrayList<String>()
        val occRe = Regex("(\\w+):occ([0-9]+[a-z]?)", RegexOption.IGNORE_CASE)
        for (m in occRe.findAll(p.myGroups)) {
            val code = m.groupValues[1].uppercase()
            val occ = m.groupValues[2]
            if (Timetable.autoSetup(this, code, occ)) rebuilt.add("$code occ$occ") else failed.add(code)
        }
        val msg = when {
            rebuilt.isEmpty() -> "Nothing to refresh — no course has an Occ set."
            failed.isEmpty() -> "Timetable updated: " + rebuilt.joinToString(", ") + "."
            else -> "Updated " + rebuilt.joinToString(", ") + ". Not in the UM list (learned from Spectrum instead): " + failed.joinToString(", ") + "."
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** Every course, one row each: code + full name on the left, short-form field on the right. One Save writes all. */
    private fun batchShorts(list: List<Courses.Course>) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(10), dp(24), 0) }
        val fields = LinkedHashMap<String, EditText>()
        for (c in list) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(tv(c.code, Type.BODY, Ui.TEXT, true))
            // the name without the code in front ("GIG1003 BASIC …" repeated the code and cut the name off)
            val name = Timetable.classTitle(c.code, c.name, "")
            if (name.isNotEmpty()) {
                left.addView(tv(name, Type.CAPTION, Ui.DIM).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            val f = EditText(this).apply {
                setText(Shorts.get(this@CoursesActivity, c.code))
                hint = "short name"
                textSize = Type.LABEL * Ui.fontScale
                setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM); setSingleLine()
                minWidth = dp(90)
                background = roundBg(Ui.BAR, Ui.BORDER, this@CoursesActivity, Radius.SM)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            fields[c.code] = f
            row.addView(f, LinearLayout.LayoutParams(dp(120), WRAP))
            col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        val scroll = android.widget.ScrollView(this).apply { addView(col) }
        scroll.layoutParams = LinearLayout.LayoutParams(
            MATCH,
            (resources.displayMetrics.heightPixels * 0.5).toInt().coerceAtLeast(dp(180)),
        )
        AlertDialog.Builder(this, Ui.dialogTheme)
            .setTitle("Short names")
            .setMessage("Blank = code only.")
            .setView(scroll)
            .setPositiveButton("Save all") { _, _ ->
                var n = 0
                for ((code, f) in fields) {
                    val v = f.text.toString().trim()
                    if (v != Shorts.get(this, code)) { Shorts.set(this, code, v); n++ }
                }
                Toast.makeText(this, "Saved $n short name" + (if (n == 1) "" else "s") + ".", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** Spectrum sends course names in capitals; show them in title case (roman numerals and short codes stay). */
fun titleCase(t: String): String {
    if (t != t.uppercase()) return t
    val small = setOf("and", "of", "the", "in", "for", "to", "a", "an", "on", "with")
    return t.split(" ").mapIndexed { i, w ->
        when {
            w.matches(Regex("[IVX]+")) || w.any { it.isDigit() } -> w
            i > 0 && w.lowercase() in small -> w.lowercase()
            else -> w.lowercase().replaceFirstChar { it.uppercase() }
        }
    }.joinToString(" ")
}
