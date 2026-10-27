package my.spectrum.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-customizable shortcuts to frequently used UM websites (maya, epay, starecs,
 * mysiswa, …). Shown in the floating toolbox on every tab. Stored as a JSON array
 * of {name,url} in SharedPreferences "shortcuts".
 */
object Shortcuts {
    data class Link(val name: String, val url: String)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    /** Defaults seeded on first read; user edits replace the whole list. */
    fun all(ctx: Context): List<Link> {
        val raw = sp(ctx).getString("list", null)
        if (raw == null) {
            val defaults = listOf(
                Link("Maya", "https://maya.um.edu.my/s/studentsignin"),
                Link("ePay", "https://epay.um.edu.my/"),
                Link("STAREcs", "https://starecs.um.edu.my/"),
                Link("MySiswa", "https://mysiswa.um.edu.my/"),
            )
            save(ctx, defaults)
            return defaults
        }
        val arr = JSONArray(raw)
        val out = ArrayList<Link>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name").trim()
            val u = o.optString("url").trim()
            if (n.isNotEmpty() && u.isNotEmpty()) out.add(Link(n, u))
        }
        return out
    }

    @Synchronized
    fun save(ctx: Context, links: List<Link>) {
        val arr = JSONArray()
        for (l in links.filter { it.name.isNotBlank() && it.url.isNotBlank() }) {
            arr.put(JSONObject().put("name", l.name.trim()).put("url", l.url.trim()))
        }
        sp(ctx).edit().putString("list", arr.toString()).apply()
    }
}
