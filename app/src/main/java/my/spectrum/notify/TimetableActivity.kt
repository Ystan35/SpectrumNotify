package my.spectrum.notify

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

/**
 * Weekly timetable. Day view = the chosen weekday as cards; Week view = a scrollable time grid.
 * One colour per course; the lecture / tutorial / lab tag is text only, so colour always means "which course".
 */
class TimetableActivity : BaseActivity() {
    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var box: LinearLayout
    private var mode = 0                                   // 0 = Day, 1 = Week
    private var day = LocalDate.now(zone()).dayOfWeek.value // 1 = Mon .. 7 = Sun
    private var slots: List<Slot> = emptyList()
    private var colours: Map<String, Int> = emptyMap()
    private var zoom = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = screen("Timetable", navTo = TimetableActivity::class.java, onRefresh = {
            Scheduler.pollNow(this)
            Toast.makeText(this, "Checking Spectrum… the timetable updates when it finishes", Toast.LENGTH_SHORT).show()
        }, actionIcon = R.drawable.ic_add, actionDesc = "Add a class or an event", onAction = { showAddChoice() })
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        render()
        Timetable.justHidden?.let { key ->
            Timetable.justHidden = null
            undoBar("Class removed from your timetable") { Timetable.unhide(this, key); render() }
        }
    }


    private fun openEdit(s: Slot?, presetDay: Int = 0) {
        val i = Intent(this, TimetableEditActivity::class.java)
        if (s != null) {
            if (s.manual) i.putExtra(TimetableEditActivity.EXTRA_GRP, s.grp) else i.putExtra(TimetableEditActivity.EXTRA_KEY, s.key)
        } else if (presetDay != 0) {
            i.putExtra(TimetableEditActivity.EXTRA_DAY, presetDay)
        }
        startActivity(i)
    }

    // ------------------------------------------------------------ render
    private fun render() {
        slots = Timetable.all(this)
        colours = Timetable.colours(this, slots.map { it.course }.toSet())
        box.removeAllViews()

        if (slots.isEmpty() && Events.all(this).isEmpty()) {
            renderEmpty()
            return
        }

        box.segmented(listOf("Day", "Week", "Month"), mode) { mode = it; render() }
        when (mode) {
            0 -> renderDay()
            1 -> renderWeek()
            else -> MonthGridView.render(this, box, onClass = { slotDetail(it) }) { render() }
        }
        if (mode != 2) renderLegend()
        // shortcut to Settings → Online or in person, with how many courses are in person now (a setting, so it sits last)
        val nIn = Courses.all(this).count { Timetable.inPerson(this, it.code) }
        box.addView(iconText(
            if (nIn > 0) R.drawable.ic_place else R.drawable.ic_laptop,
            if (nIn > 0) "$nIn course" + (if (nIn > 1) "s" else "") + " in person" else "Classes online or in person",
            Type.LABEL, Ui.CYAN, true, trailing = R.drawable.ic_chevron_right,
        ).apply {
            minHeight = dp(Ui.touch(48))
            isClickable = true
            background = rippleBg(Ui.BG, Ui.BG, this@TimetableActivity, Radius.SM)
            contentDescription = "Set which classes are online or in person"
            setOnClickListener {
                startActivity(android.content.Intent(this@TimetableActivity, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_OPEN, "inperson"))
            }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })
    }

    private fun renderEmpty() {
        val loggedIn = Prefs(this).token.isNotEmpty()
        box.label("No classes yet", Type.HEADLINE, Ui.TEXT, 24).setTypeface(null, Typeface.BOLD)
        box.label(
            if (loggedIn) "Your timetable fills in by itself from the classes Spectrum lists for you. " +
                "It usually appears after the next check. You can also add your own classes."
            else "Log in first, then your timetable fills in from Spectrum. You can also add your own classes without logging in.",
            Type.BODY, Ui.DIM, 8,
        )
        if (loggedIn) {
            box.btn("Check Spectrum now") {
                Scheduler.pollNow(this)
                Toast.makeText(this, "Checking Spectrum...", Toast.LENGTH_SHORT).show()
            }
        } else {
            box.btn("Log in") { startActivity(Intent(this, AccountActivity::class.java)) }
        }
        box.btn("Add a class") { openEdit(null) }
    }

    // ------------------------------------------------------------ day view
    private fun renderDay() {
        val today = LocalDate.now(zone()).dayOfWeek.value
        box.chips(Timetable.DAYS, setOf(day - 1), false) { day = it.first() + 1; render() }

        val list = Timetable.on(slots, Timetable.dateThisWeek(day))
        val heading = (if (day == today) "Today" else Timetable.DAY_FULL[day - 1]) +
            if (list.isEmpty()) "" else "  ·  ${list.size} class" + (if (list.size == 1) "" else "es")
        box.label(heading, Type.BODY_L, Ui.TEXT, 20).setTypeface(null, Typeface.BOLD)

        if (list.isEmpty()) {
            box.label("Nothing scheduled on ${Timetable.DAY_FULL[day - 1]}.", Type.BODY, Ui.DIM, 8)
            box.btn("Add a class on ${Timetable.DAYS[day - 1]}") { openEdit(null, day) }
            return
        }

        val nowMin = if (day == today) java.time.LocalTime.now(zone()).let { it.hour * 60 + it.minute } else -1
        val nextStart = if (nowMin >= 0) list.firstOrNull { it.start > nowMin }?.start ?: -1 else -1
        for (s in list) {
            val badge = when {
                nowMin in s.start until s.end -> "NOW"
                nowMin >= 0 && s.start == nextStart -> "NEXT"
                else -> ""
            }
            // zoom is for the week grid only; scaling cards here made them overlap or leave gaps
            box.addView(slotCard(s, badge), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
    }

    private fun slotCard(s: Slot, badge: String): View {
        val c = colours[s.course] ?: Ui.CYAN
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundBg(Ui.BAR, Ui.BORDER, this@TimetableActivity, Radius.MD)
            isClickable = true
            setOnClickListener { openEdit(s) }
        }
        val r = dp(14).toFloat()
        card.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(c)
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
        }, LinearLayout.LayoutParams(dp(7), MATCH))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(tv("${Timetable.fmtMin(s.start)} – ${Timetable.fmtMin(s.end)}", Type.BODY_L, Ui.TEXT, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (badge.isNotEmpty()) {
            top.addView(tv(badge, Type.CAPTION, onAccent(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = GradientDrawable().apply { cornerRadius = dp(Radius.SM).toFloat(); setColor(c) }
            })
        }
        col.addView(top)

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        mid.addView(tv(Shorts.display(this, s.course), Type.BODY_L, c, true))
        if (s.kind != SlotKind.CLASS) {
            mid.addView(tv(s.kind.label, Type.CAPTION, Ui.TEXT).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = roundBg(0, Ui.BORDER, this@TimetableActivity, Radius.SM)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(10) })
        }
        col.addView(mid)

        val dur = s.end - s.start
        val durS = (if (dur >= 60) "${dur / 60}h" else "") + (if (dur % 60 != 0) (if (dur >= 60) " " else "") + "${dur % 60}m" else "")
        // room + lecturer from the UM catalog when this slot matches a catalog choice
        val choice = Catalog.find(this, s.course)?.choices(s.kind)?.firstOrNull { ch ->
            ch.occ == occOf(s) || ch.parts.any { it.day == s.day && it.start == s.start }
        }
        val room = choice?.parts?.filter { it.day == s.day }?.map { it.room }?.filter { it.isNotEmpty() }?.distinct()?.joinToString(", ") ?: ""
        val lecturer = choice?.detail() ?: ""
        // Spectrum classes: the course title as Home shows it, not Spectrum's raw event name ("Attendance - … (2026/2027 Group 3)")
        val full = Courses.all(this).firstOrNull { it.code.equals(s.course, true) }?.name?.takeIf { it.isNotEmpty() }
            ?: Catalog.find(this, s.course)?.name ?: ""
        val detail = listOf(if (s.manual) s.title else Timetable.classTitle(s.course, full, s.title), s.note).filter { it.isNotEmpty() }.joinToString("  ·  ")
        val oneOff = if (s.date != null) "This week only" else ""
        val meta = listOf(oneOff, detail, durS, if (s.manual) "Added by you" else "From Spectrum").filter { it.isNotEmpty() }.joinToString("  ·  ")
        col.addView(tv(meta, Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(6), 0, 0) })
        // location: user override first; else the auto meeting link (Online) or the catalog room
        val userLoc = Timetable.locationOf(this, s.course)
        val autoMeeting = meetingOf(s)
        val locLine = when {
            Timetable.inPerson(this, s.course) -> "📍 In person" + (if (userLoc.startsWith("room:")) " · " + userLoc.removePrefix("room:") else if (room.isNotEmpty()) " · $room" else "")
            userLoc.startsWith("online:") -> "💻 Online" + (if (Timetable.meetingLinkOf(this, s.course).isNotEmpty()) " · your link" else "")
            userLoc.startsWith("room:") -> "📍 " + userLoc.removePrefix("room:")
            autoMeeting.isNotEmpty() -> "💻 Online (auto)"
            room.isNotEmpty() -> "📍 $room"
            else -> "📍 Tap to set location"
        }
        val loc = tv(locLine, Type.CAPTION, if (userLoc.isEmpty() && room.isEmpty() && autoMeeting.isEmpty()) Ui.DIM else Ui.TEXT).apply {
            setPadding(0, dp(2), 0, 0)
            isClickable = true
            background = rippleBg(Ui.BAR, 0, this@TimetableActivity, Radius.SM)
        }
        loc.setOnClickListener { editLocation(s.course, autoMeeting) }
        col.addView(loc)
        // attendance field: auto-filled from the course's Spectrum attendance page,
        // placeholder when nothing is known, tap to paste one or more links
        val auto = Checker.attendanceUrl(this, courseIdOf(s.course), preferLecOf(s))
        val user = userAttLink(s.course)
        val link = user.ifEmpty { auto }
        val att = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val meeting = autoMeeting
        fun openBtn(label: String, url: String, filled: Boolean): TextView =
            button(label, if (filled) Emphasis.PRIMARY else Emphasis.SECONDARY, c) {
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                catch (_: Exception) { Toast.makeText(this@TimetableActivity, "Cannot open link", Toast.LENGTH_SHORT).show() }
            }
        if (link.isNotEmpty()) att.addView(openBtn("Attendance", link, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        if (meeting.isNotEmpty()) att.addView(openBtn("Join class", meeting, link.isEmpty()), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(6) })
        // round link icon: "+ Link" as text wrapped mid-word at large text sizes
        att.addView(android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.lk_link)
            setColorFilter(Ui.CYAN)
            background = rippleBg(0, Ui.BORDER, this@TimetableActivity, Radius.PILL)
            contentDescription = "Paste an attendance link for ${s.course}"
            setOnClickListener { pasteAttendance(s.course) }
        }, LinearLayout.LayoutParams(dp(Ui.touch(48)), dp(Ui.touch(48))).apply { leftMargin = dp(6) })
        if (link.isEmpty() && meeting.isEmpty()) {
            att.addView(tv("no link yet — tap to paste", Type.CAPTION, Ui.DIM).apply {
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        if (link.isEmpty() && meeting.isEmpty()) {
            att.isClickable = true
            att.setOnClickListener { pasteAttendance(s.course) }
        }
        col.addView(att)
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return card
    }

    private fun courseIdOf(courseCode: String): Long =
        Courses.all(this).firstOrNull { it.code.equals(courseCode, true) }?.id ?: 0L

    private fun userAttLink(course: String): String =
        getSharedPreferences("timetable", MODE_PRIVATE).getString("att:$course", "") ?: ""

    /** Dialog to paste one or more attendance links (one per line). Empty text clears. */
    /** Location editor: Online (auto meeting link if found, else paste) or Physical room. */
    private fun editLocation(course: String, autoMeeting: String) {
        val names = listOf("Online class", "Physical room", "Clear")
        val current = Timetable.locationOf(this, course)
        val initial = when {
            current.startsWith("online:") -> 0
            current.startsWith("room:") -> 1
            else -> 2
        }
        val mode = intArrayOf(if (current.isEmpty()) 0 else initial)
        val linkField = android.widget.EditText(this).apply {
            hint = "Meeting link (https://…)"
            setText(if (current.startsWith("online:")) current.removePrefix("online:")
                    else if (autoMeeting.isNotEmpty()) autoMeeting else "")
            setTextIsSelectable(true)
        }
        val roomField = android.widget.EditText(this).apply {
            hint = "Room, e.g. DK2"
            setText(if (current.startsWith("room:")) current.removePrefix("room:") else "")
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(linkField)
        box.addView(roomField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        fun refresh() {
            linkField.visibility = if (mode[0] == 0) android.view.View.VISIBLE else android.view.View.GONE
            roomField.visibility = if (mode[0] == 1) android.view.View.VISIBLE else android.view.View.GONE
        }
        refresh()
        android.app.AlertDialog.Builder(this, Ui.dialogTheme)
            .setTitle("Location — $course")
            .setMessage(
                "Online: the app looks for this course's meeting link automatically" +
                    (if (autoMeeting.isNotEmpty()) " (one was found)" else " (none found — paste the link yourself)") + ". " +
                    "Physical: type the room."
            )
            .setView(box)
            .setSingleChoiceItems(names.toTypedArray(), if (current.isEmpty()) 0 else initial) { _, which -> mode[0] = which; refresh() }
            .setPositiveButton("Save") { _, _ ->
                when (mode[0]) {
                    0 -> {
                        val link = linkField.text.toString().trim()
                        if (link.isEmpty() && autoMeeting.isEmpty()) {
                            Toast.makeText(this, "No meeting link found — paste one, or choose Physical room.", Toast.LENGTH_LONG).show()
                            return@setPositiveButton
                        }
                        // the Join buttons read the meeting-link override: a pasted link must land there,
                        // and keeping the found link means "follow whatever Spectrum posts next"
                        val pinned = Timetable.meetingLinkOf(this, course).isNotEmpty()
                        Timetable.setMeetingLink(this, course, if (link.isEmpty() || (link == autoMeeting && !pinned)) "" else link)
                        Timetable.setLocation(this, course, "online:")
                    }
                    1 -> Timetable.setLocation(this, course, "room:" + roomField.text.toString().trim())
                    else -> Timetable.setLocation(this, course, "")
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Which Occ this slot belongs to: from the picker group id (cat-CODE-kind-N), else empty. */
    private fun occOf(s: Slot): String =
        if (s.grp.startsWith("cat-")) s.grp.split('-').getOrNull(3) ?: "" else ""

    private fun pasteAttendance(course: String) {
        val field = android.widget.EditText(this).apply {
            setText(userAttLink(course))
            hint = "https://spectrum.um.edu.my/mod/attendance/view.php?id=…"
            setTextIsSelectable(true)
        }
        android.app.AlertDialog.Builder(this, Ui.dialogTheme)
            .setTitle("Attendance links — $course")
            .setMessage("Paste one or more links (one per line). Leave empty to go back to the automatic one.")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                Timetable.setAttendanceLink(this, course, field.text.toString())
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------ week view
    private fun renderWeek() {
        box.addView(zoomBar(), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        val grid = weekGrid()
        attachPinch(grid)
        box.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
    }

    /** +/- buttons next to a zoom % label; persisted for the session. */
    private fun zoomBar(): View {
        val row = LinearLayout(this).apply { gravity = Gravity.END }
        fun btn(t: String): TextView = TextView(this).apply {
            text = t; textSize = Type.TITLE * Ui.fontScale; setTextColor(Ui.CYAN)
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            minHeight = dp(Ui.touch(48)); minWidth = dp(Ui.touch(48))
            background = rippleBg(Ui.BAR, Ui.BORDER, this@TimetableActivity, Radius.PILL)
        }
        val minus = btn("−")
        minus.setOnClickListener { zoom = (zoom - 0.15f).coerceAtLeast(0.7f); render() }
        val label = tv("${(zoom * 100).toInt()}%", Type.CAPTION, Ui.DIM, true)
        label.gravity = Gravity.CENTER; label.minWidth = dp(48)
        val plus = btn("+")
        plus.setOnClickListener { zoom = (zoom + 0.15f).coerceAtMost(2f); render() }
        row.addView(minus); row.addView(label); row.addView(plus)
        return row
    }

    /** Pinch to zoom: adjusts [zoom] and re-renders on release. */
    private fun attachPinch(v: View) {
        val scale = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: android.view.ScaleGestureDetector): Boolean {
                zoom = (zoom * det.scaleFactor).coerceIn(0.7f, 2f)
                return true
            }
            override fun onScaleEnd(det: android.view.ScaleGestureDetector) { render() }
        })
        v.setOnTouchListener { _, ev -> scale.onTouchEvent(ev); false }
    }

    private fun weekGrid(): View {
        val hourH = (dp(56) * zoom).toInt()
        val colW = (dp(104) * zoom).toInt()
        val headH = dp(30)
        val gutterW = dp(44)
        val minH = minOf(8, slots.minOf { it.start } / 60)
        val maxH = maxOf(17, (slots.maxOf { it.end } + 59) / 60)
        val totalH = headH + (maxH - minH) * hourH + dp(10)
        val today = LocalDate.now(zone()).dayOfWeek.value
        // Mon–Fri always; Saturday and Sunday only when they have a class this week
        val byDay = (1..7).associateWith { d -> Timetable.on(slots, Timetable.dateThisWeek(d)) }
        val days = (1..7).filter { it <= 5 || byDay[it]!!.isNotEmpty() }
        val gridW = days.size * colW

        val gutter = FrameLayout(this)
        val grid = FrameLayout(this)

        // today's column tint
        if (today in days) {
            grid.addView(View(this).apply { setBackgroundColor(Ui.tint(Ui.CYAN, 0x14)) },
                FrameLayout.LayoutParams(colW, totalH - headH).apply { leftMargin = days.indexOf(today) * colW; topMargin = headH })
        }

        for (h in minH..maxH) {
            val y = headH + (h - minH) * hourH
            gutter.addView(tv(String.format(java.util.Locale.ENGLISH, "%02d:00", h), Type.CAPTION, Ui.DIM),
                FrameLayout.LayoutParams(gutterW, WRAP).apply { topMargin = y - dp(8) })
            grid.addView(View(this).apply { setBackgroundColor(Ui.BORDER) },
                FrameLayout.LayoutParams(gridW, 1).apply { topMargin = y })
        }
        for ((col, d) in days.withIndex()) {
            grid.addView(tv(Timetable.DAYS[d - 1], Type.BODY, if (d == today) Ui.CYAN else Ui.TEXT, d == today).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(colW, headH).apply { leftMargin = col * colW })
        }

        for ((col, d) in days.withIndex()) {
            val list = byDay[d]!!
            var i = 0
            while (i < list.size) {
                // a cluster = slots that overlap in time; they share the column side by side
                var j = i
                var clusterEnd = list[i].end
                val laneEnds = ArrayList<Int>()
                val placed = ArrayList<Pair<Slot, Int>>()
                while (j < list.size && list[j].start < clusterEnd) {
                    val s = list[j]
                    var lane = laneEnds.indexOfFirst { it <= s.start }
                    if (lane < 0) { laneEnds.add(s.end); lane = laneEnds.size - 1 } else laneEnds[lane] = s.end
                    placed.add(Pair(s, lane))
                    clusterEnd = maxOf(clusterEnd, s.end)
                    j++
                }
                val lanes = laneEnds.size
                for ((s, lane) in placed) {
                    val w = colW / lanes
                    val top = headH + (s.start - minH * 60) * hourH / 60
                    val h = maxOf(dp(36), (s.end - s.start) * hourH / 60 - dp(2))
                    grid.addView(weekBlock(s, lanes > 1),
                        FrameLayout.LayoutParams(w - dp(4), h).apply { leftMargin = col * colW + lane * w + dp(2); topMargin = top })
                }
                i = j
            }
        }

        val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wrap.addView(gutter, LinearLayout.LayoutParams(gutterW, totalH))
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(grid, FrameLayout.LayoutParams(gridW, totalH))
        }
        wrap.addView(hs, LinearLayout.LayoutParams(0, totalH, 1f))
        hs.post { hs.scrollTo(maxOf(0, days.indexOf(today).coerceAtLeast(0) - 1) * colW, 0) }
        return wrap
    }

    private fun weekBlock(s: Slot, narrow: Boolean): View {
        val c = colours[s.course] ?: Ui.CYAN
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(4), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(Radius.SM).toFloat()
                setColor(Ui.tint(c, 0x40))
                setStroke(dp(1), c)
            }
            isClickable = true
            setOnClickListener { slotDetail(s) }   // tap anywhere: full detail popup
        }
        b.addView(tv(Shorts.display(this, s.course), Type.LABEL, Ui.TEXT, true).apply { maxLines = 1 })
        val sub = if (narrow) s.kind.tag else listOf(s.kind.tag, Timetable.fmtMin(s.start)).filter { it.isNotEmpty() }.joinToString(" · ")
        if (sub.isNotEmpty()) b.addView(tv(sub, Type.CAPTION, Ui.DIM).apply { maxLines = 1 })
        // location line when there is room for it (not the narrow multi-lane blocks)
        if (!narrow) {
            val loc = Timetable.locationOf(this, s.course)
            val line = when {
                loc.startsWith("online:") -> "💻 Online"
                loc.startsWith("room:") -> "📍 " + loc.removePrefix("room:")
                else -> ""
            }
            if (line.isNotEmpty()) b.addView(tv(line, Type.CAPTION, Ui.DIM).apply { maxLines = 1 })
        }
        b.contentDescription = "${Shorts.display(this, s.course)} ${s.kind.label}, ${Timetable.DAY_FULL[s.day - 1]} " +
            "${Timetable.fmtMin(s.start)} to ${Timetable.fmtMin(s.end)}. Tap for details."
        return b
    }

    private fun preferLecOf(s: Slot): Boolean? = when (s.kind) {
        SlotKind.LECTURE -> true
        SlotKind.TUTORIAL -> false
        else -> Checker.preferLecOf(s.title)
    }

    /** The meeting link for this exact class, resolved the same way as on the Home screen. */
    private fun meetingOf(s: Slot): String = Checker.linksForCourse(
        this, s.course, courseIdOf(s.course), Checker.groupTokens(s.title + " " + s.note),
        preferLecOf(s), s.day, s.start, s.end,
    ).first

    /** Popup with the full class detail: name, time, location, lecturer, attendance link(s). */
    private fun slotDetail(s: Slot) {
        val c = colours[s.course] ?: Ui.CYAN
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
        box.addView(tv(Shorts.display(this, s.course), Type.HEADLINE, c, true))
        box.addView(tv("${Timetable.DAY_FULL[s.day - 1]} · ${Timetable.fmtMin(s.start)} – ${Timetable.fmtMin(s.end)}", Type.BODY, Ui.TEXT, true).apply { setPadding(0, dp(6), 0, 0) })
        if (s.kind != SlotKind.CLASS) box.addView(tv(s.kind.label, Type.LABEL, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) })
        val loc = Timetable.locationOf(this, s.course)
        val autoMeeting = meetingOf(s)
        val attUrl = userAttLink(s.course).ifEmpty { Checker.attendanceUrl(this, courseIdOf(s.course), preferLecOf(s)) }
        val locLine = when {
            Timetable.inPerson(this, s.course) -> if (loc.startsWith("room:")) "📍 In person · " + loc.removePrefix("room:") else ""
            loc.startsWith("online:") -> "💻 Online" + (if (Timetable.meetingLinkOf(this, s.course).isNotEmpty()) " · your link" else "")
            loc.startsWith("room:") -> "📍 " + loc.removePrefix("room:")
            autoMeeting.isNotEmpty() -> "💻 Online (auto)"
            else -> ""
        }
        val choice = Catalog.find(this, s.course)?.choices(s.kind)?.firstOrNull { ch ->
            ch.occ == occOf(s) || ch.parts.any { it.day == s.day && it.start == s.start }
        }
        val catRoom = choice?.parts?.filter { it.day == s.day }?.map { it.room }?.filter { it.isNotEmpty() }?.distinct()?.joinToString(", ") ?: ""
        if (locLine.isNotEmpty()) box.addView(tv(locLine, Type.LABEL, Ui.TEXT).apply { setPadding(0, dp(10), 0, 0) })
        else if (catRoom.isNotEmpty()) box.addView(tv("📍 $catRoom", Type.LABEL, Ui.TEXT).apply { setPadding(0, dp(10), 0, 0) })
        val lecturer = choice?.detail() ?: ""
        if (lecturer.isNotEmpty()) box.addView(tv("👤 $lecturer", Type.LABEL, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) })
        if (s.note.isNotEmpty()) box.addView(tv(s.note, Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(6), 0, 0) })
        // action buttons: attendance page (primary), meeting link, course page — whichever exist
        fun pillBtn(label: String, url: String, filled: Boolean): TextView = TextView(this).apply {
            text = label
            textSize = Type.LABEL * Ui.fontScale
            setTextColor(if (filled) onAccent() else c)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            background = GradientDrawable().apply {
                cornerRadius = dp(Radius.LG).toFloat()
                if (filled) setColor(c) else { setColor(0x00000000); setStroke(dp(1), c) }
            }
            setOnClickListener {
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                catch (_: Exception) { Toast.makeText(this@TimetableActivity, "Cannot open link", Toast.LENGTH_SHORT).show() }
            }
        }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        if (attUrl.isNotEmpty()) btnRow.addView(pillBtn("Attendance", attUrl, true), LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        val meeting = autoMeeting
        if (meeting.isNotEmpty()) btnRow.addView(pillBtn("Join class", meeting, attUrl.isEmpty()), LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) })
        val courseUrl = Prefs(this).site.let { if (it.isNotEmpty()) "$it/course/view.php?id=${courseIdOf(s.course)}" else "" }
        if (courseUrl.isNotEmpty()) btnRow.addView(pillBtn("Course page", courseUrl, attUrl.isEmpty() && meeting.isEmpty()), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (btnRow.childCount > 0) box.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(tv("Edit class", Type.LABEL, Ui.CYAN, true).apply {
            setPadding(0, dp(14), 0, 0)
            minHeight = dp(Ui.touch(48))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }.also { it.setOnClickListener { openEdit(s) } })
        android.app.AlertDialog.Builder(this, Ui.dialogTheme)
            .setView(box)
            .setPositiveButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------ legend + hidden classes
    private fun renderLegend() {
        val courses = slots.map { it.course }.toSortedSet()
        box.addView(sectionLabel("Courses").apply { setPadding(0, dp(28), 0, 0) })
        val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in courses) {
            val text = SpannableString("●  $c")
            text.setSpan(ForegroundColorSpan(colours[c] ?: Ui.CYAN), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            row.addView(TextView(this).apply {
                this.text = text
                textSize = Type.BODY * Ui.fontScale
                setTextColor(Ui.TEXT)
                gravity = Gravity.CENTER
                minHeight = dp(Ui.touch(48))
                setPadding(dp(14), 0, dp(14), 0)
                background = roundBg(Ui.BAR, Ui.BORDER, this@TimetableActivity, Radius.PILL)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        sc.addView(row)
        box.addView(sc, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        box.label("Tap any class to change its type or colour, or to remove it.", Type.CAPTION, Ui.DIM, 8)

        val hidden = Timetable.hiddenCount(this)
        if (hidden > 0) {
            box.btn("Show $hidden hidden Spectrum class" + (if (hidden == 1) "" else "es")) {
                Timetable.restoreHidden(this)
                render()
            }
        }
    }


    /** "+" opens a choice: a class (existing editor) or a calendar event. */
    private fun showAddChoice() {
        val names = arrayOf("Add a class", "Add an event")
        android.app.AlertDialog.Builder(this, Ui.dialogTheme)
            .setTitle("What do you want to add?")
            .setItems(names) { _, which ->
                if (which == 0) openEdit(null) else EventsUi.editor(this, null, { render() })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}