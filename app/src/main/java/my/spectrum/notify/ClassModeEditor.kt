package my.spectrum.notify

import android.app.Activity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings → Online or in person: your courses with a tick box each (all ticked), whether each is online or in
 * person now, and buttons to switch the ticked ones. Every switch offers Undo.
 */
object ClassModeEditor {

    fun render(activity: Activity, parent: LinearLayout) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        draw(activity, box, null)
    }

    private fun draw(activity: Activity, box: LinearLayout, keepTicked: Set<String>?) {
        box.removeAllViews()
        val courses = Courses.all(activity).filter { it.code.isNotEmpty() }.distinctBy { it.code.uppercase(Locale.ROOT) }
        if (courses.isEmpty()) {
            box.label("Your courses appear here after the first check.", 13f, Ui.DIM, 8)
            return
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        val ticks = LinkedHashMap<String, CheckBox>()
        for (c in courses) {
            val code = c.code.uppercase(Locale.ROOT)
            val inP = Timetable.inPerson(activity, code)
            val since = Timetable.inPersonSince(activity, code)
            val state = if (inP) "📍 In person" + (if (since > 0) " since " + Instant.ofEpochSecond(since).atZone(zone()).format(fmt) else "")
                else "💻 Online links used"
            val cb = CheckBox(activity).apply {
                text = Shorts.display(activity, code) + DayTimeline.courseName(code, c.name).let { if (it.isNotEmpty()) " · $it" else "" } + "\n" + state
                textSize = 14f * Ui.fontScale
                setTextColor(Ui.TEXT)
                minHeight = activity.dp(Ui.touch(48))
                isChecked = keepTicked?.contains(code) ?: true
            }
            ticks[code] = cb
            box.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(4) })
        }

        fun ticked() = ticks.filter { it.value.isChecked }.keys
        fun switch(on: Boolean) {
            val chosen = ticked()
            if (chosen.isEmpty()) { android.widget.Toast.makeText(activity, "Tick at least one course.", android.widget.Toast.LENGTH_SHORT).show(); return }
            // what each was before, for Undo
            val before = chosen.associateWith { Timetable.inPerson(activity, it) }
            Timetable.setInPerson(activity, chosen, on)
            Scheduler.scheduleAll(activity)
            draw(activity, box, chosen)
            val n = chosen.size
            activity.undoBar(if (on) "$n course${if (n > 1) "s" else ""} in person" else "$n course${if (n > 1) "s" else ""} online again") {
                Timetable.setInPerson(activity, before.filterValues { it }.keys, true)
                Timetable.setInPerson(activity, before.filterValues { !it }.keys, false)
                Scheduler.scheduleAll(activity)
                draw(activity, box, chosen)
            }
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(activity.chip("Tick all", false, size = 13f, padH = 12) { ticks.values.forEach { it.isChecked = true } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(activity.chip("Untick all", false, size = 13f, padH = 12) { ticks.values.forEach { it.isChecked = false } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(6) })
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) })
        box.action("Mark ticked as in person", "No Join class or meeting links for them; the room is shown. " +
            "If one of them posts a new meeting link, you are asked whether it is online again.") { switch(true) }
        box.action("Mark ticked as online", "Join class and the detected meeting links come back.") { switch(false) }
    }
}
