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

        fun lbl(t: String) { col.label(t, 13f, Ui.TEXT, 12).setTypeface(null, Typeface.BOLD) }
        fun dateRow(title: String, initial: LocalDate?, onPick: (LocalDate) -> Unit) {
            lbl(title)
            val b = Button(activity); b.isAllCaps = false
            b.text = initial?.format(F) ?: "Not set"
            b.setOnClickListener {
                val base = initial ?: LocalDate.now()
                android.app.DatePickerDialog(activity, { _, y, m, d ->
                    val picked = LocalDate.of(y, m + 1, d)
                    onPick(picked)
                    b.text = picked.format(F)
                }, base.year, base.monthValue - 1, base.dayOfMonth).show()
            }
            col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // label as a text field
        val labelF = EditText(activity).apply {
            setText(dt.label); setTextSize(14f); setTextColor(Ui.TEXT); setSingleLine()
            background = roundBg(Ui.BAR, Ui.BORDER, activity, 10); setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        col.label("Semester name", 13f, Ui.TEXT, 0).setTypeface(null, Typeface.BOLD)
        col.addView(labelF, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        dateRow("Teaching starts", teachStart) { teachStart = it }
        dateRow("Teaching ends", teachEnd) { teachEnd = it }
        dateRow("Mid-semester break starts", breakStart) { breakStart = it }
        dateRow("Mid-semester break ends", breakEnd) { breakEnd = it }
        dateRow("Exams start", examStart) { examStart = it }
        dateRow("Exams end", examEnd) { examEnd = it }
        dateRow("Semester ends", semEnd) { semEnd = it }
        col.label(
            "During breaks and after teaching ends, class entries hide from the timetable, " +
                "hero card and widgets — but Spectrum notifications keep coming.",
            12f, Ui.DIM, 12,
        )

        val scroll = android.widget.ScrollView(activity).apply { addView(col) }
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.7).toInt().coerceAtLeast(dp(240)),
        )
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_DayNight)
            .setTitle("Semester dates")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val l = labelF.text.toString().trim().ifEmpty { "Semester" }
                // sanity: end after start where both set
                if (teachEnd.isBefore(teachStart)) {
                    Toast.makeText(activity, "Teaching end must be after teaching start.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                AcademicCalendar.save(activity, AcademicCalendar.Dates(
                    l, teachStart, teachEnd, breakStart, breakEnd, examStart, examEnd, semEnd,
                ))
                Toast.makeText(activity, "Semester dates saved.", Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
