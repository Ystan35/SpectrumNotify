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
 * UM shuttle: plan a trip from one place to another (leave now / leave at / arrive by, one change at most) on a map,
 * the next buses from a stop, and each route's stops and trips for today.
 * Opened from the toolbox, from Settings → UM shuttle, and by tapping the bus line on Home.
 */
class ShuttleActivity : BaseActivity() {
    companion object {
        const val EXTRA_ROUTE = "route"
        const val EXTRA_FROM = "from"
    }

    private lateinit var planBox: LinearLayout
    private lateinit var restBox: LinearLayout
    private lateinit var map: ShuttleMap
    private var fromId = ""
    private var toId = ""
    /** 0 = leave now, 1 = leave at [atMin], 2 = arrive by [atMin]. */
    private var mode = 0
    private var atMin = LocalTime.now(zone()).let { it.hour * 60 + it.minute }
    /** The journey drawn on the map; -1 = show the chosen route instead. */
    private var chosenJ = 0
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
        val col = screen("UM Shuttle")
        val d = Shuttle.data(this)
        fromId = intent.getStringExtra(EXTRA_FROM)?.takeIf { it in d.places }
            ?: Shuttle.homeId(this).takeIf { id -> id in d.places }
            ?: "UMC"
        routeIdx = d.routes.indexOfFirst { it.id == intent.getStringExtra(EXTRA_ROUTE) }.coerceAtLeast(0)
        if (intent.hasExtra(EXTRA_ROUTE)) chosenJ = -1
        planBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        restBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        map = ShuttleMap(this) { p -> pickOnMap(p) }
        col.addView(planBox)
        col.addView(map.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)).apply { topMargin = dp(12) })
        col.label("Green: from · red: to · orange: faculties. Tap a dot to choose it. Pinch to zoom.", 11f, Ui.DIM, 4)
        col.addView(restBox)
    }

    /** A dot on the map was tapped: use it as From or To, or see the next buses there. */
    private fun pickOnMap(p: ShuttleModel.Place) {
        val opts = arrayListOf("Go from here", "Go here")
        if (p.isStop) opts.add("Next buses from here")
        android.app.AlertDialog.Builder(this, Ui.dialogTheme)
            .setTitle(p.name)
            .setItems(opts.toTypedArray()) { _, which ->
                when (which) {
                    0 -> setTrip(p.id, toId.takeIf { it != p.id } ?: "")
                    1 -> setTrip(fromId.takeIf { it != p.id } ?: fromId, p.id)
                    else -> { fromId = p.id; render() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setTrip(from: String, to: String) {
        fromId = from
        toId = to
        chosenJ = 0
        if (from.isNotEmpty() && to.isNotEmpty() && from != to) Shuttle.addRecentTrip(this, from, to)
        render()
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        map.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        handler.removeCallbacks(tick)
    }

    private fun open(url: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) { Toast.makeText(this, "Cannot open this", Toast.LENGTH_SHORT).show() }

    private fun dot(color: Int): View = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun place(d: ShuttleModel.Data, id: String) = d.places[id]?.name ?: id

    /** One bus or walk, as a line of a journey card. */
    private fun legText(d: ShuttleModel.Data, leg: ShuttleModel.Leg): String = when (leg) {
        is ShuttleModel.Leg.Walk -> "🚶 Walk " + (if (leg.meters == null) "about " else "") + "${leg.end - leg.start} min to ${leg.to.name}" +
            (leg.meters?.let { m -> " ($m m)" } ?: "")
        is ShuttleModel.Leg.Bus -> {
            val r = leg.ride
            val a = place(d, r.route.stops[r.fromIdx])
            val b = place(d, r.route.stops[r.toIdx])
            if (r.route.frequent) "🚌 ${r.route.label} (every ~${r.wait} min): be at $a by ${ShuttleModel.fmt(r.depart)} → $b, ~${ShuttleModel.fmt(r.arrive)}"
            else "🚌 ${r.route.label} ${ShuttleModel.fmt(r.depart)} from $a → $b, ~${ShuttleModel.fmt(r.arrive)}"
        }
    }

    /** Looks like the app's dropdowns; opens [pickPlace]. */
    private fun pickerField(text: String, title: String, onPick: (String) -> Unit): TextView =
        tv("$text  ▾", 14f, Ui.TEXT).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minHeight = dp(Ui.touch(48))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            background = rippleBg(Ui.BAR, Ui.BORDER, this@ShuttleActivity, 10)
            isClickable = true
            contentDescription = "$title: $text. Tap to choose."
            setOnClickListener { pickPlace(title, onPick) }
        }

    /** Which group a place is listed under in the picker. */
    private fun groupOf(p: ShuttleModel.Place): Int {
        val n = p.name.lowercase(Locale.ROOT)
        return when {
            !p.isStop || n.startsWith("faculty") || n.startsWith("academy") || n.startsWith("fakulti") || "pasum" in n -> 0
            " rc" in "$n " || "residential" in n || "kolej kediaman" in n || "international house" in n -> 1
            else -> 2
        }
    }

    /** A searchable list of every place, grouped: faculties and buildings, residential colleges, other stops. */
    private fun pickPlace(title: String, onPick: (String) -> Unit) {
        val d = Shuttle.data(this)
        val all = Shuttle.placesForPicker(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val search = android.widget.EditText(this).apply {
            hint = "Search, e.g. FSKTM, KK12, library"
            setSingleLine()
            textSize = 15f * Ui.fontScale; setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM)
            background = roundBg(Ui.BAR, Ui.BORDER, this@ShuttleActivity, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        col.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)).apply { topMargin = dp(8) })
        val dlg = android.app.AlertDialog.Builder(this, Ui.dialogTheme)
            .setTitle(title)
            .setView(col)
            .setNegativeButton("Cancel", null)
            .create()
        val home = Shuttle.homeId(this)
        val heads = listOf("Faculties and buildings", "Residential colleges", "Bus stops")
        fun fill(q: String) {
            list.removeAllViews()
            val words = q.lowercase(Locale.ROOT).split(" ").filter { it.isNotBlank() }
            // each word matches the start of a word of the name ("api" finds "(API)", not "Rapid"), or the id
            val hits = all.filter { p ->
                val parts = p.name.lowercase(Locale.ROOT).split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }
                val akas = p.aka.flatMap { it.lowercase(Locale.ROOT).split(" ") }
                words.all { w -> parts.any { it.startsWith(w) } || akas.any { it.startsWith(w) } || p.id.lowercase(Locale.ROOT).startsWith(w) }
            }
            // "Raja Dr Nazrin Shah RC · KK12": the short names that are not in the name already
            fun row(p: ShuttleModel.Place, label: String = p.name + p.aka.filter { a -> !p.name.contains(a, true) && a.none { it == ' ' } }
                .takeIf { it.isNotEmpty() }?.joinToString(", ", " · ").orEmpty()) {
                list.addView(tv(label, 15f, Ui.TEXT).apply {
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    background = rippleBg(Ui.BG, 0, this@ShuttleActivity, 8)
                    isClickable = true
                    setOnClickListener { dlg.dismiss(); onPick(p.id) }
                })
            }
            if (words.isEmpty()) d.places[home]?.let { list.label("Your home stop", 12f, Ui.CYAN, 8); row(it) }
            for (g in 0..2) {
                val inG = hits.filter { groupOf(it) == g }
                if (inG.isEmpty()) continue
                list.label(heads[g], 12f, Ui.CYAN, 12).setTypeface(null, Typeface.BOLD)
                inG.forEach { row(it) }
            }
            if (hits.isEmpty()) list.label("Nothing matches \"$q\".", 13f, Ui.DIM, 12)
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) = fill(e?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        fill("")
        dlg.show()
    }

    /** From / To, how to time it, recent trips and the journeys found. Returns the journey to draw on the map. */
    private fun renderPlanner(d: ShuttleModel.Data, today: LocalDate, now: Int): ShuttleModel.Journey? {
        val box = planBox
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) } }
        box.label("Plan a trip", 13f, Ui.TEXT, 8).setTypeface(null, Typeface.BOLD)
        box.label("From", 12f, Ui.DIM, 6)
        box.addView(pickerField(d.places[fromId]?.name ?: "Choose where you start", "From") { id ->
            if (id != fromId) setTrip(id, toId.takeIf { it != id } ?: "")
        }, lp())
        val toRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toRow.addView(pickerField(d.places[toId]?.name ?: "Choose where to go", "To") { id ->
            if (id != toId) setTrip(fromId, id.takeIf { it != fromId } ?: "")
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toRow.addView(chip("⇅", false, size = 16f, padH = 10) {
            if (toId.isNotEmpty()) setTrip(toId, fromId)
        }.apply { contentDescription = "Swap from and to" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        box.label("To", 12f, Ui.DIM, 8)
        box.addView(toRow, lp())

        // your last trips, one tap to plan again
        val recent = Shuttle.recentTrips(this).filter { (a, b) -> a in d.places && b in d.places && !(a == fromId && b == toId) }
        if (recent.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((a, b) in recent) {
                fun short(id: String) = place(d, id).substringBefore(" (").let { if (it.length > 16) it.take(15) + "…" else it }
                row.addView(chip("${short(a)} → ${short(b)}", false, size = 12f, padH = 10) { setTrip(a, b) }.apply {
                    contentDescription = "Plan again: ${place(d, a)} to ${place(d, b)}"
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) })
            }
            box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp())
        }

        box.chips(listOf("Leave now", "Leave at", "Arrive by"), setOf(mode), false) { mode = it.first(); chosenJ = 0; render() }
        if (mode != 0) box.addView(timeField(atMin) { atMin = it; chosenJ = 0; render() }.view, lp())

        val from = d.places[fromId]
        val to = d.places[toId]
        if (from == null || to == null) {
            box.label("Pick where you want to go, or tap a dot on the map.", 12f, Ui.DIM, 8)
            return null
        }
        val time = if (mode == 0) now else atMin
        val list = ShuttleModel.journeys(d, from, to, today, time, mode == 2, max = 3)
        if (list.isEmpty()) {
            box.label(if (mode == 2) "No bus gets there by ${ShuttleModel.fmt(time)} today." else "No more buses for this trip today.", 13f, Ui.ORANGE, 8)
            return null
        }
        if (chosenJ >= list.size) chosenJ = 0
        list.forEachIndexed { i, j ->
            val on = i == chosenJ
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rippleBg(if (on) Ui.tint(Ui.CYAN, 0x22) else Ui.BAR, if (on) Ui.CYAN else Ui.BORDER, this@ShuttleActivity, 12)
                isClickable = true
                setOnClickListener { chosenJ = i; render() }
            }
            val mins = j.arrive - j.depart
            val kind = when (j.buses.size) { 0 -> "walk"; 1 -> "direct"; else -> "${j.buses.size - 1} change" }
            val head = "${ShuttleModel.fmt(j.depart)} → ${ShuttleModel.fmt(j.arrive)}  ·  $mins min  ·  $kind"
            card.addView(tv(head, 15f, Ui.TEXT, true))
            // the chosen journey step by step; the others in one line ("BA 14:09 → Rapid T789")
            if (on) for (leg in j.legs) card.addView(tv(legText(d, leg), 13f, Ui.DIM).apply { setPadding(0, dp(4), 0, 0) })
            else card.addView(tv(if (j.walkOnly) "🚶 Walk all the way" else j.buses.joinToString(" → ") { b ->
                "🚌 " + b.ride.route.label.removePrefix("Route ") + if (b.ride.route.frequent) "" else " " + ShuttleModel.fmt(b.ride.depart)
            } + " · tap for the steps", 13f, Ui.DIM).apply { setPadding(0, dp(4), 0, 0) })
            if (on && j.walkOnly) card.addView(chip("Walking route in Maps", false, size = 13f, padH = 12) { open(ShuttleModel.mapsWalkUrl(from, to)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            card.contentDescription = head + ". " + j.legs.joinToString(". ") { legText(d, it).drop(2) } + if (on) ". Shown on the map." else ". Tap to show it on the map."
            box.addView(card, lp())
        }
        if (list.any { j -> j.buses.any { it.ride.route.frequent } }) box.label("Rapid KL times count the longest wait.", 11f, Ui.DIM, 4)
        return if (chosenJ >= 0) list[chosenJ] else null
    }

    private fun render() {
        val d = Shuttle.data(this)
        val today = LocalDate.now(zone())
        val now = LocalTime.now(zone()).let { it.hour * 60 + it.minute }
        planBox.removeAllViews()
        restBox.removeAllViews()
        val shownJourney = renderPlanner(d, today, now)
        map.show(d, d.routes.getOrNull(routeIdx.coerceIn(0, (d.routes.size - 1).coerceAtLeast(0))), shownJourney,
            d.places[fromId], d.places[toId])
        val col = restBox

        // ---- does it run today?
        val runsToday = d.routes.any { ShuttleModel.runs(it, today).isNotEmpty() }
        val status = when {
            !runsToday -> "No buses from here today."
            d.routes.none { !it.frequent && ShuttleModel.runs(it, today).isNotEmpty() } -> "No UM shuttle today (it runs Monday to Friday). Rapid KL buses still run."
            !AcademicCalendar.inTeaching(this, today) -> "Outside teaching weeks: the shuttle may not run (breaks and study week)."
            today.dayOfWeek == DayOfWeek.FRIDAY -> "Friday: no buses 12:30 pm – 2:30 pm (Friday prayer)."
            else -> ""
        }
        if (status.isNotEmpty()) col.label(status, 13f, Ui.ORANGE, 6).setTypeface(null, Typeface.BOLD)

        // ---- next buses from a stop
        col.label("Next buses from", 13f, Ui.TEXT, 16).setTypeface(null, Typeface.BOLD)
        val stops = d.places.values.filter { it.isStop }.sortedBy { it.name.lowercase(Locale.ROOT) }
        // a building has no buses of its own: its stop's
        val busFrom = d.places[fromId]?.let { ShuttleModel.stopOf(d, it) }?.takeIf { it.isNotEmpty() } ?: "UMC"
        val sel = stops.indexOfFirst { it.id == busFrom }.coerceAtLeast(0)
        col.addView(spinner(stops.map { it.name }, sel) { i ->
            if (stops[i].id != busFrom) { fromId = stops[i].id; render() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val all = ShuttleModel.departures(d, busFrom, today, now)
        // a Rapid route shows once ("every 15 min"); UM shuttle trips by time
        val next = (all.filter { !it.run.route.frequent }.take(5) +
            all.filter { it.run.route.frequent }.distinctBy { it.run.route.id }).sortedBy { it.time }
        if (next.isEmpty()) {
            // the first bus on the next day the shuttle runs
            val later = (1..7L).asSequence().map { today.plusDays(it) }
                .mapNotNull { day -> ShuttleModel.departures(d, busFrom, day).firstOrNull()?.let { Pair(day, it) } }
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
                setOnClickListener { routeIdx = d.routes.indexOf(r); chosenJ = -1; render() }
            }
            row.addView(tv(r.id, 14f, onAccent(), true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(r.color) }
            }, LinearLayout.LayoutParams(dp(44), dp(32)))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            val mins = dep.time - now
            val head = if (r.frequent) "Rapid · every ~${dep.run.wait} min (until ${ShuttleModel.fmt(r.bandsOn(today).maxOf { it.to })})"
                else "${ShuttleModel.fmt(dep.time)}  ·  " + (if (mins <= 0) "now" else "in $mins min")
            texts.addView(tv(head, 15f, Ui.TEXT, true))
            texts.addView(tv("→ " + r.stops.drop(dep.idx + 1).joinToString(" → ") { Shuttle.stopName(this, it) }, 12f, Ui.DIM).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.contentDescription = if (r.frequent) "${r.label}, every ${dep.run.wait} minutes. Tap for the route."
                else "${r.label} at ${ShuttleModel.fmt(dep.time)}, ${if (mins <= 0) "now" else "in $mins minutes"}. Tap for the route."
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        if (Shuttle.homeId(this).isEmpty()) {
            col.label("Tip: set your home stop in Settings → UM shuttle, and Home shows which bus to take to each class.", 12f, Ui.DIM, 8)
        }

        // ---- one route: stops in order with the next trip's time, then today's trips
        col.label("Routes", 13f, Ui.TEXT, 22).setTypeface(null, Typeface.BOLD)
        routeIdx = routeIdx.coerceIn(0, (d.routes.size - 1).coerceAtLeast(0))
        if (d.routes.isEmpty()) return
        col.chips(d.routes.map { it.id }, setOf(routeIdx), false) { routeIdx = it.first(); chosenJ = -1; render() }
        if (d.routes.any { it.frequent }) col.label("AB … 13: UM shuttle · " + d.routes.filter { it.frequent }.joinToString(", ") { it.id } + ": Rapid KL", 11f, Ui.DIM, 4)
        val r = d.routes[routeIdx]
        col.label("${r.label} · ${r.name}", 15f, Ui.TEXT, 12).setTypeface(null, Typeface.BOLD)
        val runs = ShuttleModel.runs(r, today)
        // the trip to show along the stops: the one running now, else the next, else the first of the day
        // (a Rapid route has no fixed trips: its stops show minutes from the start instead)
        val shown = if (r.frequent) null else runs.firstOrNull { now <= it.end } ?: runs.firstOrNull()
        col.label(
            when {
                r.frequent -> "Rapid KL bus, no fixed times. Stops, with minutes from ${Shuttle.stopName(this, r.stops[0])}:"
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

        if (r.frequent) {
            col.label("How often", 13f, Ui.TEXT, 16).setTypeface(null, Typeface.BOLD)
            val week = r.bands.filter { 1 in it.days }
            val weekend = r.bands.filter { 6 in it.days || 7 in it.days }
            fun bandText(bs: List<ShuttleModel.Band>) = bs.joinToString("\n") { "every ${it.every} min, ${ShuttleModel.fmt(it.from)}–${ShuttleModel.fmt(it.to)}" }
            if (week.isNotEmpty()) col.label("Weekdays\n" + bandText(week), 13f, Ui.TEXT, 4)
            if (weekend.isNotEmpty()) col.label("Weekends\n" + bandText(weekend), 13f, Ui.TEXT, 8)
            col.label("Runs in semester breaks too. Live arrivals: MyRapid PULSE app.", 12f, Ui.DIM, 6)
            col.addView(chip("Open MyRapid PULSE", false, size = 13f, padH = 12) {
                open("https://play.google.com/store/search?q=MyRapid%20PULSE&c=apps")
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        } else if (runs.isNotEmpty()) {
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
