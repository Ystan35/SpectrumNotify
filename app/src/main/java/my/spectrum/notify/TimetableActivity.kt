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
class TimetableActivity : Activity() {
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
        box = screen("Timetable", navTo = TimetableActivity::class.java, actionIcon = R.drawable.ic_add, actionDesc = "Add a class", onAction = { openEdit(null) })
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        render()
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size * Ui.fontScale
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
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

        if (slots.isEmpty()) {
            renderEmpty()
            return
        }

        box.chips(listOf("Day", "Week"), setOf(mode), false) { mode = it.first(); render() }
        if (mode == 0) renderDay() else renderWeek()
        renderLegend()
    }

    private fun renderEmpty() {
        val loggedIn = Prefs(this).token.isNotEmpty()
        box.label("No classes yet", 20f, Ui.TEXT, 24).setTypeface(null, Typeface.BOLD)
        box.label(
            if (loggedIn) "Your timetable fills in by itself from the classes Spectrum lists for you. " +
                "It usually appears after the next check. You can also add your own classes."
            else "Log in first, then your timetable fills in from Spectrum. You can also add your own classes without logging in.",
            14f, Ui.DIM, 8,
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

        val list = slots.filter { it.day == day }
        val heading = (if (day == today) "Today" else Timetable.DAY_FULL[day - 1]) +
            if (list.isEmpty()) "" else "  ·  ${list.size} class" + (if (list.size == 1) "" else "es")
        box.label(heading, 16f, Ui.TEXT, 20).setTypeface(null, Typeface.BOLD)

        if (list.isEmpty()) {
            box.label("Nothing scheduled on ${Timetable.DAY_FULL[day - 1]}.", 14f, Ui.DIM, 8)
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
            val card = slotCard(s, badge)
            card.scaleX = zoom
            card.scaleY = zoom
            box.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (dp(10) * zoom).toInt() })
        }
    }

    private fun slotCard(s: Slot, badge: String): View {
        val c = colours[s.course] ?: Ui.CYAN
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundBg(Ui.BAR, Ui.BORDER, this@TimetableActivity, 14)
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
        top.addView(tv("${Timetable.fmtMin(s.start)} – ${Timetable.fmtMin(s.end)}", 16f, Ui.TEXT, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (badge.isNotEmpty()) {
            top.addView(tv(badge, 11f, onAccent(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(c) }
            })
        }
        col.addView(top)

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        mid.addView(tv(s.course, 15f, c, true))
        if (s.kind != SlotKind.CLASS) {
            mid.addView(tv(s.kind.label, 12f, Ui.TEXT).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = roundBg(0, Ui.BORDER, this@TimetableActivity, 8)
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
        val detail = listOf(s.title, s.note).filter { it.isNotEmpty() }.joinToString("  ·  ")
        val meta = listOf(detail, durS, if (s.manual) "Added by you" else "From Spectrum").filter { it.isNotEmpty() }.joinToString("  ·  ")
        col.addView(tv(meta, 12f, Ui.DIM).apply { setPadding(0, dp(6), 0, 0) })
        // location: user override first; else the auto meeting link (Online) or the catalog room
        val userLoc = Timetable.locationOf(this, s.course)
        val autoMeeting = Checker.courseMeetingLink(this, courseIdOf(s.course))
        val locLine = when {
            userLoc.startsWith("online:") -> "💻 Online" + (if (userLoc.removePrefix("online:").isNotEmpty()) " · link set" else "")
            userLoc.startsWith("room:") -> "📍 " + userLoc.removePrefix("room:")
            autoMeeting.isNotEmpty() -> "💻 Online (auto)"
            room.isNotEmpty() -> "📍 $room"
            else -> "📍 Tap to set location"
        }
        val loc = tv(locLine, 12f, if (userLoc.isEmpty() && room.isEmpty() && autoMeeting.isEmpty()) Ui.DIM else Ui.TEXT).apply {
            setPadding(0, dp(2), 0, 0)
            isClickable = true
            background = rippleBg(Ui.BAR, 0, this@TimetableActivity, 10)
        }
        loc.setOnClickListener { editLocation(s.course, autoMeeting) }
        col.addView(loc)
        // attendance field: auto-filled from the course's Spectrum attendance page,
        // placeholder when nothing is known, tap to paste one or more links
        val auto = Checker.attendanceUrl(this, courseIdOf(s.course))
        val user = userAttLink(s.course)
        val link = user.ifEmpty { auto }
        val att = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        att.addView(tv("Attendance", 12f, Ui.DIM, true))
        if (link.isNotEmpty()) {
            att.addView(tv(link.take(46) + if (link.length > 46) "…" else "", 12f, c, true).apply {
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            val open = TextView(this).apply {
                text = "Open"
                textSize = 12f
                setTextColor(Ui.CYAN)
                setTypeface(typeface, Typeface.BOLD)
                minHeight = dp(40)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = rippleBg(Ui.BAR, 0, this@TimetableActivity, 10)
            }
            open.setOnClickListener {
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))) }
                catch (_: Exception) { Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show() }
            }
            att.addView(open)
        } else {
            att.addView(tv("no link yet — tap to paste", 12f, Ui.DIM).apply {
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        att.isClickable = true
        att.setOnClickListener { pasteAttendance(s.course) }
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
        val names = listOf("💻 Online class", "📍 Physical room", "Clear")
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
                        Timetable.setLocation(this, course, "online:${link.ifEmpty { autoMeeting }}")
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
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
            text = t; textSize = 18f * Ui.fontScale; setTextColor(Ui.CYAN)
            setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            minHeight = dp(40); minWidth = dp(40)
            background = rippleBg(Ui.BAR, Ui.BORDER, this@TimetableActivity, 20)
        }
        val minus = btn("−")
        minus.setOnClickListener { zoom = (zoom - 0.15f).coerceAtLeast(0.7f); render() }
        val label = tv("${(zoom * 100).toInt()}%", 12f, Ui.DIM, true)
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

        val gutter = FrameLayout(this)
        val grid = FrameLayout(this)

        // today's column tint
        grid.addView(View(this).apply { setBackgroundColor(Ui.tint(Ui.CYAN, 0x14)) },
            FrameLayout.LayoutParams(colW, totalH - headH).apply { leftMargin = (today - 1) * colW; topMargin = headH })

        for (h in minH..maxH) {
            val y = headH + (h - minH) * hourH
            gutter.addView(tv(String.format(java.util.Locale.ENGLISH, "%02d:00", h), 11f, Ui.DIM),
                FrameLayout.LayoutParams(gutterW, WRAP).apply { topMargin = y - dp(7) })
            grid.addView(View(this).apply { setBackgroundColor(Ui.BORDER) },
                FrameLayout.LayoutParams(7 * colW, 1).apply { topMargin = y })
        }
        for (d in 1..7) {
            grid.addView(tv(Timetable.DAYS[d - 1], 13f, if (d == today) Ui.CYAN else Ui.TEXT, d == today).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(colW, headH).apply { leftMargin = (d - 1) * colW })
        }

        for (d in 1..7) {
            val list = slots.filter { it.day == d }.sortedBy { it.start }
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
                    val h = maxOf(dp(30), (s.end - s.start) * hourH / 60 - dp(2))
                    grid.addView(weekBlock(s, lanes > 1),
                        FrameLayout.LayoutParams(w - dp(4), h).apply { leftMargin = (d - 1) * colW + lane * w + dp(2); topMargin = top })
                }
                i = j
            }
        }

        val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wrap.addView(gutter, LinearLayout.LayoutParams(gutterW, totalH))
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(grid, FrameLayout.LayoutParams(7 * colW, totalH))
        }
        wrap.addView(hs, LinearLayout.LayoutParams(0, totalH, 1f))
        hs.post { hs.scrollTo(maxOf(0, today - 2) * colW, 0) }
        return wrap
    }

    private fun weekBlock(s: Slot, narrow: Boolean): View {
        val c = colours[s.course] ?: Ui.CYAN
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(4), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Ui.tint(c, 0x40))
                setStroke(dp(1), c)
            }
            isClickable = true
            setOnClickListener { openEdit(s) }
        }
        b.addView(tv(s.course, 12f, Ui.TEXT, true).apply { maxLines = 1 })
        val sub = if (narrow) s.kind.tag else listOf(s.kind.tag, Timetable.fmtMin(s.start)).filter { it.isNotEmpty() }.joinToString(" · ")
        if (sub.isNotEmpty()) b.addView(tv(sub, 10f, Ui.DIM).apply { maxLines = 1 })
        return b
    }

    // ------------------------------------------------------------ legend + hidden classes
    private fun renderLegend() {
        val courses = slots.map { it.course }.toSortedSet()
        box.label("Courses", 13f, Ui.CYAN, 28).setTypeface(null, Typeface.BOLD)
        val sc = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in courses) {
            val text = SpannableString("●  $c")
            text.setSpan(ForegroundColorSpan(colours[c] ?: Ui.CYAN), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 14f * Ui.fontScale
                setTextColor(Ui.TEXT)
                gravity = Gravity.CENTER
                minHeight = dp(40)
                setPadding(dp(14), 0, dp(14), 0)
                background = roundBg(Ui.BAR, Ui.BORDER, this@TimetableActivity, 20)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        sc.addView(row)
        box.addView(sc, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        box.label("Tap any class to change its type or colour, or to remove it.", 12f, Ui.DIM, 8)

        val hidden = Timetable.hiddenCount(this)
        if (hidden > 0) {
            box.btn("Show $hidden hidden Spectrum class" + (if (hidden == 1) "" else "es")) {
                Timetable.restoreHidden(this)
                render()
            }
        }
    }
}
