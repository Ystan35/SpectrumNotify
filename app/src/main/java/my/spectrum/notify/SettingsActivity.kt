package my.spectrum.notify

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.webkit.CookieManager
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    companion object { const val EXTRA_OPEN = "open_section" }
    private lateinit var p: Prefs
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var alarmBtn: Button
    private var alarmHelp: View? = null
    private lateinit var profileStatus: TextView
    private lateinit var siteField: EditText
    private lateinit var tokenField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        val col = screen("Settings", navTo = SettingsActivity::class.java)
        var target: LinearLayout = col   // the open-able body of the current section

        fun add(key: String, title: String, help: String, value: String, hint: String = "", kind: Int = 0) {
            fields[key] = target.setting(title, help, value, hint, kind)
        }

        col.label("Changes are saved automatically when you leave this screen.", 12f, Ui.DIM, 4)

        // search: type a keyword, matching sections are listed; tapping one opens it
        var searchBox: EditText? = null
        var searchResults: LinearLayout? = null
        fun buildSearch() {
            val results = searchResults ?: return
            results.removeAllViews()
            val q = searchBox?.text?.toString()?.trim()?.lowercase() ?: return
            if (q.isEmpty()) return
            val hits = sections.filter { s ->
                q in s.name.lowercase() || s.contents.any { q in it.lowercase() } || s.key.contains(q)
            }
            if (hits.isEmpty()) {
                results.label("Nothing matches \"$q\".", 13f, Ui.DIM, 8)
                return
            }
            for (s in hits) {
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    isClickable = true
                    background = rippleBg(Ui.BAR, 0, this@SettingsActivity, 12)
                }
                if (s.icon != 0) {
                    r.addView(ImageView(this).apply {
                        setImageResource(s.icon)
                        setColorFilter(Ui.CYAN)
                    }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
                }
                r.addView(TextView(this).apply {
                    text = s.name.lowercase().replaceFirstChar { it.uppercase() }
                    textSize = 14f * Ui.fontScale
                    setTextColor(Ui.TEXT)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(TextView(this).apply {
                    text = "Open ▸"
                    textSize = 13f * Ui.fontScale
                    setTextColor(Ui.CYAN)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                r.setOnClickListener {
                    openSection(s, col)
                    searchBox?.setText("")
                    // highlight the section briefly so the user can spot where it opened
                    s.body.post {
                        s.body.background = roundBg(Ui.tint(Ui.CYAN, 30), Ui.CYAN, this@SettingsActivity, 12)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            s.body.background = null
                        }, 1200)
                    }
                }
                results.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
        }

        col.label("Search settings", 13f, Ui.TEXT, 12).setTypeface(null, android.graphics.Typeface.BOLD)
        searchBox = col.field("", "e.g. quiet, filter, theme, check", kind = 0)
        searchResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(searchResults, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        searchBox?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { buildSearch() }
        })

        target = accordion(col, "PROFILE", icon = R.drawable.ic_settings,
            contents = listOf("Site URL", "Log in / log out", "Token"))
        profileStatus = target.label("", 15f, Ui.TEXT, 8)
        target.label("Spectrum site URL", 12f, Ui.DIM, 12)
        siteField = target.field(p.site)
        target.btn("Log in with Spectrum") {
            p.site = siteField.text.toString()
            startActivity(Intent(this, LoginWebActivity::class.java))
        }
        target.btn("Log in with browser instead") {
            p.site = siteField.text.toString()
            Login.start(this)
        }
        target.label("Or paste a token manually", 13f, Ui.TEXT, 16)
        tokenField = target.field("", "Token", true)
        target.btn("Save token") {
            val t = tokenField.text.toString().trim()
            if (t.isNotEmpty()) {
                p.site = siteField.text.toString()
                tokenField.setText("")
                Login.saveAndVerify(this, t) { msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        refreshProfile()
                    }
                }
            }
        }
        target.btn("Log out") {
            p.clearToken()
            CookieManager.getInstance().removeAllCookies(null)
            NotifyLog.sys(this, "Logged out")
            refreshProfile()
        }

        target = accordion(col, "QUIET HOURS",
            "During these hours the app does not check Spectrum, so you are not woken up. Use the 24-hour clock: 0 = midnight, 13 = 1 pm.",
            icon = R.drawable.ic_cat_daily, contents = listOf("Quiet from", "Quiet until"))

        target = accordion(col, "DAILY MESSAGES",
            "Two short messages each day. Type the time as HH:MM, for example 07:05. Leave a box empty to turn that message off.",
            icon = R.drawable.ic_cat_news, contents = listOf("Morning summary", "Afternoon check-in"))
        add("summaryTime", "Morning summary", "Sent once each morning with today's classes and deadlines.", p.summaryTime, "07:05", 2)
        add("afternoonTime", "Afternoon check-in", "Sent in the afternoon about the rest of today's classes.", p.afternoonTime, "14:00", 2)

        target = accordion(col, "YOUR CLASSES", icon = R.drawable.ic_cat_class,
            contents = listOf("Classes to ignore", "Teams-recording courses"))
        add(
            "skipClasses", "Classes to ignore",
            "Use this when a course has classes for another group. Write the course code, then @ and the day. " +
                "WIX1003@Mon ignores only Monday's WIX1003. Separate several with commas: WIX1003@Mon, WIA2010@Tue. " +
                "Days: Mon Tue Wed Thu Fri Sat Sun. Without @day, every day of that course is ignored.",
            p.skipClasses, "WIX1003@Mon",
        )
        add(
            "manualCourses", "Courses marked by Teams recording",
            "Courses where attendance is taken by watching the Teams recording, not a live class link. " +
                "Write course codes separated by commas, for example WIA2010.",
            p.manualCourses, "WIA2010",
        )

        target = accordion(col, "OTHER GROUPS' POSTS", icon = R.drawable.ic_cat_material,
            contents = listOf("My Occ picker", "My group"))
        target.action(
            "Choose my Occ from a list",
            "Pick a course, then your Occ from a drop-down. It fills your lecture and tutorial times and the My group box below.",
        ) { startActivity(android.content.Intent(this, CatalogActivity::class.java)) }
        add(
            "myGroups", "My group",
            "Another lecturer's materials and announcements for a different Occ or tutorial group are hidden. " +
                "Leave this empty and the app works out your group from your class names. " +
                "To set it yourself write the course, a colon and the group, for example WIX1003:occ2, WIX1003:t3. " +
                "Write just occ2 to use it for every course. Write off to turn the filter off. " +
                "Posts that do not name any group are always shown.",
            p.myGroups, "WIX1003:occ2, WIX1003:t3",
        )

        target = accordion(col, "DEADLINES", icon = R.drawable.ic_cat_deadline,
            contents = listOf("Remind me before a deadline"))
        add(
            "deadlineHours", "Remind me before a deadline",
            "Number of hours before the deadline, separated by commas. 24,3 means one reminder a day before and another 3 hours before.",
            p.deadlineHours, "24,3",
        )

        target = accordion(col, "BACKGROUND CHECKS", icon = R.drawable.ic_refresh,
            contents = listOf("Check every (minutes)"))
        add(
            "pollMinutes", "Check Spectrum every (minutes)",
            "How often the app looks for new materials, announcements and notifications. The lowest is 10. " +
                "Below 15 the app uses alarms, so keep the battery setting on Unrestricted (see Tools) or Android may delay checks while the phone sleeps.",
            p.pollMinutes.toString(), "10", 1,
        )

        target = accordion(col, "APPEARANCE & ACCESSIBILITY",
            "Change how the app looks. Tap a chip to apply it right away — the screen redraws with your choice.",
            icon = R.drawable.ic_settings, contents = listOf("Colour mode", "Text size", "High contrast"), key = "appearance",
        )
        target.label("Colour mode", 15f, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
        target.chips(listOf("Phone", "Light", "Dark"), setOf(p.themeMode), false) { sel ->
            val v = sel.firstOrNull() ?: 0
            if (v != p.themeMode) { p.themeMode = v; recreate() }
        }
        target.label("Text size", 15f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        target.chips(listOf("Normal", "Large", "Extra large"), setOf(p.textScale), false) { sel ->
            val v = sel.firstOrNull() ?: 0
            if (v != p.textScale) { p.textScale = v; recreate() }
        }
        target.label("Easier to see and touch", 15f, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        val initial = buildSet {
            if (p.highContrast) add(0)
            if (p.bigTouch) add(1)
        }
        target.chips(listOf("High contrast", "Big touch targets"), initial, true) { sel ->
            val hc = 0 in sel
            val bt = 1 in sel
            if (hc != p.highContrast || bt != p.bigTouch) {
                p.highContrast = hc
                p.bigTouch = bt
                recreate()
            }
        }
        target.label("High contrast uses stronger text colours. Big touch targets make every button and box 56 dp tall.", 12f, Ui.DIM, 8)
        target = accordion(col, "TOOLS", icon = R.drawable.ic_add,
            contents = listOf("Save", "Check now", "Test notification", "Alarms", "Battery"))
        target.action("Save settings", "Saves everything above now.") {
            save(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        target.action("Check now", "Looks at Spectrum right away. A new notification appears in the list if something is new.") {
            save()
            Scheduler.pollNow(this)
            Toast.makeText(this, "Checking Spectrum...", Toast.LENGTH_SHORT).show()
        }
        target.action("Send test notification", "Sends one test message so you can see how notifications look and that tapping opens the app.") {
            Notifier.push(this, "Test", "Notifications are working.\nTap the button below to open Spectrum.", true, listOf(Pair("Open Spectrum", p.site)))
        }
        alarmBtn = target.action("Allow exact alarms", "Needed so class reminders arrive about 10 minutes before the class, on time.") {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        alarmHelp = target.getChildAt(target.childCount - 1)
        target.action("Battery settings", "Open the list, find Spectrum Notify and choose Unrestricted, so Android does not stop the background checks.") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Open battery settings manually", Toast.LENGTH_LONG).show()
            }
        }
        // not logged in yet: open the Profile section so the login buttons are in view
        if (p.token.isEmpty() && sections.isNotEmpty()) {
            sections[0].body.visibility = View.VISIBLE
            sections[0].arrow.text = "▾"
        }
        // deep link from the floating toolbox on home: open the appearance section directly
        if (intent.getStringExtra(EXTRA_OPEN) == "appearance") {
            sections.firstOrNull { it.key == "appearance" }?.let {
                it.body.visibility = View.VISIBLE
                it.arrow.text = "▾"
            }
        }
    }

    /** One settings section: its views plus the metadata the search and headers show. */
    class Section(
        val body: LinearLayout,
        val arrow: TextView,
        val name: String,
        val icon: Int,
        val contents: List<String>,
        val key: String,
    )

    private val sections = ArrayList<Section>()

    /** Opens one section and closes the others (shared by headers and search results). */
    private fun openSection(s: Section, col: LinearLayout) {
        android.transition.TransitionManager.beginDelayedTransition(col, android.transition.AutoTransition().setDuration(160))
        for (x in sections) { x.body.visibility = View.GONE; x.arrow.text = "▸" }
        s.body.visibility = View.VISIBLE
        s.arrow.text = "▾"
    }

    /** A tappable section header with icon, name and a one-line contents list; one open at a time. */
    private fun accordion(
        col: LinearLayout,
        title: String,
        intro: String = "",
        key: String = "",
        icon: Int = 0,
        contents: List<String> = emptyList(),
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Ui.BAR, Ui.BORDER, this@SettingsActivity, 14)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), 0, dp(14), dp(16))
        }
        val arrow = TextView(this).apply {
            text = "▸"
            textSize = 16f * Ui.fontScale
            setTextColor(Ui.DIM)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            contentDescription = "$title: ${contents.joinToString(", ")}"
        }
        if (icon != 0) {
            head.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(Ui.CYAN)
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(12) })
        }
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsActivity).apply {
                text = title.lowercase().replaceFirstChar { it.uppercase() }
                textSize = 15f * Ui.fontScale
                setTextColor(Ui.TEXT)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (contents.isNotEmpty()) addView(TextView(this@SettingsActivity).apply {
                text = contents.joinToString("  ·  ")
                textSize = 12f * Ui.fontScale
                setTextColor(Ui.DIM)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(arrow)
        val s = Section(body, arrow, title, icon, contents, key)
        head.setOnClickListener {
            if (body.visibility == View.VISIBLE) {
                // tapping the open section closes it again
                android.transition.TransitionManager.beginDelayedTransition(col, android.transition.AutoTransition().setDuration(160))
                body.visibility = View.GONE
                arrow.text = "▸"
            } else openSection(s, col)
        }
        card.addView(head)
        card.addView(body)
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        if (intro.isNotEmpty()) body.label(intro, 12f, Ui.DIM, 0)
        sections.add(s)
        return body
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        val vis = if (Scheduler.canExact(this)) View.GONE else View.VISIBLE
        alarmBtn.visibility = vis
        alarmHelp?.visibility = vis
        refreshProfile()
        fields["myGroups"]?.setText(p.myGroups)  // may have been set by the Occ list
    }

    override fun onPause() {
        super.onPause()
        save()  // settings are saved when you leave the screen
    }

    private fun refreshProfile() {
        val loggedIn = p.token.isNotEmpty()
        profileStatus.text = (if (loggedIn) "Logged in" + (if (p.userName.isNotEmpty()) " as ${p.userName}" else "") else "Not logged in") +
            "\nLast check: ${p.lastRun}"
        profileStatus.setTextColor(if (loggedIn) Ui.GREEN else Ui.RED)
    }

    private fun f(key: String) = fields[key]?.text?.toString()?.trim() ?: ""

    private fun save() {
        // Validate before storing: bad input keeps the old value and marks the box red,
        // so a typo can never silently kill the daily messages or the reminders.
        var ok = true
        fun bad(key: String) { fields[key]?.setBackgroundResource(0); fields[key]?.background = roundBg(Ui.tint(Ui.RED, 80), Ui.RED, this); ok = false }
        fun good(key: String) { fields[key]?.background = rippleBg(Ui.BAR, Ui.BORDER, this) }

        val qs = f("quietStart").toIntOrNull()
        if (qs == null || qs !in 0..23) bad("quietStart") else { good("quietStart"); p.quietStart = qs }
        val qe = f("quietEnd").toIntOrNull()
        if (qe == null || qe !in 0..23) bad("quietEnd") else { good("quietEnd"); p.quietEnd = qe }
        val pm = f("pollMinutes").toIntOrNull()
        if (pm == null) bad("pollMinutes") else { good("pollMinutes"); p.pollMinutes = maxOf(10, pm) }  // 10 min is the documented floor
        for (key in listOf("summaryTime", "afternoonTime")) {
            val v = f(key)
            if (v.isEmpty() || parseHm(v) != null) { good(key); if (key == "summaryTime") p.summaryTime = v else p.afternoonTime = v }
            else bad(key)
        }
        if (!ok) Toast.makeText(this, "Some boxes are red — fix them or the related messages will not work.", Toast.LENGTH_LONG).show()

        p.manualCourses = f("manualCourses")
        p.skipClasses = f("skipClasses")
        p.myGroups = f("myGroups")
        p.deadlineHours = f("deadlineHours")
        Scheduler.ensurePolling(this)
        Scheduler.scheduleAll(this)
    }
}
