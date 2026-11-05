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
    }

    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = screen("Courses", navTo = CoursesActivity::class.java, onRefresh = { Scheduler.pollNow(this) })
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
            box.addView(card(c, colours[c.code] ?: Ui.CYAN, learned[c.code.uppercase()] ?: emptyList(), st), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
    }

    private fun card(c: Courses.Course, colour: Int, learned: List<String>, st: State): View {
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
            setPadding(dp(16), dp(14), dp(16), dp(10))
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
        // short names are set for all courses at once (Short names, above); tapping this line changes just this one
        head.addView(tv(c.code.uppercase() + if (short.isNotEmpty()) "  ($short)" else "", Type.LABEL, colour, true).apply {
            isClickable = true
            minHeight = dp(Ui.touch(48))
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = c.code + (if (short.isNotEmpty()) ", short name $short" else "") + ". Tap to change the short name"
            setOnClickListener { editShortName(c.code, short) }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (occs.isNotEmpty()) {
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
        })

        // class times, one line each
        if (c.slots.isEmpty()) {
            val nh = tv("No class times known yet", Type.BODY, Ui.DIM).apply { setPadding(0, dp(10), 0, 0) }
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
                times.addView(iconText(R.drawable.ic_cat_deadline,
                    "${Timetable.DAY_FULL[s.day - 1]}  ${Timetable.fmtMin(s.start)} – ${Timetable.fmtMin(s.end)}$kind",
                    Type.BODY, Ui.TEXT).apply { setPadding(0, dp(4), 0, dp(4)) })
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

        // ONE notice when the Occ needs a look: just picked (check it against MAYA), or Spectrum has
        // weekly classes this Occ does not (the Occ may be wrong). "Looks right" settles both.
        val fromList = c.slots.filter { it.grp.startsWith("cat-") }
        val notInOcc = c.slots.filter { s -> !s.manual && fromList.none { it.day == s.day && it.start < s.end && s.start < it.end } }
        val okKey = "${c.code.uppercase()}|$current"
        val acked = okKey in occOk()
        val mismatch = current.isNotEmpty() && fromList.isNotEmpty() && notInOcc.isNotEmpty() && !acked
        if (current.isNotEmpty() && (pp.occNeedsCheck(c.code) || mismatch)) {
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

        // actions: the course page (a link out) and which pages notify you
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // three equal buttons on one line: a long label shrinks a little instead of wrapping
        fun TextView.fit() = apply {
            setPadding(dp(6), dp(6), dp(6), dp(6)); maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(11, (Type.BODY * Ui.fontScale).toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        if (c.id > 0L) {
            // the course's files, folders and links, one tap each (downloaded in the app)
            actions.addView(button("Materials", Emphasis.PRIMARY, Ui.SIGNAL) {
                startActivity(Intent(this, MaterialsActivity::class.java)
                    .putExtra(MaterialsActivity.EXTRA_ID, c.id)
                    .putExtra(MaterialsActivity.EXTRA_CODE, c.code + Timetable.classTitle(c.code, c.name, "").let { if (it.isNotEmpty()) ", " + titleCase(it) else "" }))
            }.fit().apply { contentDescription = "Materials for ${c.code}: files, folders and links"; tag = "guide:materials" },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        }
        if (c.url.isNotEmpty()) {
            actions.addView(button("Course page", Emphasis.SECONDARY, Ui.CYAN) { open(c.url) }.fit().apply {
                contentDescription = "Open ${c.code} home page on Spectrum"
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        }
        if (c.id > 0L) {
            actions.addView(button("Notifications", Emphasis.SECONDARY, Ui.CYAN) {
                startActivity(
                    Intent(this, SectionsActivity::class.java)
                        .putExtra(SectionsActivity.EXTRA_ID, c.id)
                        .putExtra(SectionsActivity.EXTRA_NAME, c.code + Timetable.classTitle(c.code, c.name, "").let { if (it.isNotEmpty()) " · $it" else "" })
                )
            }.fit().apply { contentDescription = "Notification pages for ${c.code}: " + Sections.summary(this@CoursesActivity, c.id, st) },
                LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        if (actions.childCount > 0) col.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return card
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
