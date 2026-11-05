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
        MyPlaces.isMine(p.id) -> 3
        // a college is a college, with or without a stop of its own (KK2 has none: bus to KK1, then walk)
        " rc" in "$n " || "residential" in n || "kolej kediaman" in n || "international house" in n -> 1
        !p.isStop || n.startsWith("faculty") || n.startsWith("academy") || n.startsWith("fakulti") || "pasum" in n -> 0
        else -> 2
    }
}

/**
 * A searchable list of the places that pass [only], grouped: your places, faculties and buildings, residential
 * colleges, other stops. When your own places can be picked here, a search also looks on the map, and you can
 * add a place yourself (pin on a map).
 */
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
    val heads = listOf("Faculties and buildings", "Residential colleges", "Bus stops", "Your places")
    // your places only where this picker takes any place (not Settings' home stop, which must be a stop)
    val canAdd = only(ShuttleModel.Place(MyPlaces.PREFIX, "", false, "", "", 0.0, 0.0))
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var seq = 0
    fun picked(id: String) { dlg.dismiss(); onPick(id) }
    fun addYourself(name: String, lat: Double?, lng: Double?) = editPlace(name, lat, lng, null) { picked(it) }
    lateinit var fill: (String) -> Unit
    fill = { q ->
        list.removeAllViews()
        val words = q.lowercase(Locale.ROOT).split(" ").filter { it.isNotBlank() }
        // each word matches the start of a word of the name ("api" finds "(API)", not "Rapid"), or the id
        val hits = all.filter { p ->
            val parts = p.name.lowercase(Locale.ROOT).split(Regex("[^a-z0-9']+")).filter { it.isNotEmpty() }
            val akas = p.aka.flatMap { it.lowercase(Locale.ROOT).split(" ") }
            words.all { w -> parts.any { it.startsWith(w) } || akas.any { it.startsWith(w) } || p.id.lowercase(Locale.ROOT).startsWith(w) }
        }
        fun row(label: String, id: String, sub: String = "", onClick: () -> Unit = { picked(id) }) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                background = rippleBg(Ui.BG, 0, this@pickPlace, Radius.SM)
                isClickable = true
                addView(tv(label, Type.BODY_L, Ui.TEXT))
                if (sub.isNotEmpty()) addView(tv(sub, Type.CAPTION, Ui.DIM).apply { maxLines = 2 })
                contentDescription = if (sub.isEmpty()) label else "$label, $sub"
                setOnClickListener { onClick() }
                // your own places: hold to move, rename or delete
                if (MyPlaces.isMine(id)) setOnLongClickListener {
                    manageMyPlace(d.places[id] ?: return@setOnLongClickListener true) {
                        dlg.dismiss(); pickPlace(title, only, none, onPick)
                    }
                    true
                }
            })
        }
        if (words.isEmpty() && canAdd) row("+ Add a place of your own", "", "Search the map or drop a pin") { addYourself("", null, null) }
        if (words.isEmpty()) {
            if (none != null) row(none, "")
            d.places[home]?.takeIf(only)?.let { list.label("Your home stop", Type.CAPTION, Ui.CYAN, 8); row(it.label(), it.id) }
        }
        for (g in listOf(3, 0, 1, 2)) {
            val inG = hits.filter { groupOf(it) == g }
            if (inG.isEmpty()) continue
            list.label(heads[g], Type.CAPTION, Ui.CYAN, 12).setTypeface(null, Typeface.BOLD)
            inG.forEach { row(it.label(), it.id) }
        }
        if (hits.isEmpty() && !canAdd) list.label("Nothing matches \"$q\".", Type.LABEL, Ui.DIM, 12)
        if (words.isNotEmpty() && canAdd) {
            // places outside the UM list: the map search (after a pause in typing), then "add it yourself"
            val mine = ++seq
            val head = list.label("Places on the map", Type.CAPTION, Ui.CYAN, 12).apply { setTypeface(null, Typeface.BOLD) }
            val status = list.label(if (q.trim().length < 3) "Type a little more to search the map." else "Searching the map…", Type.LABEL, Ui.DIM, 4)
            val at = list.indexOfChild(status)
            row("+ Add \"${q.trim()}\" yourself", "", "Drop a pin on the map") { addYourself(q.trim(), null, null) }
            if (q.trim().length >= 3) handler.postDelayed({
                if (mine == seq) searchMap(q.trim()) { found ->
                    if (mine != seq) return@searchMap
                    list.removeView(status)
                    if (found.isEmpty()) list.addView(tv("Nothing found on the map. Add it yourself below.", Type.LABEL, Ui.DIM).apply { setPadding(0, dp(4), 0, 0) }, at)
                    // insert the results where "Searching…" was, above "Add yourself"
                    val before = list.childCount
                    found.forEach { f -> row(f.name, "", f.detail) { addYourself(f.name, f.lat, f.lng) } }
                    val added = (before until list.childCount).map { list.getChildAt(it) }
                    added.forEach { list.removeView(it) }
                    added.forEachIndexed { i, v -> list.addView(v, at + i) }
                }
            }, 500)
            head.contentDescription = "Places on the map"
        }
    }
    search.addTextChangedListener(object : android.text.TextWatcher {
        override fun afterTextChanged(e: android.text.Editable?) = fill(e?.toString() ?: "")
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    fill("")
    dlg.show()
}
