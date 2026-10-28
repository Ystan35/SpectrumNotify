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
        get() = sp.getString("manualCourses", "") ?: ""
        set(v) { sp.edit().putString("manualCourses", v.trim()).apply() }
    /** Classes to ignore, e.g. "WIX1003@Mon" = the Monday WIX1003 class is another group. No @day = all days. */
    var skipClasses: String
        get() = sp.getString("skipClasses", "") ?: ""
        set(v) { sp.edit().putString("skipClasses", v.trim()).apply() }
    /** Which group's posts to keep. Empty = work it out from my classes; "off" = show everything; or e.g. "WIX1003:occ2, WIX1003:t3". */
    var myGroups: String
        get() = sp.getString("myGroups", "") ?: ""
        set(v) { sp.edit().putString("myGroups", v.trim()).apply() }
    val groupsOff: Boolean get() = myGroups.trim().equals("off", ignoreCase = true)
    var deadlineHours: String
        get() = sp.getString("deadlineHours", "24,3") ?: ""
        set(v) { sp.edit().putString("deadlineHours", v.trim()).apply() }
    /** Minutes between checks. Default and minimum 10 (new key, so old 15-minute settings reset to the new default). */
    var pollMinutes: Int
        get() = maxOf(10, sp.getInt("pollMin", 10))
        set(v) { sp.edit().putInt("pollMin", maxOf(10, v)).apply() }

    // ---------------- cloud push (FCM via Cloudflare Worker) ----------------
    /** Master switch: receive pushes through the cloud poller. */
    var cloudPush: Boolean
        get() = sp.getBoolean("cloudPush", false)
        set(v) { sp.edit().putBoolean("cloudPush", v).apply() }
    /** URL of the poller's /register endpoint, e.g. https://...workers.dev/register */
    var workerUrl: String
        get() = (sp.getString("workerUrl", "") ?: "").trim()
        set(v) { sp.edit().putString("workerUrl", v.trim()).apply() }
    /** Shared key the Worker checks on /register (same value as its adminKey). */
    var workerKey: String
        get() = try { secure?.getString("workerKey", "") ?: "" } catch (e: Exception) { "" }
        set(v) { try { secure?.edit()?.putString("workerKey", v)?.commit() } catch (e: Exception) { } }
    /** FCM device token, filled by PushService.onNewToken. */
    var fcmToken: String
        get() = sp.getString("fcmToken", "") ?: ""
        set(v) { sp.edit().putString("fcmToken", v).apply() }
    var loginStartedAt: Long
        get() = sp.getLong("loginStartedAt", 0L)
        set(v) { sp.edit().putLong("loginStartedAt", v).apply() }
    /** Passport of the pending login attempt; the redirect's signature must match it. */
    var loginPassport: String
        get() = sp.getString("loginPassport", "") ?: ""
        set(v) { sp.edit().putString("loginPassport", v).apply() }

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

    /** Request codes of the class/daily alarms armed last time, comma-separated (so stale ones can be cancelled). */
    var classAlarmCodes: String
        get() = health.getString("classAlarmCodes", "") ?: ""
        set(v) { health.edit().putString("classAlarmCodes", v).apply() }
    /** One past the last event-reminder request code armed last time (codes start at 9000).
     *  Unset (builds before this was tracked): sweep a generous range once. */
    var eventAlarmEnd: Int
        get() = health.getInt("eventAlarmEnd", 9500)
        set(v) { health.edit().putInt("eventAlarmEnd", v).apply() }

    // ---------------- appearance / accessibility
    /** Theme override: 0 = follow phone, 1 = light, 2 = dark. */
    var themeMode: Int
        get() = sp.getInt("themeMode", 0)
        set(v) { sp.edit().putInt("themeMode", v).apply() }
    /** Text scale: 0 = normal (1.0), 1 = large (1.2), 2 = extra large (1.4). */
    var textScale: Int
        get() = sp.getInt("textScale", 0)
        set(v) { sp.edit().putInt("textScale", v).apply() }
    /** Higher-contrast text (darker greys / brighter accents). */
    var highContrast: Boolean
        get() = sp.getBoolean("highContrast", false)
        set(v) { sp.edit().putBoolean("highContrast", v).apply() }
    /** Bigger touch targets (56 dp instead of 48 dp). */
    var bigTouch: Boolean
        get() = sp.getBoolean("bigTouch", false)
        set(v) { sp.edit().putBoolean("bigTouch", v).apply() }

    /** versionCode of this installed build (to detect updates). */
    var buildVersionCode: Int
        get() = sp.getInt("buildVersionCode", 0)
        set(v) { sp.edit().putInt("buildVersionCode", v).apply() }
    /** Latest versionCode the user has already been notified about (remind once per version). */
    var updateSeenCode: Int
        get() = sp.getInt("updateSeenCode", 0)
        set(v) { sp.edit().putInt("updateSeenCode", v).apply() }

    /** Show debug/system lines (Check OK, skipped-item notes) in the notification list. Default off. */
    var debugLog: Boolean
        get() = sp.getBoolean("debugLog", false)
        set(v) { sp.edit().putBoolean("debugLog", v).apply() }

    /** Occ confirmed by the user in the startup dialog (per course code). */
    fun occConfirmed(code: String): Boolean = sp.getBoolean("occconf:$code", false)
    fun setOccConfirmed(code: String) { sp.edit().putBoolean("occconf:$code", true).apply() }

    /** Attendance duplicate labels per course, e.g. attlabel:WIX1003 = "0=Lecture,1=Tutorial". */
    fun attendanceLabel(course: String): String? =
        health.getString("attlabel:$course", null)

    fun setAttendanceLabel(course: String, first: String, second: String) {
        health.edit().putString("attlabel:$course", "0=$first,1=$second").apply()
    }
}
