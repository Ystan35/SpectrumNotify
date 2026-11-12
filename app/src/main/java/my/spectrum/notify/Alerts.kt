package my.spectrum.notify

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/** The alerts and summaries sent from the saved data (no network): class links, attendance, leave-by, umbrella, morning and afternoon. */
object Alerts {
    private const val TAG = "SpectrumNotify"

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
     * When to set off for a class event (epoch seconds) and how: by shuttle or on foot (Shuttle.tripFor), from
     * your corrected class time. Null when there is nothing to plan or a walk's length is not known.
     */
    fun leaveBy(ctx: Context, e: JSONObject): Pair<Long, Shuttle.TripInfo>? {
        val start = classTimes(ctx, e).first
        val z = localDt(start)
        val t = Shuttle.tripFor(ctx, Timetable.courseCode(e.optJSONObject("course")), z.toLocalDate(), z.hour * 60 + z.minute)
            ?: return null
        val leave = t.leaveAt ?: return null
        val midnight = z.toLocalDate().atStartOfDay(z.zone).toEpochSecond()
        return Pair(midnight + leave * 60L, t)
    }

    /**
     * Leave-by alert, [Shuttle.leadMin] minutes before you must set off for a class: "Leave by 9:31 for WIX1003",
     * with the bus or the walk. Off when the lead is 0 (Settings → UM shuttle).
     */
    private fun checkLeaveBy(ctx: Context, st: State, p: Prefs, cal: List<JSONObject>, now: Long) {
        val lead = Shuttle.leadMin(ctx)
        if (lead <= 0) return
        val sent = st.strings("leaveby_sent")
        for (e in cal) {
            if (!Checker.isAttEvent(e)) continue
            val (leave, t) = leaveBy(ctx, e) ?: continue
            // the alarm can fire a little early; never after the moment to leave
            if (now < leave - lead * 60L - 60 || now > leave) continue
            val key = "leaveby:${e.optLong("id")}:$leave"
            if (key in sent) continue
            val code = Timetable.courseCode(e.optJSONObject("course"))
            val buttons = ArrayList<Pair<String, String>>()
            Shuttle.walkUrl(t)?.let { buttons.add(Pair(if (((t.plan as? ShuttleModel.Plan.Walk)?.minutes ?: 0) > 45) "Directions" else "Walking route", it)) }
            // rain from setting off to the end of the class: say so (once, with this alert)
            val day = localDt(leave).toLocalDate()
            val endZ = localDt(classTimes(ctx, e).second)
            val rain = if (!p.umbrellaAlert) null else Weather.rainBetween(ctx, day,
                localDt(leave).let { it.hour * 60 + it.minute }, if (endZ.toLocalDate() == day) endZ.hour * 60 + endZ.minute else 24 * 60)
            Notifier.push(
                ctx, "Leave by ${hhmm(leave)} for ${Shorts.display(ctx, code)}",
                (Shuttle.line(ctx, t) ?: "") + "\nClass starts ${ShuttleModel.fmt(t.arriveBy)}." +
                    (if (rain != null) "\n${rain.line()}: take an umbrella.\n\n${Weather.CREDIT}" else ""),
                true, buttons,
            )
            sent.add(key)
        }
        st.putStrings("leaveby_sent", sent.toList().takeLast(200))
    }

