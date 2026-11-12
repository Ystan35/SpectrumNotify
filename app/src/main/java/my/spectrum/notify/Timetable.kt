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
    init {
        // Saturday 7 Nov: Monday's class is 9 Nov; Saturday's own class is today
        LocalDate.of(2026, 11, 7).let { sat -> check(thisWeek(1, sat) == LocalDate.of(2026, 11, 9) && thisWeek(6, sat) == sat) }
    }

    private const val KEEP_SECS = 42L * 86400

    val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val DAY_FULL = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    // ------------------------------------------------------------ colours
    private val PALETTE_DARK = intArrayOf(
        0xFF7A9BFF.toInt(), 0xFF4ADE9A.toInt(), 0xFFFFC857.toInt(), 0xFFFF7A7A.toInt(), 0xFFB794FF.toInt(),
        0xFF5CD6E6.toInt(), 0xFFFFA45C.toInt(), 0xFFF48FB1.toInt(), 0xFFA3E635.toInt(), 0xFF94A3B8.toInt(),
    )
    private val PALETTE_LIGHT = intArrayOf(
        // every colour reads at 4.5:1 or more on the light background (course names are drawn in it)
        0xFF2F55E4.toInt(), 0xFF0E7553.toInt(), 0xFF8A5E00.toInt(), 0xFFC93636.toInt(), 0xFF6B46C8.toInt(),
        0xFF0A7584.toInt(), 0xFFA85008.toInt(), 0xFFC2185B.toInt(), 0xFF3F6E0B.toInt(), 0xFF475569.toInt(),
    )
    val PALETTE_SIZE: Int get() = PALETTE_DARK.size

    /**
     * The line under a course code: the course's title ("BASIC ENTREPRENEURSHIP ENCULTURATION"), else the UM catalog's
     * name for it, else the event name without Spectrum's clutter ("Attendance OCC29 - Audi FSSS (10 - 12pm) (Group
     * GROUP 29 2026/2027_1)" → "OCC29 - Audi FSSS"). Ticket SN-CKHNR: a missing fullname showed the raw event name
     * ("Lecture Occ 2 (DR NAZEAN)") where other cards showed a title.
     */
    fun classTitle(code: String, full: String, eventName: String, catalogName: String = ""): String {
        val title = full.trim().removePrefix(code).trim(' ', '-', '–', ':').takeIf { it.isNotEmpty() && !it.equals(code, true) }
            ?: catalogName.trim().takeIf { it.isNotEmpty() }
        // course titles are shown in capitals, as UM writes them ("BASIC ENTREPRENEURSHIP ENCULTURATION")
        if (title?.isNotEmpty() == true) return title.uppercase()
        if (eventName.equals(code, true)) return ""
        return eventName
            .replace(Regex("""^\s*attendance\b\s*[-:]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\([^)]*(?:\d{4}/\d{4}|\bgroup\b|\d\s*(?:am|pm))[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim(' ', '-', '–', ':')
    }

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

    /**
     * Auto-setup: write the LEC/TUT/LAB slots of one catalog Occ into the timetable.
     * Slots use grp "cat-CODE-kind-occ-i" so they are editable/deletable like any manual entry.
     * Also assigns a course colour. Returns true when slots were written (course in catalog).
     */
    /** Removes the classes added from the UM list for [code] (its Occ was cleared). Your own entries stay. */
    fun clearCatalog(ctx: Context, code: String) {
        val c = Catalog.find(ctx, code)?.code ?: code
        manual(ctx).map { it.grp }.filter { it.startsWith("cat-$c-") }.distinct().forEach { deleteManual(ctx, it) }
    }

    /**
     * Makes the classes picked from the UM list match your saved Occ ("CODE:occN"). Settings used to change the Occ
     * without swapping the classes, so the timetable kept the old Occ's times and rooms (WIX1001 showed DK2 while
     * Occ 2 says DK1). Only courses already set up from the UM list are touched. Returns the courses it rebuilt.
     */
    fun syncOccs(ctx: Context): List<String> {
        val want = Regex("""([A-Z]{3,4}\d{4}):occ([0-9]+[a-z]?)""", RegexOption.IGNORE_CASE)
            .findAll(Prefs(ctx).myGroups).associate { it.groupValues[1].uppercase(Locale.ROOT) to it.groupValues[2] }
        val have = manual(ctx).filter { it.grp.startsWith("cat-") }
            .associate { it.course.uppercase(Locale.ROOT) to (it.grp.split('-').getOrNull(3) ?: "") }
        return want.filter { (code, occ) -> have[code] != null && have[code] != occ && autoSetup(ctx, code, occ) }.keys.toList()
    }

    /**
     * The ONE way to save a course's Occ ("" = not set): the group, "check the class times", "confirmed", the UM-list
     * classes, alarms and widget. Settings, Courses, the UM list and the Home dialog used to each set a different mix,
     * so an Occ picked in Settings stayed "unconfirmed" and was asked about again. False = the course is not in the UM list. [notify] false: the caller saves several and calls [changed] once.
     */
    fun setOcc(ctx: Context, code: String, occ: String, notify: Boolean = true): Boolean {
        val pp = Prefs(ctx)
        // picking an Occ is an explicit choice: it also takes the "off" switch off, as it always did in effect
        val keep = pp.myGroups.split(",").map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("off", true) && !it.startsWith("$code:", true) }
        pp.myGroups = (if (occ.isEmpty()) keep else keep + "$code:occ$occ").joinToString(", ")
        pp.setOccNeedsCheck(code, occ.isNotEmpty())
        pp.setOccConfirmed(code.uppercase(), occ.isNotEmpty())
        val inList = if (occ.isEmpty()) { clearCatalog(ctx, code); true } else autoSetup(ctx, code, occ)
        if (notify) changed(ctx)
        return inList
    }

    fun autoSetup(ctx: Context, code: String, occ: String): Boolean {
        val course = Catalog.find(ctx, code) ?: return false
        // the previous Occ's classes go first, so changing Occ never leaves two sets
        clearCatalog(ctx, course.code)
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

    /**
     * Courses back in person (e.g. online classes over after the haze): their meeting links are ignored everywhere
     * (no Join class, no link in alerts) and the room is shown. Stored as "inperson:CODE" = when you switched (epoch s).
     */
    fun inPerson(ctx: Context, course: String): Boolean =
        course.isNotEmpty() && sp(ctx).contains("inperson:" + course.uppercase(Locale.ROOT))

    fun inPersonSince(ctx: Context, course: String): Long =
        sp(ctx).getLong("inperson:" + course.uppercase(Locale.ROOT), 0L)

    fun setInPerson(ctx: Context, courses: Collection<String>, on: Boolean) {
        val now = System.currentTimeMillis() / 1000
        val e = sp(ctx).edit()
        for (c in courses) {
            val k = "inperson:" + c.uppercase(Locale.ROOT)
            if (on) { if (!sp(ctx).contains(k)) e.putLong(k, now) } else e.remove(k)
        }
        e.apply()
    }

    /** The course-wide choices, in the order the dropdowns show them. */
    val COURSE_MODES = listOf("" to "As timetable", "online" to "Online", "inperson" to "In person")

    /** What each course-wide choice does, in words the dropdowns show under them. */
    const val COURSE_MODES_HELP = "As timetable: each class shows the room or Online that the UM timetable lists. " +
        "Online: every class of the course is online, with Join class. In person: every class is in its room, with no Join class."

    /**
     * The course-wide choice (ticket SN-HCTRV: "Online" used to be the default too, so it could not mean online):
     * "" = as the timetable says, "online" = every class online, "inperson" = every class in its room.
     * Online is kept as your location "online:" (which every screen already reads as Online), so nothing new is stored.
     */
    fun courseMode(ctx: Context, course: String): String = when {
        inPerson(ctx, course) -> "inperson"
        locationOf(ctx, course).startsWith("online:") -> "online"
        else -> ""
    }

    fun setCourseMode(ctx: Context, course: String, mode: String) {
        val loc = locationOf(ctx, course)
        // a meeting link pasted before v0.25.1 lives in the location: keep it as the course's link before clearing it
        if (mode != "online" && loc.startsWith("online:")) {
            val legacy = loc.removePrefix("online:").trim()
            if (legacy.isNotEmpty() && (sp(ctx).getString("mlink:$course", "") ?: "").isEmpty()) setMeetingLink(ctx, course, legacy)
            setLocation(ctx, course, "")
        }
        setInPerson(ctx, listOf(course), mode == "inperson")
        if (mode == "online" && !loc.startsWith("online:")) setLocation(ctx, course, "online:")
    }

    /**
     * Online or in person for one class (slot [key]), since courses switch week by week and a tutorial can be in person
     * while the lecture stays online: "online", "inperson" or "" = as usual. A choice for one [date] beats the every-week one.
     */
    fun classMode(ctx: Context, key: String, date: LocalDate): String =
        sp(ctx).getString("cmode:$key:$date", null) ?: sp(ctx).getString("cmode:$key", "") ?: ""

    /**
     * The key every screen uses for "this class at [ts]": its timetable slot when it matches one, else the course and the
     * class's own start. The hero card switch WRITES it and venueAt / onlineAt READ it, so they can never disagree (ticket
     * SN-JEATK: the switch wrote the UM slot at 12:00 while the card, a 10:00-14:00 Spectrum class, read nothing).
     */
    fun modeKeyAt(ctx: Context, code: String, ts: Long): String =
        slotAt(ctx, code, ts)?.key ?: localDt(ts).let { "ev:" + code.uppercase(Locale.ROOT) + "|" + (it.hour * 60 + it.minute) }

    /** This class is set Online for its own day (the Home "Mark online" switch is per week), whatever the course is set to. */
    fun onlineAt(ctx: Context, code: String, ts: Long): Boolean =
        classMode(ctx, modeKeyAt(ctx, code, ts), localDt(ts).toLocalDate()) == "online"

    /** Saves [mode] for [date] only, or for every week when [date] is null; "" clears that choice. */
    fun setClassMode(ctx: Context, key: String, date: LocalDate?, mode: String) {
        val k = if (date == null) "cmode:$key" else "cmode:$key:$date"
        sp(ctx).edit().apply { if (mode.isEmpty()) remove(k) else putString(k, mode) }.apply()
    }

    /**
     * The next date of weekday [day] (1 = Mon), today included: the class a "this week" choice is about. The
     * Monday-to-Sunday week pointed at days already past at the weekend (Saturday showed Monday 2 Nov, not 9 Nov).
     */
    fun thisWeek(day: Int, today: LocalDate = LocalDate.now(zone())): LocalDate =
        today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.of(day)))

    /**
     * Your meeting link for a course's lectures ([preferLec] true) or tutorials (false), else the one for the whole
     * course (what older versions saved); empty = use the auto-detected one.
     */
    fun meetingLinkOf(ctx: Context, course: String, preferLec: Boolean? = null): String {
        val k = when (preferLec) { true -> "lec"; false -> "tut"; null -> "" }
        val own = if (k.isEmpty()) "" else sp(ctx).getString("mlink:$course:$k", "") ?: ""
        return own.ifEmpty { sp(ctx).getString("mlink:$course", "") ?: "" }.ifEmpty {
            // links pasted in the Location dialog before v0.25.1 were only stored as the location
            locationOf(ctx, course).takeIf { it.startsWith("online:") }?.removePrefix("online:")?.trim() ?: ""
        }
    }

    /**
     * Saves the meeting link for [kind] ("lec", "tut", or "" for every class of the course); "" clears it.
     * Same rules as [setAttFor]: clearing one kind while a whole-course link exists keeps that link for the other kind.
     */
    fun setMeetingLink(ctx: Context, course: String, url: String, kind: String = "") {
        val e = sp(ctx).edit()
        val key = if (kind.isEmpty()) "mlink:$course" else "mlink:$course:$kind"
        if (url.isNotBlank()) e.putString(key, url.trim())
        else if (kind.isEmpty()) { e.remove(key); e.remove("mlink:$course:lec"); e.remove("mlink:$course:tut") }
        else {
            e.remove(key)
            val wide = sp(ctx).getString("mlink:$course", "") ?: ""
            if (wide.isNotEmpty()) {
                val other = if (kind == "lec") "tut" else "lec"
                if ((sp(ctx).getString("mlink:$course:$other", "") ?: "").isEmpty()) e.putString("mlink:$course:$other", wide)
                e.remove("mlink:$course")
            }
        }
        e.apply()
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
        if (links.trim() == userAttLinkRaw(ctx, course)) return   // unchanged: keep a pin made in the picker
        // a typed/pasted link is only a fallback; the picker (setAttFor) is what pins one (ticket SN-UDTQV)
        sp(ctx).edit().putString("att:$course", links.trim()).remove("attpin:$course").apply()
    }

    /** Call after ANY save of a class or course setting: moves the alarms and redraws the widget, so no screen is left stale. */
    fun changed(ctx: Context) {
        Scheduler.scheduleAll(ctx)
        try { NextClassWidget.updateAll(ctx) } catch (_: Exception) { }
    }

    /**
     * Once, after the update that introduced pins: a whole-course link that is one of the attendance pages Spectrum lists
     * for the course was picked in the picker, so it stays a pin. Anything else (a pasted address) stays a fallback.
     * Waits for the first sync when no page is known yet.
     */
    fun migrateAttPins(ctx: Context) {
        val s = sp(ctx)
        if (s.getBoolean("attPinMigrated", false)) return
        val wide = s.all.filter { (k, v) -> k.startsWith("att:") && k.count { it == ':' } == 1 && v is String && v.isNotBlank() }
        if (wide.isNotEmpty()) {
            val known = HashSet<Long>()
            val labels = State.readOnly(ctx).j.optJSONObject("attlabels")
            labels?.keys()?.forEach { k -> k.substringAfter(':').toLongOrNull()?.let { known.add(it) } }
            if (known.isEmpty()) return
            val e = s.edit()
            for ((k, v) in wide) {
                val id = Links.attendanceCmid((v as String).lineSequence().firstOrNull { it.isNotBlank() } ?: "")
                if (id != null && id in known) e.putBoolean("attpin:" + k.removePrefix("att:"), true)
            }
            e.apply()
        }
        s.edit().putBoolean("attPinMigrated", true).apply()
    }

    internal fun attPinned(ctx: Context, course: String) = sp(ctx).getBoolean("attpin:$course", false)

    /**
     * The attendance link you chose for a course's lectures ([preferLec] true) or tutorials (false), else the one for
     * the whole course (also what older versions saved when you pasted a link); "" = the app chooses.
     */
    fun attFor(ctx: Context, course: String, preferLec: Boolean?): String {
        val k = when (preferLec) { true -> "lec"; false -> "tut"; null -> "" }
        val own = if (k.isEmpty()) "" else sp(ctx).getString("att:$course:$k", "") ?: ""
        return Links.pickAttendance(own, userAttLinkRaw(ctx, course), attPinned(ctx, course)) {
            Links.attendanceUrl(ctx, Links.courseIdForCode(ctx, course), preferLec)
        }
    }

    /**
     * Saves the attendance link for [kind] ("lec", "tut", or "" for every class of the course); "" clears it.
     * Clearing one kind while a whole-course link exists keeps that link for the other kind only.
     */
    fun setAttFor(ctx: Context, course: String, kind: String, url: String) {
        val e = sp(ctx).edit()
        val key = if (kind.isEmpty()) "att:$course" else "att:$course:$kind"
        if (url.isNotEmpty()) { e.putString(key, url.trim()); if (kind.isEmpty()) e.putBoolean("attpin:$course", true) }
        else if (kind.isEmpty()) { e.remove(key); e.remove("attpin:$course"); e.remove("att:$course:lec"); e.remove("att:$course:tut") }
        else {
            e.remove(key)
            val wide = if (attPinned(ctx, course)) userAttLinkRaw(ctx, course) else ""
            if (wide.isNotEmpty()) {
                val other = if (kind == "lec") "tut" else "lec"
                if ((sp(ctx).getString("att:$course:$other", "") ?: "").isEmpty()) e.putString("att:$course:$other", wide)
                e.remove("att:$course"); e.remove("attpin:$course")
            }
        }
        e.apply()
    }

    /** How attendance is taken for a course: "link" (Spectrum page), "manual" (lecturer, in class), "none", or "" = not set. */
    fun attMode(ctx: Context, course: String): String = sp(ctx).getString("attmode:$course", "") ?: ""

    /** Attendance is taken in class by the lecturer: no Attendance button anywhere (nothing to open). */
    fun attByLecturer(ctx: Context, course: String): Boolean =
        attMode(ctx, course) == "manual" || attMode(ctx, course.uppercase(Locale.ROOT)) == "manual"

    fun setAttMode(ctx: Context, course: String, mode: String) {
        sp(ctx).edit().putString("attmode:$course", mode).apply()
    }

    // ---------------------------------------------------------- location per course
    // "online:<link>" = online class with a meeting link; "room:<text>" = physical room; "" = not set.

    /** The shuttle place (bus.json id) you chose for a course's classes; "" = use the guess from its code. */
    fun busStop(ctx: Context, course: String): String =
        sp(ctx).getString("busstop:" + course.uppercase(Locale.ROOT), "") ?: ""

    fun setBusStop(ctx: Context, course: String, id: String) {
        sp(ctx).edit().putString("busstop:" + course.uppercase(Locale.ROOT), id).apply()
    }

    /** The location line shown for a course: user-set first, else the catalog room. */
    fun locationOf(ctx: Context, course: String): String =
        sp(ctx).getString("loc:$course", "") ?: ""

    /**
     * The room of [course]'s class on [day] (1 = Mon) nearest [startMin]: the UM timetable's room first (kept as the
     * note of the classes picked from it), else a room typed for the whole course. The typed one used to win, so a
     * room typed once showed on every class of the course.
     */
    fun roomAt(ctx: Context, course: String, day: Int, startMin: Int): String {
        val um = all(ctx).filter { it.course.equals(course, true) && it.grp.startsWith("cat-") && it.day == day }
            .minByOrNull { kotlin.math.abs(it.start - startMin) }?.let { umRoom(ctx, it) }?.takeIf { it.isNotEmpty() }
        // the UM timetable's "ONLINE" is not a room: no room line rather than "In person · ONLINE"
        if (um != null) return if (isOnline(um)) "" else um
        return locationOf(ctx, course).takeIf { it.startsWith("room:") }?.removePrefix("room:")?.trim().orEmpty()
    }

    /**
     * The room of a class picked from the UM timetable, read from the UM list now for your own Occ (its grp is
     * "cat-CODE-KIND-OCC-i"). The room copied into the class when you picked the Occ goes stale when the list is
     * updated (WIX1002's tutorial showed MM3 after the list said MM4), so the copy is only the fallback.
     */
    fun umRoom(ctx: Context, s: Slot): String {
        val occ = s.grp.split('-').getOrNull(3) ?: return s.note
        return Catalog.find(ctx, s.course)?.choices(s.kind)?.firstOrNull { it.occ == occ }
            ?.parts?.filter { it.day == s.day }?.map { it.room }?.filter { it.isNotEmpty() }?.distinct()?.joinToString(", ")
            ?.takeIf { it.isNotEmpty() } ?: s.note
    }

    /** The UM catalog choice this slot matches (room, lecturer), or null. */
    fun choiceOf(ctx: Context, s: Slot?): Catalog.Choice? {
        if (s == null) return null
        val choices = Catalog.find(ctx, s.course)?.choices(s.kind) ?: return null
        // your own Occ (the picked class's, else Settings' "CODE:occN"); matching by time alone found another Occ
        // meeting at the same hour, with its room and lecturer (WIX1002's tutorial showed MM3 and the wrong lecturer)
        val occ = occOf(s).ifEmpty {
            Regex(Regex.escape(s.course) + """:occ([0-9]+[a-z]?)""", RegexOption.IGNORE_CASE).find(Prefs(ctx).myGroups)?.groupValues?.get(1).orEmpty()
        }
        return if (occ.isNotEmpty()) choices.firstOrNull { it.occ == occ }
        else choices.firstOrNull { ch -> ch.parts.any { it.day == s.day && it.start == s.start } }
    }

    /** This class's room: the UM timetable's (its catalog choice, else the UM class's note), else the typed course room. */
    fun roomOf(ctx: Context, s: Slot): String =
        (if (s.grp.startsWith("cat-")) umRoom(ctx, s)
        else choiceOf(ctx, s)?.parts?.filter { it.day == s.day }?.map { it.room }?.filter { it.isNotEmpty() }?.distinct()?.joinToString(", ").orEmpty())
            .takeUnless { isOnline(it) }.orEmpty()
            .ifEmpty { roomAt(ctx, s.course, s.day, s.start) }

    /** Your online / in-person choice for this class this week ("" = as usual). */
    fun modeOf(ctx: Context, s: Slot): String = classMode(ctx, s.key, thisWeek(s.day))

    /** The UM timetable lists this class's room as "ONLINE". */
    fun umOnline(ctx: Context, s: Slot): Boolean =
        if (s.grp.startsWith("cat-")) isOnline(umRoom(ctx, s))
        else choiceOf(ctx, s)?.parts?.any { it.day == s.day && isOnline(it.room) } == true

    /** Where this class is this week: "Online", the room, "In person", or "" when not known (the week view's rule). */
    fun venueOf(ctx: Context, s: Slot, date: LocalDate = thisWeek(s.day)): String {
        val room = roomOf(ctx, s)
        return when (classMode(ctx, s.key, date)) {
            "online" -> "Online"
            "inperson" -> room.ifEmpty { "In person" }
            else -> when {
                inPerson(ctx, s.course) -> room.ifEmpty { "In person" }
                locationOf(ctx, s.course).startsWith("online:") || umOnline(ctx, s) -> "Online"
                else -> room
            }
        }
    }

    /**
     * Where [course]'s class starting at [ts] is: the ONE rule every screen and alert uses. Only trusted data: your
     * choice for that class on that date, the course set In person, your saved location, then the UM timetable
     * (ONLINE or a room). A meeting link found on Spectrum does not make a class online. "" = not known.
     */
    fun venueAt(ctx: Context, course: String, ts: Long): String {
        val t = localDt(ts)
        val s = slotAt(ctx, course, ts)
        if (s != null) return venueOf(ctx, s, t.toLocalDate())
        // not on your timetable: your choice for this class on this date first
        when (classMode(ctx, modeKeyAt(ctx, course, ts), t.toLocalDate())) {
            "online" -> return "Online"
            "inperson" -> return roomAt(ctx, course, ts).ifEmpty { "In person" }
        }
        // then the course setting and your location
        val loc = locationOf(ctx, course)
        return when {
            inPerson(ctx, course) -> roomAt(ctx, course, ts).ifEmpty { "In person" }
            loc.startsWith("online:") -> "Online"
            else -> roomAt(ctx, course, ts)
        }
    }

    /** The class on your timetable for [course] starting nearest [ts] (within an hour, same weekday), or null. */
    fun slotAt(ctx: Context, course: String, ts: Long): Slot? {
        val t = localDt(ts)
        val min = t.hour * 60 + t.minute
        return all(ctx).filter { it.course.equals(course, true) && it.day == t.dayOfWeek.value && kotlin.math.abs(it.start - min) <= 60 }
            .minByOrNull { kotlin.math.abs(it.start - min) }
    }

    /** Lecture (true), tutorial (false) or not known (null) for the class at [ts], from your timetable. */
    fun preferLecAt(ctx: Context, course: String, ts: Long): Boolean? = when (slotAt(ctx, course, ts)?.kind) {
        SlotKind.LECTURE -> true
        SlotKind.TUTORIAL -> false
        else -> null
    }

    /** Which Occ this slot belongs to: from the picker group id (cat-CODE-kind-N), else empty. */
    fun occOf(s: Slot): String =
        if (s.grp.startsWith("cat-")) s.grp.split('-').getOrNull(3) ?: "" else ""

    /** The UM timetable writes "ONLINE" where a room would be. */
    fun isOnline(room: String) = room.trim().equals("ONLINE", true)

    /** [roomAt] for a class starting at epoch second [ts]. */
    fun roomAt(ctx: Context, course: String, ts: Long): String =
        localDt(ts).let { roomAt(ctx, course, it.dayOfWeek.value, it.hour * 60 + it.minute) }

    /** Sets the user location override; empty text clears it. */
    fun setLocation(ctx: Context, course: String, value: String) {
        sp(ctx).edit().putString("loc:$course", value.trim()).apply()
    }

    private fun hiddenKeys(ctx: Context): MutableSet<String> = LinkedHashSet(readArr(ctx, "hidden").strs())

    fun hiddenCount(ctx: Context): Int = hiddenKeys(ctx).size

    /** Keys of the learned classes you removed ("Remove from my timetable"). */
    fun removedKeys(ctx: Context): Set<String> = hiddenKeys(ctx)

    /** The learned-slot key of a Spectrum class event ("WIX1003|1|600|720"), from Spectrum's own times as [fromCalendar] keys it. */
    fun slotKeyOf(e: JSONObject): String? {
        val ts = e.optLong("timestart")
        if (ts <= 0L) return null
        val z = localDt(ts)
        val dur = e.optLong("timeduration")
        val start = z.hour * 60 + z.minute
        val end = minOf(24 * 60, start + (if (dur > 0L) (dur / 60).toInt() else 60))
        return "${courseCode(e.optJSONObject("course"))}|${z.dayOfWeek.value}|$start|$end"
    }

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
        return DataStamp.get("tt:$sem", ctx) { computeAll(ctx, sem) }
    }

    private fun computeAll(ctx: Context, sem: String?): List<Slot> {
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
        // a course picked from the UM timetable follows it alone: Spectrum's attendance sessions name other Occs too,
        // and their one-offs replaced the real class on that date (ticket SN-H4XVU)
        val umCourses = manual(ctx).filter { it.grp.startsWith("cat-") && inSem(it, sem) }.map { it.course.uppercase(Locale.ROOT) }.toSet()
        val auto = merged.filter { it.key !in hidden && !isSkipped(it, p) && it.course.uppercase(Locale.ROOT) !in umCourses }.map { s ->
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
