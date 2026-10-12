package my.spectrum.notify

import android.app.Activity
import android.content.Intent
import android.webkit.CookieManager
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class AccountActivity : Activity() {
    private lateinit var p: Prefs
    private lateinit var status: TextView
    private lateinit var siteField: EditText
    private lateinit var tokenField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        val col = screen("Account")

        status = col.label("", 16f, Ui.TEXT)

        col.label("Spectrum site URL")
        siteField = col.field(p.site)

        col.btn("Log in with Spectrum") {
            p.site = siteField.text.toString()
            startActivity(Intent(this, LoginWebActivity::class.java))
        }
        col.btn("Log in with browser instead") {
            p.site = siteField.text.toString()
            Login.start(this)
        }

        col.label("Or paste a token manually", 14f, Ui.TEXT, 24)
        tokenField = col.field("", "Token", true)
        col.btn("Save token") {
            val t = tokenField.text.toString().trim()
            if (t.isNotEmpty()) {
                p.site = siteField.text.toString()
                tokenField.setText("")
                Login.saveAndVerify(this, t) { msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }
            }
        }

        col.label("", 12f, Ui.DIM, 24)
        col.btn("Log out") {
            p.clearToken()
            CookieManager.getInstance().removeAllCookies(null)
            NotifyLog.sys(this, "Logged out")
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val loggedIn = p.token.isNotEmpty()
        status.text = (if (loggedIn) "Logged in" + (if (p.userName.isNotEmpty()) " as ${p.userName}" else "") else "Not logged in") + "\nLast check: ${p.lastRun}"
        status.setTextColor(if (loggedIn) Ui.GREEN else Ui.RED)
    }
}
