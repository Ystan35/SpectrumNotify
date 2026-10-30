package my.spectrum.notify

import android.app.Activity
import android.widget.EditText
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** "Semester dates" editor: the single active academic-calendar date set. */
object AcademicCalendarEditor {

    fun open(activity: Activity, onChanged: () -> Unit) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        val dt = AcademicCalendar.get(activity)
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(10), dp(24), 0) }

        var teachStart = dt.teachStart; var teachEnd = dt.teachEnd
        var breakStart = dt.breakStart; var breakEnd = dt.breakEnd
        var examStart = dt.examStart; var examEnd = dt.examEnd
        var semEnd = dt.semEnd
        val F = DateTimeFormatter.ofPattern("d MMM yyyy")

        fun lbl(t: String) { col.label(t, Type.LABEL, Ui.TEXT, 12).setTypeface(null, Typeface.BOLD) }
        /** [optional] dates (break, exams) get a Clear button, so a set date can be removed again. */
        fun dateRow(title: String, initial: LocalDate?, optional: Boolean = false, onPick: (LocalDate?) -> Unit) {
            lbl(title)
            var current = initial
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val b = Button(activity); b.isAllCaps = false
            b.text = initial?.format(F) ?: "Not set"
            b.setOnClickListener {
                val base = current ?: LocalDate.now(zone())
                android.app.DatePickerDialog(activity, { _, y, m, d ->
                    val picked = LocalDate.of(y, m + 1, d)
                    current = picked
                    onPick(picked)
                    b.text = picked.format(F)
                }, base.year, base.monthValue - 1, base.dayOfMonth).show()
            }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (optional) row.addView(activity.button("Clear", Emphasis.TERTIARY) {
                current = null
                onPick(null)
                b.text = "Not set"
            }.apply { contentDescription = "Clear $title" })
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // label as a text field
        val labelF = EditText(activity).apply {
            setText(dt.label); textSize = Type.BODY * Ui.fontScale; setTextColor(Ui.TEXT); setSingleLine()
            background = roundBg(Ui.BAR, Ui.BORDER, activity, Radius.SM); setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        col.label("Semester name", Type.LABEL, Ui.TEXT, 0).setTypeface(null, Typeface.BOLD)
        col.addView(labelF, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        // in the order they happen in the semester
        dateRow("Teaching starts", teachStart) { teachStart = it ?: teachStart }
        dateRow("Mid-semester break starts", breakStart, optional = true) { breakStart = it }
        dateRow("Mid-semester break ends", breakEnd, optional = true) { breakEnd = it }
        dateRow("Teaching ends", teachEnd) { teachEnd = it ?: teachEnd }
        dateRow("Exams start", examStart, optional = true) { examStart = it }
        dateRow("Exams end", examEnd, optional = true) { examEnd = it }
        dateRow("Semester ends", semEnd) { semEnd = it ?: semEnd }
        col.label(
            "During breaks and after teaching ends, class entries hide from the timetable, " +
                "hero card and widgets — but Spectrum notifications keep coming.",
            Type.CAPTION, Ui.DIM, 12,
        )

        val scroll = android.widget.ScrollView(activity).apply { addView(col) }
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.7).toInt().coerceAtLeast(dp(240)),
        )
        /** What is wrong with the dates, in plain words, or null when they are in order. */
        fun problem(): String? {
            val bs = breakStart; val be = breakEnd; val xs = examStart; val xe = examEnd
            return when {
                teachEnd.isBefore(teachStart) -> "Teaching ends before it starts."
                (bs == null) != (be == null) -> "Set both mid-semester break dates, or clear both."
                bs != null && be != null && be.isBefore(bs) -> "The mid-semester break ends before it starts."
                bs != null && be != null && (bs.isBefore(teachStart) || be.isAfter(teachEnd)) -> "The mid-semester break must be between teaching start and teaching end."
                (xs == null) != (xe == null) -> "Set both exam dates, or clear both."
                xs != null && xe != null && xe.isBefore(xs) -> "Exams end before they start."
                xs != null && xs.isBefore(teachEnd) -> "Exams start before teaching ends."
                semEnd.isBefore(xe ?: teachEnd) -> "The semester ends before " + (if (xe != null) "exams end." else "teaching ends.")
                else -> null
            }
        }
        // the reason a save was refused, shown in the dialog so nothing you entered is lost
        val error = col.label("", Type.LABEL, Ui.RED, 12).apply { visibility = android.view.View.GONE }

        val dialog = AlertDialog.Builder(activity, Ui.dialogTheme)
            .setTitle("Semester dates")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .show()
        // set here, not in the builder, so a refused save keeps the dialog open
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val bad = problem()
            if (bad != null) {
                error.text = bad
                error.visibility = android.view.View.VISIBLE
                scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                return@setOnClickListener
            }
            val l = labelF.text.toString().trim().ifEmpty { "Semester" }
            AcademicCalendar.save(activity, AcademicCalendar.Dates(
                l, teachStart, teachEnd, breakStart, breakEnd, examStart, examEnd, semEnd,
            ))
            Toast.makeText(activity, "Semester dates saved.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onChanged()
        }
    }
}
