package my.spectrum.notify

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.Toast

/**
 * One deadline as a bottom sheet: View task, Mark as done, Hide (with Undo). Home's Due soon cards and the course
 * page both open it; [onChanged] redraws the caller after done or hide.
 */
fun Activity.showDueSheet(d: Checker.DueItem, onChanged: () -> Unit): android.app.AlertDialog {
    var dialog: android.app.AlertDialog? = null
    val isDone = Checker.isTaskDone(this, d.name, d.eventId, d.ts)
    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(20))
        background = GradientDrawable().apply {
            cornerRadius = dp(Radius.LG).toFloat()
            setColor(Ui.RAISED)
            setStroke(dp(1), Ui.RAISED_BORDER)
        }
    }
    val left = d.ts - System.currentTimeMillis() / 1000
    val col = if (left < 86400) Ui.RED else if (left < 3 * 86400) Ui.ORANGE else Ui.GREEN
    sheet.addView(tv("Due in " + relShort(left) + " · " + dayLabel(d.ts) + ", " + hhmm(d.ts), Type.LABEL, col, true))
    sheet.addView(tv(d.name, Type.HEADLINE, Ui.TEXT, true).apply { setPadding(0, dp(4), 0, 0); maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
    if (d.course.isNotEmpty()) sheet.addView(tv(Shorts.display(this, d.course), Type.LABEL, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) })
    if (isDone) sheet.addView(iconText(R.drawable.ic_check, "Marked as done", Type.CAPTION, Ui.GREEN, true).apply { setPadding(0, dp(8), 0, 0) })

    fun row(title: String, subText: String, go: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundBg(Ui.BAR, Ui.BORDER, this@showDueSheet, Radius.MD)
            pressRipple()
        }
        r.addView(tv(title, Type.BODY_L, Ui.TEXT, true))
        r.addView(tv(subText, Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(2), 0, 0); maxLines = 3 })
        r.isClickable = true
        r.setOnClickListener { dialog?.dismiss(); go() }
        sheet.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }
    if (d.url.isNotEmpty()) row("View task", d.url) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.url))) } catch (_: Exception) { Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show() } }
    row(
        if (isDone) "Mark as not done" else "Mark as done",
        if (isDone) "Puts it back on your active list, with reminders" else "Keeps it on the list, struck through; no more reminders",
    ) {
        Checker.setTaskDone(this, d.name, !isDone, d.eventId, d.ts)
        onChanged()
    }
    row("Hide this (wrong?)", "Removes it from Home, Notifications and reminders — you can undo right after") {
        // by id, and by name so its reminders leave the Notifications list too
        if (d.eventId != 0L) NotifyLog.hideEvent(this, "dl:" + d.eventId, d.name, d.course, d.ts)
        NotifyLog.hideEvent(this, Checker.hiddenNameKey(d.name), d.name, d.course, d.ts)
        onChanged()
        undoBar("Deadline hidden") {
            if (d.eventId != 0L) NotifyLog.unhideEvent(this, "dl:" + d.eventId)
            NotifyLog.unhideEvent(this, Checker.hiddenNameKey(d.name))
            onChanged()
        }
    }
    val dlg = android.app.AlertDialog.Builder(this, Ui.dialogTheme)
        .setView(sheet)
        .create()
    dialog = dlg
    dlg.show()
    dlg.window?.setLayout(
        android.view.WindowManager.LayoutParams.MATCH_PARENT,
        android.view.WindowManager.LayoutParams.WRAP_CONTENT,
    )
    dlg.window?.setGravity(android.view.Gravity.BOTTOM)
    dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
    dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    dlg.window?.setDimAmount(0.55f)
    return dlg
}
