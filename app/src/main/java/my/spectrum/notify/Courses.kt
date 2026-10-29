package my.spectrum.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every course you are enrolled in, with its weekly class slots and the address of its Spectrum home page.
 * The list is remembered from the last successful check ("courses" in state.json); courses seen in the
 * cached calendar are added so the page is not empty before the first full check.
 */
object Courses {
    data class Course(val id: Long, val code: String, val name: String, val url: String, val slots: List<Slot>)

    /** Called by the poll with the enrolled courses (core_enrol_get_users_courses). */
    fun remember(st: State, enrolled: List<JSONObject>) {
        val arr = JSONArray()
        for (c in enrolled) {
            val id = c.optLong("id")
            if (id <= 0L) continue
            arr.put(
                JSONObject().put("id", id).put("shortname", c.str("shortname")).put("fullname", c.str("fullname"))
                    .put("startdate", c.optLong("startdate")).put("enddate", c.optLong("enddate"))
            )
        }
        if (arr.length() > 0) st.j.put("courses", arr)
    }

    /**
     * Spectrum keeps you enrolled in past semesters' courses. A course whose Spectrum end date is more than
     * two weeks gone is a past one (0 = no end date set: kept).
     */
    fun isPast(c: JSONObject, now: Long = System.currentTimeMillis() / 1000): Boolean {
        val end = c.optLong("enddate", 0L)
        return end > 0L && end < now - 14 * 86400L
    }

    fun all(ctx: Context): List<Course> {
        val p = Prefs(ctx)
        val st = State(ctx)
        val byId = LinkedHashMap<Long, JSONObject>()
        for (c in (st.j.optJSONArray("courses") ?: JSONArray()).objs()) if (!isPast(c)) byId[c.optLong("id")] = c
        for (e in (st.j.optJSONArray("cal") ?: JSONArray()).objs()) {
            val c = e.optJSONObject("course") ?: continue
            val id = c.optLong("id", 0L)
            if (id > 0L && id !in byId) byId[id] = c
        }
        val slots = Timetable.all(ctx)
        val out = ArrayList<Course>()
        for ((id, c) in byId) {
            val code = Timetable.courseCode(c)
            val name = clean(c.str("fullname").ifEmpty { c.str("shortname") }, 80)
            out.add(Course(id, code, name, "${p.site}/course/view.php?id=$id", slots.filter { it.course == code && it.date == null }))
        }
        // courses that only exist as your own timetable entries (no Spectrum course page)
        val known = out.map { it.code }.toSet()
        for (code in slots.map { it.course }.toSet() - known) {
            out.add(Course(0L, code, "", "", slots.filter { it.course == code && it.date == null }))
        }
        // courses with classes first, then by code
        return out.sortedWith(compareBy<Course>({ it.slots.isEmpty() }, { it.code }))
    }
}
