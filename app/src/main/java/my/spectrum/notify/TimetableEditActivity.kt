package my.spectrum.notify

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import java.time.LocalDate
import java.util.Locale

/**
 * Add or edit one class.
 *  - New / own entry: course, name, type, days, start and end time, room or note, colour.
 *  - Class learned from Spectrum: the time comes from Spectrum so it is shown read-only;
 *    you can change its type and the course colour, or remove it from your timetable.
 */
class TimetableEditActivity : Activity() {
    companion object {
        const val EXTRA_GRP = "grp"   // id of one of your own entries
        const val EXTRA_KEY = "key"   // key of a class learned from Spectrum
        const val EXTRA_DAY = "day"   // weekday to pre-select for a new entry (1 = Mon)
    }

    private val kinds = listOf(SlotKind.LECTURE, SlotKind.TUTORIAL, SlotKind.LAB, SlotKind.CLASS)
    private val kindLabels = listOf("Lecture", "Tutorial", "Lab", "Other")

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val grp = intent.getStringExtra(EXTRA_GRP) ?: ""
        val key = intent.getStringExtra(EXTRA_KEY) ?: ""
        val own = if (grp.isNotEmpty()) Timetable.manualGroup(this, grp) else emptyList()
        val learned = if (key.isNotEmpty()) Timetable.all(this).firstOrNull { it.key == key } else null
        if ((grp.isNotEmpty() && own.isEmpty()) || (key.isNotEmpty() && learned == null)) {
            finish()
            return
        }
        if (learned != null) buildLearned(learned) else buildOwn(grp, own)
    }

    private fun kindIndex(k: SlotKind) = maxOf(0, kinds.indexOf(k))

    // ------------------------------------------------------------ own entries
    private fun buildOwn(grp: String, own: List<Slot>) {
        val editing = own.isNotEmpty()
        val col = screen(if (editing) "Edit class" else "Add class")
        val first = own.firstOrNull()

        if (!editing) {
            col.label("Studying at UM? Choose your course and your Lecture / Tutorial Occ from the UM timetable.", 12f, Ui.DIM, 6)
            col.btn("Pick from UM timetable") {
                startActivity(Intent(this, CatalogActivity::class.java))
                finish()
            }
            col.label("Or add a class by hand:", 12f, Ui.DIM, 14)
        }

        var course = first?.course ?: ""
        var kindIdx = kindIndex(first?.kind ?: SlotKind.LECTURE)
        val days = own.map { it.day }.toMutableSet()
        if (days.isEmpty()) {
            val pre = intent.getIntExtra(EXTRA_DAY, 0)
            days.add(if (pre in 1..7) pre else LocalDate.now(zone()).dayOfWeek.value)
        }
        var start = first?.start ?: (9 * 60)
        var end = first?.end ?: (11 * 60)
        var colourIdx = Timetable.colourIndexOrNull(this, course) ?: Timetable.nextColourIndex(this)
        var colourTouched = false
        var setSwatch: (Int) -> Unit = {}

        // course code, with the courses already in the timetable as quick picks
        col.label("Course code", 13f, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label("For example WIA2010. Pick one below, or type a new one.", 12f, Ui.DIM, 2)
        val courseField = col.field(course, "WIA2010")
        courseField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                course = normCourse(s?.toString() ?: "")
                if (!colourTouched) {
                    colourIdx = Timetable.colourIndexOrNull(this@TimetableEditActivity, course) ?: Timetable.nextColourIndex(this@TimetableEditActivity)
                    setSwatch(colourIdx)
                }
            }
        })
        val known = Timetable.all(this).map { it.course }.toSortedSet()
        if (known.isNotEmpty()) {
            val colours = Timetable.colours(this, known)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in known) {
                row.addView(chip(c, false, dot = colours[c] ?: Ui.CYAN) {
                    courseField.setText(c)
                    courseField.setSelection(c.length)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            }
            col.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }

        col.label("Type", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        col.chips(kindLabels, setOf(kindIdx), false) { kindIdx = it.first() }

        col.label("Days", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label("Choose every day this class happens at the same time.", 12f, Ui.DIM, 2)
        col.chips(Timetable.DAYS, days.map { it - 1 }.toSet(), true) { sel ->
            days.clear()
            days.addAll(sel.map { it + 1 })
        }

        col.label("Time", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val endBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var endField: TimeField
        val startField = timeField(start) {
            start = it
            if (end <= start) {
                end = minOf(24 * 60 - 1, start + 60)
                endField.set(end)
            }
        }
        endField = timeField(end) { end = it }
        val fieldLp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) } }
        startBox.label("Starts", 12f, Ui.DIM, 0)
        startBox.addView(startField.view, fieldLp())
        endBox.label("Ends", 12f, Ui.DIM, 0)
        endBox.addView(endField.view, fieldLp())
        times.addView(startBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(endBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        col.addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        col.label("Name (optional)", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        val titleField = col.field(first?.title ?: "", "Lecture, Group A")
        col.label("Room or note (optional)", 13f, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
        val noteField = col.field(first?.note ?: "", "Hall 3, bring laptop")

        col.label("Attendance", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label("How is attendance taken for this class?", 12f, Ui.DIM, 2)
        // mode: Spectrum link (auto/paste) or manual by lecturer (no link needed)
        val attModes = listOf("Spectrum link", "Manual by lecturer", "None")
        var attMode = when {
            (first?.note ?: "").startsWith("[manual-att]") -> 1
            (first?.note ?: "").startsWith("[att]") -> 0
            else -> 2
        }
        val attLinks = (first?.note ?: "").removePrefix("[att]").removePrefix("[manual-att]").trim()
        val attLinksField = col.field(attLinks, "Paste attendance link(s), one per line")
        attLinksField.visibility = if (attMode == 0) android.view.View.VISIBLE else android.view.View.GONE
        col.chips(attModes, setOf(attMode), false) { sel ->
            attMode = sel.firstOrNull() ?: 2
            attLinksField.visibility = if (attMode == 0) android.view.View.VISIBLE else android.view.View.GONE
        }
        col.label("Spectrum link = the app finds the attendance page automatically, and you can paste extra links. " +
            "Manual by lecturer = the lecturer marks attendance in person, so no link is needed.", 12f, Ui.DIM, 6)

        col.label("Colour", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label("One colour per course, so every class of the same course looks the same.", 12f, Ui.DIM, 2)
        setSwatch = col.swatches(colourIdx) { colourIdx = it; colourTouched = true }

        col.btn("Save") {
            val code = normCourse(courseField.text.toString())
            when {
                code.isEmpty() -> toast("Enter a course code, for example WIA2010")
                days.isEmpty() -> toast("Choose at least one day")
                end <= start -> toast("The end time must be after the start time")
                else -> {
                    val baseNote = noteField.text.toString().trim()
                    val attNote = when (attMode) {
                        0 -> "[att]" + attLinksField.text.toString().trim()
                        1 -> "[manual-att]"
                        else -> baseNote
                    }
                    Timetable.saveManual(
                        this, if (editing) grp else Timetable.newGroup(), code, titleField.text.toString().trim(),
                        days, start, end, kinds[kindIdx], attNote,
                    )
                    if (colourTouched || Timetable.colourIndexOrNull(this, code) == null) {
                        Timetable.setColourIndex(this, code, colourIdx)
                    }
                    finish()
                }
            }
        }
        if (editing) {
            dangerBtn(col, "Delete this class") {
                android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Delete this class?")
                    .setMessage("It will be removed from your timetable. You can add it again, but you will have to re-enter the times.")
                    .setPositiveButton("Delete") { _, _ ->
                        Timetable.deleteManual(this, grp)
                        finish()
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }
        }
    }

    // ------------------------------------------------------------ classes learned from Spectrum
    private fun buildLearned(s: Slot) {
        val col = screen("Class details")
        var kindIdx = kindIndex(s.kind)
        var colourIdx = Timetable.colourIndexOrNull(this, s.course) ?: Timetable.nextColourIndex(this)

        col.label(s.course, 22f, Timetable.colours(this, setOf(s.course))[s.course] ?: Ui.TEXT, 8).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label(
            "${Timetable.DAY_FULL[s.day - 1]}, ${Timetable.fmtMin(s.start)} – ${Timetable.fmtMin(s.end)}" +
                if (s.title.isNotEmpty()) "\n${s.title}" else "",
            15f, Ui.TEXT, 4,
        )
        col.label("This class comes from Spectrum, so its day and time follow Spectrum.", 12f, Ui.DIM, 6)

        col.label("Type", 13f, Ui.TEXT, 24).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label(
            if (s.kind == SlotKind.CLASS) "Spectrum did not say whether this is a lecture, tutorial or lab. Choose one if you know."
            else "Spotted from the class name on Spectrum. Change it if it is wrong.",
            12f, Ui.DIM, 2,
        )
        col.chips(kindLabels, setOf(kindIdx), false) { kindIdx = it.first() }

        col.label("Colour", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label("Applies to every ${s.course} class.", 12f, Ui.DIM, 2)
        col.swatches(colourIdx) { colourIdx = it }

        col.btn("Save") {
            Timetable.setKind(this, s.key, kinds[kindIdx])
            Timetable.setColourIndex(this, s.course, colourIdx)
            finish()
        }
        dangerBtn(col, "Remove from my timetable") {
            Timetable.hide(this, s.key)
            finish()
        }
        col.label("Removed classes can be brought back from the Timetable screen.", 12f, Ui.DIM, 6)
    }

    // ------------------------------------------------------------ helpers
    private fun normCourse(s: String) = s.trim().uppercase(Locale.ROOT)

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun dangerBtn(col: LinearLayout, text: String, onClick: () -> Unit) {
        val b = col.btn(text, onClick)
        b.setTextColor(Ui.RED)
        b.background = roundBg(Ui.BAR, Ui.RED, this, 24)
    }
}