    /**
     * When a class starts (your corrected time), one alert so you do not forget to sign attendance:
     * Attendance and Join class buttons. Skipped for courses where the lecturer takes attendance in class or
     * attendance is by Teams recording, and when turned off in Settings → Your classes.
     */
    private fun checkAttendanceStart(ctx: Context, st: State, p: Prefs, cal: List<JSONObject>, now: Long) {
        if (!p.attStartAlert) return
        val sent = st.strings("att_sent")
        val links = Links.linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        val sameRun = HashSet<String>()   // Spectrum lists one event per group: same course, name and start = one alert
        for (e in cal) {
            if (!Checker.isAttEvent(e)) continue
            val (start, end) = classTimes(ctx, e)
            // signing only works during class: once when it starts, and a last chance 10 minutes before it ends
            // (not for a class of 25 minutes or less, where the two would come almost together)
            val atStart = now >= start && now <= minOf(end, start + 15 * 60)
            val lastChance = end - start > 25 * 60 && now >= end - 10 * 60 - 60 && now < end
            if (!atStart && !lastChance) continue
            val key = (if (atStart) "attstart:" else "attend:") + "${e.optLong("id")}:$start"
            if (key in sent) continue
            val c = e.optJSONObject("course")
            val code = Timetable.courseCode(c)
            if (Timetable.attMode(ctx, code) == "manual" || Checker.isManual(e, p)) { sent.add(key); continue }
            // an attendance page for another Occ ("Attendance OCC13, 15"): not yours, so no alert (ticket SN-J6ACW)
            // (only when the Occ was set or confirmed: a guessed Occ list also holds the other groups' numbers)
            if (Groups.isForeign(e.str("name"), Groups.mineSure(p, code))) continue
            if (!sameRun.add("$code:$start:${atStart}:${e.str("name").lowercase()}")) continue
            val name = unesc(e.str("name"))
            val z = localDt(start)
            val (_, att, coursePage) = Links.linksForCourse(
                ctx, code, Links.courseId(e), Links.groupTokens(name), Checker.preferLecOf(name) ?: Timetable.preferLecAt(ctx, code, start),
                day = z.dayOfWeek.value, startMin = z.hour * 60 + z.minute, endMin = localDt(end).let { it.hour * 60 + it.minute },
            )
            val meeting = Links.linksForEvent(ctx, e, cal, links).firstOrNull { Links.linkIsMeeting(it.first) }?.first ?: ""
            val buttons = ArrayList<Pair<String, String>>()
            att.ifEmpty { coursePage }.takeIf { it.isNotEmpty() }?.let { buttons.add(Pair("Sign attendance", it)) }
            if (meeting.isNotEmpty()) buttons.add(Pair("Join class", meeting))
            Notifier.push(
                ctx, if (atStart) "${Shorts.display(ctx, code)} has started: sign your attendance"
                    else "${Shorts.display(ctx, code)}: signed your attendance?",
                if (atStart) "$name\nStarted ${hhmm(start)}. Open the attendance page and mark yourself present (until ${hhmm(end)})."
                    else "$name\nClass ends at ${hhmm(end)}. If you have not signed yet, do it now; it closes when class ends.",
                true, buttons,
            )
            sent.add(key)
        }
        // classes on your timetable that Spectrum's calendar has no attendance entry for get the same alerts
        val today = localDt(now).toLocalDate()
        for (s in slotsWithoutAttEvent(ctx, p, cal, today)) {
            val start = today.atTime(s.start / 60, s.start % 60).atZone(zone()).toEpochSecond()
            val end = today.atTime(s.end / 60, s.end % 60).atZone(zone()).toEpochSecond()
            val atStart = now >= start && now <= minOf(end, start + 15 * 60)
            val lastChance = end - start > 25 * 60 && now >= end - 10 * 60 - 60 && now < end
            if (!atStart && !lastChance) continue
            val key = (if (atStart) "attstart:" else "attend:") + "${s.key}:$start"
            if (key in sent) continue
            val cid = Courses.all(ctx).firstOrNull { it.code.equals(s.course, true) }?.id ?: 0L
            val (meeting, att, coursePage) = Links.linksForCourse(
                ctx, s.course, cid, Links.groupTokens(s.title), Checker.preferLecOf(s.title),
                day = s.day, startMin = s.start, endMin = s.end,
                online = Timetable.classMode(ctx, s.key, today) == "online",
            )
            val buttons = ArrayList<Pair<String, String>>()
            att.ifEmpty { coursePage }.takeIf { it.isNotEmpty() }?.let { buttons.add(Pair("Sign attendance", it)) }
            if (meeting.isNotEmpty()) buttons.add(Pair("Join class", meeting))
            Notifier.push(
                ctx, if (atStart) "${Shorts.display(ctx, s.course)} has started: sign your attendance"
                    else "${Shorts.display(ctx, s.course)}: signed your attendance?",
                if (atStart) "${s.title}\nStarted ${hhmm(start)}. Open the attendance page and mark yourself present (until ${hhmm(end)})."
                    else "${s.title}\nClass ends at ${hhmm(end)}. If you have not signed yet, do it now; it closes when class ends.",
                true, buttons,
            )
            sent.add(key)
        }
        st.putStrings("att_sent", sent.toList().takeLast(200))
    }

