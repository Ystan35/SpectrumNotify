package my.spectrum.notify

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Rain forecast for UM, for the umbrella reminder (morning summary, its own notification, leave-by alert).
 * From Open-Meteo (free, no account; data CC BY 4.0, credited wherever it is shown). Fetched by the background
 * check at most every 2 hours and kept, so alarms work offline; older than 12 hours counts as unknown.
 */
object Weather {
    private const val URL_S = "https://api.open-meteo.com/v1/forecast?latitude=3.1209&longitude=101.6538" +
        "&hourly=precipitation_probability&timezone=Asia%2FKuala_Lumpur&forecast_days=2"
    /** Rain counts from this chance (%) on. */
    const val RAIN_PCT = 60
    /** Credit (required by the data licence) and disclaimer, under every rain message. */
    const val CREDIT = "Forecast by Open-Meteo.com (CC BY 4.0). A forecast, not a promise: it can be wrong."
    private const val REFRESH_MS = 2 * 3600_000L
    private const val STALE_MS = 12 * 3600_000L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)

    /** Downloads the forecast when the kept one is over 2 hours old. Quiet on failure (the kept one stays). */
    fun refresh(ctx: Context) {
        val sp = sp(ctx)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("at", 0L) < REFRESH_MS) return
        try {
            val conn = URL(URL_S).openConnection() as HttpURLConnection
            val text = try {
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                if (conn.responseCode !in 200..299) return
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            if (JSONObject(text).optJSONObject("hourly")?.optJSONArray("time") == null) return
            sp.edit().putString("json", text).putLong("at", now).apply()
        } catch (_: Exception) {
        }
    }

    /** Chance of rain (%) per hour of [date] (hour 0–23 -> %); empty when unknown or too old. */
    private fun hourly(ctx: Context, date: LocalDate): Map<Int, Int> {
        val sp = sp(ctx)
        if (System.currentTimeMillis() - sp.getLong("at", 0L) > STALE_MS) return emptyMap()
        return try {
            val h = JSONObject(sp.getString("json", "{}") ?: "{}").optJSONObject("hourly") ?: return emptyMap()
            val times = h.optJSONArray("time") ?: return emptyMap()
            val pct = h.optJSONArray("precipitation_probability") ?: return emptyMap()
            val out = HashMap<Int, Int>()
            for (i in 0 until times.length()) {
                if (pct.isNull(i)) continue
                val t = LocalDateTime.parse(times.optString(i))   // "2026-10-30T14:00", already Malaysian time
                if (t.toLocalDate() == date) out[t.hour] = pct.optInt(i)
            }
            out
        } catch (_: Exception) { emptyMap() }
    }

    /** Is there a forecast to go by for [date]? */
    fun known(ctx: Context, date: LocalDate): Boolean = hourly(ctx, date).isNotEmpty()

    /** A rainy stretch: [fromHour] to [toHour] (exclusive), highest chance [pct]. */
    class Rain(val fromHour: Int, val toHour: Int, val pct: Int) {
        /** "Rain likely 2 – 5 pm (up to 70%)" */
        fun line(): String = "Rain likely ${span()} (up to $pct%)"

        private fun span(): String {
            fun h12(h: Int) = (h % 12).let { if (it == 0) 12 else it }
            fun ap(h: Int) = if (h % 24 < 12) "am" else "pm"
            val a = fromHour
            val b = toHour
            return if (ap(a) == ap(b)) "${h12(a)} – ${h12(b)} ${ap(b)}" else "${h12(a)} ${ap(a)} – ${h12(b)} ${ap(b)}"
        }
    }

    /** The hours from [fromMin] to [toMin] (minutes of [date]) with a rain chance of [RAIN_PCT]% or more, or null. */
    fun rainBetween(ctx: Context, date: LocalDate, fromMin: Int, toMin: Int): Rain? {
        val h = hourly(ctx, date)
        if (h.isEmpty() || toMin <= fromMin) return null
        val hours = (fromMin.coerceAtLeast(0) / 60..(toMin.coerceAtMost(24 * 60) - 1) / 60).filter { (h[it] ?: 0) >= RAIN_PCT }
        if (hours.isEmpty()) return null
        return Rain(hours.first(), hours.last() + 1, hours.maxOf { h[it] ?: 0 })
    }
}
