package my.spectrum.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/** What kind of class a slot is. CLASS = could not tell. */
enum class SlotKind(val label: String, val tag: String) {
    LECTURE("Lecture", "LEC"),
    TUTORIAL("Tutorial", "TUT"),
    LAB("Lab", "LAB"),
    CLASS("Class", "");

    companion object {
        fun parse(s: String?): SlotKind = values().firstOrNull { it.name == s } ?: CLASS
    }
}

/**
 * One class. day: 1 = Monday ... 7 = Sunday. start/end: minutes after midnight.
 * date: null = every week; set = a one-off on that date only (seen in a single week so far).
 * sem: the semester it belongs to (AcademicCalendar label); you take different subjects each semester.
 */
data class Slot(
    val key: String,
    val course: String,
    val title: String,
    val day: Int,
    val start: Int,
    val end: Int,
    val kind: SlotKind,
    val manual: Boolean,
    val note: String = "",
    val grp: String = "",
    val date: LocalDate? = null,
    val sem: String = "",
) {
    fun occursOn(d: LocalDate): Boolean = day == d.dayOfWeek.value && (date == null || date == d)
}

/**
 * Weekly timetable. Two sources:
 *  - learned from Spectrum: attendance events in the cached calendar, grouped by course + weekday + time;
 *  - added by the user (stored in the "timetable" preferences).
 * Learned slots are remembered in state.json for a few weeks so the timetable does not empty out between calendar windows.
 */
object Timetable {
    private const val KEEP_SECS = 42L * 86400

    val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val DAY_FULL = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    // ------------------------------------------------------------ colours
    private val PALETTE_DARK = intArrayOf(
        0xFF7A9BFF.toInt(), 0xFF4ADE9A.toInt(), 0xFFFFC857.toInt(), 0xFFFF7A7A.toInt(), 0xFFB794FF.toInt(),
        0xFF5CD6E6.toInt(), 0xFFFFA45C.toInt(), 0xFFF48FB1.toInt(), 0xFFA3E635.toInt(), 0xFF94A3B8.toInt(),
    )
    private val PALETTE_LIGHT = intArrayOf(
        0xFF2F55E4.toInt(), 0xFF12805A.toInt(), 0xFF9A6700.toInt(), 0xFFC93636.toInt(), 0xFF6B46C8.toInt(),
        0xFF0A7C8C.toInt(), 0xFFC25E0B.toInt(), 0xFFC2185B.toInt(), 0xFF4D7C0F.toInt(), 0xFF475569.toInt(),
    )
    val PALETTE_SIZE: Int get() = PALETTE_DARK.size

    fun colorOf(idx: Int): Int {
        val pal = if (Ui.dark) PALETTE_DARK else PALETTE_LIGHT
        return pal[((idx % pal.size) + pal.size) % pal.size]
    }

    // ------------------------------------------------------------ detecting course and type
    private val CODE_RE = Regex("""\b[A-Z]{2,4}\d{3,4}[A-Z]?\b""")
    private val LAB_RE = Regex("""\b(?:lab|labs|laboratory|makmal|practical|amali)\b""", RegexOption.IGNORE_CASE)
    private val TUT_RE = Regex("""\b(?:tutorial|tutorials|tut|tutor|t\d+)\b""", RegexOption.IGNORE_CASE)
    private val LEC_RE = Regex("""\b(?:lecture|lectures|lec|kuliah|k\d+)\b""", RegexOption.IGNORE_CASE)
    private val ATT_ONLY = Regex("""^(?:attendance|kehadiran)\W*$""", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]*>")

    /** "WIA2010" from the course's short or full name; falls back to whatever name there is. */
    fun courseCode(c: JSONObject?): String {
        val short = c?.str("shortname") ?: ""
        val full = c?.str("fullname") ?: ""
        for (t in listOf(short, full)) {
            val m = CODE_RE.find(t.uppercase(Locale.ROOT))
            if (m != null) return m.value
        }
        return short.ifEmpty { full }.trim().ifEmpty { "Class" }.take(20)
    }

