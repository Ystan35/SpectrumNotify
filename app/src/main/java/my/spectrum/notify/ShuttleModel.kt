package my.spectrum.notify

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The UM shuttle timetable (assets/bus.json) and trip planning, with no Android types, so it can be run and
 * checked on its own. Times are minutes after midnight; days are 1 = Monday … 7 = Sunday.
 *
 * Every route is a run of stops with minutes from the first stop. A trip normally starts at UM Central; before
 * [Route.farUntil] some routes start at their far end ([Route.farStop]) and run the rest of the loop into UM Central.
 */
object ShuttleModel {

    /** A bus stop, or a building that is not one (then [near] names the stop to use, "" = not known yet). */
    class Place(
        val id: String, val name: String, val isStop: Boolean, val near: String,
        /** What Google Maps should search for when there are no coordinates. */
        val query: String,
        val lat: Double?, val lng: Double?,
    )

    class Schedule(val days: Set<Int>, val starts: List<Int>, val fridayBreak: IntRange?)

    class Route(
        val id: String, val name: String, val color: Int,
        val stops: List<String>, val offsets: List<Int>, val loop: Boolean,
        val farStop: String, val farUntil: Int,
        /** The last stops' minutes are guessed (the source did not show them). */
        val tailEstimated: Boolean,
        val schedule: Schedule,
    )

    class Data(
        val generatedAt: String, val source: String, val officialUrl: String, val phone: String, val email: String,
        val notes: List<String>, val places: Map<String, Place>, val routes: List<Route>,
        /** Course-code prefix → place id ("WI" → FSKTM); the longest matching prefix wins. */
        val faculties: Map<String, String>,
    )

    /** One trip of a route: it leaves stops[[first]] at [start]. */
    class Run(val route: Route, val first: Int, val start: Int) {
        fun at(i: Int): Int = start + route.offsets[i] - route.offsets[first]
        val end: Int get() = at(route.stops.size - 1)
    }

    /** Ride [route] from stop index [from] (leaving [depart]) to stop index [to] (arriving [arrive]). */
    class Ride(val route: Route, val fromIdx: Int, val toIdx: Int, val depart: Int, val arrive: Int)

    sealed class Plan {
        /** Origin and destination are the same place: nothing to do. */
        object Here : Plan()
        class Bus(val ride: Ride, val from: Place, val to: Place) : Plan()
        /** [minutes]/[meters] are null when a place has no coordinates; [noBus] = no direct bus gets you there in time. */
        class Walk(val from: Place, val to: Place, val meters: Int?, val minutes: Int?, val noBus: Boolean) : Plan()
    }

    const val WALK_M_PER_MIN = 80.0
    const val WALK_DETOUR = 1.3
    /** Within this many metres walking always wins. */
    const val WALK_ALWAYS_M = 500
    /** Be at the destination this many minutes before class. */
    const val ARRIVE_EARLY = 5
    /** On a loop route you may stay on the bus into the next trip if it leaves UM Central within this many minutes. */
    private const val STAY_ON_MAX_WAIT = 15

    // ------------------------------------------------------------------ parsing

