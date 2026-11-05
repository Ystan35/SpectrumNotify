package my.spectrum.notify

import android.app.Activity
import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Choose which Spectrum attendance page a course's classes use, when the app's guess is wrong.
 * Lectures and tutorials can have their own page (kind "lec" / "tut"), or one page serves both ("").
 * Pages are listed in the order Spectrum shows them on the course page.
 * Opened from the round edit button before Attendance on a Timetable card (or holding Attendance), or from "Attendance" on a Courses card.
 */
object AttendancePicker {
    /** kind: "lec" / "tut" / "" (all classes of the course). */
    private fun kindName(kind: String) = when (kind) { "lec" -> "Lectures"; "tut" -> "Tutorials"; else -> "All classes" }
    private fun prefer(kind: String): Boolean? = when (kind) { "lec" -> true; "tut" -> false; else -> null }

    /** Lecture AND tutorial classes for this course on your timetable: each can have its own page. */
    fun hasBothKinds(a: Activity, course: String): Boolean {
        val kinds = Timetable.all(a).filter { it.course.equals(course, true) }.map { it.kind }.toSet()
        return SlotKind.LECTURE in kinds && SlotKind.TUTORIAL in kinds
    }

    /** From a Courses card: asks Lectures / Tutorials first when the course has both, then the list. */
    fun openForCourse(a: Activity, course: String, cid: Long, onDone: () -> Unit) {
        if (!hasBothKinds(a, course)) return open(a, course, cid, "", onDone)
        AlertDialog.Builder(a, Ui.dialogTheme)
            .setTitle("Attendance link for $course")
            .setItems(arrayOf(
                "Lectures: " + label(a, course, cid, "lec"),
                "Tutorials: " + label(a, course, cid, "tut"),
            )) { _, i -> open(a, course, cid, if (i == 0) "lec" else "tut", onDone) }
            .setNegativeButton("Close", null)
            .show()
    }

    /** What a class of [kind] uses now, in words: the page's name, "your pasted link", or the app's guess. */
    fun label(a: Activity, course: String, cid: Long, kind: String): String {
        val chosen = Timetable.attFor(a, course, prefer(kind))
        val cands = candidates(a, cid)
        if (chosen.isNotEmpty()) return cands.firstOrNull { it.second == chosen }?.first ?: "your pasted link"
        val auto = Checker.attendanceUrl(a, cid, prefer(kind))
        return (cands.firstOrNull { it.second == auto }?.first ?: if (auto.isEmpty()) "none found" else "Spectrum's page") + " (app's choice)"
    }

    /** Every attendance page the last check saw in the course, in Spectrum's order: (name, address). */
    private fun candidates(a: Activity, cid: Long): List<Pair<String, String>> {
        val site = Prefs(a).site
        val labels = State(a).j.optJSONObject("attlabels") ?: return emptyList()
        return labels.keys().asSequence().filter { it.startsWith("$cid:") }.mapNotNull { k ->
            val id = k.substringAfter(':').toLongOrNull() ?: return@mapNotNull null
            val o = labels.optJSONObject(k)
            val name = o?.optString("name", "").orEmpty().ifEmpty { "Attendance" }
            // saved before "o" existed: keep the saved order (also Spectrum's) until the next check
            Triple(o?.optInt("o", Int.MAX_VALUE) ?: Int.MAX_VALUE, name, "$site/mod/attendance/view.php?id=$id")
        }.toList().sortedBy { it.first }.map { it.second to it.third }
    }

    /** The list for one [kind]: every attendance page, "Let the app choose", "Paste a link". Ticks what is used now. */
    fun open(a: Activity, course: String, cid: Long, kind: String, onDone: () -> Unit) {
        val cands = candidates(a, cid)
        val chosen = Timetable.attFor(a, course, prefer(kind))
        val auto = Checker.attendanceUrl(a, cid, prefer(kind))
        val autoName = cands.firstOrNull { it.second == auto }?.first ?: if (auto.isEmpty()) "none found" else "Spectrum's page"
        val items = listOf("Let the app choose ($autoName)") + cands.map { it.first } + "Paste a link…"
        val sel = if (chosen.isEmpty()) 0 else cands.indexOfFirst { it.second == chosen }.let { if (it >= 0) it + 1 else items.size - 1 }
        val both = kind.isNotEmpty()
        val title = "Attendance for $course" + if (both) ", ${kindName(kind).lowercase()}" else ""
        AlertDialog.Builder(a, Ui.dialogTheme)
            .setTitle(title)
            .apply {
                if (cands.isNotEmpty()) setCustomTitle(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(a.dp(24), a.dp(20), a.dp(24), a.dp(4))
                    addView(a.tv(title, Type.TITLE, Ui.TEXT))
                    addView(a.tv("Listed in the same order as on the Spectrum course page. Not sure which is yours? " +
                        "Check the course page on Spectrum.", Type.CAPTION, Ui.DIM).apply { setPadding(0, a.dp(6), 0, 0) })
                })
            }
            .setSingleChoiceItems(items.toTypedArray(), sel) { d, i ->
                d.dismiss()
                when (i) {
                    0 -> { Timetable.setAttFor(a, course, kind, ""); done(a, "The app chooses the attendance page again.", onDone) }
                    items.size - 1 -> paste(a, course, kind, if (sel == items.size - 1) chosen else "", onDone)
                    else -> { Timetable.setAttFor(a, course, kind, cands[i - 1].second); done(a, "Attendance: ${cands[i - 1].first}", onDone) }
                }
            }
            .apply {
                // one page for every class of the course: sets the course-wide link and drops the per-kind ones
                if (both && cands.isNotEmpty()) setNeutralButton("Same for all classes") { _, _ ->
                    val pick = Timetable.attFor(a, course, prefer(kind)).ifEmpty { auto }
                    Timetable.setAttFor(a, course, "lec", ""); Timetable.setAttFor(a, course, "tut", "")
                    Timetable.setAttFor(a, course, "", pick)
                    done(a, "Lectures and tutorials use the same attendance page.", onDone)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun paste(a: Activity, course: String, kind: String, current: String, onDone: () -> Unit) {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(24), a.dp(8), a.dp(24), 0) }
        val f: EditText = col.field(current, "https://…/mod/attendance/view.php?id=…")
        f.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val d = AlertDialog.Builder(a, Ui.dialogTheme)
            .setTitle("Paste the attendance link")
            .setView(col)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = Shortcuts.normalizeUrl(f.text.toString())
            if (url == null) { f.error = "Paste a web address"; return@setOnClickListener }
            Timetable.setAttFor(a, course, kind, url)
            d.dismiss()
            done(a, "Attendance link saved.", onDone)
        }
    }

    private fun done(a: Activity, msg: String, onDone: () -> Unit) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
        onDone()
    }
}
