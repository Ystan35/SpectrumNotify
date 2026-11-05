package my.spectrum.notify

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * "Events" view inside the Timetable tab: a chronological list of the user's own
 * calendar events (dated, optionally weekly-repeating, optionally reminded).
 * Separate from classes by design — Events.kt owns the store.
 */
object EventsUi {

    fun fmtDate(d: LocalDate): String =
        if (d == LocalDate.now(zone())) "Today"
        else if (d == LocalDate.now(zone()).plusDays(1)) "Tomorrow"
        else d.format(DateTimeFormatter.ofPattern("EEE d MMM"))

    /**
     * Adds the events list to [box]: upcoming occurrences (custom + repeating) with
     * edit/delete on tap, an "Add event" button, and past-one-off events omitted.
     */
    fun render(activity: Activity, box: LinearLayout, onChanged: () -> Unit) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        box.label("Your events", Type.BODY_L, Ui.TEXT, 20).setTypeface(null, Typeface.BOLD)
        val today = LocalDate.now(zone())
        val rows = ArrayList<Triple<Events.Event, LocalDate, Int>>() // event, occurrence date, minutes
        for (e in Events.all(activity)) {
            for ((d, t) in e.upcoming(today, 30)) {
                rows.add(Triple(e, d, t.hour * 60 + t.minute))
            }
        }
        rows.sortWith(compareBy({ it.second }, { it.third }))

