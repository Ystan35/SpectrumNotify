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

    private val GROUP_RE = Regex("""\b(?:occ\.?\s*\d+|[kt]\d+|grou?p\s*\d+|tutorial\s*\d+)\b""", RegexOption.IGNORE_CASE)
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
    fun groupTokens(text: String?): Set<String> =
        GROUP_RE.findAll(unesc(text)).map { it.value.replace(Regex("[\\s.]+"), "").lowercase() }.toSet()

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

    private fun linkIsMeeting(url: String) = linkType(url) != "Teams channel/chat"

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

    data class ClassItem(
        val name: String, val course: String, val full: String,
        val start: Long, val end: Long, val link: String, val courseUrl: String, val manual: Boolean,
    )
    data class DueItem(val name: String, val course: String, val ts: Long, val url: String)
    class Overview(val classes: List<ClassItem>, val due: List<DueItem>)

    /** Classes (not yet finished) and deadlines from the cached data, for the main screen. */
    fun overview(ctx: Context): Overview {
        val p = Prefs(ctx)
        val st = State(ctx)
        val now = System.currentTimeMillis() / 1000
        val cal = myCalendar(st, p)
        val links = linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        val classes = cal.filter { isAttEvent(it) }.map { e ->
            val start = e.optLong("timestart")
            val dur = e.optLong("timeduration")
            val c = e.optJSONObject("course")
            val short = c?.str("shortname") ?: ""
            val full = c?.str("fullname") ?: ""
            val cid = courseId(e)
            ClassItem(
                unesc(e.str("name")), short.ifEmpty { full.ifEmpty { "Class" } }, full,
                start, start + (if (dur > 0) dur else 3600L),
                linksForEvent(e, cal, links).firstOrNull()?.first ?: "",
                if (cid != 0L) "${p.site}/course/view.php?id=$cid" else p.site,
                isManual(e, p),
            )
        }.filter { it.end > now }.sortedBy { it.start }
        val due = (st.j.optJSONArray("deadlines") ?: JSONArray()).objs().mapNotNull { e ->
            val ts = e.optLong("timesort")
            if (ts <= now) return@mapNotNull null
            val act = e.optJSONObject("action")
            DueItem(
                unesc(e.str("name")).ifEmpty { "Deadline" },
                e.optJSONObject("course")?.str("shortname") ?: "",
                ts,
                e.str("url").ifEmpty { act?.str("url") ?: "" },
            )
        }.sortedBy { it.ts }
        return Overview(classes, due)
    }

    private fun courseId(e: JSONObject): Long = e.optJSONObject("course")?.optLong("id", 0L) ?: 0L

    private fun courseGroupTokens(cid: Long, cal: List<JSONObject>): Set<String> {
        val toks = HashSet<String>()
        for (x in cal) if (isAttEvent(x) && courseId(x) == cid) toks.addAll(groupTokens(x.str("name")))
        return toks
    }

    private fun linksForEvent(e: JSONObject, cal: List<JSONObject>, links: Map<String, List<Link>>): List<Pair<String, String>> {
        val cid = courseId(e)
        var evTokens = groupTokens(e.str("name"))
        if (evTokens.isEmpty()) evTokens = courseGroupTokens(cid, cal)
        val cands = ArrayList<Cand>()
        val seen = HashSet<String>()
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
    private fun snapshotCourse(api: Moodle, site: String, c: JSONObject, extra: MutableMap<String, String>,
                               secOf: MutableMap<String, String>, secNames: MutableMap<String, String>): Pair<Map<String, Triple<String, String, String>>, List<Link>> {
        val snap = LinkedHashMap<String, Triple<String, String, String>>()
        val raw = ArrayList<Triple<String, String, Long>>()
        val cid = c.optLong("id")
        for (sec in api.arr("core_course_get_contents", mapOf("courseid" to cid)).objs()) {
            val sname = sec.str("name")
            val skey = sec.optLong("id").toString()
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
        val enrolled = api.arr("core_enrol_get_users_courses", mapOf("userid" to userid)).objs()
        Courses.remember(st, enrolled)
        for (c in enrolled) {
            val cid = c.optLong("id")
            val ckey = "course$cid"
            val extra = HashMap<String, String>()
            val secOf = HashMap<String, String>()
            val secNames = LinkedHashMap<String, String>()
            val result = try {
                snapshotCourse(api, p.site, c, extra, secOf, secNames)
            } catch (e: Exception) {
                Log.w(TAG, "course $cid skipped: $e")
                prevLinks["$cid"]?.let { linksByCourse["$cid"] = it }
                continue
            }
            val snap = result.first
            Sections.remember(st, cid, secNames)
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
    private fun checkClassLinks(ctx: Context, st: State, p: Prefs, cal: List<JSONObject>, links: Map<String, List<Link>>, now: Long) {
        val sent = st.strings("links_sent")
        for (e in cal) {
            if (!isAttEvent(e)) continue
            val start = e.optLong("timestart")
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
            val ls = linksForEvent(e, cal, links)
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
        val dl = (st.j.optJSONArray("deadlines") ?: JSONArray()).objs()
        val links = linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        try { checkClassLinks(ctx, st, p, cal, links, now.toEpochSecond()) } catch (e: Exception) { Log.w(TAG, "class links: $e") }
        try { sendMorningSummary(ctx, st, p, cal, dl, now) } catch (e: Exception) { Log.w(TAG, "summary: $e") }
        try { sendAfternoon(ctx, st, p, cal, now) } catch (e: Exception) { Log.w(TAG, "afternoon: $e") }
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
                NotifyLog.sys(ctx, "Check failed: ${msg.take(150)}")
                if ((p.fails >= 3 || "invalidtoken" in msg) && !p.alerted) {
                    Notifier.push(
                        ctx, "Spectrum Notify is failing",
                        "${msg.take(300)}\n\nIf this says invalidtoken, log in again in the app.", true,
                    )
                    p.alerted = true
                }
            }
        }
        try { Catalog.refresh(ctx) } catch (e: Exception) { Log.w(TAG, "catalog: $e") }
        Scheduler.scheduleAll(ctx)
    }
}
