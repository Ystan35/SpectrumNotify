package my.spectrum.notify

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast

/** Choose which pages (sections) of one course send notifications. New pages are on by default. */
class SectionsActivity : BaseActivity() {
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
        col.label(name, Type.HEADLINE, Ui.TEXT, 8).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label(
            "Which pages of this course notify you about new files and links. Sections inside a page are listed under it.",
            Type.CAPTION, Ui.DIM, 4,
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
        // the course announcements first, then the main pages in page order, each with its sub-sections under it
        val pages = Sections.known(this, cid)
        if (pages.isEmpty()) {
            box.label("The pages of this course are not known yet. Run a check first.", Type.LABEL, Ui.TEXT, 16)
            box.btn("Check Spectrum now") {
                Scheduler.pollNow(this)
                Toast.makeText(this, "Checking Spectrum... come back in a minute", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val ids = pages.map { it.id }.toSet()
        val tops = pages.filter { it.parent.isEmpty() || it.parent !in ids }.sortedBy { if (it.id == Sections.ANN) 0 else 1 }
        val kids = pages.filter { it.parent.isNotEmpty() && it.parent in ids }.groupBy { it.parent }
        val off = Sections.offSet(this, cid)

        // your Occ decides which Occ pages are yours; pages for another Occ are skipped automatically
        val code = Courses.all(this).firstOrNull { it.id == cid }?.code ?: ""
        val mine = Groups.mine(State(this), Prefs(this), code)
        val myOcc = mine.mapNotNull { Regex("^occ(.+)").find(it)?.groupValues?.get(1) }
        box.label(if (myOcc.isNotEmpty()) "Your Occ: ${myOcc.joinToString(", ")}. Pages for another Occ are skipped."
            else "Set your Occ on the Courses page to skip pages for other Occs.", Type.LABEL, if (myOcc.isNotEmpty()) Ui.GREEN else Ui.ORANGE, 12)

        fun row(pg: Sections.Page, sub: Boolean, parentOff: Boolean) {
            val foreign = Groups.isForeign(pg.name, mine)
            val ours = mine.isNotEmpty() && Groups.tokens(pg.name).any { it in mine }
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // sub-sections sit indented under their main page
                setPadding(if (sub) dp(20) else 0, 0, 0, 0)
            }
            val sw = Switch(this)
            sw.text = pg.name.ifEmpty { "(unnamed page)" }
            sw.setTextColor(Ui.TEXT)
            sw.textSize = (if (sub) Type.BODY else Type.BODY_L) * Ui.fontScale
            sw.minHeight = dp(Ui.touch(48))
            sw.setPadding(dp(4), dp(8), dp(4), dp(if (foreign || ours || parentOff) 0 else 8))
            // another Occ's page or a page under a switched-off main page: shown off and greyed, its own choice kept
            val forced = foreign || parentOff
            sw.isChecked = !forced && pg.id !in off
            sw.isEnabled = !forced
            sw.alpha = if (forced) 0.55f else 1f
            sw.setOnCheckedChangeListener { _, on -> Sections.setOn(this@SectionsActivity, cid, pg.id, on); render() }
            wrap.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val note = when {
                foreign -> Pair("Another Occ: skipped", Ui.DIM)
                parentOff -> Pair("Off with its main page", Ui.DIM)
                ours -> Pair("Your Occ", Ui.GREEN)
                else -> null
            }
            if (note != null) wrap.addView(tv(note.first, Type.CAPTION, note.second, true).apply { setPadding(dp(4), 0, 0, dp(8)) })
            box.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        for (top in tops) {
            row(top, sub = false, parentOff = false)
            val topOff = top.id in off || Groups.isForeign(top.name, mine)
            kids[top.id]?.forEach { row(it, sub = true, parentOff = topOff) }
        }
        box.btn("Turn all pages on") { setAll(pages, true) }
        box.btn("Turn all pages off") { setAll(pages, false) }
        box.label("Pages you turn off stay off; turning off a main page covers the sections inside it. " +
            "New pages added later are switched on.", Type.CAPTION, Ui.DIM, 8)
    }

    private fun setAll(pages: List<Sections.Page>, on: Boolean) {
        for (pg in pages) Sections.setOn(this, cid, pg.id, on)
        render()
    }
}
