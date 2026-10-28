package my.spectrum.notify

import android.app.Activity
import android.app.AlertDialog
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
import java.util.Locale

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
    /** Day sheet: what's on this date — academic marker, classes, events, add-event row. */
    private fun openDaySheet(
        activity: Activity,
        date: LocalDate,
        events: List<Events.Event>,
        onChanged: () -> Unit,
        redraw: () -> Unit,
    ) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (Ui.dark) 0xFF20242E.toInt() else 0xFFFFFFFF.toInt())
                setStroke(dp(1), if (Ui.dark) 0xFF3A4150.toInt() else 0xFFD8D4CA.toInt())
            }
        }
        sheet.addView(TextView(activity).apply {
            text = EventsUi.fmtDate(date)
            textSize = 20f * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
        })

        val academic = AcademicCalendar.describe(activity, date)
        if (academic != null) sheet.addView(TextView(activity).apply {
            text = academic
            textSize = 13f; setTextColor(Ui.ORANGE); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        })

        val inTeaching = AcademicCalendar.inTeaching(activity, date)
        val dow = date.dayOfWeek.value
        val classList = if (inTeaching) Timetable.on(Timetable.all(activity), date) else emptyList()

        fun sectionLabel(t: String) {
            sheet.addView(TextView(activity).apply {
                text = t; textSize = 13f; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(2))
            })
        }
        fun itemRow(title: String, sub: String, onClick: (() -> Unit)? = null) {
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(12))
                background = roundBg(Ui.BAR, Ui.BORDER, activity, 12)
                isClickable = onClick != null
                if (onClick != null) setOnClickListener { onChanged(); onClick() }
            }
            r.addView(TextView(activity).apply { text = title; textSize = 14f; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD) })
            r.addView(TextView(activity).apply { text = sub; textSize = 12f; setTextColor(Ui.DIM); setPadding(0, dp(2), 0, 0); maxLines = 3 })
            sheet.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        if (classList.isNotEmpty()) {
            sectionLabel("Classes")
            for (sl in classList) {
                val fullName: String = run {
                    var n = ""
                    val codeUpper = sl.course.uppercase(java.util.Locale.ROOT)
                    for (cc in Courses.all(activity)) if (cc.code.uppercase(java.util.Locale.ROOT) == codeUpper) n = cc.name
                    if (n.isEmpty()) {
                        val cc = Catalog.load(activity)[codeUpper]
                        if (cc != null) n = cc.name
                    }
                    n
                }
                // place: user-set location first, else the slot's own note/room
                val place = Timetable.locationOf(activity, sl.course)
                    .ifEmpty { sl.note }
                    .ifEmpty { "Room not set" }
                itemRow(
                    Shorts.display(activity, sl.course) + (if (sl.title.isNotEmpty()) " · " + sl.title else ""),
                    EventsUi.fmtMin(sl.start) + "–" + EventsUi.fmtMin(sl.end) +
                        (if (fullName.isNotEmpty()) " · " + fullName else "") +
                        " · " + place,
                )
            }
        }
        if (events.isNotEmpty()) {
            sectionLabel("Events")
            for (ev in events.sortedBy { it.startTime }) {
                itemRow(
                    ev.title,
                    EventsUi.fmtMin(ev.startTime) + "–" + EventsUi.fmtMin(ev.endTime) + (if (ev.note.isNotEmpty()) " · " + ev.note else ""),
                ) { EventsUi.editor(activity, ev, onChanged) }
            }
        }
        if (classList.isEmpty() && events.isEmpty()) {
            sheet.addView(TextView(activity).apply {
                text = if (inTeaching) "Nothing on this day." else "No classes (holiday mode) and no events."
                textSize = 13f; setTextColor(Ui.DIM); setPadding(0, dp(10), 0, 0)
            })
        }
        itemRow("Add an event on this day", "Opens the event editor with this date filled in") {
            EventsUi.editor(activity, null, onChanged, prefillDate = date)
        }

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_DayNight)
            .setView(sheet)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.55f)
    }

    fun render(activity: Activity, box: LinearLayout, onChanged: () -> Unit) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        var month: YearMonth = YearMonth.now()
        var selected: LocalDate = LocalDate.now()

        // what each day holds: class days (Mon-Sun during teaching weeks), event days,
        // academic markers. Days with NOTHING stay completely plain — no color at all.
        val slots = Timetable.all(activity)
        // weekdays with a weekly class; one-offs mark only their own date
        val classDays = slots.filter { it.date == null }.map { it.day }.toSet()
        val oneOffDates = slots.mapNotNull { it.date }.toSet()
        val eventsByDate = HashMap<LocalDate, MutableList<Events.Event>>()
        for (e in Events.all(activity)) {
            for ((d, _) in e.upcoming(month.atDay(1).minusMonths(1), 3 * 31 + 10L)) {
                eventsByDate.getOrPut(d) { ArrayList() }.add(e)
            }
        }

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
            // center = date picker: jump straight to any month (faster than ‹ › spamming)
            head.addView(TextView(activity).apply {
                text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                textSize = 16f * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = "Choose month"
                background = rippleBg(Ui.BAR, 0, activity, 12)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    android.app.DatePickerDialog(activity, { _, y, m, d ->
                        month = YearMonth.of(y, m + 1)
                        selected = if (selected.month == month.month && selected.year == month.year) selected else month.atDay(1)
                        redraw()
                    }, month.year, month.monthValue - 1, 1).show()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(arrowBtn("›", "Next month") { month = month.plusMonths(1); redraw() })
            holder.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // ---- grid
            val gridHolder = FrameLayout(activity)
            val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val eventsOn = eventsByDate.filterKeys { it.monthValue == month.monthValue && it.year == month.year }
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
                val hasEvents = eventsOn[date]?.isNotEmpty() == true
                // class days only count when the day is inside teaching weeks (holiday mode)
                val hasClasses = (date.dayOfWeek.value in classDays || date in oneOffDates) && AcademicCalendar.inTeaching(activity, date)
                val hasAnything = hasEvents || hasClasses

                val cellBox = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    // ONLY tint cells that hold something; empty/break days stay plain
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        when {
                            isSel -> setColor(Ui.tint(Ui.CYAN, 0x30))
                            hasAnything -> setColor(Ui.tint(Ui.CYAN, 0x14))
                            else -> setColor(0x00000000)
                        }
                        setStroke(dp(1), if (isToday) Ui.CYAN else 0x00000000)
                    }
                    isClickable = true
                    contentDescription = "Day $dayNum ${month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}" +
                        (if (hasClasses) ", has classes" else "") +
                        (if (hasEvents) ", has events" else "")
                    setOnClickListener {
                        selected = date
                        openDaySheet(activity, date, eventsOn[date] ?: emptyList(), onChanged, redraw)
                    }
                }
                cellBox.addView(TextView(activity).apply {
                    text = dayNum.toString()
                    textSize = 13f * Ui.fontScale
                    setTextColor(if (isSel) Ui.TEXT else if (date.isBefore(LocalDate.now())) Ui.DIM else Ui.TEXT)
                    setTypeface(typeface, if (isToday || isSel) Typeface.BOLD else Typeface.NORMAL)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                // marker row: orange dot = classes that day, cyan dot = events; plain days have none
                val dots = LinearLayout(activity).apply { gravity = Gravity.CENTER }
                if (hasClasses) dots.addView(dot(activity, Ui.ORANGE))
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
            val slots = Timetable.on(Timetable.all(activity), date)
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
