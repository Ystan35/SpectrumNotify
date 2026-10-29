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

    private fun render() {
        val list = Courses.all(this)
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
        box.addView(button("+  Add a course from the UM timetable", Emphasis.TERTIARY) {
            startActivity(Intent(this, CatalogActivity::class.java))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        for (c in list) {
            box.addView(card(c, colours[c.code] ?: Ui.CYAN), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
    }

    private fun card(c: Courses.Course, colour: Int): View {
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

        // header: code (and your short form) small in the course colour, the course name big and bold under it
        val short = Shorts.get(this, c.code)
        // short names are set for all courses at once (Short names, above); tapping this line changes just this one
        col.addView(tv(c.code.uppercase() + if (short.isNotEmpty()) "  ·  $short" else "", Type.LABEL, colour, true).apply {
            letterSpacing = 0.06f
            isClickable = true
            contentDescription = c.code + (if (short.isNotEmpty()) ", short name $short" else "") + ". Tap to change the short name"
            setOnClickListener { editShortName(c.code, short) }
        })
        val title = Timetable.classTitle(c.code, c.name, "").ifEmpty { c.code }
        col.addView(tv(title, Type.TITLE, Ui.TEXT, true).apply {
            setPadding(0, dp(2), 0, 0)
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

        // your Occ for this course: a compact dropdown, changed in place
        val pp = Prefs(this)
        val catOccs = Catalog.find(this, c.code)?.occs() ?: emptyList()
        val learned = Groups.learnedPerCourse(this)[c.code.uppercase()] ?: emptyList()
        val occs = if (catOccs.isNotEmpty()) catOccs else learned
        val current = Regex(Regex.escape(c.code) + ":occ([0-9]+[a-z]?)", RegexOption.IGNORE_CASE)
            .find(pp.myGroups)?.groupValues?.get(1) ?: ""
        val occMissing = current.isEmpty() && occs.isNotEmpty()
        val mine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mine.addView(tv("Occ", Type.LABEL, if (occMissing) Ui.ORANGE else Ui.DIM, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        if (occs.isEmpty()) {
            mine.addView(tv("learned from Spectrum", Type.LABEL, Ui.DIM), LinearLayout.LayoutParams(0, WRAP, 1f))
        } else {
            val names = listOf("Not set") + occs.map { "Occ $it" }
            val sel = occs.indexOf(current) + 1
            val occSp = spinner(names, if (sel >= 0) sel else 0) { pos ->
                val picked = if (pos <= 0) "" else occs[pos - 1]
                if (picked == current) return@spinner
                setOcc(c.code, picked)
                render()
                // a slip of the finger is one tap to put back: the old Occ and its classes return
                val what = if (picked.isEmpty()) "Occ cleared for ${c.code}" else "${c.code} set to Occ $picked"
                undoBar(if (current.isEmpty()) what else "$what (was Occ $current)") { setOcc(c.code, current); render() }
            }
            // as wide as its longest choice ("Occ 12"), not the whole card; orange edge while not set
            occSp.background = roundBg(Ui.BAR, if (occMissing) Ui.ORANGE else Ui.BORDER_STRONG, this, Radius.SM)
            occSp.minimumHeight = dp(Ui.touch(48))
            occSp.contentDescription = "Occ for ${c.code}: " + (if (current.isEmpty()) "not set" else current)
            mine.addView(occSp, LinearLayout.LayoutParams(WRAP, WRAP))
            mine.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        col.addView(mine, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        if (occMissing) {
            col.label("Pick your Occ (lecture/tutorial group): it decides which links and posts are yours.", Type.CAPTION, Ui.ORANGE, 4)
        }
        // Spectrum knows when YOUR classes are. Its weekly classes that the picked Occ does not have
        // (no UM-list class at that time) mean the Occ is probably wrong: say so instead of showing both quietly.
        val fromList = c.slots.filter { it.grp.startsWith("cat-") }
        val notInOcc = c.slots.filter { s -> !s.manual && fromList.none { it.day == s.day && it.start < s.end && s.start < it.end } }
        if (current.isNotEmpty() && fromList.isNotEmpty() && notInOcc.isNotEmpty()) {
            val whenText = notInOcc.take(2).joinToString(", ") { "${Timetable.DAYS[it.day - 1]} ${Timetable.fmtMin(it.start)}" }
            col.label("Spectrum also lists ${c.code} on $whenText, which Occ $current does not have. " +
                "If that is your class, your Occ may be wrong.", Type.CAPTION, Ui.ORANGE, 4)
        }

        // actions: the course page (a link out) and which pages notify you
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (c.url.isNotEmpty()) {
            actions.addView(button("Course page", Emphasis.SECONDARY, colour) { open(c.url) }.apply {
                contentDescription = "Open ${c.code} home page on Spectrum"
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(4) })
        }
        if (c.id > 0L) {
            actions.addView(button("Notification pages", Emphasis.TERTIARY) {
                startActivity(
                    Intent(this, SectionsActivity::class.java)
                        .putExtra(SectionsActivity.EXTRA_ID, c.id)
                        .putExtra(SectionsActivity.EXTRA_NAME, c.code + if (c.name.isNotEmpty()) "  " + c.name else "")
                )
            }.apply { contentDescription = "Notification pages for ${c.code}: " + Sections.summary(this@CoursesActivity, c.id) })
        }
        if (actions.childCount > 0) col.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return card
    }


    /** Saves the Occ for [code] ("" = not set) and swaps its UM-list classes to that Occ's (none when not set). */
    private fun setOcc(code: String, occ: String) {
        val pp = Prefs(this)
        val keep = pp.myGroups.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("$code:", true) }
        pp.myGroups = (if (occ.isEmpty()) keep else keep + "$code:occ$occ").joinToString(", ")
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
            if (c.name.isNotEmpty() && !c.name.equals(c.code, true)) {
                left.addView(tv(c.name, Type.CAPTION, Ui.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            val f = EditText(this).apply {
                setText(Shorts.get(this@CoursesActivity, c.code))
                hint = "short form"
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
            .setTitle("Short forms for your courses")
            .setMessage("Write the name you recognise. Blank = show the code only.")
            .setView(scroll)
            .setPositiveButton("Save all") { _, _ ->
                var n = 0
                for ((code, f) in fields) {
                    val v = f.text.toString().trim()
                    if (v != Shorts.get(this, code)) { Shorts.set(this, code, v); n++ }
                }
                Toast.makeText(this, "Saved $n short form" + (if (n == 1) "" else "s") + ".", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}