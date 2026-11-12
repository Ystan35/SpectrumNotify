package my.spectrum.notify

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import java.util.Locale

/**
 * Settings → Online or in person: one row per course with an As timetable / Online / In person dropdown, and buttons
 * to switch every course at once (with Undo).
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
            val modes = Timetable.COURSE_MODES
            row.addView(activity.spinner(modes.map { it.second }, modes.indexOfFirst { it.first == Timetable.courseMode(activity, code) }.coerceAtLeast(0)) { pos ->
                if (modes[pos].first != Timetable.courseMode(activity, code)) {
                    Timetable.setCourseMode(activity, code, modes[pos].first)
                    Timetable.changed(activity)
                }
            }.apply { contentDescription = "$code: as timetable, online or in person" }, LinearLayout.LayoutParams(activity.dp(176), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = activity.dp(8) })
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) })
        }

        // every course at once (after the haze), with Undo
        // during the haze: All online; after it: All as timetable (each class back to its room)
        fun switchAll(mode: String) {
            val all = courses.map { it.code.uppercase(Locale.ROOT) }
            val before = all.associateWith { Timetable.courseMode(activity, it) }
            all.forEach { Timetable.setCourseMode(activity, it, mode) }
            Timetable.changed(activity)
            draw(activity, box)
            activity.undoBar(if (mode == "online") "All courses online" else "All courses as the timetable says") {
                before.forEach { (c, m) -> Timetable.setCourseMode(activity, c, m) }
                Timetable.changed(activity)
                draw(activity, box)
            }
        }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        row.addView(activity.button("All as timetable", Emphasis.SECONDARY) { switchAll("") },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(activity.button("All online", Emphasis.SECONDARY) { switchAll("online") },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(8) })
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(14) })
        box.label(Timetable.COURSE_MODES_HELP + " If an in-person course posts a new meeting link, you are asked whether it is online again.",
            Type.CAPTION, Ui.DIM, 8)
    }
}
