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
        val learned = if (key.isNotEmpty()) Timetable.all(this, null).firstOrNull { it.key == key } else null
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

        // older versions kept the attendance choice inside the note ("[att]links" / "[manual-att]"),
        // which wiped the room; read those once, then keep note and attendance apart
        val oldNote = first?.note ?: ""
        val legacyMode = when {
            oldNote.startsWith("[manual-att]") -> 1
            oldNote.startsWith("[att]") -> 0
            else -> -1
        }
        val legacyLinks = if (legacyMode == 0) oldNote.removePrefix("[att]").trim() else ""
        val cleanNote = if (legacyMode >= 0) "" else oldNote

        col.label("Name (optional)", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        val titleField = col.field(first?.title ?: "", "Lecture, Group A")
        col.label("Room or note (optional)", 13f, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
        val noteField = col.field(cleanNote, "Hall 3, bring laptop")

        col.label("Attendance", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label("How is attendance taken for this class?", 12f, Ui.DIM, 2)
        // mode: Spectrum link (auto/paste) or manual by lecturer (no link needed)
        val attModes = listOf("Spectrum link", "Manual by lecturer", "None")
        val modeKeys = listOf("link", "manual", "none")
        var attMode = when {
            legacyMode >= 0 -> legacyMode
            course.isNotEmpty() -> modeKeys.indexOf(Timetable.attMode(this, course)).takeIf { it >= 0 } ?: 0
            else -> 0
        }
        val attLinks = legacyLinks.ifEmpty { if (course.isNotEmpty()) Timetable.userAttLinkRaw(this, course) else "" }
        val attLinksField = col.field(attLinks, "Paste attendance link(s), one per line (optional)")
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
                    Timetable.saveManual(
                        this, if (editing) grp else Timetable.newGroup(), code, titleField.text.toString().trim(),
                        days, start, end, kinds[kindIdx], noteField.text.toString().trim(),
                    )
                    Timetable.setAttMode(this, code, modeKeys[attMode])
                    // pasted links feed the same resolver as the Attendance buttons everywhere
                    if (attMode == 0) Timetable.setAttendanceLink(this, code, attLinksField.text.toString().trim())

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
        // s already carries any time override; Spectrum's own time is in the key "course|day|start|end"
        val keyParts = s.key.split("|")
        val specStart = keyParts.getOrNull(2)?.toIntOrNull() ?: s.start
        val specEnd = keyParts.getOrNull(3)?.toIntOrNull() ?: s.end

        col.label(s.course, 22f, Timetable.colours(this, setOf(s.course))[s.course] ?: Ui.TEXT, 8).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label(
            "${Timetable.DAY_FULL[s.day - 1]}, ${Timetable.fmtMin(specStart)} – ${Timetable.fmtMin(specEnd)}" +
                (if (specStart != s.start || specEnd != s.end) "  (yours: ${Timetable.fmtMin(s.start)} – ${Timetable.fmtMin(s.end)})" else "") +
                if (s.title.isNotEmpty()) "\n${s.title}" else "",
            15f, Ui.TEXT, 4,
        )
        col.label("This class comes from Spectrum. You can override its time and meeting link below.", 12f, Ui.DIM, 6)

        // time override (the Spectrum event may cover all sessions; yours may differ)
        val ov = Timetable.timeOverride(this, s.key)
        col.label("Time override", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label("Only if your session time differs from what Spectrum lists. Leave untouched to follow Spectrum.", 12f, Ui.DIM, 2)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val endBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var newStart = ov?.first ?: specStart
        var newEnd = ov?.second ?: specEnd
        lateinit var endOvField: TimeField
        val startOvField = timeField(newStart) {
            newStart = it
            if (newEnd <= it) { newEnd = minOf(24 * 60 - 1, it + 60); endOvField.set(newEnd) }
        }
        endOvField = timeField(newEnd) { newEnd = it }
        startBox.label("Starts", 12f, Ui.DIM, 0)
        startBox.addView(startOvField.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        endBox.label("Ends", 12f, Ui.DIM, 0)
        endBox.addView(endOvField.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        times.addView(startBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(endBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        col.addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // attendance link override (the Attendance pill reads this first)
        col.label("Attendance link", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label("Paste your occ's attendance page to override the automatically picked one.", 12f, Ui.DIM, 2)
        val attLinkField = col.field(Timetable.userAttLinkRaw(this, s.course), "https://spectrum.um.edu.my/mod/attendance/view.php?id=…")

        // meeting link override
        col.label("Meeting link", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label("Detected automatically from Spectrum. Paste your own to override, or clear to go back.", 12f, Ui.DIM, 2)
        val mlinkField = col.field(Timetable.meetingLinkOf(this, s.course), "https://teams.microsoft.com/…")

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
            if (newEnd <= newStart) {
                toast("The end time must be after the start time")
                return@btn
            }
            Timetable.setKind(this, s.key, kinds[kindIdx])
            Timetable.setColourIndex(this, s.course, colourIdx)
            // write overrides only when they differ from Spectrum's values
            val hasChanged = newStart != specStart || newEnd != specEnd
            if (hasChanged) Timetable.setTimeOverride(this, s.key, newStart, newEnd)
            else Timetable.clearTimeOverride(this, s.key)
            Timetable.setMeetingLink(this, s.course, mlinkField.text.toString().trim())
            // persist the pasted ATTENDANCE link too: the Attendance pill reads
            // userAttLinkRaw first, so this overrides the occ-scored page (fixes wrong-occ)
            if (attLinkField.text.toString().trim() != Timetable.userAttLinkRaw(this, s.course)) {
                Timetable.setAttendanceLink(this, s.course, attLinkField.text.toString().trim())
            }
            Scheduler.scheduleAll(this)   // the class alarm moves with the new time right away
            finish()
        }
        if (Timetable.timeOverride(this, s.key) != null || Timetable.meetingLinkOf(this, s.course).isNotEmpty() ||
            Timetable.userAttLinkRaw(this, s.course).isNotEmpty()
        ) {
            dangerBtn(col, "Reset to Spectrum values") {
                Timetable.clearTimeOverride(this, s.key)
                Timetable.setMeetingLink(this, s.course, "")
                Timetable.setAttendanceLink(this, s.course, "")
                // a link pasted in the Location dialog (pre-0.25.1) would otherwise come back as the override
                if (Timetable.locationOf(this, s.course).startsWith("online:")) Timetable.setLocation(this, s.course, "online:")
                Scheduler.scheduleAll(this)
                finish()
            }
        }
        dangerBtn(col, "Remove from my timetable") {
            Timetable.hide(this, s.key)
            Timetable.justHidden = s.key   // the Timetable screen offers Undo when it comes back
            finish()
        }
        col.label("You can undo this right after, or bring removed classes back from the Timetable screen.", 12f, Ui.DIM, 6)
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