    /** Looks for lecture / tutorial / lab words (English and Malay) in the texts, in order; the earliest word in a text wins. */
    fun detectKind(vararg texts: String): SlotKind {
        for (t in texts) {
            val s = unesc(t)
            val hits = ArrayList<Pair<Int, SlotKind>>()
            LAB_RE.find(s)?.let { hits.add(Pair(it.range.first, SlotKind.LAB)) }
            TUT_RE.find(s)?.let { hits.add(Pair(it.range.first, SlotKind.TUTORIAL)) }
            LEC_RE.find(s)?.let { hits.add(Pair(it.range.first, SlotKind.LECTURE)) }
            if (hits.isNotEmpty()) return hits.minByOrNull { it.first }!!.second
        }
        return SlotKind.CLASS
    }

    fun fmtMin(m: Int): String = String.format(Locale.ENGLISH, "%02d:%02d", m / 60, m % 60)

    // ------------------------------------------------------------ learning from the Spectrum calendar
    private class Acc(val course: String, val day: Int, val start: Int, val end: Int) {
        val kinds = HashMap<SlotKind, Int>()
        val dates = HashSet<LocalDate>()
        var title = ""
        fun best(): SlotKind =
            kinds.filter { it.key != SlotKind.CLASS }.maxByOrNull { it.value }?.key ?: SlotKind.CLASS
    }

    private class Learned(val slot: Slot, val dates: Set<LocalDate>)

    /** Groups the attendance events of the calendar into slots, with the dates each was seen on. */
    private fun fromCalendar(cal: List<JSONObject>): List<Learned> {
        val acc = LinkedHashMap<String, Acc>()
        for (e in cal) {
            if (!Checker.isAttEvent(e)) continue
            val ts = e.optLong("timestart")
            if (ts <= 0L) continue
            val z = localDt(ts)
            val dur = e.optLong("timeduration")
            val start = z.hour * 60 + z.minute
            val end = minOf(24 * 60, start + (if (dur > 0L) (dur / 60).toInt() else 60))
            if (end <= start) continue
            val course = courseCode(e.optJSONObject("course"))
            val day = z.dayOfWeek.value
            val key = "$course|$day|$start|$end"
            val a = acc.getOrPut(key) { Acc(course, day, start, end) }
            a.dates.add(z.toLocalDate())
            val name = e.str("name")
            val desc = unesc(e.str("description").replace(TAGS, " ")).replace(Regex("\\s+"), " ").trim().take(200)
            val kind = detectKind(name, desc)
            a.kinds[kind] = (a.kinds[kind] ?: 0) + 1
            if (a.title.isEmpty()) {
                val t = clean(name, 40)
                a.title = if (ATT_ONLY.matches(t)) "" else t
            }
        }
        return acc.map { (key, a) -> Learned(Slot(key, a.course, a.title, a.day, a.start, a.end, a.best(), false), a.dates) }
    }

    /** dates: when this slot was seen; null = stored before dates were kept, treated as weekly. */
    private class Stored(val slot: Slot, val seen: Long, val dates: Set<LocalDate>?)

    /** Weekly once seen in two different weeks; a single sighting may be a make-up or moved class. */
    private fun weekly(dates: Set<LocalDate>?): Boolean =
        dates == null || dates.map { it.with(DayOfWeek.MONDAY) }.toSet().size >= 2

    private fun overlaps(a: Slot, b: Slot) = a.course == b.course && a.day == b.day && a.start < b.end && b.start < a.end

    private fun readStored(st: State): List<Stored> =
        (st.j.optJSONArray("tt_auto") ?: JSONArray()).objs().mapNotNull { o ->
            val day = o.optInt("day")
            val s = o.optInt("start")
            val e = o.optInt("end")
            if (day !in 1..7 || e <= s) return@mapNotNull null
            val dates = o.optJSONArray("dates")?.strs()?.mapNotNull { d ->
                try { LocalDate.parse(d) } catch (_: Exception) { null }
            }?.toSet()
            Stored(Slot(o.str("key"), o.str("course"), o.str("title"), day, s, e, SlotKind.parse(o.str("kind")), false), o.optLong("seen"), dates)
        }

