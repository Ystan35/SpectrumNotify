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

    /** Pages you switched back on past a mute (another Occ's page, or under a page that is off). */
    fun keptOnSet(ctx: Context, cid: Long): Set<String> {
        val all = try { JSONObject(sp(ctx).getString("kept", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        return LinkedHashSet((all.optJSONArray("$cid") ?: JSONArray()).strs())
    }

    /** [keep]: this page is muted by default, so a switch-on has to be remembered. */
    fun setOn(ctx: Context, cid: Long, key: String, on: Boolean, keep: Boolean = false) {
        val all = readOff(ctx)
        val cur = LinkedHashSet(offSet(ctx, cid))
        if (on) cur.remove(key) else cur.add(key)
        if (cur.isEmpty()) all.remove("$cid") else all.put("$cid", jarr(cur))
        val keptAll = try { JSONObject(sp(ctx).getString("kept", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        val kept = LinkedHashSet(keptOnSet(ctx, cid))
        if (on && keep) kept.add(key) else kept.remove(key)
        if (kept.isEmpty()) keptAll.remove("$cid") else keptAll.put("$cid", jarr(kept))
        sp(ctx).edit().putString("off", all.toString()).putString("kept", keptAll.toString()).apply()
    }

    /** Is page [key] on past a mute, because you switched it or its main page on? */
    fun isKeptOn(kept: Set<String>, off: Set<String>, parents: Map<String, String>, key: String): Boolean =
        key in kept || (key !in off && parents[key]?.let { it in kept } == true)

    /** One page; [parent] = the main page a sub-section sits in ("" = a main page). */
    data class Page(val id: String, val name: String, val parent: String = "")

    /** Called by the check: the pages the course has now, in page order (key -> name), and each sub-section's main page. */
    fun remember(st: State, cid: Long, names: Map<String, String>, parents: Map<String, String> = emptyMap()) {
        val root = st.j.optJSONObject("sections") ?: JSONObject()
        val arr = JSONArray()
        for ((k, n) in names) arr.put(JSONObject().put("id", k).put("name", n).put("parent", parents[k] ?: ""))
        root.put("$cid", arr)
        st.j.put("sections", root)
    }

    /** Pages of a course as last seen. */
    fun known(ctx: Context, cid: Long, st: State = State(ctx)): List<Page> {
        val arr = st.j.optJSONObject("sections")?.optJSONArray("$cid") ?: return emptyList()
        return arr.objs().map { Page(it.str("id"), unesc(it.str("name")), it.str("parent")) }
    }

    /** Is page [key] off, by itself or because its main page ([parents]: sub-section -> main page) is off? */
    fun isOff(off: Set<String>, parents: Map<String, String>, key: String, kept: Set<String> = emptySet()): Boolean =
        key !in kept && (key in off || parents[key]?.let { it in off } == true)

    /** "all pages on", or "2 pages off". */
    fun summary(ctx: Context, cid: Long, st: State = State(ctx)): String {
        val known = known(ctx, cid, st).map { it.id }.toSet()
        val off = offSet(ctx, cid).count { it in known }
        return if (off == 0) "all pages on" else "$off page" + (if (off == 1) "" else "s") + " off"
    }
}
