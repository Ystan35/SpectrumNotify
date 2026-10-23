package my.spectrum.notify

import android.app.Application

/**
 * Saves any uncaught crash to files/crash.txt (last 200 lines) before the system handles it.
 * The file survives the restart, so a crash-on-launch loop can still be diagnosed:
 * the next successful launch reads and logs it, then clears it.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val p = Prefs(this)
            val code = packageManager.getPackageInfo(packageName, 0).let {
                if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
            }
            if (p.buildVersionCode != code) {
                p.buildVersionCode = code
                p.updateSeenCode = 0   // new install/update: allow one update notification for newer versions
            }
        } catch (_: Exception) { }
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val text = android.util.Log.getStackTraceString(e)
                java.io.File(filesDir, "crash.txt")
                    .writeText("${System.currentTimeMillis()} thread=${t.name}\n$text")
            } catch (_: Exception) { }
            default?.uncaughtException(t, e)
        }
        // if a previous crash was captured, surface it in the console log once the app is up
        try {
            val f = java.io.File(filesDir, "crash.txt")
            if (f.exists()) {
                NotifyLog.sys(this, "Last crash: " + f.readText().take(300))
            }
        } catch (_: Exception) { }
    }
}
