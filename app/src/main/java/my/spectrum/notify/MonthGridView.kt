package my.spectrum.notify

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Month-grid calendar (replaces the flat Events list, per user decision):
 * month header with ‹ ›, 7-column day cells with markers, tap a day → day sheet.
 * Markers: theme dot = user event, red dot = academic date (exam/break).
 */
object MonthGridView {

    /** Occurrences of user events per date within [month]. */
    private fun eventsOn(activity: Activity, month: YearMonth): Map<LocalDate, List<Events.Event>> {
        val out = HashMap<LocalDate, MutableList<Events.Event>>()
        val start = month.atDay(1)
        for (e in Events.all(activity)) {
            for ((d, _) in e.upcoming(start, month.lengthOfMonth().toLong())) {
                if (d.monthValue != month.monthValue || d.year != month.year) continue
                out.getOrPut(d) { ArrayList() }.add(e)
            }
        }
        return out
    }

    /** Renders the whole month view into [box]. [onChanged] after edits. */
    fun render(activity: Activity, box: LinearLayout, onChanged: () -> Unit) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        var month: YearMonth = YearMonth.now()
        var selected: LocalDate = LocalDate.now()

        val holder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        lateinit var redraw: () -> Unit
        val dayDetails = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        redraw = {
            holder.removeAllViews()

            // ---- header: ‹ month ›
            val head = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4))
            }
            fun arrowBtn(arrow: String, desc: String, onClick: () -> Unit): TextView =
                TextView(activity).apply {
                    text = arrow
                    textSize = 20f * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                    minHeight = dp(Ui.touch(48)); minWidth = dp(Ui.touch(48))
                    gravity = Gravity.CENTER
                    contentDescription = desc
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setStroke(dp(1), Ui.BORDER)
                    }
                    setOnClickListener { onClick() }
                }
            head.addView(arrowBtn("‹", "Previous month") { month = month.minusMonths(1); redraw() })
            head.addView(TextView(activity).apply {
                text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                textSize = 16f * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(arrowBtn("›", "Next month") { month = month.plusMonths(1); redraw() })
            holder.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // ---- grid
            val gridHolder = FrameLayout(activity)
            val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val eventsOn = eventsOn(activity, month)
            val first = month.atDay(1)
            val lead = (first.dayOfWeek.value + 6) % 7 // Monday-first
            val days = month.lengthOfMonth()

            // weekday header row
            val wk = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val wd = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            for (i in 0 until 7) {
                wk.addView(TextView(activity).apply {
                    text = wd[i]; textSize = 11f * Ui.fontScale; setTextColor(Ui.DIM); gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(wk, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            var cell = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            repeat(lead) { cell.addView(View(activity), LinearLayout.LayoutParams(0, dp(44), 1f)) }
            var filled = lead
            for (dayNum in 1..days) {
                if (filled == 7) {
                    grid.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    cell = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    filled = 0
                }
                val date = month.atDay(dayNum)
                val isSel = date == selected
                val isToday = date == LocalDate.now()
                val academic = AcademicCalendar.describe(activity, date)
                val hasEvents = eventsOn[date]?.isNotEmpty() == true

                val cellBox = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(if (isSel) Ui.tint(Ui.CYAN, 0x30) else 0x00000000)
                        setStroke(dp(1), if (isToday) Ui.CYAN else 0x00000000)
                    }
                    isClickable = true
                    contentDescription = "Day $dayNum ${month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}" +
                        (academic?.let { ", $it" } ?: "") + (if (hasEvents) ", has events" else "")
                    setOnClickListener { selected = date; redraw() }
                }
                cellBox.addView(TextView(activity).apply {
                    text = dayNum.toString()
                    textSize = 13f * Ui.fontScale
                    setTextColor(if (isSel) Ui.TEXT else if (date.isBefore(LocalDate.now())) Ui.DIM else Ui.TEXT)
                    setTypeface(typeface, if (isToday || isSel) Typeface.BOLD else Typeface.NORMAL)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                // marker row: up to 3 dots
                val dots = LinearLayout(activity).apply { gravity = Gravity.CENTER }
                if (academic != null) dots.addView(dot(activity, Ui.RED))
                if (hasEvents) dots.addView(dot(activity, Ui.CYAN))
                if (dots.childCount > 0) cellBox.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
                else cellBox.addView(View(activity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
                cell.addView(cellBox, LinearLayout.LayoutParams(0, dp(44), 1f))
                filled++
            }
            while (filled in 1..6) { cell.addView(View(activity), LinearLayout.LayoutParams(0, dp(44), 1f)); filled++ }
            grid.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            gridHolder.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            holder.addView(gridHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // ---- selected-day details
            dayDetails.removeAllViews()
            renderDay(activity, dayDetails, selected, eventsOn[selected] ?: emptyList(), onChanged) { redraw() }
            holder.addView(dayDetails, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        redraw()
    }

    private fun dot(activity: Activity, color: Int): View =
        View(activity).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color)
            }
        }

    /** One day's contents as labeled rows: academic marker, classes (teaching weeks), events. */
    private fun renderDay(
        activity: Activity,
        box: LinearLayout,
        date: LocalDate,
        events: List<Events.Event>,
        onChanged: () -> Unit,
        redraw: () -> Unit,
    ) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        box.label(EventsUi.fmtDate(date) + " · " + date.format(DateTimeFormatter.ofPattern("MMMM yyyy")), 15f, Ui.TEXT, 18)
            .setTypeface(null, Typeface.BOLD)

        AcademicCalendar.describe(activity, date)?.let {
            box.label(it, 13f, Ui.ORANGE, 6).setTypeface(null, Typeface.BOLD)
        }

        // classes: only inside teaching weeks (holiday mode)
        val inTeaching = AcademicCalendar.inTeaching(activity, date)
        if (inTeaching) {
            val dow = date.dayOfWeek.value
            val slots = Timetable.all(activity).filter { it.day == dow }
            if (slots.isNotEmpty()) {
                box.label("Classes", 13f, Ui.TEXT, 10).setTypeface(null, Typeface.BOLD)
                for (s in slots.sortedBy { it.start }) {
                    box.label("${Timetable.fmtMin(s.start)}–${Timetable.fmtMin(s.end)} · ${Shorts.display(activity, s.course)}${if (s.title.isNotEmpty()) " (${s.title})" else ""}", 13f, Ui.DIM, 4)
                }
            }
        }

        if (events.isNotEmpty()) {
            box.label("Events", 13f, Ui.TEXT, 10).setTypeface(null, Typeface.BOLD)
            for (e in events.sortedBy { it.startTime }) {
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(12))
                    background = roundBg(Ui.BAR, Ui.BORDER, activity, 12)
                    isClickable = true
                    setOnClickListener { EventsUi.editor(activity, e, { onChanged(); redraw() }) }
                }
                card.addView(TextView(activity).apply {
                    text = "${fmtMin(e.startTime)}–${fmtMin(e.endTime)} · ${e.title}"
                    textSize = 13f; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                })
                if (e.note.isNotEmpty()) card.addView(TextView(activity).apply {
                    text = e.note; textSize = 12f; setTextColor(Ui.DIM); setPadding(0, dp(2), 0, 0); maxLines = 2
                })
                box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
        }

        if (!inTeaching && events.isEmpty()) {
            box.label("No classes (holiday mode) and no events on this day.", 13f, Ui.DIM, 6)
        }
        box.btn("Add an event on ${EventsUi.fmtDate(date)}") {
            EventsUi.editor(activity, null, onChanged, prefillDate = date)
        }
    }

    private fun fmtMin(min: Int): String = String.format("%02d:%02d", min / 60, min % 60)
}
