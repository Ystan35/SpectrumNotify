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
            card.addView(tv(activity, "${fmtDate(d)} · ${fmt(min)}–${fmt(e.endTime)}", Type.LABEL, Ui.CYAN, true))
            card.addView(tv(activity, e.title, Type.BODY_L, Ui.TEXT, true).apply { setPadding(0, dp(4), 0, 0) })
            val meta = ArrayList<String>()
            if (e.repeat == Events.REPEAT_WEEKLY) meta.add("repeats weekly")
            if (e.reminderMin > 0) meta.add("reminds ${e.reminderMin} min before")
            if (e.note.isNotEmpty()) meta.add(e.note)
            if (meta.isNotEmpty()) card.addView(tv(activity, meta.joinToString(" · "), Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(3), 0, 0); maxLines = 2 })
            box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        box.btn("Add an event") { editor(activity, null, onChanged) }
    }

    /** Add/edit dialog: title, note, date, start/end, repeat chip, reminder chip. */
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
        lateinit var dateBtn: Button
        dateBtn = col.btn(dateLabel(date)) {
            val parts = date.toString().split("-").map { it.toInt() }
            android.app.DatePickerDialog(activity, { _, y, m, d ->
                date = LocalDate.of(y, m + 1, d)
                dateBtn.text = dateLabel(date)
            }, parts[0], parts[1] - 1, parts[2]).show()
        }

        // time pickers, like the class editor; moving the start keeps the end after it
        lateinit var endF: TimeField
        val startF = TimeField(activity, existing?.startTime ?: 10 * 60) { st ->
            if (endF.minutes <= st) endF.set(minOf(24 * 60 - 1, st + 60))
        }
        endF = TimeField(activity, existing?.endTime ?: 11 * 60) { }
        lbl("Starts"); col.addView(startF.view)
        lbl("Ends"); col.addView(endF.view)

        lbl("Repeat")
        val repeats = listOf("Does not repeat" to Events.REPEAT_NONE, "Every week" to Events.REPEAT_WEEKLY)
        var repeat = existing?.repeat ?: Events.REPEAT_NONE
        col.chips(repeats.map { it.first }, setOf(repeats.indexOfFirst { it.second == repeat }.coerceAtLeast(0)), false) { sel ->
            repeat = repeats.getOrNull(sel.firstOrNull() ?: 0)?.second ?: Events.REPEAT_NONE
        }

        lbl("Remind me before")
        val opts = listOf("No reminder" to 0, "10 min before" to 10, "30 min before" to 30, "1 hour before" to 60, "1 day before" to 1440)
        var remind = existing?.reminderMin ?: 30
        col.chips(opts.map { it.first }, setOf(opts.indexOfFirst { it.second == remind }.coerceAtLeast(0)), false) { sel ->
            remind = opts.getOrNull(sel.firstOrNull() ?: 0)?.second ?: 0
        }

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
                en <= st -> Toast.makeText(activity, "The end time must be after the start time.", Toast.LENGTH_LONG).show()
                else -> {
                    Events.save(activity, Events.Event(
                        existing?.id ?: Events.newId(), title, noteF.text.toString().trim(),
                        date, st, en, repeat, remind,
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

    private fun titleFieldRow(activity: Activity, f: EditText): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(f, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun dateLabel(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))

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
