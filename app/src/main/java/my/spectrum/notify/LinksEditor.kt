package my.spectrum.notify

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Settings → UM links: the shortcut list shown in the floating toolbox. Each link has a line icon,
 * a name and a web address. Tap a link to edit it, the up/down arrows to reorder; removing or resetting offers Undo.
 */
object LinksEditor {

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun Activity.icon(key: String, sizeDp: Int, tint: Int): ImageView = ImageView(this).apply {
        setImageResource(Shortcuts.iconRes(key))
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Fills [box] with the list and its buttons; redraws itself after every change. */
    fun renderSection(activity: Activity, box: LinearLayout) {
        box.removeAllViews()
        val links = Shortcuts.all(activity)
        fun redraw() = renderSection(activity, box)
        fun saveAndRedraw(list: List<Shortcuts.Link>) { Shortcuts.save(activity, list); redraw() }

        if (links.isEmpty()) box.label("No links yet. Add one below, or reset to the UM defaults.", Type.LABEL, Ui.DIM, 8)
        links.forEachIndexed { i, l ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = activity.dp(Ui.touch(56))
                setPadding(activity.dp(12), activity.dp(6), activity.dp(4), activity.dp(6))
                background = rippleBg(Ui.BAR, Ui.BORDER, activity, Radius.MD)
                isClickable = true
                contentDescription = "${l.name}, ${l.url}. Tap to edit."
                setOnClickListener { editOne(activity, i) { redraw() } }
            }
            row.addView(activity.icon(l.icon, 24, Ui.CYAN))
            val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(activity.dp(12), 0, activity.dp(4), 0) }
            text.addView(TextView(activity).apply {
                this.text = l.name; textSize = Type.BODY_L * Ui.fontScale; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            text.addView(TextView(activity).apply {
                this.text = l.url.removePrefix("https://").removePrefix("http://").trimEnd('/')
                textSize = Type.CAPTION * Ui.fontScale; setTextColor(Ui.DIM); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
            fun move(up: Boolean, to: Int, enabled: Boolean) = android.widget.ImageView(activity).apply {
                setImageResource(R.drawable.ic_expand_more)
                rotation = if (up) 180f else 0f
                setColorFilter(if (enabled) Ui.CYAN else Ui.tint(Ui.DIM, 0x55))
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                minimumWidth = activity.dp(Ui.touch(48)); minimumHeight = activity.dp(Ui.touch(48))
                isEnabled = enabled; isClickable = enabled
                contentDescription = "Move ${l.name} " + (if (to < i) "up" else "down")
                setOnClickListener {
                    val m = links.toMutableList()
                    m.add(to, m.removeAt(i))
                    saveAndRedraw(m)
                }
            }
            row.addView(move(true, i - 1, i > 0))
            row.addView(move(false, i + 1, i < links.size - 1))
            box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = activity.dp(8) })
        }

        box.btn("+ Add a link") { editOne(activity, null) { redraw() } }
        val courses = Courses.all(activity).filter { it.id > 0L && it.url.isNotEmpty() }
        if (courses.isNotEmpty()) {
            box.btn("+ Add a Spectrum course page") {
                val names = courses.map { c -> Shorts.display(activity, c.code) + if (c.name.isNotEmpty()) " · ${c.name}" else "" }
                AlertDialog.Builder(activity, Ui.dialogTheme)
                    .setTitle("Add a course page")
                    .setItems(names.toTypedArray()) { _, which ->
                        val c = courses[which]
                        saveAndRedraw(Shortcuts.all(activity) + Shortcuts.Link(Shorts.display(activity, c.code), c.url, "book"))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        box.action("Reset to UM defaults", "Puts back Maya, ePay, STAREcs and MySiswa and removes your other links. You can undo right after.") {
            val before = Shortcuts.all(activity)
            saveAndRedraw(Shortcuts.defaults())
            activity.undoBar("UM links reset") { saveAndRedraw(before) }
        }
    }

    /** Edit link [index] (null = add a new one). Invalid input keeps the dialog open with the reason on the field. */
    fun editOne(activity: Activity, index: Int?, onChanged: () -> Unit) {
        val list = Shortcuts.all(activity)
        val old = index?.let { list.getOrNull(it) }
        val dp = { n: Int -> activity.dp(n) }
        var chosen = old?.icon ?: ""
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }

        col.label("Icon", Type.LABEL, Ui.TEXT, 4).setTypeface(null, Typeface.BOLD)
        val cells = ArrayList<Pair<String, ImageView>>()
        fun paint() = cells.forEach { (k, v) ->
            val on = k == chosen
            v.setColorFilter(if (on) onAccent() else Ui.TEXT)
            v.background = if (on) roundBg(Ui.CYAN, 0, activity, Radius.MD) else rippleBg(Ui.BAR, Ui.BORDER, activity, Radius.MD)
            v.contentDescription = Shortcuts.iconLabel(k) + if (on) ", selected" else ""
        }
        Shortcuts.ICONS.chunked(5).forEach { chunk ->
            val r = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { (key, res) ->
                val v = ImageView(activity).apply {
                    setImageResource(res)
                    scaleType = ImageView.ScaleType.CENTER
                    isClickable = true
                    setOnClickListener { chosen = key; paint() }
                }
                cells.add(Pair(key, v))
                r.addView(v, LinearLayout.LayoutParams(0, dp(Ui.touch(48)), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            repeat(5 - chunk.size) { r.addView(android.view.View(activity), LinearLayout.LayoutParams(0, dp(Ui.touch(48)), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            col.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        paint()

        fun field(hintText: String, value: String, uri: Boolean): EditText = EditText(activity).apply {
            setText(value); hint = hintText
            textSize = Type.BODY_L * Ui.fontScale; setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM); setSingleLine()
            if (uri) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = roundBg(Ui.BAR, Ui.BORDER, activity, Radius.SM)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(Ui.touch(48))
        }
        col.label("Name", Type.LABEL, Ui.TEXT, 14).setTypeface(null, Typeface.BOLD)
        val nameF = field("e.g. Library", old?.name ?: "", false)
        col.addView(nameF, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        col.label("Web address", Type.LABEL, Ui.TEXT, 12).setTypeface(null, Typeface.BOLD)
        val urlF = field("e.g. umlib.um.edu.my", old?.url ?: "", true)
        col.addView(urlF, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        col.label("https:// is added for you if you leave it out.", Type.CAPTION, Ui.DIM, 4)

        val scroll = android.widget.ScrollView(activity).apply { addView(col) }
        val b = AlertDialog.Builder(activity, Ui.dialogTheme)
            .setTitle(if (old == null) "Add a link" else "Edit link")
            .setView(scroll)
            .setPositiveButton("Save", null)   // set below, so a mistake keeps the dialog open
            .setNegativeButton("Cancel", null)
        if (old != null && index != null) b.setNeutralButton("Remove") { _, _ ->
            val before = Shortcuts.all(activity)
            Shortcuts.save(activity, before.filterIndexed { i, _ -> i != index })
            onChanged()
            activity.undoBar("${old.name} removed") { Shortcuts.save(activity, before); onChanged() }
        }
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameF.text.toString().trim()
                val url = Shortcuts.normalizeUrl(urlF.text.toString())
                when {
                    name.isEmpty() -> { nameF.error = "Give the link a name"; nameF.requestFocus() }
                    url == null -> { urlF.error = "This is not a web address (e.g. umlib.um.edu.my)"; urlF.requestFocus() }
                    else -> {
                        val link = Shortcuts.Link(name, url, chosen.ifEmpty { Shortcuts.guessIcon(name, url) })
                        val cur = Shortcuts.all(activity).toMutableList()
                        if (index != null && index in cur.indices) cur[index] = link else cur.add(link)
                        Shortcuts.save(activity, cur)
                        dlg.dismiss()
                        onChanged()
                    }
                }
            }
        }
        dlg.show()
    }
}
