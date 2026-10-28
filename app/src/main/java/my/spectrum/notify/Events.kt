package my.spectrum.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * User-created calendar events ("Google-calendar style"): dated, optional weekly repeat,
 * optional reminder. Stored separately from classes (SharedPreferences "events") —
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
        val reminderMin: Int,      // minutes before start; 0 = off
    ) {
        /** Occurrences within the next [days] days (1 for one-off; per-week for weekly). */
        fun upcoming(now: LocalDate, days: Long = 60): List<Pair<LocalDate, LocalTime>> {
            val out = ArrayList<Pair<LocalDate, LocalTime>>()
            if (repeat == REPEAT_WEEKLY) {
                var d = date
                var guard = 0
                while (d.isBefore(now) && guard++ < 520) d = d.plusWeeks(1)
                while (!d.isAfter(now.plusDays(days)) && guard++ < 520) {
                    out.add(d to LocalTime.of(startTime / 60, startTime % 60))
                    d = d.plusWeeks(1)
                }
            } else if (!d_isPast(now)) {
                out.add(date to LocalTime.of(startTime / 60, startTime % 60))
            }
            return out
        }

        private fun d_isPast(now: LocalDate) = date.isBefore(now)
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("events", Context.MODE_PRIVATE)

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
                    o.optString("repeat", REPEAT_NONE), o.optInt("remind", 0),
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
        .put("repeat", e.repeat).put("remind", e.reminderMin)

    /** Next occurrence epoch seconds (for alarm scheduling); null if none upcoming. */
    fun nextOccurrenceEpoch(e: Event, now: java.time.ZonedDateTime): Long? {
        val occ = e.upcoming(now.toLocalDate(), 30)
        val today = now.toLocalDate()
        val first = occ.firstOrNull { o ->
            val d = o.first
            val t = o.second
            d.isAfter(today) || (d == today && t.isAfter(now.toLocalTime().minusMinutes(1)))
        } ?: return null
        return e.date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().epochSecond.let {
            // rebuild from occurrence date, not stored date (weekly repeats differ)
            first.first.atTime(first.second).atZone(java.time.ZoneId.systemDefault()).toInstant().epochSecond
        }
    }

    private val DATE_FMT = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
}
