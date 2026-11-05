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
 * a name and a web address. Tap a link to edit it, drag its handle to reorder; removing or resetting offers Undo.
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

    /** The handle of row [from]: the row follows your finger, the others make room, and [onDrop] gets the new position. */
    private fun dragger(row: LinearLayout, from: Int, rows: List<LinearLayout>, onDrop: (Int) -> Unit) = object : android.view.View.OnTouchListener {
        private var startY = 0f
        private var to = from
        private fun slot() = row.height + ((row.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0)

        private fun settle() {
            rows.forEach { it.animate().cancel(); it.translationY = 0f; it.translationZ = 0f }
        }

        override fun onTouch(v: android.view.View, ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    startY = ev.rawY
                    to = from
                    row.translationZ = row.context.dp(6).toFloat()
                    row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = (ev.rawY - startY).coerceIn(-(from * slot()).toFloat(), ((rows.size - 1 - from) * slot()).toFloat())
                    row.translationY = dy
                    val next = (from + Math.round(dy / slot())).coerceIn(0, rows.size - 1)
                    if (next != to) {
                        to = next
                        rows.forEachIndexed { i, r ->
                            if (r === row) return@forEachIndexed
                            val shift = when {
                                i in (from + 1)..to -> -slot()
                                i in to until from -> slot()
                                else -> 0
                            }
                            r.animate().translationY(shift.toFloat()).setDuration(120).start()
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP -> { settle(); onDrop(to) }
                android.view.MotionEvent.ACTION_CANCEL -> settle()
            }
            return true
        }
    }

    /** Fills [box] with the list and its buttons; redraws itself after every change. */
    fun renderSection(activity: Activity, box: LinearLayout) {
        box.removeAllViews()
        val links = Shortcuts.all(activity)
        fun redraw() = renderSection(activity, box)
        fun saveAndRedraw(list: List<Shortcuts.Link>) { Shortcuts.save(activity, list); redraw() }

        val rows = ArrayList<LinearLayout>()
        if (links.isEmpty()) box.label("No links yet. Add one below, or reset to the defaults.", Type.LABEL, Ui.DIM, 8)
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
            fun moveTo(to: Int) {
                val m = links.toMutableList()
                m.add(to, m.removeAt(i))
                saveAndRedraw(m)
            }
            row.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_drag_handle)
                setColorFilter(Ui.DIM)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                minimumWidth = activity.dp(Ui.touch(48)); minimumHeight = activity.dp(Ui.touch(48))
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnTouchListener(dragger(row, i, rows) { to -> if (to != i) moveTo(to) })
            })
            // dragging is no use to a screen reader: the same moves as actions on the row
            val upId = 0x7f0b0001; val downId = 0x7f0b0002
            row.accessibilityDelegate = object : android.view.View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (i > 0) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(upId, "Move ${l.name} up"))
                    if (i < links.size - 1) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(downId, "Move ${l.name} down"))
                }
                override fun performAccessibilityAction(host: android.view.View, action: Int, args: android.os.Bundle?): Boolean = when (action) {
                    upId -> { moveTo(i - 1); true }
                    downId -> { moveTo(i + 1); true }
                    else -> super.performAccessibilityAction(host, action, args)
                }
            }
            rows.add(row)
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
        box.action("Reset to defaults", "Puts back Spectrum, SpectrumExam, Maya, ePay, MySiswa, STAREcs and the academic calendar, and removes your other links. You can undo right after.") {
            val before = Shortcuts.all(activity)
            saveAndRedraw(Shortcuts.defaults())
            activity.undoBar("Links reset") { saveAndRedraw(before) }
        }
    }

    /** Edit link [index] (null = add a new one). Invalid input keeps the dialog open with the reason on the field. */
    fun editOne(activity: Activity, index: Int?, onChanged: () -> Unit) {
        val list = Shortcuts.all(activity)
        val old = index?.let { list.getOrNull(it) }
        val dp = { n: Int -> activity.dp(n) }
        var chosen = old?.icon ?: ""
        // an icon you tapped stays; otherwise it follows what you type (a Drive slides link gets the slides icon)
        var pickedByHand = old != null
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
                    setOnClickListener { chosen = key; pickedByHand = true; paint() }
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
        val follow = object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                if (pickedByHand) return
                chosen = Shortcuts.guessIcon(nameF.text.toString(), urlF.text.toString())
                paint()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        nameF.addTextChangedListener(follow)
        urlF.addTextChangedListener(follow)

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
