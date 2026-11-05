package my.spectrum.notify

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Hides materials and announcements that are meant for another group (occurrence / tutorial group) of the same course.
 * Your own group is learned from the names of your attendance events ("... Occ 2", "T3"), or set by hand in Settings.
 * An item is only hidden when it clearly names a group of a kind you have (e.g. "Occ 3" while you are in Occ 2);
 * items that name no group are always shown.
 */
object Groups {
    private val LETTERS = Regex("^[a-z]+")
    private val CODE_RE = Regex("""\b[A-Z]{2,4}\d{3,4}[A-Z]?\b""")

    /** One spelling per kind of group: tutorial3 -> t3, grp3 / grup3 -> group3. */
    private fun canon(tok: String): String {
        val letters = LETTERS.find(tok)?.value ?: return tok
        val rest = tok.removePrefix(letters)
        val l = when (letters) {
            "tutorial" -> "t"
            "grp", "grup" -> "group"
            else -> letters
        }
        return l + rest
    }

    /** occ2 -> "occ", t3 -> "t", group1 -> "group" */
    fun family(tok: String): String = LETTERS.find(tok)?.value ?: tok

    /** Group tokens in a text; "Tutorial 3" and "T3" both become t3. */
    fun tokens(text: String?): Set<String> =
        Checker.groupTokens(text).map { if (it.startsWith("tutorial")) "t" + it.removePrefix("tutorial") else it }.toSet().map { canon(it) }.toSet()

    private class Manual(val code: String, val tokens: Set<String>)

    /** "WIX1003:occ2, WIX1003:t3, occ1" -> entries; without "CODE:" the group applies to every course. */
    private fun manual(p: Prefs): List<Manual> =
        p.myGroups.split(",").map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { e ->
            val parts = e.split(":", limit = 2)
            val code = if (parts.size == 2) parts[0].trim().uppercase(Locale.ROOT) else ""
            val toks = tokens(if (parts.size == 2) parts[1] else parts[0])
            if (toks.isEmpty()) null else Manual(code, toks)
        }

    /** Remember each course's group tokens from the user's own (not ignored) attendance events. */
    fun learn(st: State, p: Prefs) {
        val acc = LinkedHashMap<String, MutableSet<String>>()
        for (e in Checker.myCalendar(st, p)) {
            if (!Checker.isAttEvent(e)) continue
            val toks = tokens(e.str("name"))
            if (toks.isEmpty()) continue
            acc.getOrPut(Timetable.courseCode(e.optJSONObject("course"))) { LinkedHashSet() }.addAll(toks)
        }
        if (acc.isEmpty()) return
        val o = st.j.optJSONObject("my_groups") ?: JSONObject()
        for ((c, t) in acc) o.put(c, jarr(t))
        st.j.put("my_groups", o)
    }

    /** Your group tokens for one course (empty = unknown, nothing is hidden). */
    fun mine(st: State, p: Prefs, code: String): Set<String> {
        if (p.groupsOff) return emptySet()
        val up = code.uppercase(Locale.ROOT)
        val own = manual(p).filter { it.code.isEmpty() || it.code == up }.flatMap { it.tokens }.toSet()
        if (own.isNotEmpty()) return own
        return (st.j.optJSONObject("my_groups")?.optJSONArray(up) ?: JSONArray()).strs().toSet()
    }

    /**
     * Your groups for one course only when you set or confirmed them (Courses / Confirm your Occ / Settings),
     * not guessed from attendance names: whole pages are muted for another Occ only when this is sure.
     */
    fun mineSure(p: Prefs, code: String): Set<String> {
        if (p.groupsOff) return emptySet()
        val up = code.uppercase(Locale.ROOT)
        return manual(p).filter { it.code.isEmpty() || it.code == up }.flatMap { it.tokens }.toSet()
    }