    /**
     * Your timetable's classes on [date] with no attendance entry on Spectrum's calendar at about that time (some
     * lecturers never add one), leaving out courses where attendance is taken in class. The window is wide because
     * Spectrum's time can be off from the real class ("Attendance Tutorial 11-1pm" listed at 10:00).
     */
    fun slotsWithoutAttEvent(ctx: Context, p: Prefs, cal: List<JSONObject>, date: java.time.LocalDate): List<Slot> {
        if (!AcademicCalendar.inTeaching(ctx, date)) return emptyList()
        return Timetable.on(Timetable.all(ctx, AcademicCalendar.labelFor(ctx, date)), date).filter { s ->
            Timetable.attMode(ctx, s.course) != "manual" &&
                p.manualList.none { s.course.lowercase().contains(it) } &&
                cal.none { e ->
                    if (!Checker.isAttEvent(e) || !Timetable.courseCode(e.optJSONObject("course")).equals(s.course, true)) return@none false
                    // another Occ's attendance event is not yours, so it must not hide your own slot's alert
                    if (Groups.isForeign(e.str("name"), Groups.mineSure(p, s.course))) return@none false
                    val z = localDt(classTimes(ctx, e).first)
                    z.toLocalDate() == date && kotlin.math.abs(z.hour * 60 + z.minute - s.start) <= 120
                }
        }
    }

    private fun checkClassLinks(ctx: Context, st: State, p: Prefs, cal: List<JSONObject>, links: Map<String, List<Links.Link>>, now: Long) {
        val sent = st.strings("links_sent")
        for (e in cal) {
            if (!Checker.isAttEvent(e)) continue
            val start = classTimes(ctx, e).first   // your corrected time, not Spectrum's
            if (now < start - 600 || now > start + 300) continue
            val eid = e.optLong("id")
            val kok = "linkok:$eid:$start"
            val kno = "linkno:$eid:$start"
            if (kok in sent) continue
            val c = e.optJSONObject("course")
            val cname = Shorts.display(ctx, Timetable.courseCode(c))
            val name = unesc(e.str("name"))
            val mins = maxOf(0L, Math.round((start - now) / 60.0))
            val whenS = fmtTime(start)
            val note = if (Checker.isManual(e, p)) "\nAttendance for this class is via Teams record." else ""
            val code = Timetable.courseCode(c)
            // attendance can only be signed in class time: the notice says when, the sign alerts come then
            val classEnd = classTimes(ctx, e).second
            val signLine = if (!p.attStartAlert || Checker.isManual(e, p) || Timetable.attMode(ctx, code) == "manual") ""
                else "\nAttendance: sign from ${hhmm(start)} to ${hhmm(classEnd)}. You'll get a reminder when class starts."
            // in person (the same trusted rule as every screen): a plain notice with the room
            // (no attendance button: signing opens when class starts)
            val venue = Timetable.venueAt(ctx, code, start)
            if (venue.isNotEmpty() && venue != "Online") {
                if (kok in sent) continue
                val room = venue.takeUnless { it == "In person" }.orEmpty()
                val cid = Links.courseId(e)
                val buttons = ArrayList<Pair<String, String>>()
                if (cid != 0L) buttons.add(Pair("Course page", "${p.site}/course/view.php?id=$cid"))
                Notifier.push(ctx, "Upcoming class in ~$mins min: $cname · in person",
                    "$name\nStarts $whenS" + (if (room.isNotEmpty()) " · $room" else "") + ".$note" + signLine, true, buttons)
                sent.add(kok)
                sent.add(kno)
                continue
            }
            val ls = Links.linksForEvent(ctx, e, cal, links)
            if (ls.isNotEmpty()) {
                val body = "$name\nStarts $whenS.$note$signLine\n\nOnline link:" +
                    ls.take(3).joinToString("") { "\n  ${it.first}  (${it.second})" }
                val buttons = ls.take(3).mapIndexed { i, l -> Pair(if (i == 0) "Join class" else "Links.Link ${i + 1}", l.first) }
                val title = if (kno in sent) "Online link posted: $cname" else "Upcoming class in ~$mins min: $cname"
                Notifier.push(ctx, title, body, true, buttons)
                sent.add(kok)
                sent.add(kno)
            } else if (kno !in sent) {
                val cid = Links.courseId(e)
                val courseUrl = if (cid != 0L) "${p.site}/course/view.php?id=$cid" else p.site
                val body = "$name\nStarts $whenS.$note$signLine\n\nNo online link found yet in the calendar event, " +
                    "course page or announcements. It may be in person, or not posted yet. " +
                    "I'll keep checking until the class starts.\n  $courseUrl"
                Notifier.push(ctx, "Upcoming class in ~$mins min: $cname (no link yet)", body, true, listOf(Pair("Open course", courseUrl)))
                sent.add(kno)
            }
        }
        st.putStrings("links_sent", sent.toList().takeLast(300))
    }

