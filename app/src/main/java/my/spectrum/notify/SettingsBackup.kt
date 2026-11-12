package my.spectrum.notify

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings → Export / Import settings (feedback SN-QRXWL: set up a new phone without redoing everything).
 * Only what you chose is in the file: course settings (Occ, online or in person, links, rooms, times, colours, short
 * names, hidden classes), reminders and your events, alerts and quiet hours, your links, shuttle places, hidden and
 * done tasks. Never the login (the token lives in encrypted prefs, not listed here), synced data, caches, the feedback
 * install id or this phone's push id.
 *
 * A file can be edited by anyone, so import trusts nothing in it: see [read].
 */
object SettingsBackup {
    private const val MARK = "SpectrumNotify settings"

    /** Bigger than any real export by far (one is a few KB): refused before it is read. */
    const val MAX_BYTES = 512 * 1024
    private const val MAX_KEYS = 5000
    private const val MAX_KEY_LEN = 200
    private const val MAX_TEXT = 20_000
    private const val MAX_SET = 2000

    /** Whole files that hold only your choices. */
    private val FILES = listOf("settings", "timetable", "courses", "shortcuts", "shuttle", "sections", "events", "shorts", "home")

    /** This phone's own: its push id and which app version it has seen. Never exported, and kept on import. */
    private val DEVICE = setOf("fcmToken", "buildVersionCode", "updateSeenCode")

    /** "health" mixes run state with your choices: only these keys travel. */
    private val HEALTH = Regex("^(hiddenEvents|hiddenMeta|doneTasks|dueDone:.*)$")

    /**
     * The type the app reads each key with (by name, or by the part before ':'); every other key is read as text. A
     * value of another type would crash the screen that reads it, so it is left out.
     */
    private val TYPES: Map<String, String> = buildMap {
        listOf("attStartAlert", "batteryAsked", "batteryConfirmed", "bigTouch", "calLink", "cloudPush", "debugLog", "highContrast",
            "on", "setupNamed", "umbrellaAlert", "userEdited", "dueDone", "occcheck", "occconf", "attpin", "attPinMigrated", "linkOrder2", "v031", "v2")
            .forEach { put(it, "b") }
        listOf("pollMin", "quietStart", "quietEnd", "themeMode", "textScale", "lead").forEach { put(it, "i") }
        listOf("inperson", "at").forEach { put(it, "l") }
        listOf("doneTasks", "hiddenEvents", "hiddenOwnSeen", "occOk").forEach { put(it, "set") }
    }

    /** Numbers the app uses as a list position or a timer: outside these they crash a screen or hammer Spectrum. */
    private val RANGES = mapOf(
        "themeMode" to 0..2, "textScale" to 0..2, "quietStart" to 0..23, "quietEnd" to 0..23,
        "pollMin" to 10..1440, "lead" to 0..120,
    )

