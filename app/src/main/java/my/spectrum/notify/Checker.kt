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

    private val ATT_RE = Regex("attend|kehadiran", RegexOption.IGNORE_CASE)
    // ------------------------------------------------------------ link helpers
    /**
     * Most courses are one flat list: an Occ heading ("6. Dr. Wong OCC 6 & 10") then that lecturer's pages ("Week 1"),
     * with nothing from Spectrum saying they belong together. Sections in order -> page to the heading it sits under.
     * Guessed from the order, so it stays careful: pages after the last Occ heading may be for everyone and stay on
     * their own, and a lecturer heading with no Occ ends the group before it.
     */
    fun occParents(sections: List<Pair<String, String>>): Map<String, String> {
        val out = HashMap<String, String>()
        var head = ""
        var occs = emptySet<String>()
        val under = ArrayList<String>()
        fun close() { for (id in under) out[id] = head; under.clear() }
        for ((id, name) in sections) {
            val t = Links.groupTokens(name).filter { it.startsWith("occ") }.toSet()
            when {
                t.isEmpty() && Links.LECTURER_RE.containsMatchIn(unesc(name)) -> { close(); head = ""; occs = emptySet() }
                t.isEmpty() || (head.isNotEmpty() && occs.containsAll(t)) -> if (head.isNotEmpty()) under.add(id)
                else -> { close(); head = id; occs = t }
            }
        }
        return out
    }

    /** "lec", "tut", "both" (e.g. "T&L") or "other", from an attendance title. */
    fun attKind(name: String): String {
        val lec = Links.LEC_RE.containsMatchIn(name)
        val tut = Links.TUT_RE.containsMatchIn(name)
        return when { lec && tut -> "both"; lec -> "lec"; tut -> "tut"; else -> "other" }
    }

    /** preferLec for the link resolver: true = lecture, false = tutorial, null = unknown or both. */
    fun preferLecOf(name: String): Boolean? = when (attKind(name)) { "lec" -> true; "tut" -> false; else -> null }

    // ------------------------------------------------------------ event helpers
    /**
     * A deadline the user hid ("Hide this (wrong?)"): no card, no reminder, not in the morning summary.
     * Hidden by id from Home, or by name from Notifications (reminders carry only the name).
     */
    fun isHiddenDeadline(ctx: Context, e: JSONObject): Boolean {
        val hidden = ctx.getSharedPreferences("health", Context.MODE_PRIVATE).getStringSet("hiddenEvents", emptySet())!!
        val id = e.optLong("id")
        return (id != 0L && "dl:$id" in hidden) || hiddenNameKey(unesc(e.str("name"))) in hidden
    }

    /** One task you hid, with every key that hides it (by name, and by event id when known). */
    class HiddenTask(val keys: List<String>, val name: String, val course: String, val due: Long)

    /** Your hidden tasks for Settings, soonest due first (unknown due times last). */
    fun hiddenTasks(ctx: Context): List<HiddenTask> {
        val keys = ctx.getSharedPreferences("health", Context.MODE_PRIVATE).getStringSet("hiddenEvents", emptySet())!!
        val meta = NotifyLog.hiddenMeta(ctx)
        // an id key goes with the name key hidden with it (same name in its meta); alone it is a task of its own
        val byName = keys.filter { it.startsWith("dn:") }.associateWith { mutableListOf(it) }
        val loose = ArrayList<String>()
        for (k in keys.filter { !it.startsWith("dn:") }) {
            val n = meta.optJSONObject(k)?.optString("n").orEmpty()
            byName[hiddenNameKey(n)]?.add(k) ?: loose.add(k)
        }
        return (byName.values.map { it.toList() } + loose.map { listOf(it) }).map { ks ->
            val m = ks.firstNotNullOfOrNull { meta.optJSONObject(it) }
            HiddenTask(ks, m?.optString("n").orEmpty().ifEmpty { ks[0].removePrefix("dn:") }, m?.optString("c").orEmpty(), m?.optLong("t") ?: 0L)
        }.sortedBy { if (it.due > 0) it.due else Long.MAX_VALUE }
    }

    /**
     * After a successful deadline check: note the name / course / due time of hidden tasks it saw ([seen]), and
     * forget hidden tasks whose due time has passed. One not seen and with no due time gets a guess of 15 days
     * from now (the check looks 14 days ahead, so a task still to come shows up and gets its real time before then).
     */
    private fun pruneHidden(ctx: Context, seen: List<JSONObject>, now: Long) {
        val sp = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
        val keys = sp.getStringSet("hiddenEvents", emptySet())!!.toMutableSet()
        val meta = NotifyLog.hiddenMeta(ctx)
        for (e in seen) {
            val name = unesc(e.str("name"))
            val info = JSONObject().put("n", name).put("c", e.optJSONObject("course")?.str("fullname").orEmpty()).put("t", e.optLong("timesort"))
            listOf("dl:" + e.optLong("id"), hiddenNameKey(name)).filter { it in keys }.forEach { meta.put(it, info) }
        }
        for (k in keys.toList()) {
            val m = meta.optJSONObject(k) ?: JSONObject().also { meta.put(k, it) }
            val t = m.optLong("t")
            if (t == 0L) m.put("t", now + 15 * 86400)
            else if (t < now) { keys.remove(k); meta.remove(k) }
        }
        sp.edit().putStringSet("hiddenEvents", keys).putString("hiddenMeta", meta.toString()).apply()
    }

    /** "dn:<name>" key [NotificationsActivity] stores when a deadline reminder is hidden. */
    fun hiddenNameKey(name: String): String = "dn:" + name.trim().lowercase()

    /** The task name in a deadline reminder's title ("Due in ~5h: Quiz 2" -> "Quiz 2"), or null for other titles. */
    fun reminderTaskName(title: String): String? =
        Regex("""^Due in ~\d+h:\s*(.+)$""").find(title)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun isAttEvent(e: JSONObject): Boolean = e.str("modulename") == "attendance" || ATT_RE.containsMatchIn(e.str("name"))

    internal fun isManual(e: JSONObject, p: Prefs): Boolean {
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

    /**
     * [myCalendar] without the classes you removed on the Timetable ("Remove from my timetable"): what Home, the
     * widget, the alerts and the daily messages use. (The Timetable itself learns from [myCalendar] and hides them.)
     */
    fun activeCalendar(ctx: Context, st: State, p: Prefs): List<JSONObject> {
        val removed = Timetable.removedKeys(ctx)
        // an attendance page that names only other Occs ("OCC13, 15") is not your class (ticket SN-J6ACW). Only an Occ you
        // set or confirmed counts: the guessed list also holds the other groups' numbers. [myCalendar] stays whole so
        // the app can still learn from it.
        val sure = HashMap<String, Set<String>>()
        return myCalendar(st, p).filter { e ->
            if (!isAttEvent(e)) return@filter true
            if (Timetable.slotKeyOf(e) in removed) return@filter false
            val code = Timetable.courseCode(e.optJSONObject("course"))
            !Groups.isForeign(e.str("name"), sure.getOrPut(code) { Groups.mineSure(p, code) })
        }
    }

    // ------------------------------------------------------------ tasks marked done
    // One "done" for a task wherever you mark it (Home or Notifications), keyed by its name because
    // reminders in the Notifications list carry only the name. A done task gets no more reminders.
    private fun doneKey(name: String) = name.trim().lowercase()

    fun isTaskDone(ctx: Context, name: String, eventId: Long = 0L, ts: Long = 0L): Boolean {
        val h = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
        return doneKey(name) in h.getStringSet("doneTasks", emptySet())!! ||
            (eventId != 0L && h.getBoolean("dueDone:$eventId:$ts", false))   // marked on Home before 0.30.1
    }

    fun setTaskDone(ctx: Context, name: String, done: Boolean, eventId: Long = 0L, ts: Long = 0L) {
        val h = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
        val set = h.getStringSet("doneTasks", emptySet())!!.toMutableSet()
        if (done) set.add(doneKey(name)) else set.remove(doneKey(name))
        val ed = h.edit().putStringSet("doneTasks", set)
        if (!done && eventId != 0L) ed.remove("dueDone:$eventId:$ts")
        ed.apply()
    }

    /** manual = attendance by Teams recording (Settings → Your classes); own = from your timetable, not Spectrum. */
    data class ClassItem(
        val name: String, val course: String, val full: String,
        val start: Long, val end: Long, val link: String, val courseUrl: String, val manual: Boolean,
        val courseId: Long = 0L,
        val code: String = "",
        val own: Boolean = false,
        /** Your own entries for this course hidden because this Spectrum class overlaps them, e.g. "12:00–13:00". */
        val hiddenOwn: String = "",
        /** The class's attendance page (Spectrum's event link, or the one found for your own entry); "" = none. */
        val attUrl: String = "",
    )
    data class DueItem(val name: String, val course: String, val ts: Long, val url: String, val eventId: Long = 0L)
    class Overview(val classes: List<ClassItem>, val due: List<DueItem>)

    /** Classes (not yet finished) and deadlines from the cached data, for the main screen. */
    fun overview(ctx: Context): Overview {
        val p = Prefs(ctx)
        // read-only shared copy + cached class list: every tab asked for both and re-parsed the 0.5 MB state.json each
        // time, which made switching tabs wait ~2 s. The cache is rebuilt when the data, a setting or the date changes.
        val st = State.readOnly(ctx)
        val now = System.currentTimeMillis() / 1000
        val classes = DataStamp.get("classItems", ctx) { classItems(ctx, st, p) }.filter { it.end > now }
        val due = (st.j.optJSONArray("deadlines") ?: JSONArray()).objs().mapNotNull { e ->
            val ts = e.optLong("timesort")
            if (ts <= now || isHiddenDeadline(ctx, e)) return@mapNotNull null
            val act = e.optJSONObject("action")
            DueItem(
                unesc(e.str("name")).ifEmpty { "Deadline" },
                e.optJSONObject("course")?.str("shortname") ?: "",
                ts,
                e.str("url").ifEmpty { act?.str("url") ?: "" },
                e.optLong("id"),
            )
        }.sortedBy { it.ts }
        return Overview(classes, due)
    }

    /**
     * Every class from today for two weeks, finished ones included: Spectrum's (your corrected times, minus the
     * ones you removed) and your own entries, merged so a class never shows twice. Only on teaching days of each
     * class's own date (holiday mode). Home, the widget and the daily messages all use this one list.
     */
    internal fun classItems(ctx: Context, st: State, p: Prefs): List<ClassItem> {
        val cal = activeCalendar(ctx, st, p)
        val links = Links.linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        val today = java.time.LocalDate.now(zone())
        val classes = cal.filter { isAttEvent(it) && AcademicCalendar.inTeaching(ctx, localDt(it.optLong("timestart")).toLocalDate()) }.map { e ->
            val c = e.optJSONObject("course")
            val short = c?.str("shortname") ?: ""
            val full = c?.str("fullname") ?: ""
            val cid = Links.courseId(e)
            val (startShown, endShown) = Alerts.classTimes(ctx, e)
            val code = Timetable.courseCode(c)
            // ticket SN-6QMFL: Spectrum's name often does not say lecture or tutorial, so the links of the other one were
            // picked; your timetable knows (your own entry or the UM class at that time), and the links you saved for
            // that kind win over anything found
            val pref = preferLecOf(e.str("name")) ?: Timetable.preferLecAt(ctx, code, startShown)
            val yours = if (!Timetable.onlineAt(ctx, code, startShown) && Timetable.inPerson(ctx, code)) "" else Timetable.meetingLinkOf(ctx, code, pref)
            ClassItem(
                unesc(e.str("name")), short.ifEmpty { full.ifEmpty { "Class" } }, full,
                startShown, endShown,
                yours.ifEmpty { Links.linksForEvent(ctx, e, cal, links).firstOrNull()?.first ?: "" },
                if (cid != 0L) "${p.site}/course/view.php?id=$cid" else p.site,
                isManual(e, p), cid,
                code = code,
                attUrl = Timetable.attFor(ctx, code, pref).ifEmpty { Links.attendanceUrl(ctx, cid, pref) }.ifEmpty { e.str("url") },   // same order as attendanceFor
            )
        }.filter { !localDt(it.end).toLocalDate().isBefore(today) }
        // manual timetable entries (user-added or catalog auto-setup) belong on the hero too;
        // links resolved the same way every other screen resolves them
        val zoneId = zone()
        val manualClasses = Timetable.manual(ctx).flatMap { slot ->
            (0..13L).map { off -> today.plusDays(off) }
                .filter { dd -> dd.dayOfWeek.value == slot.day }
                // your own entries only in their own semester's teaching days
                .filter { dd -> Timetable.inSem(slot, AcademicCalendar.labelFor(ctx, dd)) && AcademicCalendar.inTeaching(ctx, dd) }
                .map { dd ->
                    val dayStart = dd.atStartOfDay(zoneId).toEpochSecond()
                    Triple(slot, dayStart + slot.start * 60L, dayStart + slot.end * 60L)
                }
        }.map { (slot, start, end) ->
            val cid = Links.courseIdForCode(ctx, slot.course)
            // ticket SN-VQ3YR: this class's kind, day and time, so its own lecture or tutorial links are used (asked
            // without them, only a link saved for the whole course counted and HCI had no Join class)
            val pref = when (slot.kind) { SlotKind.LECTURE -> true; SlotKind.TUTORIAL -> false; else -> preferLecOf(slot.title) }
            val date = localDt(start).toLocalDate()
            val (meeting, att, coursePage) = Links.linksForCourse(
                ctx, slot.course, cid, Links.groupTokens(slot.title + " " + slot.note), pref, slot.day, slot.start, slot.end,
                online = Timetable.classMode(ctx, slot.key, date) == "online",
            )
            ClassItem(
                slot.title.ifEmpty { "Class" }, slot.course,
                slot.course, start, end,
                meeting,   // never the attendance page: that is attUrl (Join class opened it, even in person)
                coursePage,
                // Teams-recording courses only — this used to be true for every own entry, so Home said
                // "Attendance for this course is by Teams recording" for any class you added
                p.manualList.any { slot.course.lowercase().contains(it) }, cid,
                code = slot.course.uppercase(), own = true,
                // a pasted attendance link can hold several, one per line: the first one
                attUrl = att.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty(),
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
        return (shown + ownKept)
            .distinctBy { "${it.course}:${it.start / 900}" }
            .sortedBy { it.start }
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
                               kinds: MutableMap<String, FileKind>,
                               secOf: MutableMap<String, String>, secNames: MutableMap<String, String>,
                               secParent: MutableMap<String, String>, pub: MutableSet<String>,
                               attCmids: MutableMap<Long, HashMap<Long, MutableSet<String>>>, occSeen: MutableMap<Long, MutableSet<String>>,
                               st: State): Pair<Map<String, Triple<String, String, String>>, List<Links.Link>> {
        val snap = LinkedHashMap<String, Triple<String, String, String>>()
        val raw = ArrayList<Triple<String, String, Long>>()
        val cid = c.optLong("id")
        val occSets = ArrayList<Set<String>>()
        val sections = api.arr("core_course_get_contents", mapOf("courseid" to cid)).objs()
        // Sub-sections (Moodle 4.5+): a "subsection" module in a main page stands for a page of its own, which
        // Spectrum lists separately (component "mod_subsection", itemid = that module; its link names the page).
        // Remember which main page each sits in, so the pages list nests them and switching a main page off covers them.
        val parentByCm = HashMap<Long, String>()
        val parentBySec = HashMap<String, String>()
        for (sec in sections) for (m in (sec.optJSONArray("modules") ?: JSONArray()).objs()) {
            if (m.str("modname") != "subsection") continue
            parentByCm[m.optLong("id")] = sec.optLong("id").toString()
            Regex("""section\.php\?id=(\d+)""").find(m.str("url"))?.let { parentBySec[it.groupValues[1]] = sec.optLong("id").toString() }
        }
        for (sec in sections) {
            val k = sec.optLong("id").toString()
            val parent = (if (sec.str("component") == "mod_subsection") parentByCm[sec.optLong("itemid")] else null) ?: parentBySec[k]
            if (parent != null && parent != k) secParent[k] = parent
        }
        // the whole course as a list for its Materials screen (Courses tab): what you can see, minus plain text labels
        val mats = JSONArray()
        for (sec in sections) for (m in (sec.optJSONArray("modules") ?: JSONArray()).objs()) {
            val mod = m.str("modname")
            if (mod == "subsection" || mod == "label" || !m.optBoolean("uservisible", true)) continue
            val files = JSONArray()
            for (f in (m.optJSONArray("contents") ?: JSONArray()).objs()) if (f.str("type") == "file" || f.str("type") == "url")
                files.put(JSONObject().put("n", f.str("filename")).put("u", f.str("fileurl")).put("m", f.str("mimetype")).put("s", f.optLong("filesize")))
            mats.put(JSONObject().put("sec", sec.optLong("id")).put("sn", sec.str("name")).put("n", m.str("name"))
                .put("mod", mod).put("u", m.str("url")).put("f", files))
        }
        (st.j.optJSONObject("materials") ?: JSONObject().also { st.j.put("materials", it) }).put("$cid", mats)
        // no sub-sections on the course: its pages still sit under Occ headings
        if (secParent.isEmpty()) secParent.putAll(occParents(sections.map { it.optLong("id").toString() to it.str("name") }))
        val nameById = sections.associate { it.optLong("id").toString() to it.str("name") }
        var attSeq = 0   // place of each attendance page on the course page, for the picker's list
        for (sec in sections) {
            val sname = sec.str("name")
            val skey = sec.optLong("id").toString()
            // A link's own name often does not say whose it is ("Links.Link for online classes"), but the section it sits in
            // does ("DR. FAEZAH KASSIM (OCC 1 & 2)" › "Pautan MS Teams"): when the section or its main section names a
            // group, that goes into the link's label, so its Occ is matched like one in the name
            val where = listOf(sname, secParent[skey]?.let { nameById[it] } ?: "").filter { it.isNotBlank() }
                .joinToString(" › ")
            val inGroup = if (Links.groupTokens(where).isNotEmpty()) " [" + clean(where, 60) + "]" else ""
            // section descriptions often hold the weekly meeting links (e.g. "Online Lecture Links.Link …: <href>")
            // (Moodle sends "summary" as a plain HTML string; scan all of it, links can sit past 2000 chars)
            val secDesc = sec.optJSONObject("summary")?.optString("text") ?: sec.optString("summary", "")
            for (u in Links.findLinks(secDesc)) raw.add(Triple(u, "section: " + clean(sname, 50) + inGroup, mt0Of(sec)))
            val mods = (sec.optJSONArray("modules") ?: JSONArray()).objs()
            // an empty Occ heading is still listed: its pages nest under it and are muted with it
            if (mods.isNotEmpty() || skey in secParent.values) {
                secNames[skey] = sname.ifEmpty { if (sec.optInt("section") == 0) "General" else "Page ${sec.optInt("section")}" }
            }
            for (m in mods) {
                val contents = (m.optJSONArray("contents") ?: JSONArray()).objs()
                val mt = contents.maxOfOrNull { it.optLong("timemodified", 0L) } ?: 0L
                val key = "mod:${m.optLong("id")}:$mt"
                val name = m.str("name")
                val url = m.str("url")
                // a sub-section entry is a page, not material: its own files are what notify you
                val isSub = m.str("modname") == "subsection"
                if (!isSub) snap[key] = Triple(name, url, sname)
                // forums are for everyone in the course: never muted for another Occ
                if (m.str("modname") in setOf("forum", "hsuforum")) pub.add(key)
                if (!isSub) secOf[key] = skey
                // what it opens (slides, PDF, …) from its file name / type, or the address of a link
                val file = contents.firstOrNull()
                if (!isSub) FileKind.of(if (m.str("modname") == "url") file?.str("fileurl") ?: url else url,
                    "${file?.str("filename") ?: ""} ${file?.str("mimetype") ?: ""} ${m.str("modname").takeIf { it == "folder" } ?: ""}")
                    ?.let { kinds[key] = it }
                val desc = clean(m.str("description"), 300)
                if (desc.isNotEmpty() && !isSub) extra[key] = desc
                if (m.str("modname") == "attendance" && m.optLong("id") != 0L) {
                    // Spectrum can list one attendance module PER OCC (e.g. "Attendance Occ 2",
                    // "Attendance Occ 4"). Remember every one with its group tokens so the
                    // resolver can pick the user's OWN occ page instead of the first captured.
                    val attKey = m.optLong("id")
                    val nm = m.str("name")
                    val toks = Links.groupTokens(nm + " " + m.str("intro"))
                    if (attCmids[cid] == null) attCmids[cid] = HashMap()
                    (attCmids[cid] as HashMap<Long, MutableSet<String>>)[attKey] = toks.toMutableSet()
                    // remember the module name AND its kind so the resolver can filter
                    // directly by lecture/tutorial (user request: filter titles containing
                    // lecture / L / tutorial / class)
                    val kind = attKind(nm)
                    val lbl = st.j.optJSONObject("attlabels") ?: JSONObject()
                    lbl.put("$cid:$attKey", JSONObject().put("name", nm).put("kind", kind).put("vis", m.optBoolean("uservisible", true)).put("o", attSeq++))
                    st.j.put("attlabels", lbl)
                }
                // Occ detection via availability: a module the user CAN see that names an Occ
                // means the user belongs to it (Spectrum restricts other Occs' pages).
                // A page can list several Occs ("OCC 2, 12, 16"); you are in one Occ that every
                // visible page names, so keep the Occs common to all of them when there are any.
                if (m.optBoolean("uservisible", true)) {
                    val occs = Links.groupTokens(m.str("name") + " " + m.str("intro")).filter { it.startsWith("occ") }.toSet()
                    if (occs.isNotEmpty()) occSets.add(occs)
                }
                if (m.str("modname") in Links.MEETING_MODS && m.optBoolean("uservisible", true) && url.isNotEmpty()) {
                    raw.add(Triple(url, clean(name) + inGroup, mt))
                }
                for (u in Links.findLinks(m.str("description"))) raw.add(Triple(u, "page: " + clean(name, 50) + inGroup, mt))
                if (m.str("modname") == "url") {
                    for (f in contents) for (u in Links.findLinks(f.str("fileurl"))) {
                        raw.add(Triple(u, clean(name) + inGroup, f.optLong("timemodified", 0L)))
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
                    for (u in Links.findLinks(d.str("message"))) {
                        raw.add(Triple(u, "announcement: " + clean(d.str("name"), 50), maxOf(d.optLong("timemodified"), d.optLong("created"))))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "forum check skipped for ${c.str("shortname")}: $e")
        }
        return Pair(snap, Links.dedupe(raw))
    }

    /** Items no longer listed that a course's seen list still remembers. */
    private const val SEEN_KEEP = 400

    private fun pushCourse(ctx: Context, site: String, c: JSONObject, items: List<Triple<String, String, String>>, kinds: List<FileKind?>) {
        val cname = c.str("fullname").ifEmpty { c.str("shortname") }
        val lines = ArrayList<String>()
        for ((i, item) in items.take(15).withIndex()) {
            val (name, url, sec) = item
            // "- Lecture 3 (slides) [Week 3]": what it is, before you open it
            var line = "- $name" + (kinds.getOrNull(i)?.let { " (${it.word})" } ?: "") + (if (sec.isNotEmpty()) " [$sec]" else "")
            if (url.isNotEmpty()) line += "\n  $url"
            lines.add(line)
        }
        if (items.size > 15) lines.add("...and ${items.size - 15} more")
        val buttons = ArrayList<Pair<String, String>>()
        for ((i, it) in items.take(3).withIndex()) {
            // "Open slides" / "Slides 1", "PDF 2": the type on the button (its icon comes from the same word)
            val k = kinds.getOrNull(i)
            val label = if (items.size == 1) (if (k != null) "Open ${k.word}" else "Open") else "${k?.title ?: "Open"} ${i + 1}"
            if (it.second.isNotEmpty()) buttons.add(Pair(label, it.second))
        }
        if (buttons.isEmpty()) buttons.add(Pair("Open course", "$site/course/view.php?id=${c.optLong("id")}"))
        Notifier.push(ctx, "$cname: ${items.size} new", lines.joinToString("\n"), false, buttons)
    }

    private fun checkCourses(ctx: Context, api: Moodle, p: Prefs, userid: Long, st: State) {
        val prevLinks = Links.linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        val linksByCourse = LinkedHashMap<String, List<Links.Link>>()
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
            val kinds = HashMap<String, FileKind>()
            val secOf = HashMap<String, String>()
            val secNames = LinkedHashMap<String, String>()
            val secParent = HashMap<String, String>()
            val pub = HashSet<String>()
            val result = try {
                snapshotCourse(api, p.site, c, extra, kinds, secOf, secNames, secParent, pub, attCmids, occSeen, st)
            } catch (e: Exception) {
                Log.w(TAG, "course $cid skipped: $e")
                prevLinks["$cid"]?.let { linksByCourse["$cid"] = it }
                continue
            }
            val snap = result.first
            Sections.remember(st, cid, secNames, secParent)
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
            askOnlineAgain(ctx, st, Timetable.courseCode(c), prevLinks["$cid"], result.second)
            val known = st.j.has(ckey)
            val old = st.strings(ckey)
            val newKeys = snap.keys.filter { it !in old }
            if (known && newKeys.isNotEmpty()) {
                // pages you switched off, then items that name another group (Occ / tutorial); both still count as seen
                val code = Timetable.courseCode(c)
                val off = Sections.offSet(ctx, cid)
                // a page switched off also covers its sub-sections
                val kept = Sections.keptOnSet(ctx, cid)
                val inPages = newKeys.filter { k -> secOf[k]?.let { Sections.isOff(off, secParent, it, kept) } != true }
                val mine = Groups.mine(st, p, code)
                val sure = Groups.mineSure(p, code)
                val fresh = inPages.filter { k ->
                    // announcements and forums are public: always notify
                    if (k.startsWith("ann:") || k in pub) return@filter true
                    // a page you switched back on: yours, whichever Occ it names
                    if (secOf[k]?.let { Sections.isKeptOn(kept, off, secParent, it) } == true) return@filter true
                    val t = snap[k]
                    // a page (or the main page it sits in, "Tutorial Occ 3" › "Week 2") for another Occ: muted, but only
                    // when your Occ is one you set or confirmed; not sure = it stays on
                    val mainPage = secOf[k]?.let { secParent[it] }?.let { secNames[it] } ?: ""
                    if (Groups.isForeign("${t?.third ?: ""} $mainPage", sure)) return@filter false
                    // the item itself names another group ("Occ 3 slides")
                    !Groups.isForeign("${t?.first ?: ""} ${extra[k] ?: ""}", mine)
                }
                val freshItems = fresh.filter { snap[it] != null }
                if (freshItems.isNotEmpty()) pushCourse(ctx, p.site, c, freshItems.map { snap[it]!! }, freshItems.map { kinds[it] })
                if (inPages.size < newKeys.size) {
                    NotifyLog.sys(ctx, "Skipped ${newKeys.size - inPages.size} item(s) on pages switched off in $code")
                }
                if (fresh.size < inPages.size) {
                    NotifyLog.sys(ctx, "Skipped ${inPages.size - fresh.size} item(s) for other groups in $code")
                }
            }
            // seen only ever grows: replacing it with this check's list forgot everything a failed fetch left out
            // (the forum call fails quietly), and the next good check announced those old posts as new
            st.putStrings(ckey, (old.filter { it !in snap }.takeLast(SEEN_KEEP) + snap.keys))
            // saved now, not at the end of the check: a check stopped halfway would send this course again
            if (known && newKeys.isNotEmpty()) st.save()
        }
        st.j.put("links", Links.linksToJson(linksByCourse))
    }

    /**
     * A course set to in person posted a meeting link it did not have before: ask once per link whether its
     * classes are online again (Online again / Keep in person / the link).
     */
    private fun askOnlineAgain(ctx: Context, st: State, code: String, before: List<Links.Link>?, now: List<Links.Link>) {
        if (before == null || !Timetable.inPerson(ctx, code)) return
        val known = before.map { it.url }.toSet()
        val fresh = now.firstOrNull { Links.linkIsMeeting(it.url) && it.url !in known } ?: return
        val asked = st.strings("online_asked")
        val key = "$code|${fresh.url}"
        if (key in asked) return
        asked.add(key)
        st.putStrings("online_asked", asked.toList().takeLast(100))
        Notifier.push(ctx, "${Shorts.display(ctx, code)} posted a meeting link",
            "You set ${Shorts.display(ctx, code)} to in person. Is it online again?\n${fresh.label}\n${fresh.url}", true,
            listOf(Pair("Online again", AppLink.online(code)), Pair("Keep in person", AppLink.keepInPerson(code)), Pair("Open link", fresh.url)))
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
            // forum posts and announcements are public: never skipped for another group
            val public = n.str("component") == "mod_forum" || "/mod/forum/" in n.str("contexturl")
            if (!public && Groups.isForeign(text, mine)) {
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
        val hiddenSeen = ArrayList<JSONObject>()
        val upcoming = ArrayList<JSONObject>()
        // Moodle leaves work you submitted (assignment) or finished (quiz attempt) or completed OUT of this list
        // (mod_*_core_calendar_provide_event_action returns null), so it needs no check of its own here.
        // "actionable" false only means not open yet (a quiz before its open time): still due, so still listed.
        for (e in events) {
            val act = e.optJSONObject("action")
            val ts = e.optLong("timesort")
            val remaining = ts - now
            if (remaining <= 0) continue
            if (isHiddenDeadline(ctx, e)) { hiddenSeen.add(e); continue }
            upcoming.add(e)
            if (isTaskDone(ctx, unesc(e.str("name")), e.optLong("id"), ts)) continue
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
        pruneHidden(ctx, hiddenSeen, now)
        return upcoming
    }

    /** Called by the alarm receiver: act on cached data, no network. */
    fun fromCache(ctx: Context) {
        val p = Prefs(ctx)
        if (p.token.isEmpty()) return
        if (!State.lock.tryLock(3, TimeUnit.SECONDS)) return  // a poll is running; it ends with sendDue anyway
        try {
            val st = State(ctx)
            Alerts.sendDue(ctx, st, p)
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
                // the rain forecast before the messages that use it (at most every 2 hours; quiet on failure)
                try { Weather.refresh(ctx) } catch (e: Exception) { Log.w(TAG, "weather: $e") }
                Alerts.sendDue(ctx, st, p)
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
                    // Spectrum rejected the login: say so plainly, with a button straight to the sign-in (ticket SN-N3UAY)
                    if ("invalidtoken" in msg) Notifier.push(
                        ctx, "Log in to Spectrum again",
                        "Spectrum ended this app's login, so classes and deadlines are not being checked. Log in again to continue.", true,
                        listOf(Pair("Log in", AppLink.LOGIN)),
                    ) else Notifier.push(
                        ctx, "Spectrum Notify is failing",
                        "${friendly(msg).take(300)}\n\nThe app keeps trying.", true,
                    )
                    p.alerted = true
                }
            }
        }
        // every finished attempt, success or failure: the Home refresh spinner waits for this
        ctx.getSharedPreferences("health", Context.MODE_PRIVATE).edit()
            .putLong("lastAttemptMs", System.currentTimeMillis()).apply()
        try { Catalog.refresh(ctx) } catch (e: Exception) { Log.w(TAG, "catalog: $e") }
        try { Shuttle.refresh(ctx) } catch (e: Exception) { Log.w(TAG, "shuttle: $e") }
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
