package my.spectrum.notify

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * UM timetable catalogue (assets/catalog.json), made from the um-timetable-sdk output (timetable_data.json).
 * One entry per course code with its lecture / tutorial / lab slots and the occurrences (Occ) each slot is for.
 */
object Catalog {
    /** One weekly slot of an occurrence. day: 1 = Monday. start/end: minutes after midnight. */
    class Part(val day: Int, val start: Int, val end: Int, val room: String, val lecturer: String)

    /** One occurrence of a lecture / tutorial / lab: its number and the slot(s) it meets in. */
    class Choice(val occ: String, val parts: List<Part>) {
        fun label(): String = "Occ $occ · " + parts.joinToString(" + ") {
            "${Timetable.DAYS[it.day - 1]} ${Timetable.fmtMin(it.start)}–${Timetable.fmtMin(it.end)}" +
                if (it.room.isNotEmpty()) " ${it.room}" else ""
        }

        fun detail(): String = parts.map { it.lecturer }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
    }

    class Course(val code: String, val name: String, val lec: List<Choice>, val tut: List<Choice>, val lab: List<Choice>) {
        fun choices(k: SlotKind): List<Choice> = when (k) {
            SlotKind.LECTURE -> lec
            SlotKind.TUTORIAL -> tut
            SlotKind.LAB -> lab
            else -> emptyList()
        }
    }

    @Volatile private var cache: Map<String, Course>? = null
    @Volatile var semester: String = ""
        private set

    fun load(ctx: Context): Map<String, Course> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val out = LinkedHashMap<String, Course>()
            try {
                val text = ctx.applicationContext.assets.open("catalog.json").bufferedReader().use { it.readText() }
                val root = JSONObject(text)
                semester = root.optString("semester")
                val cs = root.optJSONObject("courses") ?: JSONObject()
                for (code in cs.keys()) {
                    val o = cs.optJSONObject(code) ?: continue
                    out[code] = Course(code, o.optString("n"), parse(o, "LEC"), parse(o, "TUT"), parse(o, "LAB"))
                }
            } catch (_: Exception) {
            }
            cache = out
            return out
        }
    }

    /** Groups the slots by occurrence number, sorted by number. */
    private fun parse(o: JSONObject, key: String): List<Choice> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        val byOcc = LinkedHashMap<String, MutableList<Part>>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            val part = Part(a.optInt(0), a.optInt(1), a.optInt(2), a.optString(3), a.optString(5))
            val occs = a.optJSONArray(4) ?: continue
            for (j in 0 until occs.length()) byOcc.getOrPut(occs.optString(j)) { ArrayList() }.add(part)
        }
        return byOcc.map { Choice(it.key, it.value) }
            .sortedWith(compareBy({ it.occ.toIntOrNull() ?: Int.MAX_VALUE }, { it.occ }))
    }

    fun find(ctx: Context, code: String): Course? = load(ctx)[code.trim().uppercase(Locale.ROOT)]

    /** Courses whose code starts with, or whose name contains, the text. */
    fun search(ctx: Context, q: String, limit: Int = 8): List<Course> {
        val s = q.trim().uppercase(Locale.ROOT)
        if (s.length < 2) return emptyList()
        val all = load(ctx).values
        return (all.filter { it.code.startsWith(s) } + all.filter { !it.code.startsWith(s) && s in it.name.uppercase(Locale.ROOT) }).take(limit)
    }
}
