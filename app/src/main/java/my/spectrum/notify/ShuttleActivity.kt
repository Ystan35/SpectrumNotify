package my.spectrum.notify

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * UM shuttle: the next buses from a stop, and each route's stops and trips for today.
 * Opened from the toolbox, from Settings → UM shuttle, and by tapping the bus line on Home.
 */
class ShuttleActivity : BaseActivity() {
    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_FROM = "from"
    }

    private lateinit var col: LinearLayout
    private var fromId = ""
    private var routeIdx = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 60_000) }
    }

    private fun tv(text: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size * Ui.fontScale
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = screen("UM Shuttle")
        val d = Shuttle.data(this)
        fromId = intent.getStringExtra(EXTRA_FROM)?.takeIf { it in d.places }
            ?: Shuttle.homeId(this).takeIf { id -> d.places[id]?.isStop == true }
            ?: "UMC"
        routeIdx = d.routes.indexOfFirst { it.id == intent.getStringExtra(EXTRA_ROUTE) }.coerceAtLeast(0)
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun open(url: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) { Toast.makeText(this, "Cannot open this", Toast.LENGTH_SHORT).show() }

    private fun dot(color: Int): View = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun render() {
        val d = Shuttle.data(this)
        val today = LocalDate.now(zone())
        val now = LocalTime.now(zone()).let { it.hour * 60 + it.minute }
        col.removeAllViews()

        // ---- does it run today?
        val runsToday = d.routes.any { ShuttleModel.runs(it, today).isNotEmpty() }
        val status = when {
            !runsToday -> "No shuttle today (it runs Monday to Friday)."
            !AcademicCalendar.inTeaching(this, today) -> "Outside teaching weeks: the shuttle may not run (breaks and study week)."
            today.dayOfWeek == DayOfWeek.FRIDAY -> "Friday: no buses 12:30 pm – 2:30 pm (Friday prayer)."
            else -> ""
        }
        if (status.isNotEmpty()) col.label(status, 13f, Ui.ORANGE, 6).setTypeface(null, Typeface.BOLD)

        // ---- next buses from a stop
        col.label("Next buses from", 13f, Ui.TEXT, 16).setTypeface(null, Typeface.BOLD)
        val stops = d.places.values.filter { it.isStop }.sortedBy { it.name.lowercase(Locale.ROOT) }
        val sel = stops.indexOfFirst { it.id == fromId }.coerceAtLeast(0)
        col.addView(spinner(stops.map { it.name }, sel) { i ->
            if (stops[i].id != fromId) { fromId = stops[i].id; render() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val next = ShuttleModel.departures(d, fromId, today, now).take(5)
        if (next.isEmpty()) {
            // the first bus on the next day the shuttle runs
            val later = (1..7L).asSequence().map { today.plusDays(it) }
                .mapNotNull { day -> ShuttleModel.departures(d, fromId, day).firstOrNull()?.let { Pair(day, it) } }
                .firstOrNull()
            col.label(
                "No more buses from here today." + (later?.let { (day, dep) ->
                    " Next: ${day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))} ${ShuttleModel.fmt(dep.time)}, route ${dep.run.route.id}."
                } ?: ""),
                13f, Ui.DIM, 8,
            )
        }
        for (dep in next) {
            val r = dep.run.route
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rippleBg(Ui.BAR, Ui.BORDER, this@ShuttleActivity, 12)
                isClickable = true
                setOnClickListener { routeIdx = d.routes.indexOf(r); render() }
            }
            row.addView(tv(r.id, 14f, onAccent(), true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(r.color) }
            }, LinearLayout.LayoutParams(dp(44), dp(32)))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            val mins = dep.time - now
            texts.addView(tv("${ShuttleModel.fmt(dep.time)}  ·  " + (if (mins <= 0) "now" else "in $mins min"), 15f, Ui.TEXT, true))
            texts.addView(tv("→ " + r.stops.drop(dep.idx + 1).joinToString(" → ") { Shuttle.stopName(this, it) }, 12f, Ui.DIM).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.contentDescription = "Route ${r.id} at ${ShuttleModel.fmt(dep.time)}, ${if (mins <= 0) "now" else "in $mins minutes"}. Tap for the route."
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        if (Shuttle.homeId(this).isEmpty()) {
            col.label("Tip: set your home stop in Settings → UM shuttle, and Home shows which bus to take to each class.", 12f, Ui.DIM, 8)
        }

        // ---- one route: stops in order with the next trip's time, then today's trips
        col.label("Routes", 13f, Ui.TEXT, 22).setTypeface(null, Typeface.BOLD)
        routeIdx = routeIdx.coerceIn(0, (d.routes.size - 1).coerceAtLeast(0))
        if (d.routes.isEmpty()) return
        col.chips(d.routes.map { it.id }, setOf(routeIdx), false) { routeIdx = it.first(); render() }
        val r = d.routes[routeIdx]
        col.label(r.name, 15f, Ui.TEXT, 12).setTypeface(null, Typeface.BOLD)
        val runs = ShuttleModel.runs(r, today)
        // the trip to show along the stops: the one running now, else the next, else the first of the day
        val shown = runs.firstOrNull { now <= it.end } ?: runs.firstOrNull()
        col.label(
            when {
                shown == null -> "No trips today. Stops, with minutes from the start:"
                now in shown.start..shown.end -> "On the road now (left ${ShuttleModel.fmt(shown.start)}):"
                else -> "Next trip, ${ShuttleModel.fmt(shown.start)} from ${Shuttle.stopName(this, r.stops[shown.first])}:"
            }, 12f, Ui.DIM, 4,
        )
        r.stops.forEachIndexed { i, id ->
            val skipped = shown != null && i < shown.first
            val time = when {
                shown == null -> "+${r.offsets[i] - r.offsets[0]} min"
                skipped -> "—"
                else -> "~" + ShuttleModel.fmt(shown.at(i))
            }
            val passed = shown != null && !skipped && shown.at(i) < now
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(36)
            }
            row.addView(dot(if (passed || skipped) Ui.tint(r.color, 0x66) else r.color), LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(12) })
            row.addView(tv(Shuttle.stopName(this, id), 14f, if (passed || skipped) Ui.DIM else Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(tv(time, 13f, if (passed || skipped) Ui.DIM else Ui.TEXT, !passed && !skipped))
            row.isClickable = true
            row.background = rippleBg(Ui.BG, 0, this, 8)
            row.setOnClickListener { if (d.places[id]?.isStop == true) { fromId = id; render() } }
            row.contentDescription = "${Shuttle.stopName(this, id)}, $time. Tap for the next buses from here."
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (r.tailEstimated) col.label("The last stops' times are estimated.", 11f, Ui.DIM, 2)
        if (shown != null && shown.first > 0) {
            col.label("Morning trips until ${ShuttleModel.fmt(r.farUntil)} start at ${Shuttle.stopName(this, r.farStop)}.", 11f, Ui.DIM, 2)
        }

        if (runs.isNotEmpty()) {
            col.label("Today's trips (from the first stop)", 13f, Ui.TEXT, 16).setTypeface(null, Typeface.BOLD)
            val sb = SpannableStringBuilder()
            for ((k, run) in runs.withIndex()) {
                if (k > 0) sb.append("   ")
                val s = sb.length
                sb.append(ShuttleModel.fmt(run.start) + if (run.first > 0) "*" else "")
                if (run.end < now) sb.setSpan(ForegroundColorSpan(Ui.tint(Ui.DIM, 0x88)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            col.addView(tv(sb, 14f, Ui.TEXT).apply { setLineSpacing(dp(4).toFloat(), 1f); setPadding(0, dp(6), 0, 0) })
            if (runs.any { it.first > 0 }) col.label("* starts at ${Shuttle.stopName(this, r.farStop)}", 11f, Ui.DIM, 4)
        }

        // ---- source and contact
        col.label("About these times", 13f, Ui.TEXT, 24).setTypeface(null, Typeface.BOLD)
        for (n in d.notes) col.label("• $n", 12f, Ui.DIM, 4)
        col.label("Source: ${d.source}. Data as of ${d.generatedAt}.", 11f, Ui.DIM, 8)
        val links = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun linkBtn(label: String, go: () -> Unit) = links.addView(chip(label, false, size = 13f, padH = 12) { go() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (links.childCount > 0) leftMargin = dp(6) })
        if (d.officialUrl.isNotEmpty()) linkBtn("Official page") { open(d.officialUrl) }
        if (d.phone.isNotEmpty()) linkBtn("Call") { open("tel:" + d.phone.replace(" ", "")) }
        if (d.email.isNotEmpty()) linkBtn("Email") { open("mailto:" + d.email) }
        if (links.childCount > 0) col.addView(links, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }
}
