package my.spectrum.notify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * One day as a vertical agenda: times on the left, a rail with a dot per class (◆ for your events),
 * and a card per item — title "CODE · Course name", second line "Type · Room/Online · Occ".
 * Gaps of 30 min or more read "Free 11:00–13:00"; today gets a "now" line. Tapping a class opens
 * Join class / Attendance / Course page right on the card. Used by the month view and its day pop-up.
 */
object DayTimeline {

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private class Item(val start: Int, val end: Int, val slot: Slot?, val event: Events.Event?)

    /** The rail: a vertical line through the row with a dot (class) or diamond (event) beside the title. */
    private class Rail(ctx: Context, private val dotColor: Int, private val diamond: Boolean,
                       private val first: Boolean, private val last: Boolean, private val hollow: Boolean) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Ui.BORDER; strokeWidth = 2 * d }
        // dotColor, not `color`: inside apply{} that name is the Paint's own colour (left the dots black)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }
        override fun onDraw(c: Canvas) {
            val x = width / 2f
            val y = 14 * d
            c.drawLine(x, if (first) y else 0f, x, if (last) y else height.toFloat(), line)
            if (hollow) return
            if (diamond) {
                val r = 6 * d
                c.drawPath(Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }, dot)
            } else c.drawCircle(x, y, 6 * d, dot)
        }
    }

    /**
     * Spectrum's full names start with the code and are often in capitals ("WIX1003 COMPUTER SYSTEMS AND
     * ORGANIZATION"): drop the code and write the rest in title case ("Computer Systems and Organization").
     */
    fun courseName(code: String, raw: String): String {
        var n = raw.trim().removePrefix(code).removePrefix(code.uppercase(Locale.ROOT)).trim().trimStart('-', '·', ':').trim()
        if (n.isNotEmpty() && n == n.uppercase(Locale.ROOT)) {
            val small = setOf("and", "of", "the", "in", "for", "to", "a", "an", "on", "with", "dan", "di")
            n = n.lowercase(Locale.ROOT).split(" ").mapIndexed { i, w ->
                if (i > 0 && w in small) w else w.replaceFirstChar { it.titlecase(Locale.ROOT) }
            }.joinToString(" ")
        }
        return n
    }

    /**
     * "Occ 2": the same Occ as Settings → Your classes and Courses (your confirmed "CODE:occN"), else the one
     * predicted from your attendance, as the Occ confirmation on Home prefills. Never read from Spectrum's
     * class title: "Attendance Tutorial Occ 5,Occ6" names every group sharing that session, not yours.
     */
    private fun occOf(ctx: Context, s: Slot, predicted: Map<String, String>): String {
        val code = s.course.uppercase(Locale.ROOT)
        Regex(Regex.escape(code) + """:occ([0-9]+[a-z]?)""", RegexOption.IGNORE_CASE).find(Prefs(ctx).myGroups)
            ?.let { return "Occ " + it.groupValues[1] }
        return predicted[code]?.let { "Occ $it" } ?: ""
    }

    /** "Room BK2", "Online" or "" — your Location setting first, then the room from the UM list. */
    private fun placeOf(ctx: Context, s: Slot, meeting: String): String {
        val loc = Timetable.locationOf(ctx, s.course)
        return when {
            loc.startsWith("room:") && loc.length > 5 -> loc.removePrefix("room:")
            Timetable.inPerson(ctx, s.course) -> s.note.ifEmpty { "In person" }
            loc.startsWith("online:") -> "Online"
            s.note.isNotEmpty() -> s.note
            meeting.isNotEmpty() -> "Online"
            else -> ""
        }
    }

    fun build(
        activity: Activity,
        date: LocalDate,
        slots: List<Slot>,
        events: List<Events.Event>,
        onEvent: (Events.Event) -> Unit,
        onClass: ((Slot) -> Unit)?,
    ): View {
        val dp = { n: Int -> activity.dp(n) }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val items = (slots.map { Item(it.start, it.end, it, null) } + events.map { Item(it.startTime, it.endTime, null, it) })
            .sortedWith(compareBy<Item>({ it.start }, { it.end }))
        if (items.isEmpty()) return col
        val colours = Timetable.colours(activity, slots.map { it.course }.toSet())
        val courses = Courses.all(activity)
        val nowMin = if (date == LocalDate.now()) LocalTime.now().let { it.hour * 60 + it.minute } else -1
        val predicted = Groups.predictedOcc(activity)
        // while a class or event is on, its card says "Ongoing" and no separate "now" line is drawn
        val anyLive = nowMin >= 0 && items.any { nowMin in it.start until it.end }
        var nowDrawn = nowMin < 0 || anyLive

        fun railRow(timeTop: String, timeBottom: String, rail: View, content: View): LinearLayout {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val times = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; setPadding(0, dp(4), dp(6), 0) }
            if (timeTop.isNotEmpty()) times.addView(TextView(activity).apply {
                text = timeTop; textSize = 13f * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
            })
            if (timeBottom.isNotEmpty()) times.addView(TextView(activity).apply {
                text = timeBottom; textSize = 11f * Ui.fontScale; setTextColor(Ui.DIM)
            })
            row.addView(times, LinearLayout.LayoutParams(dp((52 * Ui.fontScale).toInt()), WRAP))
            row.addView(rail, LinearLayout.LayoutParams(dp(20), MATCH))
            row.addView(content, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
            return row
        }

        fun nowLine() {
            nowDrawn = true
            col.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = "Now, ${Timetable.fmtMin(nowMin)}"
                addView(TextView(activity).apply {
                    text = "now ${Timetable.fmtMin(nowMin)}"; textSize = 11f * Ui.fontScale; setTextColor(Ui.RED); setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(dp((52 * Ui.fontScale).toInt() + 14), WRAP).apply { rightMargin = dp(6) })
                addView(View(activity).apply { setBackgroundColor(Ui.RED) }, LinearLayout.LayoutParams(0, dp(2), 1f))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4); bottomMargin = dp(4) })
        }

        var lastEnd = -1
        items.forEachIndexed { i, it ->
            // free time between items
            if (lastEnd >= 0 && it.start - lastEnd >= 30) {
                if (!nowDrawn && nowMin < it.start && nowMin >= lastEnd) nowLine()
                col.addView(railRow("", "", Rail(activity, 0, false, false, false, true), TextView(activity).apply {
                    text = "Free ${Timetable.fmtMin(lastEnd)}–${Timetable.fmtMin(it.start)}"
                    textSize = 12f * Ui.fontScale; setTextColor(Ui.DIM); setPadding(0, dp(6), 0, dp(10))
                }))
            }
            if (!nowDrawn && nowMin < it.start) nowLine()
            lastEnd = maxOf(lastEnd, it.end)
            val live = nowMin in it.start until it.end

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(10))
            }
            val s = it.slot
            val ev = it.event
            val tint = if (s != null) colours[s.course] ?: Ui.CYAN else Ui.PURPLE
            card.background = rippleBg(if (live) Ui.tint(tint, 0x22) else Ui.BAR, if (live) tint else Ui.BORDER, activity, 12)
            card.isClickable = true
            if (live) card.addView(TextView(activity).apply {
                text = "ONGOING"; textSize = 11f * Ui.fontScale; letterSpacing = 0.08f
                setTextColor(onAccent()); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(2), dp(10), dp(2))
                background = roundBg(tint, 0, activity, 10)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(4) })

            if (s != null) {
                val name = courseName(s.course, courses.firstOrNull { c -> c.code.equals(s.course, true) }?.name?.takeIf { n -> n.isNotEmpty() }
                    ?: Catalog.find(activity, s.course)?.name ?: s.title)
                val cid = courses.firstOrNull { c -> c.code.equals(s.course, true) }?.id ?: 0L
                val (meeting, att, coursePage) = Checker.linksForCourse(
                    activity, s.course, cid, Checker.groupTokens(s.title),
                    preferLec = when (s.kind) { SlotKind.LECTURE -> true; SlotKind.TUTORIAL -> false; else -> Checker.preferLecOf(s.title) },
                    day = s.day, startMin = s.start, endMin = s.end,
                )
                card.addView(TextView(activity).apply {
                    text = s.course.uppercase(Locale.ROOT) + if (name.isNotEmpty() && !name.equals(s.course, true)) " · $name" else ""
                    textSize = 15f * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
                val second = listOf(
                    if (s.kind != SlotKind.CLASS) s.kind.label else "",
                    placeOf(activity, s, meeting),
                    occOf(activity, s, predicted),
                    if (s.date != null) "this date only" else "",
                ).filter { t -> t.isNotEmpty() }.joinToString("  ·  ")
                if (second.isNotEmpty()) card.addView(TextView(activity).apply {
                    text = second; textSize = 12f * Ui.fontScale; setTextColor(Ui.DIM); setPadding(0, dp(2), 0, 0)
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })

                // tap: Join class / Attendance / Course page / Details, right on the card
                val actions = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
                fun pill(label: String, filled: Boolean, go: () -> Unit) = TextView(activity).apply {
                    text = label; textSize = 13f * Ui.fontScale; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                    setTextColor(if (filled) onAccent() else tint)
                    minHeight = dp(Ui.touch(44)); setPadding(dp(14), 0, dp(14), 0)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    background = if (filled) roundBg(tint, 0, activity, 22) else rippleBg(Ui.BAR, tint, activity, 22)
                    setOnClickListener { go() }
                }
                fun open(url: String) = try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) { Toast.makeText(activity, "Cannot open link", Toast.LENGTH_SHORT).show() }
                val pills = mutableListOf<View>()
                if (meeting.isNotEmpty()) pills.add(pill("Join class", true) { open(meeting) })
                if (Timetable.attMode(activity, s.course) == "manual") pills.add(pill("Attendance", meeting.isEmpty()) {
                    Toast.makeText(activity, "Attendance for ${s.course} is taken in class by the lecturer.", Toast.LENGTH_LONG).show()
                }) else if (att.isNotEmpty()) pills.add(pill("Attendance", meeting.isEmpty()) { open(att) })
                if (coursePage.isNotEmpty()) pills.add(pill("Course page", false) { open(coursePage) })
                if (onClass != null) pills.add(pill("Details", false) { onClass(s) })
                // two pills per line; one per line at large text sizes, where two would wrap and clip
                pills.chunked(if (Ui.fontScale > 1.1f) 1 else 2).forEach { pair ->
                    val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEachIndexed { k, v -> line.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (k > 0) leftMargin = dp(8); topMargin = dp(6) }) }
                    if (pair.size == 1 && Ui.fontScale <= 1.1f) line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) })
                    actions.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
                }
                if (pills.isEmpty()) actions.addView(TextView(activity).apply {
                    text = "No links found for this class yet."; textSize = 12f * Ui.fontScale; setTextColor(Ui.DIM)
                })
                card.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))
                card.contentDescription = (if (live) "Ongoing. " else "") + "${s.course} $name, ${Timetable.fmtMin(s.start)} to ${Timetable.fmtMin(s.end)}. $second. Tap for links."
                card.setOnClickListener {
                    actions.visibility = if (actions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            } else if (ev != null) {
                card.addView(TextView(activity).apply {
                    text = ev.title; textSize = 15f * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
                card.addView(TextView(activity).apply {
                    text = listOf("Your event", ev.note).filter { t -> t.isNotEmpty() }.joinToString("  ·  ")
                    textSize = 12f * Ui.fontScale; setTextColor(Ui.DIM); setPadding(0, dp(2), 0, 0); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                })
                card.contentDescription = (if (live) "Ongoing. " else "") + "Event ${ev.title}, ${Timetable.fmtMin(ev.startTime)} to ${Timetable.fmtMin(ev.endTime)}. Tap to edit."
                card.setOnClickListener { onEvent(ev) }
            }

            val rail = Rail(activity, tint, ev != null, i == 0, i == items.size - 1 && (nowDrawn || nowMin < 0), false)
            // spacing inside the row (not a margin between rows), so the rail line runs on unbroken
            val spaced = android.widget.FrameLayout(activity).apply { setPadding(0, 0, 0, dp(8)); addView(card) }
            col.addView(railRow(Timetable.fmtMin(it.start), Timetable.fmtMin(it.end), rail, spaced), LinearLayout.LayoutParams(MATCH, WRAP))
        }
        if (!nowDrawn && nowMin >= 0) nowLine()
        return col
    }
}
