package my.spectrum.notify

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.TextUtils
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Editor for the UM-links shortcut list shown in the floating toolbox:
 * one row per link (name + URL), add/remove rows, one Save.
 */
object LinksEditor {

    fun open(activity: Activity, onChanged: () -> Unit) {
        val dp = { n: Int -> (n * activity.resources.displayMetrics.density).toInt() }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(10), dp(24), 0) }

        class Row(val nameF: EditText, val urlF: EditText, val root: LinearLayout)

        val rows = ArrayList<Row>()
        fun addRow(name: String, url: String) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val textCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            fun field(hintText: String, value: String): EditText = EditText(activity).apply {
                setText(value); hint = hintText
                setTextSize(13f); setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM); setSingleLine()
                background = roundBg(Ui.BAR, Ui.BORDER, activity, 10)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            val nameF = field("Name (e.g. Maya)", name)
            val urlF = field("https://…", url)
            textCol.addView(nameF, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            textCol.addView(urlF, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
            val del = TextView(activity).apply {
                text = "✕"; textSize = 16f; setTextColor(Ui.RED); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                contentDescription = "Remove this link"
                setOnClickListener {
                    col.removeView(row)
                    rows.removeAll { it.root === row }
                }
            }
            row.addView(del, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            rows.add(Row(nameF, urlF, row))
        }

        for (l in Shortcuts.all(activity)) addRow(l.name, l.url)
        val addBtn = TextView(activity).apply {
            text = "+ Add a link"; textSize = 13f; setTextColor(Ui.CYAN); setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
            isClickable = true
            setOnClickListener { addRow("", "") }
        }
        col.addView(addBtn)

        val scroll = android.widget.ScrollView(activity).apply { addView(col) }
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.55).toInt().coerceAtLeast(dp(200)),
        )
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_DayNight)
            .setTitle("UM links")
            .setMessage("The shortcuts in the toolbox (toolbox button, bottom-right). Tap ✕ to remove a row.")
            .setView(scroll)
            .setPositiveButton("Save all") { _, _ ->
                val links = ArrayList<Shortcuts.Link>()
                for (r in rows) {
                    links.add(Shortcuts.Link(r.nameF.text.toString().trim(), r.urlF.text.toString().trim()))
                }
                Shortcuts.save(activity, links)
                Toast.makeText(activity, "UM links saved.", Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
