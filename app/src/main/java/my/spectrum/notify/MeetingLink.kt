package my.spectrum.notify

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

/** How a course's meeting links are split: lectures and tutorials apart when its timetable has both, else one link. */
fun Activity.meetingKinds(code: String): List<String> =
    if (AttendancePicker.hasBothKinds(this, code)) listOf("lec", "tut") else listOf("")

fun meetingKindName(kind: String) = when (kind) { "lec" -> "Lectures"; "tut" -> "Tutorials"; else -> "Every class" }

private fun preferLecOf(kind: String): Boolean? = when (kind) { "lec" -> true; "tut" -> false; else -> null }

/** The link Join class opens for [kind]: yours, else the one found on Spectrum; "" = none. */
fun Activity.meetingLinkFor(code: String, kind: String): String =
    Links.linksForCourse(this, code, 0L, preferLec = preferLecOf(kind), online = true).first

/**
 * Paste or change your meeting link for [code]'s [kind] ("lec", "tut" or "" = every class). [ask]: you just chose
 * Online and the class has no link, so the dialog says why it came up and Cancel reads "Later".
 */
fun Activity.editMeetingLink(code: String, kind: String, ask: Boolean = false, after: () -> Unit) {
    val field = EditText(this).apply {
        hint = "https://teams.microsoft.com/…"
        setText(Timetable.meetingLinkOf(this@editMeetingLink, code, preferLecOf(kind)))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
    }
    val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field, LinearLayout.LayoutParams(-1, -2)) }
    val what = if (kind.isEmpty()) code else "$code ${meetingKindName(kind).lowercase()}"
    val dlg = AlertDialog.Builder(this, Ui.dialogTheme)
        .setTitle("Meeting link for $what")
        .setMessage(
            if (ask) "No meeting link was found for this class. Paste the Teams, Zoom or Meet link so Join class works."
            else "Join class opens this link. Clear it to use the one the app finds on Spectrum."
        )
        .setView(box)
        .setPositiveButton("Save", null)
        .setNegativeButton(if (ask) "Later" else "Cancel") { _, _ -> after() }
        .setOnCancelListener { after() }
        .show()
    // set after show, so a bad link keeps the dialog open instead of closing it
    dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
        val url = field.text.toString().trim()
        if (url.isNotEmpty() && !Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(url)) {
            Toast.makeText(this, "Paste a link that starts with https://", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        Timetable.setMeetingLink(this, code, url, kind)
        Timetable.changed(this)
        dlg.dismiss()
        after()
    }
}

/** After choosing Online: asks for a link for each kind of [code]'s classes that has none, one dialog after another. */
fun Activity.askMissingMeetingLinks(code: String, kinds: List<String> = meetingKinds(code), after: () -> Unit) {
    val missing = kinds.firstOrNull { meetingLinkFor(code, it).isEmpty() } ?: return after()
    editMeetingLink(code, missing, ask = true) { askMissingMeetingLinks(code, kinds - missing, after) }
}
