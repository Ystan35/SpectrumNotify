package my.spectrum.notify

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/** Port of the Python bot's checks. Everything runs on the device with your own token. */
object Checker {
    private const val TAG = "SpectrumNotify"

    // "Occ 2", "T3", "Group 1", "Tutorial 4", and lists after a word prefix: "OCC 1, 1M, 3, 6" -> occ1 occ1m occ3 occ6
    private val GROUP_RE = Regex(
        """\b(?:(occ\.?|grou?p|tutorial)\s*|([kt]))(\d+[a-z]?)\b((?:\s*(?:,|&|/|\band\b)\s*\d+[a-z]?\b)*)""",
        RegexOption.IGNORE_CASE,
    )
    private val LIST_ITEM_RE = Regex("""\d+[a-z]?""", RegexOption.IGNORE_CASE)
    // attendance titles: "K1 (L) Attendance", "( L ) @ Bilik Kuliah", "T2 (T&L)", "Attendance (Tutorials, OCC 2)"
    private val LEC_RE = Regex("""lecture|kuliah|\bl\b|\bk\d""", RegexOption.IGNORE_CASE)
    private val TUT_RE = Regex("""tutor|kelas|class|\bt\b|\bt\d""", RegexOption.IGNORE_CASE)
    private val MEETING_RE = Regex(
        """https?://(?:[\w.-]*teams\.microsoft\.com|teams\.live\.com|[\w.-]*zoom\.us|[\w.-]*zoomgov\.com""" +
            """|meet\.google\.com|[\w.-]*webex\.com|[\w.-]*bigbluebutton[\w.-]*|whereby\.com|[\w.-]*jit\.si)""" +
            """/[^\s"'<>)\]]*""",
        RegexOption.IGNORE_CASE,
    )
    private val ATT_RE = Regex("attend|kehadiran", RegexOption.IGNORE_CASE)
    private val MEETING_MODS = setOf(
        "bigbluebuttonbn", "zoom", "teams", "msteams", "webexactivity", "googlemeet", "jitsi", "adobeconnect", "onlinemeeting",
    )

    data class Link(val url: String, val label: String, val ts: Long, val tokens: Set<String>)
    private class Cand(val score: Int, val isMeeting: Boolean, val ts: Long, val url: String, val label: String)

    // ------------------------------------------------------------ link helpers
    fun groupTokens(text: String?): Set<String> {
        val out = LinkedHashSet<String>()
        for (m in GROUP_RE.findAll(unesc(text))) {
            val word = m.groups[1]?.value
            val prefix = (word ?: m.groupValues[2]).replace(".", "").lowercase()
            out.add(prefix + m.groupValues[3].lowercase())
            if (word != null) for (n in LIST_ITEM_RE.findAll(m.groupValues[4])) out.add(prefix + n.value.lowercase())
        }
        return out
    }

    /** "lec", "tut", "both" (e.g. "T&L") or "other", from an attendance title. */
    fun attKind(name: String): String {
        val lec = LEC_RE.containsMatchIn(name)
        val tut = TUT_RE.containsMatchIn(name)
        return when { lec && tut -> "both"; lec -> "lec"; tut -> "tut"; else -> "other" }
    }

    /** preferLec for the link resolver: true = lecture, false = tutorial, null = unknown or both. */
    fun preferLecOf(name: String): Boolean? = when (attKind(name)) { "lec" -> true; "tut" -> false; else -> null }

    private fun linkType(url: String): String {
        val u = url.lowercase()
        return when {
            "teams.microsoft.com/meet/" in u || "meetup-join" in u || "teams.live.com" in u -> "Teams meeting"
            "teams.microsoft.com" in u -> "Teams channel/chat"
            "meet.google.com" in u -> "Google Meet"
            "zoom" in u -> "Zoom"
            else -> "Online class"
        }
    }

    fun linkIsMeeting(url: String) = linkType(url) != "Teams channel/chat"

    private fun findLinks(text: String?): List<String> {
        val out = LinkedHashSet<String>()
        for (m in MEETING_RE.findAll(unesc(text))) out.add(m.value.trimEnd('.', ',', ';', ':', '!', '?'))
        return out.toList()
    }

    private fun dedupe(raw: List<Triple<String, String, Long>>): List<Link> {
        val best = LinkedHashMap<String, Link>()
        for ((url, label, ts) in raw) {
            val toks = groupTokens(label)
            val old = best[url]
            best[url] = if (old == null) {
                Link(url, label, ts, toks)
            } else if (ts > old.ts) {
                Link(url, label, ts, old.tokens + toks)
            } else {
                old.copy(tokens = old.tokens + toks)
            }
        }
        return best.values.toList()
    }

