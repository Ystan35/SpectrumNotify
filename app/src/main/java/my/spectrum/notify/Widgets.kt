package my.spectrum.notify

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Notification categories: label, icon and colour used on the main screen. */
object Cat {
    const val CLASS = "class"
    const val DEADLINE = "deadline"
    const val MATERIAL = "material"
    const val NEWS = "news"
    const val DAILY = "daily"
    const val OTHER = "other"

    private val NEW_RE = Regex(""": \d+ new$""")

    fun of(title: String, body: String): String = when {
        title.startsWith("Class in") || title.startsWith("Online link posted") -> CLASS
        title.startsWith("Due in") -> DEADLINE
        title.startsWith("Good morning") || title == "Classes done for today" ||
            title.endsWith("to go") || title.endsWith("classes to go") -> DAILY
        NEW_RE.containsMatchIn(title) -> if (body.contains("Announcement:", ignoreCase = true)) NEWS else MATERIAL
        else -> OTHER
    }

    fun label(c: String) = when (c) {
        CLASS -> "CLASS"
        DEADLINE -> "DEADLINE"
        MATERIAL -> "MATERIALS"
        NEWS -> "ANNOUNCEMENT"
        DAILY -> "DAILY"
        else -> "UPDATE"
    }

    fun icon(c: String) = when (c) {
        CLASS -> R.drawable.ic_cat_class
        DEADLINE -> R.drawable.ic_cat_deadline
        MATERIAL -> R.drawable.ic_cat_material
        NEWS -> R.drawable.ic_cat_news
        DAILY -> R.drawable.ic_cat_daily
        else -> R.drawable.ic_cat_other
    }

    fun color(c: String) = when (c) {
        CLASS -> Ui.CYAN
        DEADLINE -> Ui.RED
        MATERIAL -> Ui.GREEN
        NEWS -> Ui.ORANGE
        DAILY -> Ui.YELLOW
        else -> Ui.PURPLE
    }
}

/** "in 2h 15m", "in 12 min", "in 3 days" */
fun relIn(secs: Long): String = when {
    secs < 60 -> "in under a minute"
    secs < 3600 -> "in ${secs / 60} min"
    secs < 86400 -> {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        if (m == 0L) "in ${h}h" else "in ${h}h ${m}m"
    }
    else -> {
        val d = (secs + 43200) / 86400
        "in $d day" + if (d == 1L) "" else "s"
    }
}

/** Short form for chips: "40 min", "5h", "3d" */
fun relShort(secs: Long): String = when {
    secs < 3600 -> "${maxOf(1L, secs / 60)} min"
    secs < 86400 -> "${secs / 3600}h"
    else -> "${(secs + 43200) / 86400}d"
}

fun ago(ts: Long): String {
    val d = System.currentTimeMillis() / 1000 - ts
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86400 -> "${d / 3600}h ago"
        d < 2 * 86400 -> "yesterday"
        else -> localDt(ts).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
    }
}

fun isToday(ts: Long): Boolean = localDt(ts).toLocalDate() == LocalDate.now(zone())

fun dayLabel(ts: Long): String {
    val d = localDt(ts).toLocalDate()
    val today = LocalDate.now(zone())
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> localDt(ts).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
    }
}

/** A card that can be swiped left or right to dismiss it; taps and vertical scrolling still work. */
class SwipeCard(ctx: Context, private val onDismiss: () -> Unit) : LinearLayout(ctx) {
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private fun startsDrag(ev: MotionEvent): Boolean {
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        return abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> if (!dragging && startsDrag(ev)) {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { downX = ev.rawX; downY = ev.rawY; dragging = false }
        if (!dragging && ev.actionMasked == MotionEvent.ACTION_MOVE && startsDrag(ev)) {
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (!dragging) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                translationX = ev.rawX - downX
                alpha = 1f - minOf(1f, abs(translationX) / maxOf(1, width)) * 0.7f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val away = ev.actionMasked == MotionEvent.ACTION_UP && abs(translationX) > width * 0.4f
                if (away) {
                    val to = if (translationX > 0) width.toFloat() else -width.toFloat()
                    animate().translationX(to).alpha(0f).setDuration(160).withEndAction { onDismiss() }
                } else {
                    animate().translationX(0f).alpha(1f).setDuration(160)
                }
            }
        }
        return true
    }
}
