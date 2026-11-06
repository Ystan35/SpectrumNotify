package my.spectrum.notify

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import java.util.Locale

/**
 * Settings → Online or in person: one row per course with an Online / In person dropdown, and buttons to switch
 * every course at once (with Undo).
 */
object ClassModeEditor {

    fun render(activity: Activity, parent: LinearLayout) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        draw(activity, box)
    }

    private fun draw(activity: Activity, box: LinearLayout) {
        box.removeAllViews()
        val courses = Courses.all(activity).filter { it.code.isNotEmpty() }.distinctBy { it.code.uppercase(Locale.ROOT) }
        if (courses.isEmpty()) {
            box.label("Your courses appear here after the first check.", Type.LABEL, Ui.DIM, 8)
            return
        }
        // one row per course: short name on the left, a small dropdown on the right; it saves at once (ticket SN-TG4DM)
        for (c in courses) {
            val code = c.code.uppercase(Locale.ROOT)
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(activity.tv(Shorts.display(activity, code), Type.BODY, Ui.TEXT, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(activity.spinner(listOf("Online", "In person"), if (Timetable.inPerson(activity, code)) 1 else 0) { pos ->
                if ((pos == 1) != Timetable.inPerson(activity, code)) {
                    Timetable.setInPerson(activity, listOf(code), pos == 1)
                    Scheduler.scheduleAll(activity)
                }
            }.apply { contentDescription = "$code: online or in person" }, LinearLayout.LayoutParams(activity.dp(168), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(8) })
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) })
        }

        // every course at once (after the haze), with Undo
        fun switchAll(on: Boolean) {
            val all = courses.map { it.code.uppercase(Locale.ROOT) }
            val before = all.associateWith { Timetable.inPerson(activity, it) }
            Timetable.setInPerson(activity, all, on)
            Scheduler.scheduleAll(activity)
            draw(activity, box)
            activity.undoBar(if (on) "All courses in person" else "All courses online again") {
                Timetable.setInPerson(activity, before.filterValues { it }.keys, true)
                Timetable.setInPerson(activity, before.filterValues { !it }.keys, false)
                Scheduler.scheduleAll(activity)
                draw(activity, box)
            }
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        row.addView(activity.button("All in person", Emphasis.SECONDARY) { switchAll(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(activity.button("All online", Emphasis.SECONDARY) { switchAll(false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(8) })
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(14) })
        box.label("In person: no Join class or meeting links; the room is shown. If a course posts a new meeting link, " +
            "you are asked whether it is online again.", Type.CAPTION, Ui.DIM, 8)
    }
}
