package my.spectrum.notify

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast

/** Choose which pages (sections) of one course send notifications. New pages are on by default. */
class SectionsActivity : Activity() {
    companion object {
        const val EXTRA_ID = "cid"
        const val EXTRA_NAME = "name"
    }

    private var cid = 0L
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cid = intent.getLongExtra(EXTRA_ID, 0L)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val col = screen("Notification pages")
        if (cid <= 0L) {
            finish()
            return
        }
        col.label(name, 20f, Ui.TEXT, 8).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label(
            "Choose which pages of this course send you notifications about new files and links. " +
                "A page that is new on Spectrum is switched on automatically.",
            12f, Ui.DIM, 4,
        )
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        if (cid > 0L) render()
    }

    private fun render() {
        box.removeAllViews()
        // the course announcements are listed first, then the course pages in page order
        val pages = Sections.known(this, cid).sortedBy { if (it.first == Sections.ANN) 0 else 1 }
        if (pages.isEmpty()) {
            box.label("The pages of this course are not known yet. Run a check first.", 13f, Ui.TEXT, 16)
            box.btn("Check Spectrum now") {
                Scheduler.pollNow(this)
                Toast.makeText(this, "Checking Spectrum... come back in a minute", Toast.LENGTH_SHORT).show()
            }
            return
        }
        for ((key, title) in pages) {
            val sw = Switch(this)
            sw.text = title.ifEmpty { "(unnamed page)" }
            sw.setTextColor(Ui.TEXT)
            sw.textSize = 15f * Ui.fontScale
            sw.isChecked = !Sections.isOff(this, cid, key)
            sw.setPadding(dp(4), dp(12), dp(4), dp(12))
            sw.setOnCheckedChangeListener { _, on -> Sections.setOn(this@SectionsActivity, cid, key, on) }
            box.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.btn("Turn all pages on") { setAll(pages, true) }
        box.btn("Turn all pages off") { setAll(pages, false) }
        box.label("Pages you turn off stay off. New pages added later are still switched on.", 12f, Ui.DIM, 8)
    }

    private fun setAll(pages: List<Pair<String, String>>, on: Boolean) {
        for ((k, _) in pages) Sections.setOn(this, cid, k, on)
        render()
    }
}