    /**
     * Rain (Weather) from an hour before your first class of [date] (getting there) to the end of the last,
     * or null: no classes, no rain, or no forecast.
     */
    private fun rainAroundClasses(ctx: Context, st: State, p: Prefs, date: java.time.LocalDate): Weather.Rain? {
        val day = Checker.classItems(ctx, st, p).filter { localDt(it.start).toLocalDate() == date }
        if (day.isEmpty()) return null
        fun minuteOf(ts: Long) = localDt(ts).let { it.hour * 60 + it.minute }
        val endMin = day.maxOf { c -> if (localDt(c.end).toLocalDate() == date) minuteOf(c.end) else 24 * 60 }
        return Weather.rainBetween(ctx, date, day.minOf { minuteOf(it.start) } - 60, endMin)
    }

    /**
     * "Bring an umbrella": at most ONE per day (whatever the forecast does later), from the morning-summary
     * time (07:00 when that is off) until your last class starts, once the forecast shows rain around them.
     */
    private fun sendUmbrella(ctx: Context, st: State, p: Prefs, now: ZonedDateTime) {
        if (!p.umbrellaAlert) return
        val today = now.toLocalDate()
        if (st.j.optString("umbrella_date") == today.toString()) return
        val hm = parseHm(p.summaryTime) ?: Pair(7, 0)
        if (now.isBefore(now.withHour(hm.first).withMinute(hm.second).withSecond(0).withNano(0))) return
        val day = Checker.classItems(ctx, st, p).filter { localDt(it.start).toLocalDate() == today }
        if (day.isEmpty() || now.toEpochSecond() > day.maxOf { it.start }) return
        val rain = rainAroundClasses(ctx, st, p, today) ?: return
        st.j.put("umbrella_date", today.toString())
        Notifier.push(ctx, "Bring an umbrella",
            "${rain.line()} at UM today, while you have classes (${hhmm(day.minOf { it.start })} – ${hhmm(day.maxOf { it.end })}).\n\n${Weather.CREDIT}")
    }