    private fun linksToJson(m: Map<String, List<Link>>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) {
            val a = JSONArray()
            for (l in v) {
                a.put(
                    JSONObject().put("url", l.url).put("label", l.label).put("ts", l.ts).put("tokens", jarr(l.tokens))
                )
            }
            o.put(k, a)
        }
        return o
    }

    private fun linksFromJson(o: JSONObject): Map<String, List<Link>> {
        val out = HashMap<String, List<Link>>()
        for (k in o.keys()) {
            out[k] = (o.optJSONArray(k) ?: JSONArray()).objs().map {
                Link(it.str("url"), it.str("label"), it.optLong("ts"), (it.optJSONArray("tokens") ?: JSONArray()).strs().toSet())
            }
        }
        return out
    }

    // ------------------------------------------------------------ event helpers
    /** A deadline the user hid on Home ("Hide this (wrong?)"): no card, no reminder. */
    fun isHiddenDeadline(ctx: Context, eventId: Long): Boolean =
        eventId != 0L && ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
            .getStringSet("hiddenEvents", emptySet())!!.contains("dl:$eventId")

    fun isAttEvent(e: JSONObject): Boolean = e.str("modulename") == "attendance" || ATT_RE.containsMatchIn(e.str("name"))

    private fun isManual(e: JSONObject, p: Prefs): Boolean {
        val c = e.optJSONObject("course")
        val text = "${c?.str("shortname") ?: ""} ${c?.str("fullname") ?: ""}".lowercase()
        return p.manualList.any { text.contains(it) }
    }

    /** True for a class that belongs to another group (Settings > Classes to ignore). */
    fun isSkipped(e: JSONObject, p: Prefs): Boolean {
        if (!isAttEvent(e)) return false
        val c = e.optJSONObject("course")
        val text = "${c?.str("shortname") ?: ""} ${c?.str("fullname") ?: ""} ${e.str("name")}".lowercase()
        val day = localDt(e.optLong("timestart")).dayOfWeek.name.lowercase().take(3)
        return p.skipList.any { (code, d) -> text.contains(code) && (d.isEmpty() || d == day) }
    }

    /** Cached calendar without the classes you chose to ignore. */
    fun myCalendar(st: State, p: Prefs): List<JSONObject> =
        (st.j.optJSONArray("cal") ?: JSONArray()).objs().filter { !isSkipped(it, p) }

    /** manual = attendance by Teams recording (Settings → Your classes); own = from your timetable, not Spectrum. */
    data class ClassItem(
        val name: String, val course: String, val full: String,
        val start: Long, val end: Long, val link: String, val courseUrl: String, val manual: Boolean,
        val courseId: Long = 0L,
        val code: String = "",
        val own: Boolean = false,
        /** Your own entries for this course hidden because this Spectrum class overlaps them, e.g. "12:00–13:00". */
        val hiddenOwn: String = "",
    )
    data class DueItem(val name: String, val course: String, val ts: Long, val url: String, val eventId: Long = 0L)
    class Overview(val classes: List<ClassItem>, val due: List<DueItem>)

    /** The latest meeting link recorded for a course (Teams/Meet etc.), or "" if none. */
    fun courseMeetingLink(ctx: Context, courseId: Long): String {
        if (courseId == 0L) return ""
        val st = State(ctx)
        val linksMap = linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        val links: List<Link>? = linksMap[courseId.toString()]
        return links?.firstOrNull { linkIsMeeting(it.url) }?.url ?: ""
    }

    /** The Spectrum attendance page for a course, e.g. …/mod/attendance/view.php?id=1127746, or "" if unknown. */
    fun attendanceUrl(ctx: Context, courseId: Long, preferLec: Boolean? = null): String {
        if (courseId == 0L) return ""
        val site = Prefs(ctx).site
        val st = State(ctx)
        val labels = st.j.optJSONObject("attlabels")
        val multi = st.j.optJSONObject("attcmids")
        if (multi != null && multi.length() > 0) {
            val mine = Groups.mine(st, Prefs(ctx), Courses.all(ctx).firstOrNull { it.id == courseId }?.code ?: "")
            data class Cand(val cmid: Long, val score: Int, val kind: String, val name: String, val visible: Boolean)
            val cands = ArrayList<Cand>()
            for (k in multi.keys()) {
                if (!k.startsWith("$courseId:")) continue
                val cmid = k.substringAfter(':').toLongOrNull() ?: continue
                val toks = multi.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
                val name = labels?.optJSONObject("$courseId:$cmid")?.optString("name", "") ?: ""
                val kind = labels?.optJSONObject("$courseId:$cmid")?.optString("kind", "other") ?: "other"
                val visible = labels?.optJSONObject("$courseId:$cmid")?.optBoolean("vis", true) ?: true
                cands.add(Cand(cmid, toks.intersect(mine).size, kind, name, visible))
            }
            if (cands.isNotEmpty()) {
                // USER RULE: filter attendance titles by lecture / L / tutorial / class.
                // preferLec explicitly picks the kind; null = token score decides, ties prefer lec.
                val kindWanted = when (preferLec) { true -> "lec"; false -> "tut"; null -> null }
                // pages restricted to another group (not visible to you) only as a last resort
                val open = cands.filter { it.visible }.ifEmpty { cands }
                val filtered = if (kindWanted != null) open.filter { it.kind == kindWanted || it.kind == "both" } else open
                val pool = filtered.ifEmpty { open }
                val top = pool.maxOf { it.score }
                val best = pool.filter { it.score == top }
                val picked = (best.firstOrNull { it.kind == "lec" } ?: best.firstOrNull())?.cmid ?: 0L
                return if (picked != 0L && site.isNotEmpty()) "$site/mod/attendance/view.php?id=$picked" else ""
            }
        }
        // legacy single-value fallback
        val id = try { st.j.optJSONObject("attcmid")?.optLong(courseId.toString(), 0L) ?: 0L } catch (_: Exception) { 0L }
        return if (id != 0L && site.isNotEmpty()) "$site/mod/attendance/view.php?id=$id" else ""
    }

    /** Course id for a course code (for slots that came from the timetable, which has no id). */
    fun courseIdForCode(ctx: Context, code: String): Long =
        Courses.all(ctx).firstOrNull { it.code.equals(code, true) }?.id ?: 0L

    /**
     * ONE resolver for the meeting + attendance links, shared by the timetable, the edit page
     * and the hero card, so every screen shows the same thing for the same class.
     * Returns (meeting, attendance, coursePage); any may be "".
     */
    fun linksForCourse(
        ctx: Context, code: String, courseId: Long,
        extraTokens: Set<String> = emptySet(), preferLec: Boolean? = null,
        day: Int = 0, startMin: Int = -1, endMin: Int = -1,
    ): Triple<String, String, String> {
        val p = Prefs(ctx)
        val id = if (courseId != 0L) courseId else courseIdForCode(ctx, code)
        val att = Timetable.userAttLinkRaw(ctx, code).ifEmpty { attendanceUrl(ctx, id, preferLec) }
        val meeting = Timetable.meetingLinkOf(ctx, code).ifEmpty {
            if (id == 0L) "" else {
                val ls = linksFromJson(State(ctx).j.optJSONObject("links") ?: JSONObject())
                    .get(id.toString()) ?: emptyList()
                // Score links against EVERYTHING the requesting class knows (user request:
                // "filter every detail like time, date, lecture name, occ, course"):
                //   occ tokens (event name + user's occ)  — strongest
                //   lecture/tutorial kind                  — if preferLec set
                //   DAY name + TIME RANGE in the label    — e.g. "Monday 12:00 - 02:00"
                // Captured labels carry text like
                //   "Attendance k ( L ) @ Bilik Kuliah Monday 12:00 - 02:00 (Rasha Atallah)"
                // so day-name and start-hour matching against the label is reliable.
                val mine = Groups.mine(State(ctx), Prefs(ctx), code.uppercase())
                val dayName = if (day in 1..7) Timetable.DAYS[day - 1] else ""
                val startHour = startMin / 60
                // "9" must also match "09:00", and a 2 pm class "02:00" / "2pm"
                val hourRes = if (startHour in 0..23) listOf(startHour, (startHour + 11) % 12 + 1).distinct()
                    .map { h -> Regex("(^|[^0-9])0?$h([^0-9]|$)") } else emptyList()
                val dayRe = if (dayName.isNotEmpty()) Regex("\\b" + dayName.lowercase()) else null
                val scored = ls.filter { linkIsMeeting(it.url) }.map { l ->
                    var score = (if (l.ts > 0) 1 else 0)
                    score += l.tokens.intersect(extraTokens).size * 10
                    score += l.tokens.intersect(mine).size * 10
                    val kind = attKind(l.label)
                    if (preferLec == true && (kind == "lec" || kind == "both")) score += 5
                    if (preferLec == false && (kind == "tut" || kind == "both")) score += 5
                    val labelLow = l.label.lowercase()
                    // "mon" also covers "monday"
                    if (dayRe != null && dayRe.containsMatchIn(labelLow)) score += 8
                    if (hourRes.any { it.containsMatchIn(labelLow) }) score += 8
                    Pair(l, score)
                }.filter { it.second > 0 }
                    .sortedByDescending { it.second }
                (scored.firstOrNull()?.first ?: ls.firstOrNull { linkIsMeeting(it.url) })?.url ?: ""
            }
        }
        val coursePage = if (id != 0L) "${p.site}/course/view.php?id=$id" else ""
        return Triple(meeting, att, coursePage)
    }

    /** Classes (not yet finished) and deadlines from the cached data, for the main screen. */
    fun overview(ctx: Context): Overview {
        val p = Prefs(ctx)
        val st = State(ctx)
        val now = System.currentTimeMillis() / 1000
        val cal = myCalendar(st, p)
        val links = linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        // holiday mode: outside teaching weeks the hero/widget suppress class entries
        val classesActive = AcademicCalendar.inTeaching(ctx, java.time.LocalDate.now())
        val classes = if (!classesActive) emptyList() else cal.filter { isAttEvent(it) }.map { e ->
            val c = e.optJSONObject("course")
            val short = c?.str("shortname") ?: ""
            val full = c?.str("fullname") ?: ""
            val cid = courseId(e)
            val (startShown, endShown) = classTimes(ctx, e)
            ClassItem(
                unesc(e.str("name")), short.ifEmpty { full.ifEmpty { "Class" } }, full,
                startShown, endShown,
                linksForEvent(ctx, e, cal, links).firstOrNull()?.first ?: "",
                if (cid != 0L) "${p.site}/course/view.php?id=$cid" else p.site,
                isManual(e, p), cid,
                code = Timetable.courseCode(c),
            )
        }.filter { it.end > now }.sortedBy { it.start }
        val due = (st.j.optJSONArray("deadlines") ?: JSONArray()).objs().mapNotNull { e ->
            val ts = e.optLong("timesort")
            if (ts <= now || isHiddenDeadline(ctx, e.optLong("id"))) return@mapNotNull null
            val act = e.optJSONObject("action")
            DueItem(
                unesc(e.str("name")).ifEmpty { "Deadline" },
                e.optJSONObject("course")?.str("shortname") ?: "",
                ts,
                e.str("url").ifEmpty { act?.str("url") ?: "" },
                e.optLong("id"),
            )
        }.sortedBy { it.ts }
        // manual timetable entries (user-added or catalog auto-setup) belong on the hero too;
        // links resolved the same way every other screen resolves them
        val zoneId = zone()
        val manualClasses = if (!classesActive) emptyList() else Timetable.manual(ctx).flatMap { slot ->
            (0..13L).map { off -> java.time.LocalDate.now(zoneId).plusDays(off) }
                .filter { dd -> dd.dayOfWeek.value == slot.day }
                // your own entries only in their own semester's teaching days
                .filter { dd -> Timetable.inSem(slot, AcademicCalendar.labelFor(ctx, dd)) && AcademicCalendar.inTeaching(ctx, dd) }
                .map { dd ->
                    val dayStart = dd.atStartOfDay(zoneId).toEpochSecond()
                    Triple(slot, dayStart + slot.start * 60L, dayStart + slot.end * 60L)
                }
        }.filter { (_, _, end) -> end > now }.map { (slot, start, end) ->
            val cid = courseIdForCode(ctx, slot.course)
            val (meeting, att, coursePage) = linksForCourse(ctx, slot.course, cid)
            ClassItem(
                slot.title.ifEmpty { "Class" }, slot.course,
                slot.course, start, end,
                meeting.ifEmpty { att },
                coursePage,
                // Teams-recording courses only — this used to be true for every own entry, so Home said
                // "Attendance for this course is by Teams recording" for any class you added
                p.manualList.any { slot.course.lowercase().contains(it) }, cid,
                code = slot.course.uppercase(), own = true,
            )
        }
        // the same class must not show twice: Spectrum's wins over your own entry for that course when their
        // times overlap (as on the Timetable); only start times within 15 min were merged before
        fun overlap(c: ClassItem, m: ClassItem) = c.code.equals(m.code, true) && c.start < m.end && m.start < c.end
        val ownKept = manualClasses.filter { m -> classes.none { c -> overlap(c, m) } }
        // say so on the Spectrum card, so a dropped entry of yours is never a silent surprise
        val shown = classes.map { c ->
            val hidden = manualClasses.filter { m -> overlap(c, m) }
            if (hidden.isEmpty()) c else c.copy(hiddenOwn = hidden.joinToString(", ") { "${hhmm(it.start)}–${hhmm(it.end)}" })
        }
        val merged = (shown + ownKept)
            .distinctBy { "${it.course}:${it.start / 900}" }
            .sortedBy { it.start }
        return Overview(merged, due)
    }

    private fun courseId(e: JSONObject): Long = e.optJSONObject("course")?.optLong("id", 0L) ?: 0L

    private fun courseGroupTokens(cid: Long, cal: List<JSONObject>): Set<String> {
        val toks = HashSet<String>()
        for (x in cal) if (isAttEvent(x) && courseId(x) == cid) toks.addAll(groupTokens(x.str("name")))
        return toks
    }

    private fun linksForEvent(ctx: Context, e: JSONObject, cal: List<JSONObject>, links: Map<String, List<Link>>): List<Pair<String, String>> {
        val cid = courseId(e)
        var evTokens = groupTokens(e.str("name"))
        if (evTokens.isEmpty()) evTokens = courseGroupTokens(cid, cal)
        val cands = ArrayList<Cand>()
        val seen = HashSet<String>()
        // a link you pasted for this course beats anything detected, on every screen and in the class alert
        Timetable.meetingLinkOf(ctx, Timetable.courseCode(e.optJSONObject("course"))).takeIf { it.isNotEmpty() }?.let { u ->
            seen.add(u); cands.add(Cand(1000, true, 0L, u, "your link"))
        }
        for (u in findLinks(e.str("description"))) if (seen.add(u)) cands.add(Cand(100, linkIsMeeting(u), 0L, u, "calendar event"))
        for (l in links["$cid"] ?: emptyList()) {
            if (!seen.add(l.url)) continue
            val score = if (evTokens.isNotEmpty()) evTokens.intersect(l.tokens).size else 0
            cands.add(Cand(score, linkIsMeeting(l.url), l.ts, l.url, l.label))
        }
        if (cands.isEmpty()) return emptyList()
        val sorted = cands.sortedWith(
            compareByDescending<Cand> { it.score }.thenByDescending { it.isMeeting }.thenByDescending { it.ts }
        )
        val top = sorted[0].score
        val keep = if (top > 0) sorted.filter { it.score == top } else sorted
        return keep.map { Pair(it.url, "${linkType(it.url)}, ${it.label}") }
    }

    // ------------------------------------------------------------ quiet hours
    fun inQuiet(now: ZonedDateTime, p: Prefs): Boolean {
        val s = p.quietStart
        val e = p.quietEnd
        if (s == e) return false
        val h = now.hour + now.minute / 60.0
        return if (s < e) h >= s && h < e else h >= s || h < e
    }

    // ------------------------------------------------------------ materials
    private fun mt0Of(sec: JSONObject): Long = 0L

    private fun snapshotCourse(api: Moodle, site: String, c: JSONObject, extra: MutableMap<String, String>,
                               secOf: MutableMap<String, String>, secNames: MutableMap<String, String>,
                               attCmids: MutableMap<Long, HashMap<Long, MutableSet<String>>>, occSeen: MutableMap<Long, MutableSet<String>>,
                               st: State): Pair<Map<String, Triple<String, String, String>>, List<Link>> {
        val snap = LinkedHashMap<String, Triple<String, String, String>>()
        val raw = ArrayList<Triple<String, String, Long>>()
        val cid = c.optLong("id")
        val occSets = ArrayList<Set<String>>()
        for (sec in api.arr("core_course_get_contents", mapOf("courseid" to cid)).objs()) {
            val sname = sec.str("name")
            val skey = sec.optLong("id").toString()
            // section descriptions often hold the weekly meeting links (e.g. "Online Lecture Link …: <href>")
            // (Moodle sends "summary" as a plain HTML string; scan all of it, links can sit past 2000 chars)
            val secDesc = sec.optJSONObject("summary")?.optString("text") ?: sec.optString("summary", "")
            for (u in findLinks(secDesc)) raw.add(Triple(u, "section: " + clean(sname, 50), mt0Of(sec)))
            val mods = (sec.optJSONArray("modules") ?: JSONArray()).objs()
            if (mods.isNotEmpty()) {
                secNames[skey] = sname.ifEmpty { if (sec.optInt("section") == 0) "General" else "Page ${sec.optInt("section")}" }
            }
            for (m in mods) {
                val contents = (m.optJSONArray("contents") ?: JSONArray()).objs()
                val mt = contents.maxOfOrNull { it.optLong("timemodified", 0L) } ?: 0L
                val key = "mod:${m.optLong("id")}:$mt"
                val name = m.str("name")
                val url = m.str("url")
                snap[key] = Triple(name, url, sname)
                secOf[key] = skey
                val desc = clean(m.str("description"), 300)
                if (desc.isNotEmpty()) extra[key] = desc
                if (m.str("modname") == "attendance" && m.optLong("id") != 0L) {
                    // Spectrum can list one attendance module PER OCC (e.g. "Attendance Occ 2",
                    // "Attendance Occ 4"). Remember every one with its group tokens so the
                    // resolver can pick the user's OWN occ page instead of the first captured.
                    val attKey = m.optLong("id")
                    val nm = m.str("name")
                    val toks = groupTokens(nm + " " + m.str("intro"))
                    if (attCmids[cid] == null) attCmids[cid] = HashMap()
                    (attCmids[cid] as HashMap<Long, MutableSet<String>>)[attKey] = toks.toMutableSet()
                    // remember the module name AND its kind so the resolver can filter
                    // directly by lecture/tutorial (user request: filter titles containing
                    // lecture / L / tutorial / class)
                    val kind = attKind(nm)
                    val lbl = st.j.optJSONObject("attlabels") ?: JSONObject()
                    lbl.put("$cid:$attKey", JSONObject().put("name", nm).put("kind", kind).put("vis", m.optBoolean("uservisible", true)))
                    st.j.put("attlabels", lbl)
                }
                // Occ detection via availability: a module the user CAN see that names an Occ
                // means the user belongs to it (Spectrum restricts other Occs' pages).
                // A page can list several Occs ("OCC 2, 12, 16"); you are in one Occ that every
                // visible page names, so keep the Occs common to all of them when there are any.
                if (m.optBoolean("uservisible", true)) {
                    val occs = groupTokens(m.str("name") + " " + m.str("intro")).filter { it.startsWith("occ") }.toSet()
                    if (occs.isNotEmpty()) occSets.add(occs)
                }
                if (m.str("modname") in MEETING_MODS && m.optBoolean("uservisible", true) && url.isNotEmpty()) {
                    raw.add(Triple(url, clean(name), mt))
                }
                for (u in findLinks(m.str("description"))) raw.add(Triple(u, "page: " + clean(name, 50), mt))
                if (m.str("modname") == "url") {
                    for (f in contents) for (u in findLinks(f.str("fileurl"))) {
                        raw.add(Triple(u, clean(name), f.optLong("timemodified", 0L)))
                    }
                }
            }
        }
        if (occSets.isNotEmpty()) {
            val common = occSets.reduce { a, b -> a intersect b }
            occSeen[cid] = LinkedHashSet(common.ifEmpty { occSets.flatten().toSet() })
        }
        try {
            for (f in api.arr("mod_forum_get_forums_by_courses", mapOf("courseids" to listOf(cid))).objs()) {
                if (f.str("type") != "news") continue
                val ds = api.obj(
                    "mod_forum_get_forum_discussions",
                    mapOf("forumid" to f.optLong("id"), "sortorder" to -1, "page" to 0, "perpage" to 10),
                )
                for (d in (ds.optJSONArray("discussions") ?: JSONArray()).objs()) {
                    val did = d.optLong("discussion")
                    snap["ann:$did"] = Triple(
                        "Announcement: " + d.str("name"), "$site/mod/forum/discuss.php?d=$did", "Announcements",
                    )
                    secOf["ann:$did"] = Sections.ANN
                    secNames[Sections.ANN] = "Announcements"
                    extra["ann:$did"] = clean(d.str("message"), 300)
                    for (u in findLinks(d.str("message"))) {
                        raw.add(Triple(u, "announcement: " + clean(d.str("name"), 50), maxOf(d.optLong("timemodified"), d.optLong("created"))))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "forum check skipped for ${c.str("shortname")}: $e")
        }
        return Pair(snap, dedupe(raw))
    }

    private fun pushCourse(ctx: Context, site: String, c: JSONObject, items: List<Triple<String, String, String>>) {
        val cname = c.str("fullname").ifEmpty { c.str("shortname") }
        val lines = ArrayList<String>()
        for ((name, url, sec) in items.take(15)) {
            var line = "- $name" + (if (sec.isNotEmpty()) " [$sec]" else "")
            if (url.isNotEmpty()) line += "\n  $url"
            lines.add(line)
        }
        if (items.size > 15) lines.add("...and ${items.size - 15} more")
        val buttons = ArrayList<Pair<String, String>>()
        for ((i, it) in items.take(3).withIndex()) {
            if (it.second.isNotEmpty()) buttons.add(Pair(if (items.size == 1) "Open" else "Open ${i + 1}", it.second))
        }
        if (buttons.isEmpty()) buttons.add(Pair("Open course", "$site/course/view.php?id=${c.optLong("id")}"))
        Notifier.push(ctx, "$cname: ${items.size} new", lines.joinToString("\n"), false, buttons)
    }

    private fun checkCourses(ctx: Context, api: Moodle, p: Prefs, userid: Long, st: State) {
        val prevLinks = linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        val linksByCourse = LinkedHashMap<String, List<Link>>()
        val attCmids = HashMap<Long, HashMap<Long, MutableSet<String>>>()
        val occSeen = HashMap<Long, MutableSet<String>>()
        val enrolled = api.arr("core_enrol_get_users_courses", mapOf("userid" to userid)).objs()
        Courses.remember(st, enrolled)
        for (c in enrolled) {
            val cid = c.optLong("id")
            // a past semester's course: not checked any more (saves 2-3 Spectrum calls each), its links kept
            if (Courses.isPast(c)) { prevLinks["$cid"]?.let { linksByCourse["$cid"] = it }; continue }
            val ckey = "course$cid"
            val extra = HashMap<String, String>()
            val secOf = HashMap<String, String>()
            val secNames = LinkedHashMap<String, String>()
            val result = try {
                snapshotCourse(api, p.site, c, extra, secOf, secNames, attCmids, occSeen, st)
            } catch (e: Exception) {
                Log.w(TAG, "course $cid skipped: $e")
                prevLinks["$cid"]?.let { linksByCourse["$cid"] = it }
                continue
            }
            val snap = result.first
            Sections.remember(st, cid, secNames)
            attCmids[cid]?.let { perCid ->
                // store ALL attendance cmids for this course keyed by "cid:cmid" -> tokens JSON
                val am = st.j.optJSONObject("attcmids") ?: JSONObject()
                for ((cmid, toks) in perCid) {
                    am.put("$cid:$cmid", JSONArray(toks.map { it }))
                }
                st.j.put("attcmids", am)
                // keep the legacy single-value for the first seen module (back-compat)
                val first = perCid.entries.firstOrNull()
                if (first != null) {
                    val amOld = st.j.optJSONObject("attcmid") ?: JSONObject()
                    amOld.put(cid.toString(), first.key)
                    st.j.put("attcmid", amOld)
                }
            }
            if (occSeen.containsKey(cid)) {
                val om = st.j.optJSONObject("occ_seen") ?: JSONObject()
                om.put(cid.toString(), jarr(occSeen[cid]!!.toList()))
                st.j.put("occ_seen", om)
            }
            // track per-course "seen occs" so the startup dialog can ask the user to confirm
            if (occSeen.containsKey(cid)) {
                val cm = st.j.optJSONObject("occ_courses") ?: JSONObject()
                cm.put(cid.toString(), c.str("shortname").ifEmpty { c.str("fullname") })
                st.j.put("occ_courses", cm)
            }
            linksByCourse["$cid"] = result.second
            val known = st.j.has(ckey)
            val old = st.strings(ckey)
            val newKeys = snap.keys.filter { it !in old }
            if (known && newKeys.isNotEmpty()) {
                // pages you switched off, then items that name another group (Occ / tutorial); both still count as seen
                val code = Timetable.courseCode(c)
                val off = Sections.offSet(ctx, cid)
                val inPages = newKeys.filter { k -> secOf[k]?.let { it in off } != true }
                val mine = Groups.mine(st, p, code)
                val fresh = inPages.filter { k ->
                    val t = snap[k]
                    !Groups.isForeign("${t?.first ?: ""} ${t?.third ?: ""} ${extra[k] ?: ""}", mine)
                }
                if (fresh.isNotEmpty()) pushCourse(ctx, p.site, c, fresh.mapNotNull { snap[it] })
                if (inPages.size < newKeys.size) {
                    NotifyLog.sys(ctx, "Skipped ${newKeys.size - inPages.size} item(s) on pages switched off in $code")
                }
                if (fresh.size < inPages.size) {
                    NotifyLog.sys(ctx, "Skipped ${inPages.size - fresh.size} item(s) for other groups in $code")
                }
            }
            st.putStrings(ckey, snap.keys)
        }
        st.j.put("links", linksToJson(linksByCourse))
    }

    // ------------------------------------------------------------ moodle notifications
    private fun checkNotes(ctx: Context, api: Moodle, p: Prefs, userid: Long, st: State, first: Boolean) {
        val notes = api.obj(
            "message_popup_get_popup_notifications",
            mapOf("useridto" to userid, "newestfirst" to 1, "limit" to 30, "offset" to 0),
        ).optJSONArray("notifications")?.objs() ?: emptyList()
        val seen = st.strings("notes")
        for (n in notes.reversed()) {
            val id = n.optLong("id").toString()
            if (id in seen || first) continue
            val text = n.str("subject") + " " + n.str("smallmessage").ifEmpty { n.str("fullmessage") }
            val code = Groups.codeIn(text)
            val mine = if (code.isNotEmpty()) Groups.mine(st, p, code) else Groups.mineAnywhere(st, p)
            if (Groups.isForeign(text, mine)) {
                NotifyLog.sys(ctx, "Skipped a notification for another group: ${clean(n.str("subject"), 60)}")
                continue
            }
            val url = n.str("contexturl")
            Notifier.push(
                ctx,
                n.str("subject").ifEmpty { "Spectrum" },
                unesc(n.str("smallmessage").ifEmpty { n.str("fullmessage") }),
                false,
                if (url.isNotEmpty()) listOf(Pair("Open in Spectrum", url)) else emptyList(),
            )
        }
        seen.addAll(notes.map { it.optLong("id").toString() })
        st.putStrings("notes", seen.toList().takeLast(500))
    }

    // ------------------------------------------------------------ deadlines
    private fun checkDeadlines(ctx: Context, api: Moodle, p: Prefs, st: State, now: Long): List<JSONObject> {
        val events = api.obj(
            "core_calendar_get_action_events_by_timesort",
            mapOf("timesortfrom" to now, "timesortto" to now + 14 * 86400, "limitnum" to 50),
        ).optJSONArray("events")?.objs() ?: emptyList()
        val sent = st.strings("deadlines_sent")
        val upcoming = ArrayList<JSONObject>()
        for (e in events) {
            val act = e.optJSONObject("action")
            if (act != null && !act.optBoolean("actionable", true)) continue
            val ts = e.optLong("timesort")
            val remaining = ts - now
            if (remaining <= 0) continue
            if (isHiddenDeadline(ctx, e.optLong("id"))) continue
            upcoming.add(e)
            val applicable = p.deadlineList.filter { remaining <= it * 3600L }
            if (applicable.isEmpty()) continue
            val keys = applicable.map { "dl:${e.optLong("id")}:$ts:$it" }
            if (keys[0] in sent) continue
            val hrs = maxOf(1L, Math.round(remaining / 3600.0))
            val course = e.optJSONObject("course")?.str("fullname") ?: ""
            val url = e.str("url").ifEmpty { act?.str("url") ?: "" }
            Notifier.push(
                ctx,
                "Due in ~${hrs}h: ${unesc(e.str("name")).ifEmpty { "Deadline" }}",
                "$course\nDue: ${fmtTime(ts)}",
                true,
                if (url.isNotEmpty()) listOf(Pair("Open task", url)) else emptyList(),
            )
            sent.addAll(keys)
        }
        st.putStrings("deadlines_sent", sent.toList().takeLast(500))
        return upcoming
    }

    // ------------------------------------------------------------ class links (10 min before class)
    /**
     * A class event's start/end (epoch seconds) with your Time Override from the class editor applied.
     * The editor saves under "course|day|start|end" (Timetable's slot key), so derive the same key here.
     * Home, the widget, the 10-minute alarm and the alert all use this, so they agree on the time.
     */
    fun classTimes(ctx: Context, e: JSONObject): Pair<Long, Long> {
        val start = e.optLong("timestart")
        val dur = e.optLong("timeduration")
        val z = localDt(start)
        val startMin = z.hour * 60 + z.minute
        val endMin = minOf(24 * 60, startMin + (if (dur > 0L) (dur / 60).toInt() else 60))
        val code = Timetable.courseCode(e.optJSONObject("course"))
        val o = Timetable.timeOverride(ctx, "$code|${z.dayOfWeek.value}|$startMin|$endMin")
            ?: return Pair(start, start + (if (dur > 0) dur else 3600L))
        val midnight = start - startMin * 60L
        return Pair(midnight + o.first * 60L, midnight + o.second * 60L)
    }

    /**
     * When a class starts (your corrected time), one alert so you do not forget to sign attendance:
     * Attendance and Join class buttons. Skipped for courses where the lecturer takes attendance in class or
     * attendance is by Teams recording, and when turned off in Settings → Your classes.
     */
    private fun checkAttendanceStart(ctx: Context, st: State, p: Prefs, cal: List<JSONObject>, now: Long) {
        if (!p.attStartAlert) return
        val sent = st.strings("att_sent")
        val links = linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        for (e in cal) {
            if (!isAttEvent(e)) continue
            val (start, end) = classTimes(ctx, e)
            if (now < start || now > minOf(end, start + 15 * 60)) continue
            val key = "attstart:${e.optLong("id")}:$start"
            if (key in sent) continue
            val c = e.optJSONObject("course")
            val code = Timetable.courseCode(c)
            if (Timetable.attMode(ctx, code) == "manual" || isManual(e, p)) { sent.add(key); continue }
            val name = unesc(e.str("name"))
            val z = localDt(start)
            val (_, att, coursePage) = linksForCourse(
                ctx, code, courseId(e), groupTokens(name), preferLecOf(name),
                day = z.dayOfWeek.value, startMin = z.hour * 60 + z.minute, endMin = localDt(end).let { it.hour * 60 + it.minute },
            )
            val meeting = linksForEvent(ctx, e, cal, links).firstOrNull { linkIsMeeting(it.first) }?.first ?: ""
            val buttons = ArrayList<Pair<String, String>>()
            att.ifEmpty { coursePage }.takeIf { it.isNotEmpty() }?.let { buttons.add(Pair("Sign attendance", it)) }
            if (meeting.isNotEmpty()) buttons.add(Pair("Join class", meeting))
            Notifier.push(
                ctx, "$code has started: sign your attendance",
                "$name\nStarted ${hhmm(start)}. Open the attendance page and mark yourself present.",
                true, buttons,
            )
            sent.add(key)
        }
        st.putStrings("att_sent", sent.toList().takeLast(200))
    }

    private fun checkClassLinks(ctx: Context, st: State, p: Prefs, cal: List<JSONObject>, links: Map<String, List<Link>>, now: Long) {
        val sent = st.strings("links_sent")
        for (e in cal) {
            if (!isAttEvent(e)) continue
            val start = classTimes(ctx, e).first   // your corrected time, not Spectrum's
            if (now < start - 600 || now > start + 300) continue
            val eid = e.optLong("id")
            val kok = "linkok:$eid:$start"
            val kno = "linkno:$eid:$start"
            if (kok in sent) continue
            val c = e.optJSONObject("course")
            val cname = listOf(c?.str("shortname"), c?.str("fullname")).firstOrNull { !it.isNullOrEmpty() } ?: "Class"
            val name = unesc(e.str("name"))
            val mins = maxOf(0L, Math.round((start - now) / 60.0))
            val whenS = fmtTime(start)
            val note = if (isManual(e, p)) "\nAttendance for this class is via Teams record." else ""
            val ls = linksForEvent(ctx, e, cal, links)
            if (ls.isNotEmpty()) {
                val body = "$name\nStarts $whenS.$note\n\nOnline link:" +
                    ls.take(3).joinToString("") { "\n  ${it.first}  (${it.second})" }
                val buttons = ls.take(3).mapIndexed { i, l -> Pair(if (i == 0) "Join class" else "Link ${i + 1}", l.first) }
                val title = if (kno in sent) "Online link posted: $cname" else "Class in ~$mins min: $cname"
                Notifier.push(ctx, title, body, true, buttons)
                sent.add(kok)
                sent.add(kno)
            } else if (kno !in sent) {
                val cid = courseId(e)
                val courseUrl = if (cid != 0L) "${p.site}/course/view.php?id=$cid" else p.site
                val body = "$name\nStarts $whenS.$note\n\nNo online link found yet in the calendar event, " +
                    "course page or announcements. It may be in person, or not posted yet. " +
                    "I'll keep checking until the class starts.\n  $courseUrl"
                Notifier.push(ctx, "Class in ~$mins min: $cname (no link yet)", body, true, listOf(Pair("Open course", courseUrl)))
                sent.add(kno)
            }
        }
        st.putStrings("links_sent", sent.toList().takeLast(300))
    }

    // ------------------------------------------------------------ morning summary
    private fun sendMorningSummary(ctx: Context, st: State, p: Prefs, cal: List<JSONObject>, deadlines: List<JSONObject>, now: ZonedDateTime) {
        if (p.summaryTime.isBlank()) return
        val today = now.toLocalDate().toString()
        if (st.j.optString("summary_date") == today) return
        val hm = parseHm(p.summaryTime) ?: return
        val target = now.withHour(hm.first).withMinute(hm.second).withSecond(0).withNano(0)
        if (now.isBefore(target)) return
        if (now.hour >= 12) return  // started late in the day: no "good morning"

        val nowS = now.toEpochSecond()
        val todays = cal.filter { localDt(it.optLong("timestart")).toLocalDate() == now.toLocalDate() }
            .sortedBy { it.optLong("timestart") }
        val lines = arrayListOf("TODAY")
        val buttons = ArrayList<Pair<String, String>>()
        if (todays.isNotEmpty()) {
            for (e in todays.take(10)) {
                val c = e.optJSONObject("course")?.str("shortname") ?: ""
                val name = unesc(e.str("name"))
                val ts = e.optLong("timestart")
                val t = hhmm(ts)
                val manual = isAttEvent(e) && isManual(e, p)
                val url = if (manual) "" else e.str("url")
                lines.add(
                    "$t  ${if (c.isNotEmpty()) "$c: " else ""}$name" +
                        (if (url.isNotEmpty()) "\n  $url" else "") +
                        (if (manual) "\n  (attendance via Teams record)" else "")
                )
                if (isAttEvent(e) && !manual && url.isNotEmpty() && buttons.size < 3 && ts >= nowS - 3600) {
                    buttons.add(Pair("Attendance $t", url))
                }
            }
        } else {
            lines.add("No classes or events in the calendar.")
        }

        val horizon = nowS + 7 * 86400
        val pool = ArrayList<Pair<Long, JSONObject>>()
        val seen = HashSet<Long>()
        for (e in deadlines) {
            val t = e.optLong("timesort")
            if (t > nowS && t <= horizon) { pool.add(Pair(t, e)); seen.add(e.optLong("id")) }
        }
        for (e in cal) {
            val t = e.optLong("timestart")
            if (e.str("eventtype") in setOf("due", "close") && t in nowS..horizon && e.optLong("id") !in seen) {
                pool.add(Pair(t, e)); seen.add(e.optLong("id"))
            }
        }
        pool.sortBy { it.first }
        lines.add("")
        lines.add("DUE THIS WEEK")
        if (pool.isNotEmpty()) {
            for ((t, e) in pool.take(8)) {
                val c = e.optJSONObject("course")?.str("shortname") ?: ""
                val url = e.str("url").ifEmpty { e.optJSONObject("action")?.str("url") ?: "" }
                lines.add(
                    "${fmtTime(t)}  ${unesc(e.str("name")).ifEmpty { "Deadline" }}" +
                        (if (c.isNotEmpty()) " ($c)" else "") + (if (url.isNotEmpty()) "\n  $url" else "")
                )
            }
        } else {
            lines.add("Nothing due.")
        }
        if (buttons.size < 3) buttons.add(Pair("Open Spectrum", "${p.site}/my/"))
        val title = "Good morning - " + now.format(java.time.format.DateTimeFormatter.ofPattern("EEE dd MMM", java.util.Locale.ENGLISH))
        Notifier.push(ctx, title, lines.joinToString("\n"), false, buttons)
        st.j.put("summary_date", today)
    }

    // ------------------------------------------------------------ afternoon check-in
    private val OPENERS = listOf(
        "Dear you, you're almost done with today's classes.",
        "Hey, you're doing really well today.",
        "Take a deep breath, you're closer to the finish line than you think.",
        "Good job making it this far today.",
    )
    private val DONE_OPENERS = listOf(
        "You did it, all your classes are done for today.",
        "Well done, that was your last class for today.",
        "Classes are over for today, and you showed up. Proud of you.",
    )
    private val CARE = listOf(
        "Take plenty of rest and drink more water.",
        "Sip some water, stretch a little and rest your eyes for a moment.",
        "Be kind to yourself, rest when you can and keep hydrated.",
        "Remember to eat something and drink water, you deserve a little break.",
    )

    private fun sendAfternoon(ctx: Context, st: State, p: Prefs, cal: List<JSONObject>, now: ZonedDateTime) {
        if (p.afternoonTime.isBlank()) return
        val today = now.toLocalDate().toString()
        if (st.j.optString("afternoon_date") == today) return
        val hm = parseHm(p.afternoonTime) ?: return
        val target = now.withHour(hm.first).withMinute(hm.second).withSecond(0).withNano(0)
        if (now.isBefore(target)) return
        if (now.hour >= 18) return  // started too late in the day
        st.j.put("afternoon_date", today)

        val nowS = now.toEpochSecond()
        val classes = cal.filter { isAttEvent(it) && localDt(it.optLong("timestart")).toLocalDate() == now.toLocalDate() }
            .sortedBy { it.optLong("timestart") }
        if (classes.isEmpty()) return
        val left = classes.filter { it.optLong("timestart") > nowS }
        val n = left.size
        val title: String
        val head: String
        val status: String
        when (n) {
            0 -> {
                title = "Classes done for today"
                head = DONE_OPENERS.random()
                status = listOf("Go enjoy your evening.", "Time to relax, you earned it.").random()
            }
            1 -> {
                title = "One class to go"
                head = OPENERS.random()
                status = listOf("Just one class left for this afternoon.", "Only one more class to go.", "One last class and you're free.").random()
            }
            2 -> {
                title = "Two classes to go"
                head = OPENERS.random()
                status = listOf("Two classes left, take them one at a time.", "Just two more to go this afternoon.").random()
            }
            else -> {
                title = "$n classes to go"
                head = OPENERS.random()
                status = listOf("$n classes left today, a steady pace is fine.", "$n more to go. Take it one class at a time.").random()
            }
        }
        val lines = arrayListOf(head, status, "", CARE.random())
        val buttons = ArrayList<Pair<String, String>>()
        if (left.isNotEmpty()) {
            lines.add("")
            lines.add("NEXT UP")
            for (e in left.take(6)) {
                val c = e.optJSONObject("course")?.str("shortname") ?: ""
                val name = unesc(e.str("name"))
                val t = hhmm(e.optLong("timestart"))
                val manual = isManual(e, p)
                val url = if (manual) "" else e.str("url")
                lines.add(
                    "$t  ${if (c.isNotEmpty()) "$c: " else ""}$name" +
                        (if (url.isNotEmpty()) "\n  $url" else "") +
                        (if (manual) "\n  (attendance via Teams record)" else "")
                )
                if (url.isNotEmpty() && buttons.size < 3) buttons.add(Pair("Attendance $t", url))
            }
        }
        Notifier.push(ctx, title, lines.joinToString("\n"), false, buttons)
    }

    // ------------------------------------------------------------ shared "is anything due now?" step
    /** Uses the cached calendar/links, so it also works from an alarm with no network. */
    private fun sendDue(ctx: Context, st: State, p: Prefs) {
        val now = ZonedDateTime.now(zone())
        val cal = myCalendar(st, p)
        val dl = (st.j.optJSONArray("deadlines") ?: JSONArray()).objs().filter { !isHiddenDeadline(ctx, it.optLong("id")) }
        val links = linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        try { checkClassLinks(ctx, st, p, cal, links, now.toEpochSecond()) } catch (e: Exception) { Log.w(TAG, "class links: $e") }
        try { sendMorningSummary(ctx, st, p, cal, dl, now) } catch (e: Exception) { Log.w(TAG, "summary: $e") }
        try { sendAfternoon(ctx, st, p, cal, now) } catch (e: Exception) { Log.w(TAG, "afternoon: $e") }
        try { checkAttendanceStart(ctx, st, p, cal, now.toEpochSecond()) } catch (e: Exception) { Log.w(TAG, "attendance start: $e") }
    }

    /** Called by the alarm receiver: act on cached data, no network. */
    fun fromCache(ctx: Context) {
        val p = Prefs(ctx)
        if (p.token.isEmpty()) return
        if (!State.lock.tryLock(3, TimeUnit.SECONDS)) return  // a poll is running; it ends with sendDue anyway
        try {
            val st = State(ctx)
            sendDue(ctx, st, p)
            st.save()
        } finally {
            State.lock.unlock()
        }
    }

    // ------------------------------------------------------------ the main poll
    fun run(ctx: Context) {
        val p = Prefs(ctx)
        if (p.token.isEmpty() || p.site.isEmpty()) return
        val nowZ = ZonedDateTime.now(zone())
        if (inQuiet(nowZ, p)) {
            Scheduler.scheduleAll(ctx)
            return
        }
        State.lock.withLock {
            val st = State(ctx)
            try {
                val api = Moodle(p.site, p.token)
                val first = st.j.length() == 0
                val info = api.obj("core_webservice_get_site_info")
                val userid = info.optLong("userid")
                p.userName = info.str("fullname").ifEmpty { info.str("username") }
                checkNotes(ctx, api, p, userid, st, first)
                checkCourses(ctx, api, p, userid, st)
                val nowS = System.currentTimeMillis() / 1000
                try {
                    st.j.put("deadlines", jobjs(checkDeadlines(ctx, api, p, st, nowS)))
                } catch (e: Exception) { Log.w(TAG, "deadlines: $e") }
                try {
                    val events = api.obj("core_calendar_get_calendar_upcoming_view").optJSONArray("events") ?: JSONArray()
                    st.j.put("cal", events)
                    try { Timetable.remember(st, p) } catch (e: Exception) { Log.w(TAG, "timetable: $e") }
                    try { Groups.learn(st, p) } catch (e: Exception) { Log.w(TAG, "groups: $e") }
                } catch (e: Exception) { Log.w(TAG, "calendar: $e") }
                sendDue(ctx, st, p)
                st.save()
                if (p.alerted) {
                    Notifier.push(ctx, "Spectrum Notify recovered", "Checks are working again.")
                }
                p.fails = 0
                p.alerted = false
                p.lastError = ""
                p.lastRun = fmtTime(System.currentTimeMillis() / 1000)
                NotifyLog.sys(ctx, "Check OK")
            } catch (e: Exception) {
                val msg = (e.message ?: e.toString())
                Log.w(TAG, "poll failed: $msg")
                // Keep what this run already learned/notified, or the next good check re-sends it.
                try { st.save() } catch (_: Exception) {}
                p.fails = p.fails + 1
                p.lastError = msg.take(200)
                NotifyLog.sys(ctx, "Check failed: ${friendly(msg).take(150)}")
                if ((p.fails >= 3 || "invalidtoken" in msg) && !p.alerted) {
                    Notifier.push(
                        ctx, "Spectrum Notify is failing",
                        "${friendly(msg).take(300)}\n\nIf this says invalidtoken, log in again in the app.", true,
                    )
                    p.alerted = true
                }
            }
        }
        // every finished attempt, success or failure: the Home refresh spinner waits for this
        ctx.getSharedPreferences("health", Context.MODE_PRIVATE).edit()
            .putLong("lastAttemptMs", System.currentTimeMillis()).apply()
        try { Catalog.refresh(ctx) } catch (e: Exception) { Log.w(TAG, "catalog: $e") }
        try { NextClassWidget.updateAll(ctx) } catch (e: Exception) { Log.w(TAG, "widget: $e") }
        Scheduler.scheduleAll(ctx)
    }

    /** Raw exception text is noise (ports, IPs, "after 20000ms"). One plain line per failure family. */
    fun friendly(msg: String): String = when {
        "invalidtoken" in msg -> msg
        "ConnectException" in msg || "failed to connect" in msg.lowercase() -> "Could not reach the Spectrum site. It may be down, or the phone has no internet."
        "SocketTimeout" in msg || "timeout" in msg.lowercase() || "timed out" in msg.lowercase() -> "The Spectrum site did not answer in time. It may be slow or down."
        "UnknownHost" in msg || "Unable to resolve" in msg -> "No internet connection (site name could not be resolved)."
        "SSL" in msg || "ssl" in msg -> "Secure connection to the Spectrum site failed."
        else -> msg
    }
}
