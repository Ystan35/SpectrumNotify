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

    /** Your events that fall on [date] (one-off or weekly). */
    private fun eventsFor(activity: Activity, date: LocalDate): List<Events.Event> =
        Events.all(activity).filter { e -> e.upcoming(date, 0).any { it.first == date } }

    /** Occurrences of your events per date within [month]. */
    private fun eventsIn(activity: Activity, month: YearMonth): Map<LocalDate, List<Events.Event>> {
        val out = HashMap<LocalDate, MutableList<Events.Event>>()
        val start = month.atDay(1)
        for (e in Events.all(activity)) {
            for ((d, _) in e.upcoming(start, month.lengthOfMonth() - 1L)) {
                if (YearMonth.from(d) != month) continue
                out.getOrPut(d) { ArrayList() }.add(e)
            }
        }
        return out
    }

    /** "Today", "Tomorrow", "In 5 days", "3 days ago" — where a date sits from today. */
    private fun relDay(date: LocalDate): String {
        val n = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zone()), date)
        return when {
            n == 0L -> "Today"
            n == 1L -> "Tomorrow"
            n == -1L -> "Yesterday"
            n > 1 -> "In $n days"
            else -> "${-n} days ago"
        }
    }

    /**
     * Day sheet: what's on a date — academic marker, classes, events, add-event row.
     * ‹ › step one day, tapping the date opens a date picker; [onDate] keeps the month grid in step.
     */
    private fun openDaySheet(
        activity: Activity,
        start: LocalDate,
        onChanged: () -> Unit,
        onDate: (LocalDate) -> Unit,
        onClass: ((Slot) -> Unit)?,
    ) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        var date = start
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(Radius.LG).toFloat()
                setColor(Ui.RAISED)
                setStroke(dp(1), Ui.RAISED_BORDER)
            }
        }
        lateinit var dialog: AlertDialog
        lateinit var go: (LocalDate, Int) -> Unit

        // ---- header: ‹  date (tap = picker)  ›
        fun arrowBtn(arrow: String, desc: String, onClick: () -> Unit): TextView =
            TextView(activity).apply {
                setCompoundDrawablesRelativeWithIntrinsicBounds(if (arrow == "‹") R.drawable.ic_chevron_left else R.drawable.ic_chevron_right, 0, 0, 0)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                contentDescription = desc
                background = rippleBg(Ui.BAR, Ui.BORDER, activity, Radius.PILL)
                setOnClickListener { onClick() }
            }
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val titleBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            background = rippleBg(Ui.RAISED, 0, activity, Radius.MD)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val title = TextView(activity).apply {
            textSize = Type.TITLE * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val subTitle = TextView(activity).apply {
            textSize = Type.CAPTION * Ui.fontScale; setTextColor(Ui.CYAN); gravity = Gravity.CENTER
        }
        titleBox.addView(title)
        titleBox.addView(subTitle)
        titleBox.setOnClickListener {
            android.app.DatePickerDialog(activity, { _, y, m, d ->
                val picked = LocalDate.of(y, m + 1, d)
                go(picked, picked.compareTo(date).coerceIn(-1, 1))
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        head.addView(arrowBtn("‹", "Previous day") { go(date.minusDays(1), -1) }, LinearLayout.LayoutParams(dp(Ui.touch(48)), dp(Ui.touch(48))))
        head.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        head.addView(arrowBtn("›", "Next day") { go(date.plusDays(1), 1) }, LinearLayout.LayoutParams(dp(Ui.touch(48)), dp(Ui.touch(48))))
        sheet.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val todayChip = TextView(activity).apply {
            text = "Back to today"
            textSize = Type.CAPTION * Ui.fontScale; setTextColor(Ui.CYAN); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(Ui.touch(40))
            setPadding(dp(14), 0, dp(14), 0)
            background = rippleBg(Ui.BAR, Ui.CYAN, activity, Radius.LG)
            setOnClickListener { val t = LocalDate.now(zone()); go(t, t.compareTo(date).coerceIn(-1, 1)) }
        }
        sheet.addView(todayChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4)
        })

        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(android.widget.ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun sectionLabel(t: String) {
            body.addView(TextView(activity).apply {
                text = t; textSize = Type.LABEL * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), dp(12), 0, dp(2))
            })
        }
        fun itemRow(rowTitle: String, sub: String, stripe: Int = 0, onClick: (() -> Unit)? = null) {
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(12))
                background = if (onClick != null) rippleBg(Ui.BAR, if (stripe != 0) stripe else Ui.BORDER, activity, Radius.MD)
                    else roundBg(Ui.BAR, if (stripe != 0) stripe else Ui.BORDER, activity, Radius.MD)
                if (onClick != null) {
                    isClickable = true
                    setOnClickListener { dialog.dismiss(); onClick() }
                }
            }
            r.addView(TextView(activity).apply { text = rowTitle; textSize = Type.BODY * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD) })
            r.addView(TextView(activity).apply { text = sub; textSize = Type.CAPTION * Ui.fontScale; setTextColor(Ui.DIM); setPadding(0, dp(2), 0, 0); maxLines = 3 })
            body.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        fun fill() {
            title.text = date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH))
            subTitle.text = relDay(date) + "  ·  tap to pick a date"
            titleBox.contentDescription = "${date.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH))}, ${relDay(date)}. Tap to pick another date"
            todayChip.visibility = if (date == LocalDate.now(zone())) View.GONE else View.VISIBLE
            body.removeAllViews()

            AcademicCalendar.describe(activity, date)?.let { academic ->
                body.addView(TextView(activity).apply {
                    text = academic
                    textSize = Type.LABEL * Ui.fontScale; setTextColor(Ui.ORANGE); setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(4), dp(8), 0, 0)
                })
            }
            val inTeaching = AcademicCalendar.inTeaching(activity, date)
            val classList = if (inTeaching) Timetable.on(Timetable.all(activity, AcademicCalendar.labelFor(activity, date)), date) else emptyList()
            val events = eventsFor(activity, date)

            if (classList.isNotEmpty() || events.isNotEmpty()) {
                body.addView(DayTimeline.build(
                    activity, date, classList, events,
                    onEvent = { ev -> dialog.dismiss(); EventsUi.editor(activity, ev, onChanged) },
                    onClass = onClass?.let { cb -> { sl: Slot -> dialog.dismiss(); cb(sl) } },
                ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            }
            if (classList.isEmpty() && events.isEmpty()) {
                body.addView(TextView(activity).apply {
                    text = if (inTeaching) "Nothing on this day." else "No classes (holiday mode) and no events."
                    textSize = Type.LABEL * Ui.fontScale; setTextColor(Ui.DIM); setPadding(dp(4), dp(12), 0, 0)
                })
            }
            sectionLabel("")
            itemRow("+ Add an event on this day", "Opens the event editor with this date filled in") {
                EventsUi.editor(activity, null, onChanged, prefillDate = date)
            }
        }

        // dir: +1 = later date (content comes from the right), -1 = earlier, 0 = same
        go = { d, dir ->
            date = d
            onDate(d)
            body.animate().cancel()
            fill()
            if (dir != 0) {
                body.translationX = dir * dp(40).toFloat()
                body.alpha = 0f
                body.animate().translationX(0f).alpha(1f).setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
        fill()

        dialog = AlertDialog.Builder(activity, Ui.dialogTheme)
            .setView(sheet)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.55f)
    }

    // the month and day you were looking at survive re-renders (e.g. after adding an event),
    // but a calendar opened much later starts again at today
    private var keptMonth: YearMonth? = null
    private var keptSel: LocalDate? = null
    private var keptAt = 0L

    /** Renders the whole month view into [box]. [onChanged] after edits; [onClass] when a class in the day sheet is tapped. */
    fun render(activity: Activity, box: LinearLayout, onClass: ((Slot) -> Unit)? = null, onChanged: () -> Unit) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        val fresh = System.currentTimeMillis() - keptAt < 30 * 60 * 1000L
        var month: YearMonth = (if (fresh) keptMonth else null) ?: YearMonth.now(zone())
        var selected: LocalDate = (if (fresh) keptSel else null) ?: LocalDate.now(zone())
        fun keep() { keptMonth = month; keptSel = selected; keptAt = System.currentTimeMillis() }

        // what each day holds: class days (Mon-Sun during teaching weeks), event days,
        // academic markers. Days with NOTHING stay completely plain — no color at all.
        // each date shows the classes of ITS semester (different subjects each semester); one list per semester
        val bySem = HashMap<String?, List<Slot>>()
        fun classesOn(date: LocalDate): List<Slot> {
            val sem = AcademicCalendar.labelFor(activity, date)
            return Timetable.on(bySem.getOrPut(sem) { Timetable.all(activity, sem) }, date)
        }

        val holder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        lateinit var redraw: () -> Unit
        val dayDetails = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        /** Another month: keep the selected day inside it (today when it is this month). */
        fun showMonth(m: YearMonth) {
            month = m
            if (YearMonth.from(selected) != m) selected = if (YearMonth.from(LocalDate.now(zone())) == m) LocalDate.now(zone()) else m.atDay(1)
            redraw()
        }

        redraw = {
            keep()
            holder.removeAllViews()

            // ---- header: ‹ month ›
            val head = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4))
            }
            fun arrowBtn(arrow: String, desc: String, onClick: () -> Unit): TextView =
                TextView(activity).apply {
                    // "‹" / "›" name the direction; the chevron icon is drawn instead of the character
                    setCompoundDrawablesRelativeWithIntrinsicBounds(if (arrow == "‹") R.drawable.ic_chevron_left else R.drawable.ic_chevron_right, 0, 0, 0)
                    compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.TEXT)
                    minHeight = dp(Ui.touch(48)); minWidth = dp(Ui.touch(48))
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(12), 0)
                    contentDescription = desc
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setStroke(dp(1), Ui.BORDER)
                    }
                    setOnClickListener { onClick() }
                }
            head.addView(arrowBtn("‹", "Previous month") { showMonth(month.minusMonths(1)) })
            // center = date picker: jump straight to any month (faster than ‹ › spamming)
            head.addView(TextView(activity).apply {
                text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                textSize = Type.BODY_L * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = "Choose month"
                background = rippleBg(Ui.BAR, 0, activity, Radius.MD)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    android.app.DatePickerDialog(activity, { _, y, m, d ->
                        selected = LocalDate.of(y, m + 1, d)
                        showMonth(YearMonth.of(y, m + 1))
                    }, selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(arrowBtn("›", "Next month") { showMonth(month.plusMonths(1)) })
            holder.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // ---- grid
            val gridHolder = FrameLayout(activity)
            val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val eventsOn = eventsIn(activity, month)
            val first = month.atDay(1)
            val lead = (first.dayOfWeek.value + 6) % 7 // Monday-first
            val days = month.lengthOfMonth()

            // weekday header row
            val wk = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val wd = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
            for (i in 0 until 7) {
                wk.addView(TextView(activity).apply {
                    text = wd[i]; textSize = Type.CAPTION * Ui.fontScale; setTextColor(Ui.DIM); gravity = Gravity.CENTER
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
                val isToday = date == LocalDate.now(zone())
                val hasEvents = eventsOn[date]?.isNotEmpty() == true
                // class days only count when the day is inside teaching weeks (holiday mode)
                val hasClasses = AcademicCalendar.inTeaching(activity, date) && classesOn(date).isNotEmpty()
                val hasAnything = hasEvents || hasClasses

                val cellBox = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    // ONLY tint cells that hold something; empty/break days stay plain
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(Radius.SM).toFloat()
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
                        redraw()
                        // stepping days in the sheet moves the grid's selection (and month) along
                        openDaySheet(activity, date, onChanged, { d ->
                            selected = d
                            if (YearMonth.from(d) != month) month = YearMonth.from(d)
                            redraw()
                        }, onClass)
                    }
                }
                cellBox.addView(TextView(activity).apply {
                    text = dayNum.toString()
                    textSize = Type.LABEL * Ui.fontScale
                    setTextColor(if (isSel) Ui.TEXT else if (date.isBefore(LocalDate.now(zone()))) Ui.DIM else Ui.TEXT)
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
            renderDay(activity, dayDetails, selected, eventsOn[selected] ?: emptyList(), onChanged, onClass) { redraw() }
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
        onClass: ((Slot) -> Unit)?,
        redraw: () -> Unit,
    ) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        // "Thu 7 Jan" already names the month; the year only when it is not this year
        box.label(EventsUi.fmtDate(date) + (if (date.year != LocalDate.now(zone()).year) " ${date.year}" else ""), Type.BODY_L, Ui.TEXT, 18)
            .setTypeface(null, Typeface.BOLD)

        AcademicCalendar.describe(activity, date)?.let {
            box.label(it, Type.LABEL, Ui.ORANGE, 6).setTypeface(null, Typeface.BOLD)
        }

        // classes (only inside teaching weeks: holiday mode) and your events on one timeline
        val inTeaching = AcademicCalendar.inTeaching(activity, date)
        val slots = if (inTeaching) Timetable.on(Timetable.all(activity, AcademicCalendar.labelFor(activity, date)), date) else emptyList()
        if (slots.isNotEmpty() || events.isNotEmpty()) {
            box.addView(DayTimeline.build(
                activity, date, slots, events,
                onEvent = { e -> EventsUi.editor(activity, e, { onChanged(); redraw() }) },
                onClass = onClass,
            ), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        } else if (inTeaching) {
            box.label("Nothing on this day.", Type.LABEL, Ui.DIM, 6)
        }

        if (!inTeaching && events.isEmpty()) {
            box.label("No classes (holiday mode) and no events on this day.", Type.LABEL, Ui.DIM, 6)
        }
        box.btn("Add an event on ${EventsUi.fmtDate(date)}") {
            EventsUi.editor(activity, null, onChanged, prefillDate = date)
        }
    }

    private fun fmtMin(min: Int): String = String.format("%02d:%02d", min / 60, min % 60)
}
