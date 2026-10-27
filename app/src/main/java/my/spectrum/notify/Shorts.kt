package my.spectrum.notify

import android.content.Context

/**
 * User-defined short forms for course codes, e.g. "WIA2010" -> "HCI".
 * Shown next to the raw code on every screen, so users who think in course
 * names ("computing math") are never forced to memorise faculty codes.
 */
object Shorts {
    private const val PREF = "shorts"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun get(ctx: Context, code: String): String =
        sp(ctx).getString(code.uppercase(), "") ?: ""

    fun set(ctx: Context, code: String, short: String) {
        val key = code.trim().uppercase()
        val v = short.trim()
        val sp = sp(ctx)
        if (v.isEmpty()) sp.edit().remove(key).apply() else sp.edit().putString(key, v).apply()
    }

    /** "WIA2010 · HCI" when a short form exists, else the plain code. */
    fun display(ctx: Context, code: String): String {
        val s = get(ctx, code)
        return if (s.isEmpty()) code else "$code · $s"
    }
}
