package my.spectrum.notify

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * UM timetable catalogue (assets/catalog.json), made from the um-timetable-sdk output (timetable_data.json).
 * One entry per course code with its lecture / tutorial / lab slots and the occurrences (Occ) each slot is for.
 *
 * The bundled file is a snapshot, so the app also downloads a fresh one from [REMOTE_URL] during
 * background checks and caches it. The downloaded copy wins over the bundled one when its
 * generatedAt date is the same or newer. Lecturers and rooms can change at TimeEdit after this
 * data was made, so the UI shows the generatedAt date ("as of") and Spectrum stays the source
 * of truth once classes start.
 */
object Catalog {
    /** Where a refreshed catalog.json is downloaded from. raw.githubusercontent serves the file in the repo. */
    private const val REMOTE_URL = "https://raw.githubusercontent.com/Ystan35/SpectrumNotify/main/app/src/main/assets/catalog.json"

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

        /** Every Occ number the course has (lectures are shared by several Occs, so this is the list a student picks from). */
        fun occs(): List<String> = (lec + tut + lab).map { it.occ }.distinct()
            .sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))

        /** Your lecture, tutorial and lab slots when you are in this Occ. */
        fun forOcc(occ: String): List<Pair<SlotKind, Choice>> =
            listOf(SlotKind.LECTURE, SlotKind.TUTORIAL, SlotKind.LAB).mapNotNull { k ->
                choices(k).firstOrNull { it.occ == occ }?.let { Pair(k, it) }
            }
    }

    private lateinit var sp: SharedPreferences
    @Volatile private var cache: Map<String, Course>? = null
    @Volatile var semester: String = ""
        private set

    /** Date the loaded catalogue was generated (yyyy-mm-dd), from the newest copy available. */
    @Volatile var generatedAt: String = ""
        private set

    private fun prefs(ctx: Context): SharedPreferences {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("catalog", Context.MODE_PRIVATE)
        return sp
    }

    fun load(ctx: Context): Map<String, Course> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val app = ctx.applicationContext
            // The downloaded copy wins over the bundled one when it is not older.
            // A corrupt or empty stored copy counts as "no stored copy".
            val (bundledDate, bundledSem, bundled) =
                readText(app.assets.open("catalog.json").bufferedReader().use { it.readText() })
            val stored = prefs(app).getString("json", null)
            val picked = if (stored != null) try {
                val (storedDate, storedSem, storedMap) = readText(stored)
                if (storedMap.isNotEmpty() && storedDate >= bundledDate) Triple(storedDate, storedSem, storedMap) else null
            } catch (_: Exception) { null } else null
            val (date, sem, map) = picked ?: Triple(bundledDate, bundledSem, bundled)
            semester = sem
            generatedAt = date
            cache = map
            return map
        }
    }

    /** Parses one catalogue; returns its generatedAt date, semester and courses. */
    private fun readText(text: String): Triple<String, String, LinkedHashMap<String, Course>> {
        val root = JSONObject(text)
        val out = LinkedHashMap<String, Course>()
        val cs = root.optJSONObject("courses") ?: JSONObject()
        for (code in cs.keys()) {
            val o = cs.optJSONObject(code) ?: continue
            out[code] = Course(code, o.optString("n"), parse(o, "LEC"), parse(o, "TUT"), parse(o, "LAB"))
        }
        return Triple(root.optString("generatedAt"), root.optString("semester"), out)
    }

    /**
     * Downloads the current catalogue and stores it when newer than what we have.
     * Called from the background check; any failure is quiet (the bundled copy still works).
     */
    fun refresh(ctx: Context) {
        try {
            val conn = URL(REMOTE_URL).openConnection() as HttpURLConnection
            val text: String = try {
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                if (conn.responseCode !in 200..299) return
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            // load() first: generatedAt is empty until then, and comparing against it
            // alone would let an older remote file overwrite a newer stored copy.
            load(ctx)
            val (date, sem, map) = readText(text)
            if (map.isEmpty() || date < generatedAt) return
            prefs(ctx).edit().putString("json", text).apply()
            synchronized(this) {
                cache = map
                semester = sem
                generatedAt = date
            }
        } catch (_: Exception) {
        }
    }

    /** Group slots by occurrence number, sorted by number. */
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
