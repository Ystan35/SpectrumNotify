package my.spectrum.notify

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-customizable shortcuts to frequently used UM websites (maya, epay, starecs,
 * mysiswa, …). Shown in the floating toolbox on every tab and edited in Settings → UM links.
 * Stored as a JSON array of {name,url,icon} in SharedPreferences "shortcuts".
 */
object Shortcuts {
    data class Link(val name: String, val url: String, val icon: String = "")

    /** The built-in line icons (res/drawable/lk_*.xml), in the order the picker shows them. */
    val ICONS: List<Pair<String, Int>> = listOf(
        "school" to R.drawable.lk_school, "payment" to R.drawable.lk_payment, "library" to R.drawable.lk_library,
        "calendar" to R.drawable.lk_calendar, "mail" to R.drawable.lk_mail, "book" to R.drawable.lk_book,
        "web" to R.drawable.lk_web, "document" to R.drawable.lk_document, "bus" to R.drawable.lk_bus,
        "food" to R.drawable.lk_food, "wifi" to R.drawable.lk_wifi, "person" to R.drawable.lk_person,
        "star" to R.drawable.lk_star, "grades" to R.drawable.lk_grades, "video" to R.drawable.lk_video,
        "link" to R.drawable.lk_link, "home" to R.drawable.lk_home, "map" to R.drawable.lk_map,
        "help" to R.drawable.lk_help,
    )

    /** Spoken name of each icon, for screen readers in the picker. */
    fun iconLabel(key: String): String = when (key) {
        "school" -> "Graduation cap"; "payment" -> "Payment card"; "library" -> "Library"; "calendar" -> "Calendar"
        "mail" -> "Mail"; "book" -> "Book"; "web" -> "Web"; "document" -> "Document"; "bus" -> "Bus"
        "food" -> "Food"; "wifi" -> "Wi-Fi"; "person" -> "Person"; "star" -> "Star"; "grades" -> "Grades"
        "video" -> "Video"; "link" -> "Link"; "home" -> "Home"; "map" -> "Map"; else -> "Help"
    }

    fun iconRes(key: String): Int = ICONS.firstOrNull { it.first == key }?.second ?: R.drawable.lk_link

    /** A sensible icon for a link saved before icons existed, or a new one, from its name and address. */
    fun guessIcon(name: String, url: String): String {
        val t = "$name $url".lowercase()
        return when {
            "maya" in t || "siswa" in t -> "person"
            "pay" in t || "fee" in t || "bayar" in t -> "payment"
            "starecs" in t || "result" in t || "grade" in t || "exam" in t -> "grades"
            "library" in t || "perpustakaan" in t || "umlib" in t -> "library"
            "mail" in t || "outlook" in t -> "mail"
            "spectrum" in t || "course" in t -> "book"
            "calendar" in t || "timetable" in t || "timeedit" in t -> "calendar"
            "bus" in t || "shuttle" in t -> "bus"
            "wifi" in t || "wi-fi" in t || "network" in t -> "wifi"
            "map" in t -> "map"
            else -> "web"
        }
    }

    /** UM defaults, also what "Reset to UM defaults" restores. */
    fun defaults(): List<Link> = listOf(
        Link("Maya", "https://maya.um.edu.my/s/studentsignin", "person"),
        Link("ePay", "https://epay.um.edu.my/", "payment"),
        Link("STAREcs", "https://starecs.um.edu.my/", "grades"),
        Link("MySiswa", "https://mysis.um.edu.my/", "school"),
        Link("Spectrum", "https://spectrum.um.edu.my/", "book"),
        Link("SpectrumExam", "https://spectrumexam.um.edu.my/", "grades"),
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)

    /** Defaults seeded on first read; user edits replace the whole list. */
    fun all(ctx: Context): List<Link> {
        val raw = sp(ctx).getString("list", null)
        if (raw == null) {
            val d = defaults()
            save(ctx, d)
            return d
        }
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<Link>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name").trim()
            val u = o.optString("url").trim()
            if (n.isNotEmpty() && u.isNotEmpty()) out.add(Link(n, u, o.optString("icon").ifEmpty { guessIcon(n, u) }))
        }
        return upgrade(ctx, out)
    }

    /**
     * Once, for lists saved before 0.31: MySiswa moved to mysis.um.edu.my, and Spectrum and SpectrumExam
     * join the defaults (only when no link to them is there already; your own edits stay).
     */
    private fun upgrade(ctx: Context, list: List<Link>): List<Link> {
        if (sp(ctx).getBoolean("v031", false)) return list
        val out = list.map { l ->
            if (Uri.parse(l.url).host.equals("mysiswa.um.edu.my", true)) l.copy(url = "https://mysis.um.edu.my/") else l
        }.toMutableList()
        fun has(host: String) = out.any { Uri.parse(it.url).host.equals(host, true) }
        for (d in defaults()) {
            val host = Uri.parse(d.url).host ?: continue
            if ((host == "spectrum.um.edu.my" || host == "spectrumexam.um.edu.my") && !has(host)) out.add(d)
        }
        save(ctx, out)
        sp(ctx).edit().putBoolean("v031", true).apply()
        return out
    }

    @Synchronized
    fun save(ctx: Context, links: List<Link>) {
        val arr = JSONArray()
        for (l in links.filter { it.name.isNotBlank() && it.url.isNotBlank() }) {
            arr.put(JSONObject().put("name", l.name.trim()).put("url", l.url.trim())
                .put("icon", l.icon.ifEmpty { guessIcon(l.name, l.url) }))
        }
        sp(ctx).edit().putString("list", arr.toString()).apply()
    }

    /**
     * A web address someone typed, made openable: spaces trimmed and https:// added when there is no scheme.
     * Null when it still is not a web address (no host, or not http/https).
     */
    fun normalizeUrl(raw: String): String? {
        var u = raw.trim()
        if (u.isEmpty()) return null
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(u)) u = "https://$u"
        val parsed = Uri.parse(u)
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = parsed.host ?: return null
        return if (host.contains('.')) u else null
    }
}
