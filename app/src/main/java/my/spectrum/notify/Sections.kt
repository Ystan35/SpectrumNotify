package my.spectrum.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which pages (sections) of a course may send "new material" notifications.
 * Only the pages you switch OFF are stored, so a page that is new on Spectrum is on by default.
 * The page list of each course is remembered from the last check (state.json "sections").
 */
object Sections {
    /** Pseudo page for the course announcements forum. */
    const val ANN = "ann"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("sections", Context.MODE_PRIVATE)

    private fun readOff(ctx: Context): JSONObject = try { JSONObject(sp(ctx).getString("off", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun offSet(ctx: Context, cid: Long): Set<String> = LinkedHashSet((readOff(ctx).optJSONArray("$cid") ?: JSONArray()).strs())

    fun isOff(ctx: Context, cid: Long, key: String): Boolean = key in offSet(ctx, cid)

    fun setOn(ctx: Context, cid: Long, key: String, on: Boolean) {
        val all = readOff(ctx)
        val cur = LinkedHashSet(offSet(ctx, cid))
        if (on) cur.remove(key) else cur.add(key)
        if (cur.isEmpty()) all.remove("$cid") else all.put("$cid", jarr(cur))
        sp(ctx).edit().putString("off", all.toString()).apply()
    }

    /** Called by the check: the pages the course has now, in page order (key -> name). */
    fun remember(st: State, cid: Long, names: Map<String, String>) {
        val root = st.j.optJSONObject("sections") ?: JSONObject()
        val arr = JSONArray()
        for ((k, n) in names) arr.put(JSONObject().put("id", k).put("name", n))
        root.put("$cid", arr)
        st.j.put("sections", root)
    }

    /** Pages of a course as last seen (key, name). */
    fun known(ctx: Context, cid: Long): List<Pair<String, String>> {
        val arr = State(ctx).j.optJSONObject("sections")?.optJSONArray("$cid") ?: return emptyList()
        return arr.objs().map { Pair(it.str("id"), it.str("name")) }
    }

    /** "all pages on", or "2 pages off". */
    fun summary(ctx: Context, cid: Long): String {
        val known = known(ctx, cid).map { it.first }.toSet()
        val off = offSet(ctx, cid).count { it in known }
        return if (off == 0) "all pages on" else "$off page" + (if (off == 1) "" else "s") + " off"
    }
}
