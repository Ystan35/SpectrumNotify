package my.spectrum.notify

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** The account page moved into Settings (PROFILE section). Anything that still opens it is sent there. */
class AccountActivity : Activity() {
    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}
