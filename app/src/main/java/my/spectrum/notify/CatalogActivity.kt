package my.spectrum.notify

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Pick a course from the UM timetable, then choose your Lecture Occ and Tutorial Occ from drop-down lists.
 * Saving adds the chosen slots to the timetable and sets "My group" so other groups' posts are hidden.
 */
class CatalogActivity : Activity() {
    private lateinit var holder: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = screen("Pick from UM timetable")
        val cat = Catalog.load(this)
        col.label(
            if (cat.isEmpty()) "The UM timetable list could not be loaded." else "Semester ${Catalog.semester} · ${cat.size} courses, from UM TimeEdit.",
            12f, Ui.DIM, 6,
        )
        col.label("Course code or name", 13f, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
        val field = col.field("", "WIX1003")
        status = col.label("", 12f, Ui.DIM, 6)
        holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refresh(s?.toString() ?: "") { code ->
                    field.setText(code)
                    field.setSelection(code.length)
                }
            }
        })
    }

    private fun refresh(text: String, pick: (String) -> Unit) {
        holder.removeAllViews()
        val q = text.trim().uppercase(Locale.ROOT)
        if (q.isEmpty()) {
            status.text = ""
            return
        }
        val exact = Catalog.find(this, q)
        if (exact != null) {
            status.text = exact.name
            buildPickers(exact)
            return
        }
        val found = Catalog.search(this, q)
        if (found.isEmpty()) {
            status.text = if (q.length < 2) "Type at least 2 letters." else "No course found for \"$q\"."
            return
        }
        status.text = "Tap a course:"
        holder.chips(found.map { it.code }, emptySet(), false) { sel -> sel.firstOrNull()?.let { pick(found[it].code) } }
        holder.label(found.joinToString("\n") { "${it.code}  ${it.name}" }, 12f, Ui.DIM, 8)
    }

    private fun buildPickers(c: Catalog.Course) {
        val occs = c.occs()
        if (occs.isEmpty()) {
            holder.label("No lecture, tutorial or lab slots are listed for ${c.code} this semester.", 13f, Ui.TEXT, 12)
            return
        }
        holder.label("My Occ", 13f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        holder.label("One Occ per course. It sets your lecture and tutorial together.", 12f, Ui.DIM, 2)
        val detail = holder.label("", 12f, Ui.TEXT, 8)
        var chosen = occs.indexOf(previousOcc(c.code))   // -1 = none
        val sp = spinner(listOf("Choose your Occ") + occs.map { "Occ $it" }, chosen + 1) { pos ->
            chosen = pos - 1
            detail.text = if (pos > 0) describe(c, occs[pos - 1]) else ""
        }
        holder.addView(sp, holder.indexOfChild(detail), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        holder.btn("Add to my timetable") {
            if (chosen < 0) {
                Toast.makeText(this, "Choose your Occ", Toast.LENGTH_SHORT).show()
            } else {
                val occ = occs[chosen]
                save(c, occ)
                Toast.makeText(this, "Added ${c.code} Occ $occ", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /** What Occ N means: its lecture, tutorial and lab slots with room and lecturer. */
    private fun describe(c: Catalog.Course, occ: String): String {
        val mine = c.forOcc(occ)
        if (mine.isEmpty()) return ""
        return mine.joinToString("\n") { (k, ch) ->
            val times = ch.parts.joinToString(" + ") {
                "${Timetable.DAYS[it.day - 1]} ${Timetable.fmtMin(it.start)}–${Timetable.fmtMin(it.end)}" + if (it.room.isNotEmpty()) " ${it.room}" else ""
            }
            "${k.label}: $times" + ch.detail().let { if (it.isNotEmpty()) "  ($it)" else "" }
        }
    }

    /** Occ saved earlier for this course, so the list opens on it. */
    private fun previousOcc(code: String): String =
        Timetable.manual(this).firstOrNull { it.grp.startsWith(prefix(code)) }?.grp?.removePrefix(prefix(code))?.split('-')?.getOrNull(1) ?: ""

    private fun prefix(code: String) = "cat-$code-"

    private fun save(c: Catalog.Course, occ: String) {
        // replace what was added earlier for this course (own hand-made entries use other ids and stay)
        Timetable.manual(this).map { it.grp }.filter { it.startsWith(prefix(c.code)) }.distinct()
            .forEach { Timetable.deleteManual(this, it) }
        for ((kind, choice) in c.forOcc(occ)) {
            choice.parts.forEachIndexed { i, p ->
                Timetable.saveManual(
                    this, prefix(c.code) + kind.tag + "-" + occ + "-" + i, c.code, "${kind.label} Occ $occ",
                    listOf(p.day), p.start, p.end, kind, p.room,
                )
            }
        }
        if (Timetable.colourIndexOrNull(this, c.code) == null) Timetable.setColourIndex(this, c.code, Timetable.nextColourIndex(this))
        setMyGroup(c.code, occ)
    }

    /** "WIX1003:occ2, WIX1003:t3" in Settings > My group, so posts for other Occ / tutorial groups are not notified. */
    private fun setMyGroup(code: String, occ: String) {
        val p = Prefs(this)
        if (p.groupsOff) return
        val keep = p.myGroups.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.uppercase(Locale.ROOT).startsWith("$code:") }
        p.myGroups = (keep + "$code:occ$occ").joinToString(", ")
    }

    private fun spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner {
        val sp = Spinner(this)
        val ad = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            private fun style(v: View, bg: Int): View {
                (v as TextView).apply {
                    setTextColor(Ui.TEXT)
                    textSize = 14f
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setBackgroundColor(bg)
                }
                return v
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = style(super.getView(position, convertView, parent), 0)
                (v as TextView).text = (getItem(position) ?: "") + "  ▾"
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = style(super.getDropDownView(position, convertView, parent), Ui.BAR)
        }
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        sp.background = roundBg(Ui.BAR, Ui.BORDER, this)
        sp.setPopupBackgroundDrawable(roundBg(Ui.BAR, Ui.BORDER, this))
        sp.setSelection(selected, false)
        onSelect(selected)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return sp
    }
}