    /**
     * Current slots win; remembered ones are kept for a few weeks unless the schedule has moved them
     * (the same start now ends differently, or a confirmed weekly slot now overlaps where they were).
     */
    private fun merge(stored: List<Stored>, cur: List<Learned>, now: Long): List<Stored> {
        val old = stored.associateBy { it.slot.key }
        val curKeys = cur.map { it.slot.key }.toSet()
        val curStarts = cur.map { "${it.slot.course}|${it.slot.day}|${it.slot.start}" }.toSet()
        val out = ArrayList<Stored>()
        for (l in cur) {
            val s = l.slot
            val prev = old[s.key]
            val kind = if (s.kind == SlotKind.CLASS && prev != null) prev.slot.kind else s.kind
            // a slot kept from before dates were recorded stays weekly (prev.dates == null)
            val dates = if (prev != null && prev.dates == null) null
                else ((prev?.dates ?: emptySet()) + l.dates).sorted().takeLast(8).toSet()
            out.add(Stored(s.copy(kind = kind), now, dates))
        }
        val weeklyCur = out.filter { weekly(it.dates) }.map { it.slot }
        for (s in stored) {
            if (s.slot.key in curKeys) continue
            if ("${s.slot.course}|${s.slot.day}|${s.slot.start}" in curStarts) continue
            if (weeklyCur.any { overlaps(it, s.slot) }) continue
            if (now - s.seen > KEEP_SECS) continue
            out.add(s)
        }
        return out
    }

    /** Called after each successful poll, before state is saved. */
    fun remember(st: State, p: Prefs) {
        val now = System.currentTimeMillis() / 1000
        val cur = fromCalendar(Checker.myCalendar(st, p))
        val merged = merge(readStored(st), cur, now)
        val arr = JSONArray()
        for (m in merged) {
            val s = m.slot
            val o = JSONObject().put("key", s.key).put("course", s.course).put("title", s.title).put("day", s.day)
                .put("start", s.start).put("end", s.end).put("kind", s.kind.name).put("seen", m.seen)
            m.dates?.let { d -> o.put("dates", jarr(d.map { it.toString() })) }
            arr.put(o)
        }
        st.j.put("tt_auto", arr)
    }

    private fun isSkipped(s: Slot, p: Prefs): Boolean {
        val day = DAYS[s.day - 1].lowercase(Locale.ROOT)
        val code = s.course.lowercase(Locale.ROOT)
        return p.skipList.any { (c, d) -> code.contains(c) && (d.isEmpty() || d == day) }
    }

    // ------------------------------------------------------------ the user's own settings for the timetable
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("timetable", Context.MODE_PRIVATE)

