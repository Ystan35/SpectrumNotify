package my.spectrum.notify

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Settings → Online or in person: one card per course (all ticked to start) with whether it is online or in
 * person now, and buttons to switch the ticked ones. Every switch offers Undo.
 */
object ClassModeEditor {

    fun render(activity: Activity, parent: LinearLayout) {
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        draw(activity, box, null)
    }

    private fun draw(activity: Activity, box: LinearLayout, keepTicked: Set<String>?) {
        box.removeAllViews()
        val courses = Courses.all(activity).filter { it.code.isNotEmpty() }.distinctBy { it.code.uppercase(Locale.ROOT) }
        if (courses.isEmpty()) {
            box.label("Your courses appear here after the first check.", Type.LABEL, Ui.DIM, 8)
            return
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        val colours = Timetable.colours(activity, courses.map { it.code }.toSet())
        val ticks = LinkedHashMap<String, CheckBox>()
        val count = activity.tv("", Type.LABEL, Ui.DIM, true)
        fun ticked() = ticks.filter { it.value.isChecked }.keys
        fun updateCount() { count.text = "${ticked().size} of ${ticks.size} ticked" }

        // header: how many are ticked, and tick / untick all
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(count, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(activity.button("Tick all", Emphasis.TERTIARY) { ticks.values.forEach { it.isChecked = true } })
        head.addView(activity.button("Untick all", Emphasis.TERTIARY) { ticks.values.forEach { it.isChecked = false } })
        box.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) })

        // one card per course: tick box, code in the course colour, name, and a pill saying online or in person
        for (c in courses) {
            val code = c.code.uppercase(Locale.ROOT)
            val inP = Timetable.inPerson(activity, code)
            val since = Timetable.inPersonSince(activity, code)
            val colour = colours[c.code] ?: Ui.CYAN
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(4), activity.dp(10), activity.dp(14), activity.dp(10))
                minimumHeight = activity.dp(Ui.touch(48))
                isClickable = true
            }
            val cb = CheckBox(activity).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(Ui.CYAN)
                isChecked = keepTicked?.contains(code) ?: true
                // the card is the touch target; the box only shows the state
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val short = Shorts.get(activity, code)
            text.addView(activity.tv(code + if (short.isNotEmpty()) "  ·  $short" else "", Type.LABEL, colour, true).apply { letterSpacing = 0.06f })
            val name = Timetable.classTitle(c.code, c.name, "")
            if (name.isNotEmpty()) text.addView(activity.tv(name, Type.BODY, Ui.TEXT, true).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val pillCol = if (inP) Ui.GREEN else Ui.CYAN
            text.addView(activity.iconText(
                if (inP) R.drawable.ic_place else R.drawable.ic_laptop,
                if (inP) "In person" + (if (since > 0) " since " + Instant.ofEpochSecond(since).atZone(zone()).format(fmt) else "") else "Online",
                Type.CAPTION, pillCol, true,
            ).apply {
                setPadding(activity.dp(10), activity.dp(4), activity.dp(12), activity.dp(4))
                background = roundBg(Ui.tint(pillCol, 0x26), 0, activity, Radius.PILL)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(6) })
            card.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            fun paint() {
                // ticked: accent edge and a light accent fill, so the selection reads at a glance
                card.background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Ui.tint(Ui.CYAN, 60)),
                    roundBg(if (cb.isChecked) Ui.tint(Ui.CYAN, 0x14) else Ui.BAR, if (cb.isChecked) Ui.CYAN else Ui.BORDER, activity, Radius.MD),
                    null,
                )
                card.contentDescription = "$code ${name}, " + (if (inP) "in person" else "online") + ", " + (if (cb.isChecked) "ticked" else "not ticked")
                updateCount()
            }
            cb.setOnCheckedChangeListener { _, _ -> paint() }
            card.setOnClickListener { cb.isChecked = !cb.isChecked }
            ticks[code] = cb
            paint()
            box.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) })
        }

        fun switch(on: Boolean) {
            val chosen = ticked()
            if (chosen.isEmpty()) { android.widget.Toast.makeText(activity, "Tick at least one course.", android.widget.Toast.LENGTH_SHORT).show(); return }
            // what each was before, for Undo
            val before = chosen.associateWith { Timetable.inPerson(activity, it) }
            Timetable.setInPerson(activity, chosen, on)
            Scheduler.scheduleAll(activity)
            draw(activity, box, chosen)
            val n = chosen.size
            activity.undoBar(if (on) "$n course${if (n > 1) "s" else ""} in person" else "$n course${if (n > 1) "s" else ""} online again") {
                Timetable.setInPerson(activity, before.filterValues { it }.keys, true)
                Timetable.setInPerson(activity, before.filterValues { !it }.keys, false)
                Scheduler.scheduleAll(activity)
                draw(activity, box, chosen)
            }
        }
        // the two switches side by side, then what each one does
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(activity.button("Mark in person", Emphasis.PRIMARY) { switch(true) }.apply {
            contentDescription = "Mark ticked courses as in person"
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(activity.button("Mark online", Emphasis.SECONDARY) { switch(false) }.apply {
            contentDescription = "Mark ticked courses as online"
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = activity.dp(8) })
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(14) })
        box.label("In person: no Join class or meeting links; the room is shown. If one of them posts a new meeting link, " +
            "you are asked whether it is online again. Online: Join class and the detected meeting links come back.", Type.CAPTION, Ui.DIM, 8)
    }
}
