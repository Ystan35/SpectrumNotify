package my.spectrum.notify

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** All your courses: class times, and a button to each course's Spectrum home page. */
class CoursesActivity : Activity() {
    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = screen("Courses", navTo = CoursesActivity::class.java, onRefresh = { Scheduler.pollNow(this) })
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

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun render() {
        val list = Courses.all(this)
        box.removeAllViews()
        if (list.isEmpty()) {
            val loggedIn = Prefs(this).token.isNotEmpty()
            box.label("No courses yet", 20f, Ui.TEXT, 24).setTypeface(null, Typeface.BOLD)
            box.label(
                if (loggedIn) "Your courses appear after the next check of Spectrum."
                else "Log in first, then your courses are listed here.",
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
            return
        }
        val colours = Timetable.colours(this, list.map { it.code }.toSet())
        box.label("${list.size} course" + (if (list.size == 1) "" else "s"), 14f, Ui.DIM, 8)
        for (c in list) {
            box.addView(card(c, colours[c.code] ?: Ui.CYAN), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
    }

    private fun card(c: Courses.Course, colour: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundBg(Ui.BAR, Ui.BORDER, this@CoursesActivity, 14)
        }
        val r = dp(14).toFloat()
        card.addView(View(this).apply {
            background = GradientDrawable().apply {
                setColor(colour)
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
        }, LinearLayout.LayoutParams(dp(7), MATCH))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        col.addView(tv(c.code, 17f, colour, true))
        if (c.name.isNotEmpty() && !c.name.equals(c.code, ignoreCase = true)) {
            col.addView(tv(c.name, 14f, Ui.TEXT).apply { setPadding(0, dp(2), 0, 0) })
        }

        if (c.slots.isEmpty()) {
            val nh = tv("No class times known yet", 12f, Ui.DIM).apply { setPadding(0, dp(8), 0, 0) }
            col.addView(nh)
            // refresh line: re-pull the timetable from Spectrum (Occ may have changed or not fetched yet)
            val rf = tv("↻ Refresh class times", 13f, Ui.CYAN, true).apply {
                setPadding(0, dp(4), 0, 0)
                minHeight = dp(40)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = rippleBg(Ui.BAR, 0, this@CoursesActivity, 10)
            }
            rf.setOnClickListener {
                Scheduler.pollNow(this)
                nh.text = "Checking Spectrum…"
                Toast.makeText(this, "Checking Spectrum — class times update when the check finishes.", Toast.LENGTH_SHORT).show()
            }
            col.addView(rf)
        } else {
            for (s in c.slots) {
                val kind = if (s.kind == SlotKind.CLASS) "" else "  ·  ${s.kind.label}"
                col.addView(
                    tv("${Timetable.DAYS[s.day - 1]}  ${Timetable.fmtMin(s.start)} – ${Timetable.fmtMin(s.end)}$kind", 13f, Ui.DIM)
                        .apply { setPadding(0, dp(6), 0, 0) }
                )
            }
        }

        if (c.url.isNotEmpty()) {
            col.addView(tv("Course home page", 14f, onAccent(), true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(colour) }
                isClickable = true
                contentDescription = "Open ${c.code} home page on Spectrum"
                setOnClickListener { open(c.url) }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        if (c.id > 0L) {
            col.btn("Notification pages  ·  " + Sections.summary(this, c.id)) {
                startActivity(
                    Intent(this, SectionsActivity::class.java)
                        .putExtra(SectionsActivity.EXTRA_ID, c.id)
                        .putExtra(SectionsActivity.EXTRA_NAME, c.code + if (c.name.isNotEmpty()) "  " + c.name else "")
                )
            }
        }
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return card
    }
}