    private fun readArr(ctx: Context, k: String): JSONArray =
        try { JSONArray(sp(ctx).getString(k, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

    private fun readObj(ctx: Context, k: String): JSONObject =
        try { JSONObject(sp(ctx).getString(k, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun manual(ctx: Context): List<Slot> = readArr(ctx, "manual").objs().mapNotNull { o ->
        val day = o.optInt("day")
        val s = o.optInt("start")
        val e = o.optInt("end")
        if (day !in 1..7 || e <= s) return@mapNotNull null
        val grp = o.str("grp")
        Slot("m:$grp:$day", o.str("course"), o.str("title"), day, s, e, SlotKind.parse(o.str("kind")), true, o.str("note"), grp,
            sem = o.str("sem").ifEmpty { legacySem(ctx) })
    }

    private fun writeManual(ctx: Context, list: List<Slot>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().put("grp", s.grp).put("course", s.course).put("title", s.title).put("day", s.day)
                    .put("start", s.start).put("end", s.end).put("kind", s.kind.name).put("note", s.note).put("sem", s.sem)
            )
        }
        sp(ctx).edit().putString("manual", arr.toString()).apply()
    }

    fun newGroup(): String = java.lang.Long.toString(System.currentTimeMillis(), 36)

    fun manualGroup(ctx: Context, grp: String): List<Slot> = manual(ctx).filter { it.grp == grp }

    /** Creates or replaces one own entry; it becomes one slot per chosen day. */
    fun saveManual(ctx: Context, grp: String, course: String, title: String, days: Collection<Int>,
                   start: Int, end: Int, kind: SlotKind, note: String) {
        val rest = manual(ctx).filter { it.grp != grp }
        // editing keeps the entry in its semester; a new entry belongs to the semester you are planning for
        val sem = manual(ctx).firstOrNull { it.grp == grp }?.sem ?: AcademicCalendar.planningLabel(ctx)
        val mine = days.sorted().map { d -> Slot("m:$grp:$d", course, title, d, start, end, kind, true, note, grp, sem = sem) }
        writeManual(ctx, rest + mine)
    }

    fun deleteManual(ctx: Context, grp: String) {
        writeManual(ctx, manual(ctx).filter { it.grp != grp })
    }

    // ---------------------------------------------------------- attendance links per course
    // User-pasted attendance links win over the auto-detected Spectrum attendance module page.

    /** The raw user-pasted attendance link(s) for a course; empty = none pasted. */
    fun userAttLinkRaw(ctx: Context, course: String): String =
        sp(ctx).getString("att:$course", "") ?: ""

    /** The attendance link shown for a course: user-pasted one first, else the auto one. */
    fun attendanceLink(ctx: Context, course: String, autoUrl: String): String =
        userAttLinkRaw(ctx, course).ifEmpty { autoUrl }

    /**
     * Auto-setup: write the LEC/TUT/LAB slots of one catalog Occ into the timetable.
     * Slots use grp "cat-CODE-kind-occ-i" so they are editable/deletable like any manual entry.
     * Also assigns a course colour. Returns true when slots were written (course in catalog).
     */
    fun autoSetup(ctx: Context, code: String, occ: String): Boolean {
        val course = Catalog.find(ctx, code) ?: return false
        manual(ctx).map { it.grp }.filter { it.startsWith("cat-${course.code}-") }.distinct()
            .forEach { deleteManual(ctx, it) }
        for ((kind, choice) in course.forOcc(occ)) {
            choice.parts.forEachIndexed { i, p ->
                saveManual(
                    ctx, "cat-${course.code}-${kind.tag}-$occ-$i", course.code, "${kind.label} Occ $occ",
                    listOf(p.day), p.start, p.end, kind, p.room,
                )
            }
        }
        if (colourIndexOrNull(ctx, course.code) == null) setColourIndex(ctx, course.code, nextColourIndex(ctx))
        return true
    }

    // ---------------------------------------------------------- user overrides for learned slots

    /** Meeting link override for a course (user pasted); empty = use the auto-detected one. */
    fun meetingLinkOf(ctx: Context, course: String): String =
        (sp(ctx).getString("mlink:$course", "") ?: "").ifEmpty {
            // links pasted in the Location dialog before v0.25.1 were only stored as the location
            locationOf(ctx, course).takeIf { it.startsWith("online:") }?.removePrefix("online:")?.trim() ?: ""
        }

    fun setMeetingLink(ctx: Context, course: String, url: String) {
        sp(ctx).edit().putString("mlink:$course", url.trim()).apply()
    }

    /** Time override for one learned slot: stored as "start,end" minutes, keyed by slot key. */
    fun timeOverride(ctx: Context, key: String): Pair<Int, Int>? {
        val v = sp(ctx).getString("ttime:$key", "") ?: return null
        val parts = v.split(",").map { it.trim().toIntOrNull() ?: -1 }
        return if (parts.size == 2 && parts[0] >= 0 && parts[1] > parts[0]) Pair(parts[0], parts[1]) else null
    }

    fun setTimeOverride(ctx: Context, key: String, start: Int, end: Int) {
        sp(ctx).edit().putString("ttime:$key", "$start,$end").apply()
    }

    fun clearTimeOverride(ctx: Context, key: String) {
        sp(ctx).edit().remove("ttime:$key").apply()
    }

    /** User-pasted attendance link(s) for a course (one per line allowed); empty string clears it. */
    fun setAttendanceLink(ctx: Context, course: String, links: String) {
        sp(ctx).edit().putString("att:$course", links.trim()).apply()
    }

    fun attendanceKey(course: String) = "att:$course"

    /** How attendance is taken for a course: "link" (Spectrum page), "manual" (lecturer, in class), "none", or "" = not set. */
    fun attMode(ctx: Context, course: String): String = sp(ctx).getString("attmode:$course", "") ?: ""

    fun setAttMode(ctx: Context, course: String, mode: String) {
        sp(ctx).edit().putString("attmode:$course", mode).apply()
    }

    // ---------------------------------------------------------- location per course
    // "online:<link>" = online class with a meeting link; "room:<text>" = physical room; "" = not set.

    /** The location line shown for a course: user-set first, else the catalog room. */
    fun locationOf(ctx: Context, course: String): String =
        sp(ctx).getString("loc:$course", "") ?: ""

    /** Sets the user location override; empty text clears it. */
    fun setLocation(ctx: Context, course: String, value: String) {
        sp(ctx).edit().putString("loc:$course", value.trim()).apply()
    }

    private fun hiddenKeys(ctx: Context): MutableSet<String> = LinkedHashSet(readArr(ctx, "hidden").strs())

    fun hiddenCount(ctx: Context): Int = hiddenKeys(ctx).size

    fun hide(ctx: Context, key: String) {
        val h = hiddenKeys(ctx)
        h.add(key)
        sp(ctx).edit().putString("hidden", jarr(h).toString()).apply()
    }

    /** Key of the class removed a moment ago, so the Timetable screen can offer Undo once. */
    @Volatile var justHidden: String? = null

    fun unhide(ctx: Context, key: String) {
        val h = hiddenKeys(ctx)
        h.remove(key)
        sp(ctx).edit().putString("hidden", jarr(h).toString()).apply()
    }

    fun restoreHidden(ctx: Context) {
        sp(ctx).edit().remove("hidden").apply()
    }

    fun setKind(ctx: Context, key: String, kind: SlotKind) {
        val o = readObj(ctx, "kinds")
        o.put(key, kind.name)
        sp(ctx).edit().putString("kinds", o.toString()).apply()
    }

    // ------------------------------------------------------------ colours per course
    private fun colourMap(ctx: Context): LinkedHashMap<String, Int> {
        val o = readObj(ctx, "colours")
        val m = LinkedHashMap<String, Int>()
        for (k in o.keys()) m[k] = o.optInt(k)
        return m
    }

    private fun writeColours(ctx: Context, m: Map<String, Int>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        sp(ctx).edit().putString("colours", o.toString()).apply()
    }

    private fun nextFree(used: Collection<Int>): Int {
        for (i in 0 until PALETTE_SIZE) if (i !in used) return i
        return used.size % PALETTE_SIZE
    }

    /** Palette index already given to this course, or null. */
    fun colourIndexOrNull(ctx: Context, course: String): Int? = colourMap(ctx)[course]

    /** Index a new course would get next. */
    fun nextColourIndex(ctx: Context): Int = nextFree(colourMap(ctx).values)

    fun setColourIndex(ctx: Context, course: String, idx: Int) {
        val m = colourMap(ctx)
        m[course] = idx
        writeColours(ctx, m)
    }

    /** One colour per course. Courses without a colour yet get the next unused one (in code order) and keep it. */
    fun colours(ctx: Context, courses: Collection<String>): Map<String, Int> {
        val m = colourMap(ctx)
        var changed = false
        for (c in courses.toSortedSet()) {
            if (c !in m) { m[c] = nextFree(m.values); changed = true }
        }
        if (changed) writeColours(ctx, m)
        val out = HashMap<String, Int>()
        for (c in courses) out[c] = colorOf(m[c] ?: 0)
        return out
    }

    // ------------------------------------------------------------ settings follow a moved class

    /**
     * When Spectrum moves a learned class (its old time is gone), your type, time and hide settings for the
     * old time move to the class now nearest it: same course and day, start within 2 hours.
     */
    private fun migrateOrphans(ctx: Context, live: List<Slot>) {
        val liveKeys = live.map { it.key }.toSet()
        val hidden = hiddenKeys(ctx)
        val kinds = readObj(ctx, "kinds")
        val times = sp(ctx).all.keys.filter { it.startsWith("ttime:") }.map { it.removePrefix("ttime:") }.toMutableSet()
        fun hasSettings(k: String) = k in hidden || kinds.has(k) || k in times
        val orphans = (hidden + kinds.keys().asSequence().toList() + times).filter { it !in liveKeys }.toMutableSet()
        if (orphans.isEmpty()) return
        val ed = sp(ctx).edit()
        var changed = false
        for (s in live) {
            if (hasSettings(s.key)) continue
            val best = orphans.mapNotNull { k ->
                val f = k.split("|")
                val day = f.getOrNull(1)?.toIntOrNull()
                val start = f.getOrNull(2)?.toIntOrNull()
                if (f.size == 4 && f[0] == s.course && day == s.day && start != null && Math.abs(start - s.start) <= 120)
                    Pair(k, Math.abs(start - s.start)) else null
            }.minByOrNull { it.second }?.first ?: continue
            orphans.remove(best)
            if (hidden.remove(best)) hidden.add(s.key)
            if (kinds.has(best)) { kinds.put(s.key, kinds.optString(best)); kinds.remove(best) }
            if (times.remove(best)) {
                sp(ctx).getString("ttime:$best", null)?.let { ed.putString("ttime:${s.key}", it) }
                ed.remove("ttime:$best")
                times.add(s.key)
            }
            changed = true
        }
        if (changed) ed.putString("hidden", jarr(hidden).toString()).putString("kinds", kinds.toString()).apply()
    }

    // ------------------------------------------------------------ everything for the screen
    /**
     * Learned slots (minus hidden / ignored ones, with your type overrides) plus your own entries, by day and time.
     * One-offs (date set) are kept from this week on; use [on] for what happens on a given date.
     */
    fun all(ctx: Context, sem: String? = AcademicCalendar.planningLabel(ctx)): List<Slot> {
        val p = Prefs(ctx)
        val st = State(ctx)
        val now = System.currentTimeMillis() / 1000
        val cur = fromCalendar(Checker.myCalendar(st, p))
        val weekStart = LocalDate.now(zone()).with(DayOfWeek.MONDAY)
        val stored = merge(readStored(st), cur, now)
        migrateOrphans(ctx, stored.map { it.slot })
        val merged = stored.mapNotNull { m ->
            // a learned slot belongs to the semester it was last seen in
            val lastSeen = m.dates?.maxOrNull() ?: localDt(m.seen).toLocalDate()
            val tagged = m.slot.copy(sem = AcademicCalendar.labelFor(ctx, lastSeen) ?: "")
            if (weekly(m.dates)) tagged
            else m.dates?.maxOrNull()?.takeIf { !it.isBefore(weekStart) }?.let { tagged.copy(date = it) }
        }.filter { inSem(it, sem) }
        val hidden = hiddenKeys(ctx)
        val overrides = readObj(ctx, "kinds")
        val auto = merged.filter { it.key !in hidden && !isSkipped(it, p) }.map { s ->
            var out = if (overrides.has(s.key)) s.copy(kind = SlotKind.parse(overrides.optString(s.key))) else s
            timeOverride(ctx, s.key)?.let { (t0, t1) -> out = out.copy(start = t0, end = t1) }
            out
        }
        // The same class must not show twice. Same course + day + overlapping time = one class; the one kept is
        // the Spectrum one, then one picked from the UM timetable list (Occ), then a hand-made entry.
        // Only weekly Spectrum slots hide your entries for good; a one-off replaces them on its date (see [on]).
        val weeklyAuto = auto.filter { it.date == null }
        val own = ArrayList<Slot>()
        for (m in manual(ctx).filter { inSem(it, sem) }.sortedBy { if (it.grp.startsWith("cat-")) 0 else 1 }) {
            if (weeklyAuto.any { overlaps(it, m) }) continue
            if (own.any { it.grp != m.grp && overlaps(it, m) }) continue
            own.add(m)
        }
        return (auto + own).sortedWith(compareBy<Slot>({ it.day }, { it.start }, { it.course }))
    }

    /** The classes on [date]: weekly slots plus that date's one-offs; a one-off replaces the weekly classes it overlaps. */
    fun on(slots: List<Slot>, date: LocalDate): List<Slot> {
        val day = slots.filter { it.occursOn(date) }
        val oneOffs = day.filter { it.date != null }
        return day.filter { s -> s.date != null || oneOffs.none { overlaps(it, s) } }.sortedBy { it.start }
    }

    /** [sem] null = every semester; an untagged slot shows in any semester. */
    fun inSem(s: Slot, sem: String?): Boolean = sem == null || s.sem.isEmpty() || s.sem == sem

    /**
     * Entries made before v0.26 had no semester: they belong to the one being planned when this version first ran
     * (remembered, so they do not jump to the next semester later).
     */
    private fun legacySem(ctx: Context): String {
        val k = "legacySem"
        sp(ctx).getString(k, null)?.let { return it }
        val v = AcademicCalendar.planningLabel(ctx)
        sp(ctx).edit().putString(k, v).apply()
        return v
    }

    /** The date of weekday [day] (1 = Monday) in the current week. */
    fun dateThisWeek(day: Int): LocalDate = LocalDate.now(zone()).with(DayOfWeek.MONDAY).plusDays(day - 1L)
}
