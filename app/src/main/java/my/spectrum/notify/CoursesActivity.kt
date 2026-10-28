package my.spectrum.notify

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** All your courses: class times, and a button to each course's Spectrum home page. */
class CoursesActivity : Activity() {
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

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size * Ui.fontScale
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
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
            box.label("No courses yet", 20f, Ui.TEXT, 24).setTypeface(null, Typeface.BOLD)
            box.label(
                if (loggedIn) "Your courses appear after the next check of Spectrum."
                else "Log in first, then your courses are listed here.",
                14f, Ui.DIM, 8,
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
        box.label("${list.size} course" + (if (list.size == 1) "" else "s"), 14f, Ui.DIM, 8)
        box.btn("Set short forms for all courses") { batchShorts(list) }
        box.btn("Refresh my classes") { refreshClasses() }
        box.btn("Add a course from the UM timetable") {
            startActivity(Intent(this, CatalogActivity::class.java))
        }
        box.label(
            "Occ = which lecture/tutorial group you belong to. Set it per course below; " +
                "after changing one, tap \"Refresh my classes\" so the times update.",
            12f, Ui.DIM, 8,
        )
        for (c in list) {
            box.addView(card(c, colours[c.code] ?: Ui.CYAN), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
    }

    private fun card(c: Courses.Course, colour: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundBg(Ui.BAR, Ui.BORDER, this@CoursesActivity, 14)
        }
        val r = dp(14).toFloat()
        card.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(colour)
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
        }, LinearLayout.LayoutParams(dp(7), MATCH))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        col.addView(tv(Shorts.display(this, c.code), 17f, colour, true))
        if (c.name.isNotEmpty() && !c.name.equals(c.code, ignoreCase = true)) {
            col.addView(tv(c.name, 14f, Ui.TEXT).apply { setPadding(0, dp(2), 0, 0) })
        }
        // Occ picker: lives with the course it configures
        val pp = Prefs(this)
        val catOccs = Catalog.find(this, c.code)?.occs() ?: emptyList()
        val learned = Groups.learnedPerCourse(this)[c.code.uppercase()] ?: emptyList()
        val occs = if (catOccs.isNotEmpty()) catOccs else learned
        val current = Regex(Regex.escape(c.code) + ":occ([0-9]+[a-z]?)", RegexOption.IGNORE_CASE)
            .find(pp.myGroups)?.groupValues?.get(1) ?: ""
        col.label("Occ (your group)", 12f, Ui.DIM, 4)
        if (occs.isEmpty()) {
            col.label("Not in the UM list — Occ is learned from Spectrum automatically.", 11f, Ui.DIM, 2)
        } else {
            val names = listOf("Not set") + occs.map { "Occ $it" }
            val sel = occs.indexOf(current) + 1
            val occSp = spinner(names, if (sel >= 0) sel else 0) { pos ->
                val keep = pp.myGroups.split(",").map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("${c.code}:", true) }
                pp.myGroups = (if (pos <= 0) keep else keep + "${c.code}:occ${occs[pos - 1]}").joinToString(", ")
                if (pos > 0) Timetable.autoSetup(this, c.code, occs[pos - 1])
                Toast.makeText(this,
                    "Occ saved for ${c.code} — class times updated. Check other courses too, or tap \"Refresh my classes\".",
                    Toast.LENGTH_LONG).show()
            }
            col.addView(occSp, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
            if (catOccs.isEmpty()) col.label("Occs learned from Spectrum (course not in the UM list).", 11f, Ui.DIM, 2)
        }
        // editable short form with its own Save button
        val shortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val shortField = EditText(this).apply {
            setText(Shorts.get(this@CoursesActivity, c.code))
            hint = "Your short form (e.g. cm, fop)"
            setTextSize(13f); setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM); setSingleLine()
            background = roundBg(Ui.BAR, Ui.BORDER, this@CoursesActivity, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        shortRow.addView(shortField, LinearLayout.LayoutParams(0, WRAP, 1f))
        val saveBtn = tv("Save", 13f, Ui.CYAN, true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            background = roundBg(Ui.BAR, Ui.BORDER, this@CoursesActivity, 10)
            setOnClickListener {
                Shorts.set(this@CoursesActivity, c.code, shortField.text.toString())
                Toast.makeText(this@CoursesActivity, "Saved — shows with ${c.code} everywhere.", Toast.LENGTH_SHORT).show()
            }
        }
        shortRow.addView(saveBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        col.addView(shortRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        if (c.slots.isEmpty()) {
            val nh = tv("No class times known yet", 12f, Ui.DIM).apply { setPadding(0, dp(8), 0, 0) }
            col.addView(nh)
            // refresh line: re-pull the timetable from Spectrum (Occ may have changed or not fetched yet)
            val rf = tv("↻ Refresh class times", 13f, Ui.CYAN, true).apply {
                setPadding(0, dp(4), 0, 0)
                minHeight = dp(40)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = rippleBg(Ui.BAR, 0, this@CoursesActivity, 10)
            }
            rf.setOnClickListener {
                Scheduler.pollNow(this)
                nh.text = "Checking Spectrum…"
                Toast.makeText(this, "Checking Spectrum — class times update when the check finishes.", Toast.LENGTH_SHORT).show()
            }
            col.addView(rf)
        } else {
            for (s in c.slots) {
                val kind = if (s.kind == SlotKind.CLASS) "" else "  ·  ${s.kind.label}"
                col.addView(
                    tv("${Timetable.DAYS[s.day - 1]}  ${Timetable.fmtMin(s.start)} – ${Timetable.fmtMin(s.end)}$kind", 13f, Ui.DIM)
                        .apply { setPadding(0, dp(6), 0, 0) }
                )
            }
        }

        if (c.url.isNotEmpty()) {
            col.addView(tv("Course home page", 14f, onAccent(), true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(colour) }
                isClickable = true
                contentDescription = "Open ${c.code} home page on Spectrum"
                setOnClickListener { open(c.url) }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        if (c.id > 0L) {
            col.btn("Notification pages  ·  " + Sections.summary(this, c.id)) {
                startActivity(
                    Intent(this, SectionsActivity::class.java)
                        .putExtra(SectionsActivity.EXTRA_ID, c.id)
                        .putExtra(SectionsActivity.EXTRA_NAME, c.code + if (c.name.isNotEmpty()) "  " + c.name else "")
                )
            }
        }
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return card
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
            left.addView(tv(c.code, 14f, Ui.TEXT, true))
            if (c.name.isNotEmpty() && !c.name.equals(c.code, true)) {
                left.addView(tv(c.name, 11f, Ui.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            }
            row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
            val f = EditText(this).apply {
                setText(Shorts.get(this@CoursesActivity, c.code))
                hint = "short form"
                setTextSize(13f)
                setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM); setSingleLine()
                minWidth = dp(90)
                background = roundBg(Ui.BAR, Ui.BORDER, this@CoursesActivity, 10)
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_DayNight)
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