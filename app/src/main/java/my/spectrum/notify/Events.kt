package my.spectrum.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * User-created calendar events ("Google-calendar style"): dated, timed or all day, optional weekly
 * repeat, any number of reminders, a label colour. Stored separately from classes (SharedPreferences "events") —
 * the timetable logic is untouched. The user's own event belongs to the user.
 */
object Events {
    const val REPEAT_NONE = "none"
    const val REPEAT_WEEKLY = "weekly"

    data class Event(
        val id: Long,
        val title: String,
        val note: String,
        val date: LocalDate,       // first occurrence
        val startTime: Int,        // minutes after midnight
        val endTime: Int,
        val repeat: String,        // REPEAT_NONE | REPEAT_WEEKLY
        val reminders: List<Int>,  // minutes before the start, nearest first; empty = no reminder
        val allDay: Boolean = false,
        val color: Int = -1,       // index in the timetable palette; -1 = the usual event colour
        val endDate: LocalDate? = null,   // last day of an all-day event across several days; null = one day
    ) {
        /** How many days each occurrence lasts. */
        val days: Int get() = if (endDate == null) 1 else (java.time.temporal.ChronoUnit.DAYS.between(date, endDate).toInt() + 1).coerceAtLeast(1)

        /** Is [d] one of this event's days, on any occurrence? */
        fun occursOn(d: LocalDate): Boolean {
            val off = java.time.temporal.ChronoUnit.DAYS.between(date, d)
            if (off < 0) return false
            return if (repeat == REPEAT_WEEKLY) off % 7 < days else off < days
        }

        /** Which day of the occurrence [d] is, from 1. */
        fun dayNumber(d: LocalDate): Int {
            val off = java.time.temporal.ChronoUnit.DAYS.between(date, d).toInt()
            return (if (repeat == REPEAT_WEEKLY) off % 7 else off) + 1
        }

        /** The label colour as shown right now (the palette follows the light / dark theme). */
        fun tint(): Int = if (color >= 0) Timetable.colorOf(color) else Ui.PURPLE

        /** What reminders count back from: the start, or ALL_DAY_REMIND on an all-day event. */
        fun remindFrom(): Int = if (allDay) ALL_DAY_REMIND else startTime

        /** Occurrences within the next [days] days (1 for one-off; per-week for weekly). */
        fun upcoming(now: LocalDate, days: Long = 60): List<Pair<LocalDate, LocalTime>> {
            val out = ArrayList<Pair<LocalDate, LocalTime>>()
            if (repeat == REPEAT_WEEKLY) {
                var d = date
                var guard = 0
                // an occurrence still running counts, so it stays listed until its last day
                while (d.plusDays(days - 1L).isBefore(now) && guard++ < 520) d = d.plusWeeks(1)
                while (!d.isAfter(now.plusDays(days)) && guard++ < 520) {
                    out.add(d to LocalTime.of(startTime / 60, startTime % 60))
                    d = d.plusWeeks(1)
                }
            } else if (!d_isPast(now)) {
                out.add(date to LocalTime.of(startTime / 60, startTime % 60))
            }
            return out
        }

        private fun d_isPast(now: LocalDate) = date.plusDays(days - 1L).isBefore(now)
    }

    /** All-day events have no start time: their reminders go off at this time of day, so never at midnight. */
    const val ALL_DAY_REMIND = 9 * 60

    private const val DAY = 24 * 60

    /** "30 min", "2 hours", "1 day", "1 week" before; "" for 0, the moment itself. */
    fun remindLabel(min: Int): String = when {
        min <= 0 -> ""
        min % (7 * DAY) == 0 -> (min / (7 * DAY)).let { "$it week" + if (it == 1) "" else "s" }
        min % DAY == 0 -> (min / DAY).let { "$it day" + if (it == 1) "" else "s" }
        min % 60 == 0 -> (min / 60).let { "$it hour" + if (it == 1) "" else "s" }
        else -> "$min min"
    }

    /** "reminds 1 day, 1 week before", for the list of events. */
    fun remindSummary(e: Event): String {
        if (e.reminders.isEmpty()) return ""
        val before = e.reminders.filter { it > 0 }.joinToString(", ") { remindLabel(it) }
        val atTime = if (0 in e.reminders) (if (e.allDay) "on the day" else "at the start") else ""
        return "reminds " + listOf(atTime, if (before.isEmpty()) "" else "$before before").filter { it.isNotEmpty() }.joinToString(" and ")
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("events", Context.MODE_PRIVATE)

    /** Saved reminders; events from before 0.31 have one "remind" number, where 0 meant none. */
    private fun remindersOf(o: JSONObject): List<Int> {
        val arr = o.optJSONArray("reminders")
        if (arr != null) return (0 until arr.length()).map { arr.optInt(it) }.distinct().sorted()
        return o.optInt("remind", 0).let { if (it > 0) listOf(it) else emptyList() }
    }

    @Synchronized
    fun all(ctx: Context): List<Event> {
        val arr = JSONArray(sp(ctx).getString("list", "[]") ?: "[]")
        val out = ArrayList<Event>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val d = LocalDate.parse(o.optString("date"), DATE_FMT)
            out.add(
                Event(
                    o.optLong("id"), o.optString("title"), o.optString("note"), d,
                    o.optInt("start"), o.optInt("end"),
                    o.optString("repeat", REPEAT_NONE), remindersOf(o),
                    o.optBoolean("allDay", false), o.optInt("color", -1),
                    o.optString("endDate").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it, DATE_FMT) },
                )
            )
        }
        return out.sortedWith(compareBy({ it.date }, { it.startTime }))
    }

    @Synchronized
    fun save(ctx: Context, e: Event) {
        val arr = JSONArray(sp(ctx).getString("list", "[]") ?: "[]")
        val out = JSONArray()
        var replaced = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id") == e.id) {
                out.put(toJson(e)); replaced = true
            } else out.put(o)
        }
        if (!replaced) out.put(toJson(e))
        sp(ctx).edit().putString("list", out.toString()).apply()
        Scheduler.scheduleEventReminders(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        val arr = JSONArray(sp(ctx).getString("list", "[]") ?: "[]")
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id") != id) out.put(o)
        }
        sp(ctx).edit().putString("list", out.toString()).apply()
        Scheduler.scheduleEventReminders(ctx)
    }

    fun newId(): Long = System.nanoTime()

    private fun toJson(e: Event): JSONObject = JSONObject()
        .put("id", e.id).put("title", e.title).put("note", e.note)
        .put("date", e.date.format(DATE_FMT)).put("start", e.startTime).put("end", e.endTime)
        .put("repeat", e.repeat).put("reminders", JSONArray(e.reminders))
        .put("allDay", e.allDay).put("color", e.color).put("endDate", e.endDate?.format(DATE_FMT) ?: "")

    /** Next occurrence epoch seconds (for alarm scheduling); null if none upcoming. */
    fun nextOccurrenceEpoch(e: Event, now: java.time.ZonedDateTime): Long? {
        val occ = e.upcoming(now.toLocalDate(), 30)
        val today = now.toLocalDate()
        val first = occ.firstOrNull { o ->
            val d = o.first
            val t = o.second
            d.isAfter(today) || (d == today && t.isAfter(now.toLocalTime().minusMinutes(1)))
        } ?: return null
        return e.date.atStartOfDay(zone()).toInstant().epochSecond.let {
            // rebuild from occurrence date, not stored date (weekly repeats differ)
            first.first.atTime(first.second).atZone(zone()).toInstant().epochSecond
        }
    }

    private val DATE_FMT = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
}
