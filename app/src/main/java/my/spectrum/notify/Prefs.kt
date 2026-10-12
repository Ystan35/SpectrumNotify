package my.spectrum.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Settings (plain) + token (Android Keystore-backed encrypted storage). */
class Prefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val health: SharedPreferences = app.getSharedPreferences("health", Context.MODE_PRIVATE)

    private val secure: SharedPreferences? by lazy {
        try {
            val mk = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app, "secure", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            null
        }
    }

    val token: String get() = try { secure?.getString("token", "") ?: "" } catch (e: Exception) { "" }
    fun saveToken(v: String): Boolean =
        try { secure?.edit()?.putString("token", v)?.commit() ?: false } catch (e: Exception) { false }
    fun clearToken() {
        try { secure?.edit()?.remove("token")?.commit() } catch (e: Exception) { }
        userName = ""
    }
    /** When the notification list was last viewed (epoch seconds); newer records show a "new" dot. */
    var lastSeen: Long
        get() = health.getLong("lastSeen", 0L)
        set(v) { health.edit().putLong("lastSeen", v).apply() }
    /** Name of the logged-in Spectrum user, shown at the bottom-left of the main screen. */
    var userName: String
        get() = health.getString("userName", "") ?: ""
        set(v) { health.edit().putString("userName", v).apply() }

    var site: String
        get() = (sp.getString("site", "https://spectrum.um.edu.my") ?: "").trim().trimEnd('/')
        set(v) { sp.edit().putString("site", v.trim().trimEnd('/')).apply() }
    var quietStart: Int
        get() = sp.getInt("quietStart", 1)
        set(v) { sp.edit().putInt("quietStart", v).apply() }
    var quietEnd: Int
        get() = sp.getInt("quietEnd", 7)
        set(v) { sp.edit().putInt("quietEnd", v).apply() }
    var summaryTime: String
        get() = sp.getString("summaryTime", "07:05") ?: ""
        set(v) { sp.edit().putString("summaryTime", v.trim()).apply() }
    var afternoonTime: String
        get() = sp.getString("afternoonTime", "14:00") ?: ""
        set(v) { sp.edit().putString("afternoonTime", v.trim()).apply() }
    var manualCourses: String
        get() = sp.getString("manualCourses", "WIA2010") ?: ""
        set(v) { sp.edit().putString("manualCourses", v.trim()).apply() }
    /** Classes to ignore, e.g. "WIX1003@Mon" = the Monday WIX1003 class is another group. No @day = all days. */
    var skipClasses: String
        get() = sp.getString("skipClasses", "WIX1003@Mon") ?: ""
        set(v) { sp.edit().putString("skipClasses", v.trim()).apply() }
    /** Which group's posts to keep. Empty = work it out from my classes; "off" = show everything; or e.g. "WIX1003:occ2, WIX1003:t3". */
    var myGroups: String
        get() = sp.getString("myGroups", "") ?: ""
        set(v) { sp.edit().putString("myGroups", v.trim()).apply() }
    val groupsOff: Boolean get() = myGroups.trim().equals("off", ignoreCase = true)
    var deadlineHours: String
        get() = sp.getString("deadlineHours", "24,3") ?: ""
        set(v) { sp.edit().putString("deadlineHours", v.trim()).apply() }
    var pollMinutes: Int
        get() = maxOf(15, sp.getInt("pollMinutes", 15))
        set(v) { sp.edit().putInt("pollMinutes", maxOf(15, v)).apply() }
    var loginStartedAt: Long
        get() = sp.getLong("loginStartedAt", 0L)
        set(v) { sp.edit().putLong("loginStartedAt", v).apply() }

    val manualList: List<String>
        get() = manualCourses.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val skipList: List<Pair<String, String>>
        get() = skipClasses.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map {
            val parts = it.split("@", limit = 2)
            Pair(parts[0].trim(), parts.getOrElse(1) { "" }.trim().take(3))
        }
    val deadlineList: List<Int>
        get() = deadlineHours.split(",").mapNotNull { it.trim().toIntOrNull() }.sorted()

    // health: shown on the main screen so you can see whether checks are working
    var lastRun: String
        get() = health.getString("lastRun", "never") ?: "never"
        set(v) { health.edit().putString("lastRun", v).putLong("lastRunTs", System.currentTimeMillis() / 1000).apply() }
    val lastRunTs: Long
        get() = health.getLong("lastRunTs", 0L)
    var lastError: String
        get() = health.getString("lastError", "") ?: ""
        set(v) { health.edit().putString("lastError", v).apply() }
    var fails: Int
        get() = health.getInt("fails", 0)
        set(v) { health.edit().putInt("fails", v).apply() }
    var alerted: Boolean
        get() = health.getBoolean("alerted", false)
        set(v) { health.edit().putBoolean("alerted", v).apply() }
}
