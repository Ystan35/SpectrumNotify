package my.spectrum.notify

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/**
 * Every screen extends this. It makes Android's own parts — dialogs, time/date pickers, dropdowns, the
 * DayNight theme — follow the app's Colour mode (Settings → Appearance) instead of the phone's: with the
 * app on Light and the phone on Dark, dialogs used to draw dark with the app's dark text on top.
 */
open class BaseActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        // read the raw preference: Prefs() would also open the encrypted token store, too early here
        val mode = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("themeMode", 0)
        if (mode == 0) {
            super.attachBaseContext(newBase)
            return
        }
        val cfg = Configuration(newBase.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (mode == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }
}