    fun hm(s: String): Int {
        val p = s.trim().split(":")
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun fmt(min: Int): String = String.format("%d:%02d", (min / 60) % 24, min % 60)

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    private fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it) }

    private fun parseSchedule(o: JSONObject?, base: Schedule?): Schedule {
        if (o == null) return base ?: Schedule(emptySet(), emptyList(), null)
        val starts = ArrayList<Int>()
        val every = o.optInt("every", 0)
        if (every > 0 && o.has("from") && o.has("to")) {
            var t = hm(o.optString("from"))
            val to = hm(o.optString("to"))
            while (t <= to) { starts.add(t); t += every }
        }
        starts.addAll(strs(o.optJSONArray("then")).map { hm(it) })
        starts.addAll(strs(o.optJSONArray("times")).map { hm(it) })
        val fb = strs(o.optJSONArray("fridayBreak"))
        return Schedule(
            ints(o.optJSONArray("days")).toSet().ifEmpty { base?.days ?: emptySet() },
            starts.distinct().sorted().ifEmpty { base?.starts ?: emptyList() },
            if (fb.size == 2) hm(fb[0]) until hm(fb[1]) else base?.fridayBreak,
        )
    }

    private fun color(s: String): Int = try {
        (java.lang.Long.parseLong(s.removePrefix("#"), 16) or 0xFF000000L).toInt()
    } catch (_: Exception) { 0xFF4A90D9.toInt() }

    fun parse(text: String): Data {
        val root = JSONObject(text)
        val places = LinkedHashMap<String, Place>()
        val pa = root.optJSONArray("places") ?: JSONArray()
        for (i in 0 until pa.length()) {
            val o = pa.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            val name = o.optString("name", id)
            places[id] = Place(
                id, name, o.optBoolean("stop", false), o.optString("near", ""),
                o.optString("q", "").ifEmpty { "$name, Universiti Malaya, Kuala Lumpur" },
                if (o.has("lat")) o.optDouble("lat") else null,
                if (o.has("lng")) o.optDouble("lng") else null,
            )
        }
        val base = parseSchedule(root.optJSONObject("schedule"), null)
        val routes = ArrayList<Route>()
        val ra = root.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until ra.length()) {
            val o = ra.optJSONObject(i) ?: continue
            val stops = strs(o.optJSONArray("stops"))
            val offsets = ints(o.optJSONArray("offsets"))
            if (stops.size < 2 || stops.size != offsets.size) continue
            routes.add(Route(
                o.optString("id"), o.optString("name"), color(o.optString("color")),
                stops, offsets, o.optBoolean("loop", false),
                o.optString("farStop", ""), if (o.has("farUntil")) hm(o.optString("farUntil")) else -1,
                o.optBoolean("tailEstimated", false),
                parseSchedule(o.optJSONObject("schedule"), base),
            ))
        }
        val fac = LinkedHashMap<String, String>()
        root.optJSONObject("faculties")?.let { f -> for (k in f.keys()) fac[k.uppercase()] = f.optString(k) }
        return Data(
            root.optString("generatedAt"), root.optString("source"), root.optString("officialUrl"),
            root.optString("phone"), root.optString("email"), strs(root.optJSONArray("notes")),
            places, routes, fac,
        )
    }

    // ------------------------------------------------------------------ timetable

    /** Every trip of [r] on [date] (none on days it does not run; Friday-prayer trips dropped). */
    fun runs(r: Route, date: LocalDate): List<Run> {
        val dow = date.dayOfWeek.value
        if (dow !in r.schedule.days) return emptyList()
        val farIdx = if (r.farStop.isNotEmpty()) r.stops.indexOf(r.farStop) else -1
        return r.schedule.starts
            .filter { t -> !(date.dayOfWeek == DayOfWeek.FRIDAY && r.schedule.fridayBreak?.contains(t) == true) }
            .map { t -> Run(r, if (farIdx > 0 && t <= r.farUntil) farIdx else 0, t) }
    }

    /** One bus leaving a stop: route, the trip, the stop's index in the route, the time. */
    class Departure(val run: Run, val idx: Int, val time: Int)

    /**
     * Buses leaving [stopId] on [date] at or after [afterMin], soonest first. The last stop of a loop is where the
     * trip ends, so it is not a departure (the next trip's first stop is).
     */
    fun departures(d: Data, stopId: String, date: LocalDate, afterMin: Int = 0): List<Departure> {
        val out = ArrayList<Departure>()
        for (r in d.routes) for (run in runs(r, date)) {
            for (i in run.first until r.stops.size - 1) {
                if (r.stops[i] == stopId && run.at(i) >= afterMin) out.add(Departure(run, i, run.at(i)))
            }
        }
        return out.sortedWith(compareBy<Departure>({ it.time }, { it.run.route.id }))
    }

    /**
     * The latest direct ride from [from] to [to] that arrives by [arriveBy] − [ARRIVE_EARLY], or null.
     * On a loop route you may stay on into the next trip (e.g. Faculty of Engineering → UM Central → API on AB)
     * when that trip leaves UM Central within [STAY_ON_MAX_WAIT] minutes.
     */
    fun ride(d: Data, from: String, to: String, date: LocalDate, arriveBy: Int): Ride? {
        if (from == to) return null
        val latest = arriveBy - ARRIVE_EARLY
        var best: Ride? = null
        fun consider(c: Ride) {
            if (c.arrive > latest || c.arrive < c.depart) return
            val b = best
            if (b == null || c.depart > b.depart || (c.depart == b.depart && c.arrive < b.arrive)) best = c
        }
        for (r in d.routes) {
            val rs = runs(r, date)
            for ((k, run) in rs.withIndex()) {
                for (i in run.first until r.stops.size - 1) {
                    if (r.stops[i] != from) continue
                    var found = false
                    for (j in i + 1 until r.stops.size) if (r.stops[j] == to) {
                        consider(Ride(r, i, j, run.at(i), run.at(j))); found = true; break
                    }
                    // stay on through UM Central into the next trip of the same loop
                    val next = rs.getOrNull(k + 1)
                    if (!found && r.loop && next != null && next.first == 0 && next.start - run.end in 0..STAY_ON_MAX_WAIT) {
                        for (j in 1 until r.stops.size) if (r.stops[j] == to) { consider(Ride(r, i, j, run.at(i), next.at(j))); break }
                    }
                }
            }
        }
        return best
    }

    // ------------------------------------------------------------------ walking

    /** Straight-line distance in metres, or null without coordinates. */
    fun meters(a: Place, b: Place): Int? {
        val la1 = a.lat ?: return null; val lo1 = a.lng ?: return null
        val la2 = b.lat ?: return null; val lo2 = b.lng ?: return null
        val r = 6371000.0
        val dLa = Math.toRadians(la2 - la1)
        val dLo = Math.toRadians(lo2 - lo1)
        val h = sin(dLa / 2) * sin(dLa / 2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2) * sin(dLo / 2)
        return (2 * r * asin(sqrt(h))).roundToInt()
    }

    /** Walking estimate: straight line × [WALK_DETOUR] at [WALK_M_PER_MIN]. Returns (metres, minutes) or null. */
    fun walkEstimate(a: Place, b: Place): Pair<Int, Int>? {
        val m = meters(a, b) ?: return null
        val walked = (m * WALK_DETOUR).roundToInt()
        return Pair(walked, maxOf(1, Math.ceil(walked / WALK_M_PER_MIN).toInt()))
    }

    /** The stop a place is served by: itself, or the stop set as nearest ("" = none known). */
    fun stopOf(d: Data, p: Place): String = if (p.isStop) p.id else p.near.takeIf { it in d.places } ?: ""

    /**
     * How to get from [from] to [to] to arrive by [arriveBy] on [date]: a bus, or a walk when the places are close,
     * share a stop, no direct bus is in time, or walking lets you leave later than the bus.
     */
    fun plan(d: Data, from: Place, to: Place, date: LocalDate, arriveBy: Int): Plan {
        if (from.id == to.id) return Plan.Here
        val walk = walkEstimate(from, to)
        if (walk != null && walk.first <= WALK_ALWAYS_M) return Plan.Walk(from, to, walk.first, walk.second, false)
        val a = stopOf(d, from)
        val b = stopOf(d, to)
        if (a.isNotEmpty() && a == b) return Plan.Walk(from, to, walk?.first, walk?.second, false)
        val r = (if (a.isNotEmpty() && b.isNotEmpty()) ride(d, a, b, date, arriveBy) else null)
            ?: return Plan.Walk(from, to, walk?.first, walk?.second, true)
        // walking wins when you could leave no earlier than you would have to for the bus
        if (walk != null && arriveBy - ARRIVE_EARLY - walk.second >= r.depart) return Plan.Walk(from, to, walk.first, walk.second, false)
        return Plan.Bus(r, from, to)
    }

    /** When to set off, in minutes after midnight (null when a walk's length is not known). */
    fun leaveAt(p: Plan, arriveBy: Int): Int? = when (p) {
        is Plan.Bus -> p.ride.depart
        is Plan.Walk -> p.minutes?.let { arriveBy - ARRIVE_EARLY - it }
        Plan.Here -> null
    }

    /** The place a course's classes are at, guessed from its code ("WIX1003" → FSKTM), or null. */
    fun guessPlace(d: Data, courseCode: String): Place? {
        val c = courseCode.uppercase()
        val key = d.faculties.keys.filter { c.startsWith(it) }.maxByOrNull { it.length } ?: return null
        return d.places[d.faculties[key]]
    }

    /** Google Maps walking directions: by coordinates when both places have them, else by name. */
    fun mapsWalkUrl(from: Place, to: Place): String {
        fun pt(p: Place) = if (p.lat != null && p.lng != null) "${p.lat},${p.lng}" else java.net.URLEncoder.encode(p.query, "UTF-8")
        return "https://www.google.com/maps/dir/?api=1&origin=${pt(from)}&destination=${pt(to)}&travelmode=walking"
    }
}
