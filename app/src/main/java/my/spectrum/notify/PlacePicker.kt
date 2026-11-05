package my.spectrum.notify

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * The one way to choose a UM place: a field that looks like the app's dropdowns and opens a searchable list,
 * grouped (faculties and buildings, residential colleges, bus stops), with short names ("Raja Dr Nazrin Shah RC
 * · KK12"). Used by the Shuttle planner, Settings → Home stop and the class editor's nearest bus stop.
 */

/** A place's name with the short names it is searched by that are not in the name already. */
fun ShuttleModel.Place.label(): String =
    name + aka.filter { a -> !name.contains(a, true) && a.none { it == ' ' } }
        .takeIf { it.isNotEmpty() }?.joinToString(", ", " · ").orEmpty()

/**
 * The field: shows [text], opens [pickPlace] and then shows the place chosen. [none] adds a first row
 * ("Not set", "Auto") that picks "" (its text is shown when chosen).
 */
fun Activity.pickerField(
    text: String, title: String, only: (ShuttleModel.Place) -> Boolean = { true }, none: String? = null,
    onPick: (String) -> Unit,
): TextView =
    iconText(0, text, Type.BODY, Ui.TEXT, trailing = R.drawable.ic_expand_more).apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
        minHeight = dp(Ui.touch(48))
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        background = rippleBg(Ui.BAR, Ui.BORDER, this@pickerField, Radius.SM)
        isClickable = true
        contentDescription = "$title: $text. Tap to choose."
        setOnClickListener {
            pickPlace(title, only, none) { id ->
                val shown = if (id.isEmpty()) none ?: "" else Shuttle.data(this@pickerField).places[id]?.label() ?: id
                if (shown.isNotEmpty()) { this.text = shown; contentDescription = "$title: $shown. Tap to choose." }
                onPick(id)
            }
        }
    }

/** Which group a place is listed under in the picker. */
private fun groupOf(p: ShuttleModel.Place): Int {
    val n = p.name.lowercase(Locale.ROOT)
    return when {
        // a college is a college, with or without a stop of its own (KK2 has none: bus to KK1, then walk)
        " rc" in "$n " || "residential" in n || "kolej kediaman" in n || "international house" in n -> 1
        !p.isStop || n.startsWith("faculty") || n.startsWith("academy") || n.startsWith("fakulti") || "pasum" in n -> 0
        else -> 2
    }
}

/** A searchable list of the places that pass [only], grouped: faculties and buildings, residential colleges, other stops. */
fun Activity.pickPlace(
    title: String, only: (ShuttleModel.Place) -> Boolean = { true }, none: String? = null, onPick: (String) -> Unit,
) {
    val d = Shuttle.data(this)
    val all = Shuttle.placesForPicker(this).filter(only)
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
    val search = android.widget.EditText(this).apply {
        hint = "Search, e.g. FSKTM, KK12, library"
        setSingleLine()
        textSize = Type.BODY_L * Ui.fontScale; setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM)
        background = roundBg(Ui.BAR, Ui.BORDER, this@pickPlace, Radius.SM)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    col.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val scroll = android.widget.ScrollView(this).apply { addView(list) }
    col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)).apply { topMargin = dp(8) })
    val dlg = android.app.AlertDialog.Builder(this, Ui.dialogTheme)
        .setTitle(title)
        .setView(col)
        .setNegativeButton("Cancel", null)
        .create()
    val home = Shuttle.homeId(this)
    val heads = listOf("Faculties and buildings", "Residential colleges", "Bus stops")
    fun fill(q: String) {
        list.removeAllViews()
        val words = q.lowercase(Locale.ROOT).split(" ").filter { it.isNotBlank() }
        // each word matches the start of a word of the name ("api" finds "(API)", not "Rapid"), or the id
        val hits = all.filter { p ->
            val parts = p.name.lowercase(Locale.ROOT).split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }
            val akas = p.aka.flatMap { it.lowercase(Locale.ROOT).split(" ") }
            words.all { w -> parts.any { it.startsWith(w) } || akas.any { it.startsWith(w) } || p.id.lowercase(Locale.ROOT).startsWith(w) }
        }
        fun row(label: String, id: String) {
            list.addView(tv(label, Type.BODY_L, Ui.TEXT).apply {
                setPadding(dp(8), dp(12), dp(8), dp(12))
                background = rippleBg(Ui.BG, 0, this@pickPlace, Radius.SM)
                isClickable = true
                setOnClickListener { dlg.dismiss(); onPick(id) }
            })
        }
        if (words.isEmpty()) {
            if (none != null) row(none, "")
            d.places[home]?.takeIf(only)?.let { list.label("Your home stop", Type.CAPTION, Ui.CYAN, 8); row(it.label(), it.id) }
        }
        for (g in 0..2) {
            val inG = hits.filter { groupOf(it) == g }
            if (inG.isEmpty()) continue
            list.label(heads[g], Type.CAPTION, Ui.CYAN, 12).setTypeface(null, Typeface.BOLD)
            inG.forEach { row(it.label(), it.id) }
        }
        if (hits.isEmpty()) list.label("Nothing matches \"$q\".", Type.LABEL, Ui.DIM, 12)
    }
    search.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(e: android.text.Editable?) = fill(e?.toString() ?: "")
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    fill("")
    dlg.show()
}