    private fun sendMorningSummary(ctx: Context, st: State, p: Prefs, cal: List<JSONObject>, deadlines: List<JSONObject>, now: ZonedDateTime) {
        if (p.summaryTime.isBlank()) return
        val today = now.toLocalDate().toString()
        if (st.j.optString("summary_date") == today) return
        val hm = parseHm(p.summaryTime) ?: return
        val target = now.withHour(hm.first).withMinute(hm.second).withSecond(0).withNano(0)
        if (now.isBefore(target)) return
        if (now.hour >= 12) return  // started late in the day: no "good morning"

        val nowS = now.toEpochSecond()
        // an activity's due/close date in the calendar that is not on your to-do list (deadlines): Moodle took it off
        // because you submitted, finished or completed it, so it is not due for you any more
        // (only once the to-do list has been fetched: before that, nothing counts as done)
        val todo = deadlines.map { it.optLong("id") }.toSet()
        val todoKnown = st.j.has("deadlines")
        fun doneOnSpectrum(e: JSONObject) = todoKnown &&
            e.str("modulename").isNotEmpty() && e.str("eventtype") in setOf("due", "close") && e.optLong("id") !in todo
        // today's classes as Home shows them (your corrected times, your own entries, none you removed),
        // then the day's other calendar events (not hidden or done tasks), in time order
        val todays = ArrayList<Pair<Long, String>>()
        val buttons = ArrayList<Pair<String, String>>()
        for (c in Checker.classItems(ctx, st, p).filter { localDt(it.start).toLocalDate() == now.toLocalDate() }.take(10)) {
            val t = hhmm(c.start)
            val url = if (c.manual) "" else c.attUrl
            todays.add(Pair(c.start,
                "$t  ${Shorts.display(ctx, c.code.ifEmpty { c.course })}: ${c.name}" +
                    (if (url.isNotEmpty()) "\n  $url" else "") +
                    (if (c.manual) "\n  (attendance via Teams record)" else "")))
            if (url.isNotEmpty() && buttons.size < 3 && c.start >= nowS - 3600) buttons.add(Pair("Attendance $t", url))
        }
        for (e in cal) {
            val ts = e.optLong("timestart")
            if (Checker.isAttEvent(e) || localDt(ts).toLocalDate() != now.toLocalDate()) continue
            val name = unesc(e.str("name"))
            if (Checker.isHiddenDeadline(ctx, e) || Checker.isTaskDone(ctx, name, e.optLong("id"), ts) || doneOnSpectrum(e)) continue
            val code = Timetable.courseCode(e.optJSONObject("course"))
            todays.add(Pair(ts, "${hhmm(ts)}  " + (if (e.optJSONObject("course") != null) "${Shorts.display(ctx, code)}: " else "") + name))
        }
        val rain = if (p.umbrellaAlert) rainAroundClasses(ctx, st, p, now.toLocalDate()) else null
        val lines = arrayListOf<String>()
        if (rain != null) lines.addAll(listOf("UMBRELLA: ${rain.line()} around your classes.", ""))
        lines.add("TODAY")
        if (todays.isNotEmpty()) todays.sortedBy { it.first }.forEach { lines.add(it.second) }
        else lines.add("No classes or events today.")

        val horizon = nowS + 7 * 86400
        val pool = ArrayList<Pair<Long, JSONObject>>()
        val seen = HashSet<Long>()
        for (e in deadlines) {
            val t = e.optLong("timesort")
            if (t > nowS && t <= horizon) { pool.add(Pair(t, e)); seen.add(e.optLong("id")) }
        }
        for (e in cal) {
            val t = e.optLong("timestart")
            if (e.str("eventtype") in setOf("due", "close") && t in nowS..horizon && e.optLong("id") !in seen && !Checker.isHiddenDeadline(ctx, e) &&
                !doneOnSpectrum(e)) {
                pool.add(Pair(t, e)); seen.add(e.optLong("id"))
            }
        }
        // tasks you marked done are not due any more
        pool.removeAll { (t, e) -> Checker.isTaskDone(ctx, unesc(e.str("name")), e.optLong("id"), t) }
        pool.sortBy { it.first }
        lines.add("")
        lines.add("DUE THIS WEEK")
        if (pool.isNotEmpty()) {
            for ((t, e) in pool.take(8)) {
                val c = e.optJSONObject("course")?.let { Shorts.display(ctx, Timetable.courseCode(it)) } ?: ""
                val url = e.str("url").ifEmpty { e.optJSONObject("action")?.str("url") ?: "" }
                lines.add(
                    "${fmtTime(t)}  ${unesc(e.str("name")).ifEmpty { "Deadline" }}" +
                        (if (c.isNotEmpty()) " ($c)" else "") + (if (url.isNotEmpty()) "\n  $url" else "")
                )
            }
        } else {
            lines.add("Nothing due.")
        }
        if (rain != null) lines.addAll(listOf("", Weather.CREDIT))
        if (buttons.size < 3) buttons.add(Pair("Open Spectrum", "${p.site}/my/"))
        val title = "Good morning - " + now.format(java.time.format.DateTimeFormatter.ofPattern("EEE dd MMM", java.util.Locale.ENGLISH))
        Notifier.push(ctx, title, lines.joinToString("\n"), false, buttons)
        st.j.put("summary_date", today)
    }

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