        if (rows.isEmpty()) {
            box.label(
                "No events yet. Add things classes don't cover: presentations, meetings, " +
                    "study sessions, submission days — with an optional weekly repeat and reminder.",
                Type.LABEL, Ui.DIM, 6,
            )
        }
        for ((e, d, min) in rows.take(40)) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = roundBg(Ui.BAR, Ui.BORDER, activity, Radius.MD)
                isClickable = true
                background = rippleBg(Ui.BAR, Ui.BORDER, activity, Radius.MD)
                setOnClickListener { editor(activity, e, onChanged) }
            }
            card.addView(tv(activity, whenLabel(e, d), Type.LABEL, e.tint(), true))
            card.addView(tv(activity, e.title, Type.BODY_L, Ui.TEXT, true).apply { setPadding(0, dp(4), 0, 0) })
            val meta = ArrayList<String>()
            if (e.repeat == Events.REPEAT_WEEKLY) meta.add("repeats weekly")
            if (e.reminders.isNotEmpty()) meta.add(Events.remindSummary(e))
            if (e.note.isNotEmpty()) meta.add(e.note)
            if (meta.isNotEmpty()) card.addView(tv(activity, meta.joinToString(" · "), Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(3), 0, 0); maxLines = 2 })
            box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        box.btn("Add an event") { editor(activity, null, onChanged) }
    }

    /** "Tomorrow · 10:00–11:00", "Mon 2 Nov · All day", or "Mon 2 Nov – Thu 5 Nov · All day" for occurrence [d]. */
    fun whenLabel(e: Events.Event, d: LocalDate): String =
        fmtDate(d) + (if (e.days > 1) " – " + fmtDate(d.plusDays(e.days - 1L)) else "") + " · " + timeRange(e)

    /** "All day", or "10:00–11:00". */
    fun timeRange(e: Events.Event): String = if (e.allDay) "All day" else "${fmt(e.startTime)}–${fmt(e.endTime)}"

    /** Add/edit dialog: title, note, date, all day or start/end, repeat, reminders, label colour. */
    fun editor(activity: Activity, existing: Events.Event?, onChanged: () -> Unit, prefillDate: LocalDate? = null) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(10), dp(24), 0) }

        fun field(hintText: String, value: String, single: Boolean = true): EditText =
            EditText(activity).apply {
                setText(value); hint = hintText
                textSize = Type.BODY * Ui.fontScale; setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM)
                setSingleLine(single)
                background = roundBg(Ui.BAR, Ui.BORDER, activity, Radius.SM)
                setPadding(dp(10), dp(9), dp(10), dp(9))
            }
        fun lbl(t: String) { col.label(t, Type.LABEL, Ui.TEXT, 12).setTypeface(null, Typeface.BOLD) }

        val titleF = field("e.g. Presentation, study group", existing?.title ?: "")
        lbl("Title"); col.addView(titleFieldRow(activity, titleF))

        var date: LocalDate = existing?.date ?: prefillDate ?: LocalDate.now(zone())
        var endDate: LocalDate? = existing?.endDate
        var length = (existing?.days ?: 1) - 1L
        var drawEnd: () -> Unit = {}
        lateinit var dateBtn: Button
        lbl("Date")
        dateBtn = col.btn(dateLabel(date)) {
            val parts = date.toString().split("-").map { it.toInt() }
            android.app.DatePickerDialog(activity, { _, y, m, d ->
                date = LocalDate.of(y, m + 1, d)
                dateBtn.text = dateLabel(date)
                // the last day moves with the first, so the length stays the same
                if (endDate != null) endDate = date.plusDays(length)
                drawEnd()
            }, parts[0], parts[1] - 1, parts[2]).show()
        }
        dateBtn.asField(activity)

        // time pickers, like the class editor; moving the start keeps the end after it
        lateinit var endF: TimeField
        val timed = existing?.takeIf { !it.allDay }
        val startF = TimeField(activity, timed?.startTime ?: 10 * 60) { st ->
            if (endF.minutes <= st) endF.set(minOf(24 * 60 - 1, st + 60))
        }
        endF = TimeField(activity, timed?.endTime ?: 11 * 60) { }
        var allDay = existing?.allDay ?: false
        val times = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; visibility = if (allDay) View.GONE else View.VISIBLE }
        lateinit var drawReminders: () -> Unit
        val reminders = LinkedHashSet<Int>().apply { addAll(existing?.reminders ?: listOf(30)) }
        col.addView(android.widget.Switch(activity).apply {
            text = "All day"; setTextColor(Ui.TEXT); textSize = Type.BODY_L * Ui.fontScale
            minHeight = dp(Ui.touch(48)); isChecked = allDay
            setOnCheckedChangeListener { _, on ->
                allDay = on
                times.visibility = if (on) View.GONE else View.VISIBLE
                drawEnd()
                // minutes and hours mean nothing without a start time
                if (on) {
                    reminders.removeAll(reminders.filter { it % DAY != 0 }.toSet())
                    if (reminders.isEmpty()) reminders.add(0)
                }
                drawReminders()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // all-day events can run over several days; events with a time stay within one
        val endBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(endBox)
        lateinit var endBtn: Button
        endBox.label("Last day", Type.LABEL, Ui.TEXT, 12).setTypeface(null, Typeface.BOLD)
        endBtn = endBox.btn("") {
            val cur = endDate ?: date
            android.app.DatePickerDialog(activity, { _, y, m, d ->
                val picked = LocalDate.of(y, m + 1, d)
                endDate = picked.takeIf { it.isAfter(date) }
                length = endDate?.let { java.time.temporal.ChronoUnit.DAYS.between(date, it) } ?: 0L
                drawEnd()
            }, cur.year, cur.monthValue - 1, cur.dayOfMonth).apply { datePicker.minDate = date.atStartOfDay(zone()).toInstant().toEpochMilli() }.show()
        }
        endBtn.asField(activity)
        drawEnd = {
            endBox.visibility = if (allDay) View.VISIBLE else View.GONE
            val end = endDate
            endBtn.text = if (end == null) "Same day" else dateLabel(end) + " (${length + 1} days)"
        }
        drawEnd()
        for ((name, f) in listOf("Starts" to startF, "Ends" to endF)) {
            val half = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            half.label(name, Type.LABEL, Ui.TEXT, 12).setTypeface(null, Typeface.BOLD)
            half.addView(f.view)
            times.addView(half, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (name == "Ends") leftMargin = dp(10) })
        }
        col.addView(times)

        lbl("Repeat")
        val repeats = listOf("Does not repeat" to Events.REPEAT_NONE, "Every week" to Events.REPEAT_WEEKLY)
        var repeat = existing?.repeat ?: Events.REPEAT_NONE
        col.chips(repeats.map { it.first }, setOf(repeats.indexOfFirst { it.second == repeat }.coerceAtLeast(0)), false) { sel ->
            repeat = repeats.getOrNull(sel.firstOrNull() ?: 0)?.second ?: Events.REPEAT_NONE
        }

        lbl("Remind me (pick any)")
        val reminderBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(reminderBox)
        drawReminders = {
            reminderBox.removeAllViews()
            val presets = if (allDay) listOf(0, DAY, 2 * DAY, 3 * DAY, 4 * DAY, 7 * DAY, 14 * DAY)
                else listOf(0, 10, 30, 60, DAY, 3 * DAY, 4 * DAY, 7 * DAY)
            val tags = (presets + reminders.filter { it !in presets }.sorted()).map { min ->
                val label = when {
                    min > 0 -> Events.remindLabel(min)
                    allDay -> "On the day"
                    else -> "At start"
                }
                activity.chip(label, min in reminders, size = Type.LABEL, padH = 2) {
                    if (!reminders.remove(min)) reminders.add(min)
                    drawReminders()
                }
            } + activity.chip("+ Custom", false, size = Type.LABEL, padH = 2) {
                customReminder(activity, allDay) { min -> reminders.add(min); drawReminders() }
            }
            val perLine = if (Ui.fontScale > 1.1f) 2 else 3
            tags.chunked(perLine).forEach { line ->
                reminderBox.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    line.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(6) }) }
                    repeat(perLine - line.size) { addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(6) }) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            }
            reminderBox.label(when {
                reminders.isEmpty() -> "No reminder."
                allDay -> "All-day reminders go off at 09:00."
                else -> "Before the start time."
            }, Type.CAPTION, Ui.DIM, 6)
        }
        drawReminders()

        lbl("Label colour")
        var colour = existing?.color ?: -1
        col.swatches(colour) { colour = it }

        lbl("Note (optional)")
        val noteF = field("What to bring, where to go…", existing?.note ?: "", single = false)
        noteF.setSingleLine(false); noteF.minLines = 2
        col.addView(noteF)

        val scroll = android.widget.ScrollView(activity).apply { addView(col) }
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.6).toInt().coerceAtLeast(dp(200)),
        )
        val dialog = AlertDialog.Builder(activity, Ui.dialogTheme)
            .setTitle(if (existing == null) "Add an event" else "Edit event")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .apply { if (existing != null) setNeutralButton("Delete", null) }
            .show()
        // Save checks first and keeps the dialog (and what you typed) open when something is missing
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = titleF.text.toString().trim()
            val st = startF.minutes
            val en = endF.minutes
            when {
                title.isEmpty() -> { titleF.error = "Give the event a name"; titleF.requestFocus() }
                !allDay && en <= st -> Toast.makeText(activity, "The end time must be after the start time.", Toast.LENGTH_LONG).show()
                else -> {
                    Events.save(activity, Events.Event(
                        existing?.id ?: Events.newId(), title, noteF.text.toString().trim(),
                        date, if (allDay) 0 else st, if (allDay) DAY else en, repeat, reminders.sorted(), allDay, colour,
                        endDate.takeIf { allDay },
                    ))
                    Toast.makeText(activity, "Event saved.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onChanged()
                }
            }
        }
        if (existing != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            AlertDialog.Builder(activity, Ui.dialogTheme)
                .setTitle("Delete this event?")
                .setMessage("“${existing.title}”" + (if (existing.repeat == Events.REPEAT_WEEKLY) " and all its weekly repeats" else "") + " will be removed.")
                .setPositiveButton("Delete") { _, _ ->
                    Events.delete(activity, existing.id)
                    Toast.makeText(activity, "Event deleted.", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onChanged()
                }
                .setNegativeButton("Keep", null)
                .show()
        }
    }

    /** "+ Custom": a number and a unit, e.g. 5 days. Minutes and hours only make sense with a start time. */
    private fun customReminder(activity: Activity, allDay: Boolean, onPick: (Int) -> Unit) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        val units = (if (allDay) emptyList() else listOf("minutes" to 1, "hours" to 60)) + listOf("days" to DAY, "weeks" to 7 * DAY)
        var unit = units.indexOfFirst { it.first == "days" }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val number = EditText(activity).apply {
            hint = "How many"; inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = Type.BODY_L * Ui.fontScale; setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM); setSingleLine()
            background = roundBg(Ui.BAR, Ui.BORDER, activity, Radius.SM)
            setPadding(dp(12), dp(10), dp(12), dp(10)); minHeight = dp(Ui.touch(48))
        }
        col.addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.chips(units.map { it.first }, setOf(unit), false) { sel -> unit = sel.firstOrNull() ?: unit }
        val d = AlertDialog.Builder(activity, Ui.dialogTheme)
            .setTitle("Remind me before")
            .setView(col)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val min = (number.text.toString().toLongOrNull() ?: 0L) * units[unit].second
            when {
                min <= 0 -> number.error = "Enter a number"
                min > MAX_REMIND -> number.error = "Up to 1 year before"
                else -> { onPick(min.toInt()); d.dismiss() }
            }
        }
    }

    private const val DAY = 24 * 60
    private const val MAX_REMIND = 365 * DAY

    private fun titleFieldRow(activity: Activity, f: EditText): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun dateLabel(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))

    /** A date button drawn as an input box (like the time fields): it holds a value you can change, it is not an action. */
    private fun Button.asField(activity: Activity) {
        background = rippleBg(Ui.BAR, Ui.BORDER_STRONG, activity, Radius.SM)
        setTextColor(Ui.TEXT)
        setTypeface(Typeface.create(typeface, Typeface.NORMAL))
        textSize = Type.BODY_L * Ui.fontScale
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        val p = (12 * activity.resources.displayMetrics.density).toInt()
        setPadding(p, 0, p, 0)
        (layoutParams as? LinearLayout.LayoutParams)?.topMargin = (6 * activity.resources.displayMetrics.density).toInt()
    }

    private fun fmt(min: Int): String =
        String.format("%02d:%02d", min / 60, min % 60)

    fun fmtMin(min: Int): String = fmt(min)

    fun toMin(s: String): Int? {
        val p = s.trim().split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun tv(a: Activity, t: String, size: Float, color: Int, bold: Boolean = false): TextView =
        a.tv(t, size, color, bold)
}