    /** Links may only be web pages: no javascript:, intent:, file:, content: or app links hidden in a value. */
    private val BAD_SCHEME = Regex("""(?i)\b(?:javascript|vbscript|intent|file|content|data|android-app|jar):""")
    private val ANY_SCHEME = Regex("""(?i)\b([a-z][a-z0-9+.-]*)://""")
    private val CONTROL = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F‪-‮⁦-⁩]""")

    fun export(ctx: Context): String {
        val prefs = JSONObject()
        for (f in FILES) prefs.put(f, dump(sp(ctx, f)) { it !in DEVICE })
        prefs.put("health", dump(sp(ctx, "health")) { HEALTH.matches(it) })
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "" }
        return JSONObject().put("app", MARK).put("version", version).put("exported", System.currentTimeMillis() / 1000)
            .put("prefs", prefs).toString(2)
    }

    /** What an import would write, checked by [read]; nothing is written until [apply]. */
    class Plan(val values: Map<String, Map<String, Any>>, val rejected: Map<String, Set<String>>, val skipped: Int, val version: String, val exported: Long) {
        val count get() = values.values.sumOf { it.size }
    }

    /**
     * Reads and checks the whole file first. Throws with a message for the user when it is not a settings export;
     * a single value that is unsafe (wrong type, out of range, a non-web link, a changed Spectrum address, hidden
     * control characters, too long) is left out and counted in [Plan.skipped].
     */
    fun read(text: String): Plan {
        val notOurs = IllegalArgumentException("This file is not a Spectrum Notify settings export.")
        if (text.length > MAX_BYTES) throw IllegalArgumentException("This file is too big to be a settings export.")
        val root = try { JSONObject(text) } catch (_: Exception) { throw notOurs }
        if (root.optString("app") != MARK) throw notOurs
        val prefs = root.optJSONObject("prefs") ?: throw notOurs
        val out = LinkedHashMap<String, Map<String, Any>>()
        val rejected = LinkedHashMap<String, Set<String>>()
        var skipped = 0
        var seen = 0
        for (f in FILES + "health") {
            val o = prefs.optJSONObject(f) ?: continue
            val m = LinkedHashMap<String, Any>()
            val bad = HashSet<String>()
            for (k in o.keys()) {
                if (++seen > MAX_KEYS) throw IllegalArgumentException("This file has far more settings than the app makes.")
                // this phone's own keys and run state are not settings: ignored, not counted as unsafe
                if (if (f == "health") !HEALTH.matches(k) else k in DEVICE) continue
                val v = value(f, k, o.optJSONObject(k))
                if (v == null) { skipped++; bad.add(k) } else m[k] = v
            }
            out[f] = m
            rejected[f] = bad
        }
        return Plan(out, rejected, skipped, clean(root.optString("version")).take(20), root.optLong("exported"))
    }

    /**
     * Writes a checked [Plan]: the keys it covers are replaced, the others (this phone's own, run state) stay, and a
     * setting the file had but that was left out as unsafe keeps your current value instead of resetting.
     */
    fun apply(ctx: Context, plan: Plan) {
        for ((f, m) in plan.values) {
            val p = sp(ctx, f)
            val e = p.edit()
            val keep = plan.rejected[f].orEmpty()
            p.all.keys.filter { (if (f == "health") HEALTH.matches(it) else it !in DEVICE) && it !in keep }.forEach { e.remove(it) }
            for ((k, v) in m) when (v) {
                is String -> e.putString(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
            }
            e.commit()
        }
    }

    /** One value, or null when it is not safe to keep. */
    private fun value(file: String, k: String, x: JSONObject?): Any? {
        if (x == null || k.isEmpty() || k.length > MAX_KEY_LEN || CONTROL.containsMatchIn(k)) return null
        val t = x.optString("t")
        val want = TYPES[k] ?: TYPES[k.substringBefore(':')] ?: "s"
        if (t != want) return null
        val raw = x.opt("v") ?: return null
        return when (t) {
            "b" -> raw as? Boolean
            "i" -> (raw as? Number)?.toLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                ?.takeIf { n -> RANGES[k]?.contains(n) ?: true }
            "l" -> (raw as? Number)?.toLong()?.takeIf { it >= 0 }
            "set" -> (raw as? JSONArray)?.takeIf { it.length() <= MAX_SET }?.let { a ->
                (0 until a.length()).map { a.opt(it) as? String ?: return null }.onEach { if (!safeText(it)) return null }.toSet()
            }
            else -> (raw as? String)?.takeIf { safeText(it) && (file != "settings" || k != "site" || umSite(it)) }
        }
    }

    private fun safeText(s: String) = s.length <= MAX_TEXT && !CONTROL.containsMatchIn(s) && !BAD_SCHEME.containsMatchIn(s) &&
        ANY_SCHEME.findAll(s).all { it.groupValues[1].lowercase() in setOf("http", "https") }

    /** The Spectrum address decides where you log in: a file may only set UM's, never another site. */
    private fun umSite(s: String): Boolean {
        val host = s.trim().replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "").substringBefore('/').substringBefore(':').lowercase()
        return host == "um.edu.my" || host.endsWith(".um.edu.my")
    }

    private fun clean(s: String) = s.replace(CONTROL, "")

    private fun sp(ctx: Context, name: String) = ctx.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    // each value keeps its type ("s" string, "i" int, "l" long, "f" float, "b" boolean, "set"), since JSON numbers do not
    private fun dump(p: SharedPreferences, keep: (String) -> Boolean): JSONObject {
        val o = JSONObject()
        for ((k, v) in p.all) {
            if (!keep(k) || v == null) continue
            val t = when (v) { is String -> "s"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Boolean -> "b"; is Set<*> -> "set"; else -> continue }
            o.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
        }
        return o
    }

    init {
        // the checks themselves, once: a crafted file must not get past them
        fun f(prefs: String) = """{"app":"$MARK","prefs":$prefs}"""
        check(read(f("""{"settings":{"themeMode":{"t":"i","v":2},"pollMin":{"t":"i","v":30}}}""")).count == 2)
        check(read(f("""{"settings":{"themeMode":{"t":"i","v":9}}}""")).skipped == 1)            // would crash Settings
        check(read(f("""{"settings":{"pollMin":{"t":"i","v":1}}}""")).skipped == 1)              // would hammer Spectrum
        check(read(f("""{"settings":{"themeMode":{"t":"s","v":"2"}}}""")).skipped == 1)          // wrong type
        check(read(f("""{"settings":{"site":{"t":"s","v":"https://evil.example"}}}""")).skipped == 1)
        check(read(f("""{"settings":{"site":{"t":"s","v":"https://spectrum.um.edu.my"}}}""")).count == 1)
        check(read(f("""{"timetable":{"mlink:X":{"t":"s","v":"javascript:alert(1)"}}}""")).skipped == 1)
        check(read(f("""{"timetable":{"mlink:X":{"t":"s","v":"intent://x#Intent;end"}}}""")).skipped == 1)
        check(read(f("""{"timetable":{"loc:X":{"t":"s","v":"online:https://teams.microsoft.com/l/x"}}}""")).count == 1)
        check(read(f("""{"settings":{"fcmToken":{"t":"s","v":"x"}},"health":{"lastRun":{"t":"s","v":"x"}}}""")).count == 0)
        check(runCatching { read("""{"app":"other"}""") }.isFailure)
    }
}
