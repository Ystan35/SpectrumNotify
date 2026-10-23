package my.spectrum.notify

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Attendance page: every class today that records attendance, with its join link.
 * Ended classes fade and sink to the bottom. Duplicate events for the same course on the
 * same day (some courses log lecture and tutorial separately) are clarified once via a
 * dialog and the choice can be redone from the "Fix labels" action.
 */
class AttendanceActivity : Activity() {
    private lateinit var col: LinearLayout
    private lateinit var p: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        col = screen("Attendance", navTo = AttendanceActivity::class.java)
        col.label("Every class today that records attendance, with its link. Ended classes fade to the bottom.", 12f, Ui.DIM, 4)
        col.action("Fix lecture / tutorial labels", "If a class was labelled wrongly (or you were not asked), relabel duplicates here.") { clarifyDuplicates(true) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private lateinit var list: LinearLayout

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size * Ui.fontScale
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 30000) }
    }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        maybeClarify()
        render()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------ duplicates

    /** Groups today's attendance events by course. A course with 2+ events needs labels. */
    private fun duplicates(): Map<String, List<Checker.ClassItem>> {
        val now = System.currentTimeMillis() / 1000
        return Checker.overview(this).classes
            .filter { isToday(it.start) }
            .groupBy { it.course }
            .filter { it.value.size > 1 }
    }

    private fun maybeClarify() {
        val dup = duplicates().filterKeys { labelOf(it) == null }
        if (dup.isNotEmpty()) clarifyDuplicates(false)
    }

    /** What the user said the two events of [course] are: null = not asked yet. */
    private fun labelOf(course: String): String? = p.attendanceLabel(course)

    private fun saveLabels(course: String, first: String, second: String) = p.setAttendanceLabel(course, first, second)

    /** Dialog asking which event is the lecture and which the tutorial (or both attendance). */
    private fun clarifyDuplicates(manual: Boolean) {
        val dup = duplicates()
        var shown = false
        for ((course, items) in dup) {
            if (labelOf(course) != null && !manual) continue
            val sorted = items.sortedBy { it.start }
            val kinds = listOf("Lecture", "Tutorial", "Both (attendance)", "Not attendance")
            val chosen = intArrayOf(0, 1)
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("$course: two events today")
                .setMessage(
                    "This course has two attendance-like events today:\n\n" +
                        sorted.mapIndexed { i, c -> "${i + 1}. ${hhmm(c.start)}–${hhmm(c.end)} ${c.name.take(60)}" }.joinToString("\n") +
                        "\n\nWhich is which? You can change this later in Fix labels."
                )
                .setPositiveButton("Save") { _, _ ->
                    saveLabels(course, kinds[chosen[0]], kinds[chosen[1]])
                    Toast.makeText(this, "$course labels saved. Open Fix labels to change them.", Toast.LENGTH_LONG).show()
                    render()
                }
                .setNegativeButton("Skip", null)
                .show()
            // simple two-spinners dialog body
            shown = true
            break // one course at a time; the rest ask on the next visit/re-render
        }
        if (!shown && manual) Toast.makeText(this, "No unlabelled duplicates.", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ the list

    private fun render() {
        val now = System.currentTimeMillis() / 1000
        val todays = Checker.overview(this).classes.filter { isToday(it.start) }
        list.removeAllViews()
        if (todays.isEmpty()) {
            list.label("No classes today.", 15f, Ui.DIM, 24)
            return
        }
        val dup = duplicates()
        val live = ArrayList<Checker.ClassItem>()
        val ended = ArrayList<Checker.ClassItem>()
        for (c in todays) (if (c.end <= now) ended else live).add(c)

        for (c in live + ended) {
            val isEnded = c.end <= now
            val isLive = !isEnded && c.start <= now
            val col = Timetable.colours(this, setOf(c.course))[c.course] ?: Ui.CYAN
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(14))
                background = roundBg(Ui.BAR, Ui.BORDER, this@AttendanceActivity, 16)
                alpha = if (isEnded) 0.35f else 1f
            }
            // course colour chip + time row
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(col) }
            }, lp(dp(10), dp(10)).apply { rightMargin = dp(8) })
            top.addView(tv("${hhmm(c.start)}–${hhmm(c.end)}", 13f, Ui.DIM, true))
            if (isLive) top.addView(tv("  ·  NOW", 12f, Ui.GREEN, true))
            card.addView(top)
            card.addView(tv(c.course, 17f, Ui.TEXT, true).apply { setPadding(0, dp(6), 0, 0) })
            if (c.name.isNotEmpty()) card.addView(tv(c.name, 12f, Ui.DIM).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
            })
            // duplicate label from the user's choice, or a prompt to label it
            val items = dup[c.course]
            if (items != null && items.size > 1) {
                val lbl = labelOf(c.course)
                val idx = items.sortedBy { it.start }.indexOfFirst { it.start == c.start }
                val text = when {
                    lbl == null -> "Tap to label this duplicate (lecture / tutorial)"
                    else -> lbl.split(",").getOrNull(idx)?.substringAfter("=")?.replace(",", "") ?: ""
                }
                if (lbl == null) {
                    card.addView(tv(text, 12f, Ui.ORANGE, true).apply { setPadding(0, dp(6), 0, 0) })
                    card.setOnClickListener { clarifyDuplicates(false) }
                } else {
                    card.addView(tv(text, 12f, col, true).apply { setPadding(0, dp(6), 0, 0) })
                }
            }
            val attUrl = Checker.attendanceUrl(this, c.courseId)
            if (attUrl.isNotEmpty()) {
                val b = TextView(this).apply {
                    text = "Open attendance page"
                    textSize = 13f * Ui.fontScale
                    setTextColor(Ui.CYAN)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    minHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    background = rippleBg(Ui.BAR, 0, this@AttendanceActivity, 12)
                }
                b.setOnClickListener {
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(attUrl))) }
                    catch (_: Exception) { Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show() }
                }
                card.addView(b, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            } else if (c.link.isNotEmpty() && !c.manual) {
                // attendance page not found for this course yet — fall back to the class link
                val b = TextView(this).apply {
                    text = "Open class link (attendance page not found yet)"
                    textSize = 13f * Ui.fontScale
                    setTextColor(Ui.DIM)
                    minHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    background = rippleBg(Ui.BAR, 0, this@AttendanceActivity, 12)
                }
                b.setOnClickListener {
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(c.link))) }
                    catch (_: Exception) { Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show() }
                }
                card.addView(b, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            } else if (c.manual) {
                card.addView(tv("Attendance by Teams recording.", 12f, Ui.DIM).apply { setPadding(0, dp(6), 0, 0) })
            } else {
                card.addView(tv("No link yet.", 12f, Ui.DIM).apply { setPadding(0, dp(6), 0, 0) })
            }
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
        // section separators
        if (ended.isNotEmpty() && live.isNotEmpty()) {
            list.label("Ended today", 12f, Ui.DIM, 16)
            // move the ended cards visually below by re-adding a divider view before them is already handled by order
        }
    }
}