    private fun sendAfternoon(ctx: Context, st: State, p: Prefs, now: ZonedDateTime) {
        if (p.afternoonTime.isBlank()) return
        val today = now.toLocalDate().toString()
        if (st.j.optString("afternoon_date") == today) return
        val hm = parseHm(p.afternoonTime) ?: return
        val target = now.withHour(hm.first).withMinute(hm.second).withSecond(0).withNano(0)
        if (now.isBefore(target)) return
        if (now.hour >= 18) return  // started too late in the day
        st.j.put("afternoon_date", today)

        val nowS = now.toEpochSecond()
        // today's classes as Home shows them: your corrected times and your own entries too
        val classes = Checker.classItems(ctx, st, p).filter { localDt(it.start).toLocalDate() == now.toLocalDate() }
        if (classes.isEmpty()) return
        val left = classes.filter { it.start > nowS }
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
            for (c in left.take(6)) {
                val t = hhmm(c.start)
                val manual = c.manual
                val url = if (manual) "" else c.attUrl
                lines.add(
                    "$t  ${Shorts.display(ctx, c.code.ifEmpty { c.course })}: ${c.name}" +
                        (if (url.isNotEmpty()) "\n  $url" else "") +
                        (if (manual) "\n  (attendance via Teams record)" else "")
                )
                if (url.isNotEmpty() && buttons.size < 3) buttons.add(Pair("Attendance $t", url))
            }
        }
        Notifier.push(ctx, title, lines.joinToString("\n"), false, buttons)
    }

    /** Uses the cached calendar/links, so it also works from an alarm with no network. */
    internal fun sendDue(ctx: Context, st: State, p: Prefs) {
        val now = ZonedDateTime.now(zone())
        val cal = Checker.activeCalendar(ctx, st, p)
        val dl = (st.j.optJSONArray("deadlines") ?: JSONArray()).objs().filter { !Checker.isHiddenDeadline(ctx, it) }
        val links = Links.linksFromJson(st.j.optJSONObject("links") ?: JSONObject())
        try { checkClassLinks(ctx, st, p, cal, links, now.toEpochSecond()) } catch (e: Exception) { Log.w(TAG, "class links: $e") }
        try { sendMorningSummary(ctx, st, p, cal, dl, now) } catch (e: Exception) { Log.w(TAG, "summary: $e") }
        try { sendAfternoon(ctx, st, p, now) } catch (e: Exception) { Log.w(TAG, "afternoon: $e") }
        try { sendUmbrella(ctx, st, p, now) } catch (e: Exception) { Log.w(TAG, "umbrella: $e") }
        try { checkAttendanceStart(ctx, st, p, cal, now.toEpochSecond()) } catch (e: Exception) { Log.w(TAG, "attendance start: $e") }
        try { checkLeaveBy(ctx, st, p, cal, now.toEpochSecond()) } catch (e: Exception) { Log.w(TAG, "leave by: $e") }
    }
}