    /**
     * The single best-guess Occ per course, for prefilling the confirm dialog.
     * Priority: attendance-event occ token (your own events name your Occ) >
     * availability-detected occ (pages you can see). Returns code -> occ number, only when
     * exactly one occ is known — several candidates mean we cannot guess.
     */
    fun predictedOcc(ctx: android.content.Context): Map<String, String> {
        val st = State(ctx)
        val out = LinkedHashMap<String, String>()
        val occRe = Regex("occ([0-9]+[a-z]?)", RegexOption.IGNORE_CASE)
        // attendance events (strongest signal)
        val acc = LinkedHashMap<String, MutableSet<String>>()
        for (e in Checker.myCalendar(st, Prefs(ctx))) {
            if (!Checker.isAttEvent(e)) continue
            for (t in tokens(e.str("name"))) {
                occRe.find(t)?.groupValues?.get(1)?.let {
                    acc.getOrPut(Timetable.courseCode(e.optJSONObject("course")).uppercase()) { LinkedHashSet() }.add(it)
                }
            }
        }
        for ((c, occs) in acc) if (occs.size == 1) out[c] = occs.first()
        // availability detection (weaker; only fills courses with no attendance signal)
        val seen = st.j.optJSONObject("occ_seen") ?: return out
        val courseById = HashMap<Long, String>()
        Courses.all(ctx).forEach { courseById[it.id] = it.code.uppercase() }
        for (cidS in seen.keys()) {
            val code = courseById[cidS.toLongOrNull() ?: continue] ?: continue
            if (out.containsKey(code)) continue
            val occs = (seen.optJSONArray(cidS) ?: JSONArray()).strs()
                .mapNotNull { occRe.find(it)?.groupValues?.get(1) }
                .distinct()
            if (occs.size == 1) out[code] = occs.first()
        }
        return out
    }

    /** Courses with their learned Occ numbers (from attendance event names), for the Settings chips. */
    fun learnedPerCourse(ctx: android.content.Context, st: State = State(ctx)): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        val o = st.j.optJSONObject("my_groups") ?: return out
        for (k in o.keys()) {
            val occs = (o.optJSONArray(k) ?: JSONArray()).strs()
                .mapNotNull { Regex("occ([0-9]+[a-z]?)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                .distinct().sortedBy { it.toIntOrNull() ?: 999 }
            if (occs.isNotEmpty()) out[k] = occs.toMutableList()
        }
        // merge in Occs detected from page availability (occ_seen, keyed by course id)
        val seen = st.j.optJSONObject("occ_seen") ?: return out
        val courseById = HashMap<Long, String>()
        Courses.all(ctx).forEach { courseById[it.id] = it.code.uppercase() }
        for (cidS in seen.keys()) {
            val code = courseById[cidS.toLongOrNull() ?: continue] ?: continue
            val occs = (seen.optJSONArray(cidS) ?: JSONArray()).strs()
                .mapNotNull { Regex("occ([0-9]+[a-z]?)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                .distinct().sortedBy { it.toIntOrNull() ?: 999 }
            if (occs.isEmpty()) continue
            val merged = (out[code] ?: mutableListOf()).toMutableSet()
            merged.addAll(occs)
            out[code] = merged.sortedBy { it.toIntOrNull() ?: 999 }.toMutableList()
        }
        return out
    }

    /** Every group token you have in any course (for notifications that do not say which course). */
    fun mineAnywhere(st: State, p: Prefs): Set<String> {
        if (p.groupsOff) return emptySet()
        val out = LinkedHashSet<String>()
        manual(p).forEach { out.addAll(it.tokens) }
        val o = st.j.optJSONObject("my_groups")
        if (o != null) for (k in o.keys()) out.addAll((o.optJSONArray(k) ?: JSONArray()).strs())
        return out
    }

    /** First course code mentioned in a text, e.g. "WIA2010", or "". */
    fun codeIn(text: String): String = CODE_RE.find(text.uppercase(Locale.ROOT))?.value ?: ""

    /**
     * True when the item names a group of a kind you have, and none of the groups it names is yours.
     * Kinds you have no group for are ignored, so "T3" does not hide anything if you only know your Occ.
     */
    fun isForeign(text: String, mine: Set<String>): Boolean {
        if (mine.isEmpty()) return false
        val item = tokens(text)
        if (item.isEmpty()) return false
        val myFam = mine.groupBy { family(it) }
        for ((fam, toks) in item.groupBy { family(it) }) {
            val m = myFam[fam] ?: continue
            if (toks.none { it in m }) return true
        }
        return false
    }
}
