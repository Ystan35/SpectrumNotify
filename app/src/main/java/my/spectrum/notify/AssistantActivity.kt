package my.spectrum.notify

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/**
 * Ask: a chat with the on-device assistant (Assistant.kt). Questions and answers stay on this screen only and are
 * gone when it closes (rule 8). Opened from the chat button in Home's header.
 */
class AssistantActivity : BaseActivity() {
    private lateinit var col: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: android.widget.EditText

    /**
     * The last few messages, in process memory only (rule 8: never written to disk). A mistaken back-press,
     * rotation or a switch away and back within KEEP_MS shows them again; when the process dies, so do they.
     */
    private class Msg(
        val mine: Boolean, val text: String, val source: String = "",
        /** Oli's whole reply and the question it answered, so a re-shown answer keeps its buttons (Open Timetable, Wrong answer?). */
        val reply: Assistant.Reply? = null, val asked: String? = null,
    )
    private companion object {
        val history = ArrayList<Msg>()
        var lastQ: String? = null          // the last question, for follow-ups
        var askFromTo: String? = null      // Oli just asked "Where from?"
        var at = 0L
        const val KEEP_MS = 10 * 60 * 1000L
        fun trim() { while (history.size > 6) history.removeAt(0) }
        fun add(m: Msg) { history.add(m); at = System.currentTimeMillis(); trim() }
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = screen("Oli")
        scroll = col.parent as ScrollView
        val root = scroll.parent as LinearLayout

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // room below the field so its bottom border is never cut off by the disclaimer under the bar
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setBackgroundColor(Ui.BG)
            clipChildren = false
            clipToPadding = false
        }
        input = bar.field("", "Ask Oli about classes, deadlines, the bus…").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 1   // ellipsize on an EditText only applies to a single line
            ellipsize = android.text.TextUtils.TruncateAt.END   // a long placeholder ends in "…" instead of being cut mid-letter at large text
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { send(); true } else false
            }
        }
        (input.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = 0; it.weight = 1f; it.topMargin = 0 } ?: run {
            input.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_up)
            setColorFilter(onFill(Ui.SIGNAL))
            background = roundBg(Ui.SIGNAL, Ui.SIGNAL, this@AssistantActivity, Radius.PILL)
            contentDescription = "Send question"
            pressRipple(Radius.PILL, onFill(Ui.SIGNAL))
            setOnClickListener { send() }
        }, LinearLayout.LayoutParams(dp(Ui.touch(48)), dp(Ui.touch(48))).apply { leftMargin = dp(8) })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // always in view under the box, not one message that scrolls away
        root.addView(tv("Oli is an AI assistant and can make mistakes. Check important details on Spectrum.", Type.CAPTION, Ui.DIM).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(4), dp(16), dp(10))
            setBackgroundColor(Ui.BG)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // a chat re-opened within KEEP_MS continues where it left off; older or first-open starts fresh
        val keep = System.currentTimeMillis() - at < KEEP_MS
        if (!keep) { history.clear(); lastQ = null; askFromTo = null }
        show(Assistant.Reply(
            "Hi, I'm Oli! Ask me about your classes, deadlines, reminders or the UM shuttle. I answer only from what this app has synced, " +
                "and our chat stays on this phone.",
            suggestions = Assistant.EXAMPLES.take(4)))
        if (keep) for (m in history) if (m.mine) bubble(m.text, mine = true)
        else show(m.reply ?: Assistant.Reply(m.text, source = m.source), m.asked, restored = true)
        intent.getStringExtra("q")?.let { ask(it) }
    }

    private fun send() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        input.setText("")
        ask(q)
    }

    private fun ask(q: String) {
        val mine = bubble(q, mine = true)
        val wait = bubble("Oli is thinking…", mine = false)
        val prev = lastQ
        // the answer to "Where from?": "kk10" or "from kk10" becomes "from kk10 to kps"
        val dest = askFromTo
        val asked = if (dest != null && !Regex("""\bto\b""", RegexOption.IGNORE_CASE).containsMatchIn(q))
            (if (q.trim().startsWith("from ", true)) q.trim() else "from ${q.trim()}") + " to $dest" else q
        Thread {
            val r = Assistant.answer(this, asked, prev)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                col.removeView(wait)
                // a password is never kept, not even as context
                lastQ = if (r.maskQuestion) null else asked
                askFromTo = r.askFromTo
                if (r.maskQuestion) mine.text = "•••••• (hidden: it looked like a password)"
                add(Msg(true, if (r.maskQuestion) "••••••" else asked))
                add(Msg(false, r.text, r.source, r, if (r.maskQuestion) null else asked))
                show(r, if (r.maskQuestion) null else asked)
            }
        }.start()
    }

    private fun bubble(text: String, mine: Boolean): android.widget.TextView {
        val t = tv(text, Type.BODY_L, if (mine) onFill(Ui.SIGNAL) else Ui.TEXT).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundBg(if (mine) Ui.SIGNAL else Ui.BAR, if (mine) Ui.SIGNAL else Ui.BORDER, this@AssistantActivity, Radius.MD)
            setTextIsSelectable(!mine)
        }
        col.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = dp(10)
            if (mine) leftMargin = dp(48) else rightMargin = dp(32)
        })
        scrollDown()
        return t
    }

    /** Oli's own follow-up message, for example "Done." with an Undo button. */
    fun say(r: Assistant.Reply) = show(r)

    /** [asked] = the question this answers, for the small "wrong answer?" link; null for Oli's own follow-ups and hidden questions. */
    private fun show(r: Assistant.Reply, asked: String? = null, restored: Boolean = false) {
        // red only for an emergency; a caring reply's counselling number stays calm
        val alarm = r.numbers.any { it.second == "999" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = roundBg(Ui.BAR, if (alarm) Ui.RED else Ui.BORDER, this@AssistantActivity, Radius.MD)
        }
        box.addView(tv(r.text, Type.BODY_L, Ui.TEXT).apply { setTextIsSelectable(true) })
        if (r.source.isNotEmpty()) box.addView(tv(r.source, Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(6), 0, 0) })
        fun add(v: View) = box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // rule 9: tap to dial (the phone app opens with the number; nothing is called by itself)
        for ((who, num) in r.numbers) add(button("Call $who  $num", if (alarm) Emphasis.PRIMARY else Emphasis.SECONDARY, if (alarm) Ui.RED else Ui.CYAN) { dial(num) })
        if (r.moreNumbers.isNotEmpty()) {
            val more = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            for ((who, num) in r.moreNumbers) more.addView(button("$who  $num", Emphasis.SECONDARY) { dial(num) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            add(button("More numbers", Emphasis.TERTIARY) { more.visibility = View.VISIBLE }.also { b -> b.tag = more })
            box.addView(more)
        }
        // a re-shown answer (after a colour-mode switch or rotation) does not offer a reminder that may already be saved or cancelled
        (if (restored) null else r.confirm)?.let { e ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Add reminder", Emphasis.PRIMARY, Ui.SIGNAL) {
                Events.save(this, e)
                row.visibility = View.GONE
                show(Assistant.Reply("Added. I'll remind you then. You can change or delete it in Timetable.", source = "Your reminders on this phone"))
            })
            row.addView(button("Cancel", Emphasis.TERTIARY) { row.visibility = View.GONE; show(Assistant.Reply("Okay, nothing added.")) })
            add(row)
        }
        // actions (and the confirmed/undo ones) stay on a re-shown answer: Oli's applied flag stops a second run
        for (a in r.actions) add(button(a.label, Emphasis.SECONDARY) { a.run(this) })
        if (!restored && r.suggestions.isNotEmpty()) {
            val chips = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (s in r.suggestions) chips.addView(chip(s, false) { ask(s) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            add(chips)
        }
        // rule 11: only when the user taps, and they see the ticket before it is sent
        r.feedback?.let { q ->
            add(button("Send to the developer", Emphasis.TERTIARY) {
                FeedbackActivity.open(this, "question", "The assistant couldn't answer: \"$q\"")
            })
        }
        // ticket SN-TTW9E: every reply (except emergencies) gets the wrong-answer link under the box, left-aligned
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        side.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (!alarm && asked != null) side.addView(tv("Wrong answer? Tell the developer", Type.CAPTION, Ui.DIM).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START   // text hugs the reply; 48dp tap area
            setPadding(dp(4), dp(2), dp(4), dp(2))
            minHeight = dp(Ui.touch(48))
            background = rippleBg(0, 0, this@AssistantActivity, Radius.SM)
            // no separate contentDescription: the spoken name is the visible text (WCAG 2.5.3 Label in Name)
            setOnClickListener {
                FeedbackActivity.open(this@AssistantActivity, "question",
                    "The assistant answered wrongly.\nI asked: \"$asked\"\nIt said: \"${r.text.take(300)}\"")
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 0 })
        // Oli's avatar beside each reply
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(avatar(), LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8); topMargin = dp(2) })
        row.addView(side,LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10); rightMargin = dp(8)
        })
        scrollDown()
        if (!restored) box.announceForAccessibility(r.text)
    }

    private fun avatar() = android.widget.ImageView(this).apply {
        setImageResource(R.drawable.ic_oli)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = roundBg(Ui.HERO_B, Ui.HERO_B, this@AssistantActivity, Radius.PILL)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun dial(num: String) = try {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + num.replace(Regex("[^0-9+]"), ""))))
    } catch (_: Exception) {
        Toast.makeText(this, "Call $num", Toast.LENGTH_LONG).show()
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
}
