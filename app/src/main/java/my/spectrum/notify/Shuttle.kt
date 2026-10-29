package my.spectrum.notify

import android.content.Context
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

/**
 * The UM shuttle in the app: loads assets/bus.json (or a newer copy downloaded from the repo, like Catalog), keeps
 * your home stop and leave-by setting, and works out how you get to a class. The timetable maths is in
 * [ShuttleModel], which has no Android parts.
 */
object Shuttle {
    /** Where a refreshed bus.json is downloaded from: editing the file in the repo updates every installed app. */
    private const val REMOTE_URL = "https://raw.githubusercontent.com/Ystan35/SpectrumNotify/main/app/src/main/assets/bus.json"

    private lateinit var sp: SharedPreferences
    @Volatile private var cache: ShuttleModel.Data? = null

    private fun prefs(ctx: Context): SharedPreferences {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("shuttle", Context.MODE_PRIVATE)
        return sp
    }

    fun data(ctx: Context): ShuttleModel.Data {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val app = ctx.applicationContext
            val bundled = ShuttleModel.parse(app.assets.open("bus.json").bufferedReader().use { it.readText() })
            // the downloaded copy wins when it is not older; a broken one counts as none
            val stored = prefs(app).getString("json", null)?.let { s ->
                try { ShuttleModel.parse(s).takeIf { it.routes.isNotEmpty() && it.generatedAt >= bundled.generatedAt } }
                catch (_: Exception) { null }
            }
            return (stored ?: bundled).also { cache = it }
        }
    }

    /** Downloads the current bus.json and keeps it when newer. Called from the background check; failures are quiet. */
    fun refresh(ctx: Context) {
        try {
            val conn = URL(REMOTE_URL).openConnection() as HttpURLConnection
            val text: String = try {
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                if (conn.responseCode !in 200..299) return
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val now = data(ctx)
            val fresh = ShuttleModel.parse(text)
            if (fresh.routes.isEmpty() || fresh.generatedAt <= now.generatedAt) return
            prefs(ctx).edit().putString("json", text).apply()
            synchronized(this) { cache = fresh }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ your settings

    /** Where your day starts (usually your residential college's stop); "" = not set. */
    fun homeId(ctx: Context): String = prefs(ctx).getString("home", "") ?: ""
    fun setHomeId(ctx: Context, id: String) = prefs(ctx).edit().putString("home", id).apply()

    /** Minutes before you must set off that the leave-by alert comes; 0 = off. */
    fun leadMin(ctx: Context): Int = prefs(ctx).getInt("lead", 10)
    fun setLeadMin(ctx: Context, m: Int) = prefs(ctx).edit().putInt("lead", m).apply()

    /** Your last trips planned on the Shuttle screen, newest first, as (from id, to id). */
    fun recentTrips(ctx: Context): List<Pair<String, String>> =
        (prefs(ctx).getString("recent", "") ?: "").split(";").mapNotNull { t ->
            t.split(">").takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].isNotEmpty() }?.let { Pair(it[0], it[1]) }
        }

    fun addRecentTrip(ctx: Context, from: String, to: String) {
        val list = (listOf(Pair(from, to)) + recentTrips(ctx).filter { it != Pair(from, to) }).take(3)
        prefs(ctx).edit().putString("recent", list.joinToString(";") { "${it.first}>${it.second}" }).apply()
    }

    /** The place a course's classes are at: your choice in the class editor, else the guess from its code. */
    fun placeFor(ctx: Context, course: String): ShuttleModel.Place? {
        val d = data(ctx)
        Timetable.busStop(ctx, course).takeIf { it.isNotEmpty() }?.let { id -> d.places[id]?.let { return it } }
        return ShuttleModel.guessPlace(d, course)
    }

    /** Places sorted for pickers: stops on campus first, then the rest, by name. */
    fun placesForPicker(ctx: Context): List<ShuttleModel.Place> =
        data(ctx).places.values.sortedBy { it.name.lowercase(Locale.ROOT) }

    // ------------------------------------------------------------------ your trip to a class

    /** How to get to one class: where from, where to, and by bus or on foot. */
    class TripInfo(val plan: ShuttleModel.Plan, val from: ShuttleModel.Place, val to: ShuttleModel.Place,
                   val date: LocalDate, val arriveBy: Int) {
        /** When to set off (minutes after midnight), or null when not known. */
        val leaveAt: Int? get() = ShuttleModel.leaveAt(plan, arriveBy)
    }

    /**
     * Your trip to [course]'s class starting at [startMin] on [date]: from the previous class that day (when it
     * ends at most 3 hours before), else from your home stop. Null when there is nothing to plan: an online class,
     * no teaching that day, the place of either end is not known, or no home stop is set for the first class.
     */
    fun tripFor(ctx: Context, course: String, date: LocalDate, startMin: Int): TripInfo? {
        if (Timetable.locationOf(ctx, course).startsWith("online:")) return null
        if (!AcademicCalendar.inTeaching(ctx, date)) return null
        val d = data(ctx)
        val to = placeFor(ctx, course) ?: return null
        val sem = AcademicCalendar.labelFor(ctx, date)
        val prev = Timetable.on(Timetable.all(ctx, sem), date)
            .filter { it.end <= startMin && startMin - it.end <= 180 && !it.course.equals(course, true) }
            .maxByOrNull { it.end }
        val from = prev?.let { placeFor(ctx, it.course) }
            ?: d.places[homeId(ctx)]
            ?: return null
        return TripInfo(ShuttleModel.plan(d, from, to, date, startMin), from, to, date, startMin)
    }

    /** One line for Home, e.g. "🚌 AB 9:31 Za'ba RC → Faculty of Engineering, ~9:44", or null. */
    fun line(ctx: Context, t: TripInfo): String? = when (val p = t.plan) {
        is ShuttleModel.Plan.Bus -> {
            val r = p.ride
            val from = stopName(ctx, r.route.stops[r.fromIdx])
            val to = stopName(ctx, r.route.stops[r.toIdx])
            // a Rapid bus has no fixed time: say how often it comes and when to be at the stop (longest wait counted)
            if (r.route.frequent) "🚌 ${r.route.label} (every ~${r.wait} min) from $from → $to · be at the stop by ${ShuttleModel.fmt(r.depart)}" +
                (if (p.to.id != r.route.stops[r.toIdx]) ", then walk to ${p.to.name}" else "")
            else "🚌 ${r.route.label} ${ShuttleModel.fmt(r.depart)} from $from → $to, ~${ShuttleModel.fmt(r.arrive)}" +
                (if (p.to.id != r.route.stops[r.toIdx]) ", then walk to ${p.to.name}" else "")
        }
        is ShuttleModel.Plan.Walk ->
            "🚶 Walk to ${p.to.name}" +
                (p.minutes?.let { " (~$it min" + (p.meters?.let { m -> ", $m m" } ?: "") + "), leave by ${ShuttleModel.fmt(t.arriveBy - ShuttleModel.ARRIVE_EARLY - it)}" } ?: "") +
                (if (p.noBus) " · no direct bus in time" else "")
        ShuttleModel.Plan.Here -> null
    }

    fun stopName(ctx: Context, id: String): String = data(ctx).places[id]?.name ?: id

    /** Where tapping the line goes: walking directions in Maps for a walk, else null (open the Shuttle screen). */
    fun walkUrl(t: TripInfo): String? = (t.plan as? ShuttleModel.Plan.Walk)?.let { ShuttleModel.mapsWalkUrl(it.from, it.to) }
}
