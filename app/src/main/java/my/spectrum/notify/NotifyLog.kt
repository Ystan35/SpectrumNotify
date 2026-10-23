package my.spectrum.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** History shown on the main screen: every notification sent (with its link buttons), plus short system lines. */
object NotifyLog {
    private const val MAX = 200

    data class Entry(
        val ts: Long,
        val kind: String,
        val title: String,
        val body: String,
        val buttons: List<Pair<String, String>> = emptyList(),
        /** Wall-clock seconds for display/unread; ts is only a unique id. Old records have no "at" and fall back to ts. */
        val at: Long = ts,
        /** How many times this repeated sys line has fired (1 = once). */
        val n: Int = 1,
    )

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "console.json")

    private fun readArr(ctx: Context): JSONArray =
        try { JSONArray(file(ctx).readText()) } catch (e: Exception) { JSONArray() }

    /** Changes whenever the history file changes; lets the screen skip re-reading it. */
    fun stamp(ctx: Context): Long = file(ctx).let { it.lastModified() * 31 + it.length() }

    @Synchronized
    fun add(
        ctx: Context,
        kind: String,
        title: String,
        body: String = "",
        buttons: List<Pair<String, String>> = emptyList(),
    ): Long {
        val old = readArr(ctx)
        val last = old.optJSONObject(old.length() - 1)
        // sys lines repeat every poll (Check OK, or the same failure while the host is down):
        // collapse into the previous one, keeping the latest text and a repeat count.
        val collapse = kind == "sys" && last != null && last.optString("kind") == "sys" &&
            (title == last.optString("title") || (title == "Check OK" && last.optString("title") == "Check OK") ||
                (title.startsWith("Check failed") && last.optString("title").startsWith("Check failed")))
        val end = if (collapse) old.length() - 1 else old.length()
        val out = JSONArray()
        for (i in maxOf(0, end - (MAX - 1)) until end) out.put(old.opt(i))
        // NanoTime, not wall-clock seconds: two notifications in the same poll second
        // would otherwise share an id (tap-to-open picks the wrong record, swipe deletes two).
        val ts = System.nanoTime()
        val bs = JSONArray()
        for ((l, u) in buttons.take(3)) if (u.isNotEmpty()) bs.put(JSONObject().put("l", l).put("u", u))
        val o = JSONObject().put("ts", ts).put("at", System.currentTimeMillis() / 1000)
            .put("kind", kind).put("title", title).put("body", body).put("buttons", bs)
        if (collapse) o.put("n", last.optInt("n", 1) + 1)
        out.put(o)
        try { file(ctx).writeText(out.toString()) } catch (e: Exception) { }
        return ts
    }

    /** System/debug line. Hidden while the debug toggle is off, except lines that matter. */
    fun sys(ctx: Context, text: String) {
        // shown even with debug off: check failures and login events (the user must know)
        val important = text.startsWith("Check failed") || text.startsWith("Last crash") ||
            text.startsWith("Logged in") || text.startsWith("Logged out") || text.startsWith("Token")
        if (!important && !Prefs(ctx).debugLog) return
        add(ctx, "sys", text)
    }

    @Synchronized
    fun entries(ctx: Context): List<Entry> =
        readArr(ctx).objs().map { o ->
            val bs = (o.optJSONArray("buttons") ?: JSONArray()).objs().map { Pair(it.str("l"), it.str("u")) }
            Entry(o.optLong("ts"), o.str("kind"), o.str("title"), o.str("body"), bs, o.optLong("at", o.optLong("ts")), o.optInt("n", 1))
        }

    /** Wall-clock seconds a record was created (for "X min ago" and unread marks). */
    fun atOf(e: Entry): Long = e.at

    @Synchronized
    fun remove(ctx: Context, ts: Long) {
        val old = readArr(ctx)
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optLong("ts") != ts) out.put(o)
        }
        try { file(ctx).writeText(out.toString()) } catch (e: Exception) { }
    }

    @Synchronized
    fun clear(ctx: Context) { file(ctx).delete() }
}
