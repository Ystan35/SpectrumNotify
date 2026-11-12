package my.spectrum.notify

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/** Which links belong to a class: meeting and attendance pages, found, scored and chosen in one place. */
object Links {
    // "Occ 2", "T3", "Group 1", "Tutorial 4", and lists after a word prefix: "OCC 1, 1M, 3, 6" -> occ1 occ1m occ3 occ6
    private val GROUP_RE = Regex(
        """\b(?:(occ\.?|grou?p|tutorial)\s*|([kt]))(\d+[a-z]?)\b((?:\s*(?:,|&|/|\band\b)\s*\d+[a-z]?\b)*)""",
        RegexOption.IGNORE_CASE,
    )

    private val LIST_ITEM_RE = Regex("""\d+[a-z]?""", RegexOption.IGNORE_CASE)

    // attendance titles: "K1 (L) Attendance", "( L ) @ Bilik Kuliah", "T2 (T&L)", "Attendance (Tutorials, OCC 2)"
    internal val LEC_RE = Regex("""lecture|kuliah|\bl\b|\bk\d""", RegexOption.IGNORE_CASE)

    internal val TUT_RE = Regex("""tutor|kelas|class|\bt\b|\bt\d""", RegexOption.IGNORE_CASE)

    private val MEETING_RE = Regex(
        """https?://(?:[\w.-]*teams\.microsoft\.com|teams\.live\.com|[\w.-]*zoom\.us|[\w.-]*zoomgov\.com""" +
            """|meet\.google\.com|[\w.-]*webex\.com|[\w.-]*bigbluebutton[\w.-]*|whereby\.com|[\w.-]*jit\.si)""" +
            """/[^\s"'<>)\]]*""",
        RegexOption.IGNORE_CASE,
    )

    internal val MEETING_MODS = setOf(
        "bigbluebuttonbn", "zoom", "teams", "msteams", "webexactivity", "googlemeet", "jitsi", "adobeconnect", "onlinemeeting",
    )

    data class Link(val url: String, val label: String, val ts: Long, val tokens: Set<String>)

    private class Cand(val score: Int, val isMeeting: Boolean, val ts: Long, val url: String, val label: String)

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

    // a title, or initials in brackets as in "Emran Mohd Tamil (EMT)"
    internal val LECTURER_RE = Regex("""\b(?:[Dd][Rr]|[Pp][Rr][Oo][Ff]|[Ee][Nn]|[Ee]ncik|[Pp][Nn]|[Pp]uan|[Mm]dm)\b|\([A-Z]{2,4}\)""")

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

    internal fun findLinks(text: String?): List<String> {
        val out = LinkedHashSet<String>()
        for (m in MEETING_RE.findAll(unesc(text))) out.add(m.value.trimEnd('.', ',', ';', ':', '!', '?'))
        return out.toList()
    }

