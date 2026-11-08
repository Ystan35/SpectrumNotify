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
            setPadding(dp(12), dp(8), dp(8), dp(4))
            setBackgroundColor(Ui.BG)
        }
        input = bar.field("", "Ask Oli about classes, deadlines, the bus…").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_SEND || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { send(); true } else false
            }
        }
        (input.layoutParams as? LinearLayout.LayoutParams)?.let { it.width = 0; it.weight = 1f } ?: run {
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
            setPadding(dp(16), 0, dp(16), dp(10))
            setBackgroundColor(Ui.BG)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        show(Assistant.Reply(
            "Hi, I'm Oli! Ask me about your classes, deadlines, reminders or the UM shuttle. I answer only from what this app has synced, " +
                "and our chat stays on this phone.",
            suggestions = Assistant.EXAMPLES.take(4)))
        intent.getStringExtra("q")?.let { ask(it) }
    }

    private fun send() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        input.setText("")
        ask(q)
    }

    /** The last question asked here, for follow-ups; gone when the chat closes, like the rest of it. */
    private var lastQ: String? = null
    /** Oli just asked "Where from?" for a trip to this place. */
    private var askFromTo: String? = null

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

    /** [asked] = the question this answers, for the small "wrong answer?" link; null for Oli's own follow-ups and hidden questions. */
    private fun show(r: Assistant.Reply, asked: String? = null) {
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
        r.confirm?.let { e ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Add reminder", Emphasis.PRIMARY, Ui.SIGNAL) {
                Events.save(this, e)
                row.visibility = View.GONE
                show(Assistant.Reply("Added. I'll remind you then. You can change or delete it in Timetable.", source = "Your reminders on this phone"))
            })
            row.addView(button("Cancel", Emphasis.TERTIARY) { row.visibility = View.GONE; show(Assistant.Reply("Okay, nothing added.")) })
            add(row)
        }
        for (a in r.actions) add(button(a.label, Emphasis.SECONDARY) { a.run(this) })
        if (r.suggestions.isNotEmpty()) {
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
        // every answer: a small way to say it was wrong (r.feedback already has the bigger button)
        if (asked != null && r.feedback == null && !alarm) box.addView(tv("Wrong answer? Tell the developer", Type.CAPTION, Ui.DIM).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            minHeight = dp(Ui.touch(40))
            background = rippleBg(0, 0, this@AssistantActivity, Radius.SM)
            contentDescription = "Report a wrong answer to the developer"
            setOnClickListener {
                FeedbackActivity.open(this@AssistantActivity, "question",
                    "The assistant answered wrongly.\nI asked: \"$asked\"\nIt said: \"${r.text.take(300)}\"")
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        // Oli's avatar beside each reply
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(avatar(), LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8); topMargin = dp(2) })
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10); rightMargin = dp(8)
        })
        scrollDown()
        box.announceForAccessibility(r.text)
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
