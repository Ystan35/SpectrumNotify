package my.spectrum.notify

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.time.LocalDate

/**
 * A cache for results built from the saved Spectrum data and your timetable settings (Timetable.all,
 * Courses.all). Screens ask for them once per card; each ask re-read and re-parsed state.json and rebuilt
 * the timetable, freezing Settings long enough for "isn't responding".
 *
 * Every entry is tied to a stamp: state.json's size and time, a counter bumped by any change to the
 * timetable / settings / semester preferences, and today's date. Any change makes a new stamp, so a
 * cached result is never older than the data it was built from.
 */
object DataStamp {
    @Volatile private var version = 0L
    private val entries = HashMap<String, Pair<String, Any>>()
    private var watching = false

    /** Kept in a field: SharedPreferences only holds listeners weakly. */
    private val bump = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }

    @Synchronized
    private fun watch(ctx: Context) {
        if (watching) return
        watching = true
        val app = ctx.applicationContext
        for (name in listOf("timetable", "settings", "acadcal"))
            app.getSharedPreferences(name, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(bump)
    }

    private fun stamp(ctx: Context): String {
        watch(ctx)
        val f = File(ctx.applicationContext.filesDir, "state.json")
        return "${f.length()}:${f.lastModified()}:$version:${LocalDate.now(zone())}"
    }

    /**
     * The cached value for [key], or [build] it. The stamp is taken before building, so data saved while
     * building (a background check) never gets cached under the newer stamp.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, ctx: Context, build: () -> T): T {
        val st = stamp(ctx)
        synchronized(this) { entries[key]?.let { if (it.first == st) return it.second as T } }
        val v = build()
        synchronized(this) { entries[key] = Pair(st, v) }
        return v
    }
}