    internal fun dedupe(raw: List<Triple<String, String, Long>>): List<Link> {
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

    internal fun linksToJson(m: Map<String, List<Link>>): JSONObject {
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

    internal fun linksFromJson(o: JSONObject): Map<String, List<Link>> {
        val out = HashMap<String, List<Link>>()
        for (k in o.keys()) {
            out[k] = (o.optJSONArray(k) ?: JSONArray()).objs().map {
                Link(it.str("url"), it.str("label"), it.optLong("ts"), (it.optJSONArray("tokens") ?: JSONArray()).strs().toSet())
            }
        }
        return out
    }

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
        val st = State.readOnly(ctx)
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

    /** Lecture / tutorial for a class: what its name says, else what your timetable says. ONE rule for every screen. */
    fun prefOf(ctx: Context, c: Checker.ClassItem): Boolean? =
        Checker.preferLecOf(c.name) ?: Timetable.preferLecAt(ctx, c.code.ifEmpty { c.course }, c.start)

    /** THE attendance page for a class, for every screen: your pick, else Spectrum's best page, else the event's own. "" = none. */
    fun attendanceFor(ctx: Context, c: Checker.ClassItem): String {
        val d = localDt(c.start); val e = localDt(c.end)
        val att = linksForCourse(ctx, c.code.ifEmpty { c.course }, c.courseId, groupTokens(c.name), prefOf(ctx, c),
            day = d.dayOfWeek.value, startMin = d.hour * 60 + d.minute, endMin = e.hour * 60 + e.minute).second
        return att.ifEmpty { if (c.link.contains("/mod/attendance/", true)) c.link else c.attUrl }
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
        day: Int = 0, startMin: Int = -1, endMin: Int = -1, online: Boolean = false,
    ): Triple<String, String, String> {
        val p = Prefs(ctx)
        val id = if (courseId != 0L) courseId else courseIdForCode(ctx, code)
        // your choice for this kind of class (Attendance picker) first, then the app's guess
        val att = Timetable.attFor(ctx, code, preferLec).ifEmpty { attendanceUrl(ctx, id, preferLec) }
        // [online]: you set this class online, so the course being back in person does not hide its link
        val meeting = if (!online && Timetable.inPerson(ctx, code)) "" else Timetable.meetingLinkOf(ctx, code, preferLec).ifEmpty {
            if (id == 0L) "" else {
                val ls = linksFromJson(State.readOnly(ctx).j.optJSONObject("links") ?: JSONObject())
                    .get(id.toString()) ?: emptyList()
                // Score links against EVERYTHING the requesting class knows (user request:
                // "filter every detail like time, date, lecture name, occ, course"):
                //   occ tokens (event name + user's occ)  — strongest
                //   lecture/tutorial kind                  — if preferLec set
                //   DAY name + TIME RANGE in the label    — e.g. "Monday 12:00 - 02:00"
                // Captured labels carry text like
                //   "Attendance k ( L ) @ Bilik Kuliah Monday 12:00 - 02:00 (Rasha Atallah)"
                // so day-name and start-hour matching against the label is reliable.
                val mine = Groups.mine(State.readOnly(ctx), Prefs(ctx), code.uppercase())
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
                    val kind = Checker.attKind(l.label)
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

    internal fun courseId(e: JSONObject): Long = e.optJSONObject("course")?.optLong("id", 0L) ?: 0L

    private fun courseGroupTokens(cid: Long, cal: List<JSONObject>): Set<String> {
        val toks = HashSet<String>()
        for (x in cal) if (Checker.isAttEvent(x) && courseId(x) == cid) toks.addAll(groupTokens(x.str("name")))
        return toks
    }

    /**
     * Which attendance link the user saved counts for a class. A link saved for the lecture or tutorial is always a pick;
     * the whole-course link only when [pinned]; a pasted, unpinned one only fills in when the app found no page itself
     * ([autoUrl] empty): auto wins unless you pinned (ticket SN-UDTQV). Several pasted lines: the first one.
     */
    internal fun pickAttendance(kindLink: String, wideLink: String, pinned: Boolean, autoUrl: () -> String): String {
        fun first(s: String) = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        first(kindLink).let { if (it.isNotEmpty()) return it }
        val wide = first(wideLink)
        if (wide.isEmpty()) return ""
        return if (pinned || autoUrl().isEmpty()) wide else ""
    }

    private val ATT_CMID = Regex("""/mod/attendance/view\.php\?id=(\d+)""")

    /** The attendance page id in a Spectrum attendance link, else null. */
    internal fun attendanceCmid(url: String): Long? = ATT_CMID.find(url)?.groupValues?.get(1)?.toLongOrNull()

    internal fun linksForEvent(ctx: Context, e: JSONObject, cal: List<JSONObject>, links: Map<String, List<Link>>): List<Pair<String, String>> {
        val cid = courseId(e)
        // back in person: no meeting links for this course anywhere (hero, alerts, widget)
        // (a class switched online for its own day keeps its links)
        val code = Timetable.courseCode(e.optJSONObject("course"))
        if (Timetable.inPerson(ctx, code) && !Timetable.onlineAt(ctx, code, Alerts.classTimes(ctx, e).first)) return emptyList()
        var evTokens = groupTokens(e.str("name"))
        if (evTokens.isEmpty()) evTokens = courseGroupTokens(cid, cal)
        val cands = ArrayList<Cand>()
        val seen = HashSet<String>()
        // a link you pasted for this course beats anything detected, on every screen and in the class alert
        Timetable.meetingLinkOf(ctx, Timetable.courseCode(e.optJSONObject("course")), Checker.preferLecOf(e.str("name"))).takeIf { it.isNotEmpty() }?.let { u ->
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
}
