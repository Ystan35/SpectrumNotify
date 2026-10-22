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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
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
        val col = screen("Settings")
        var target: LinearLayout = col   // the open-able body of the current section

        fun add(key: String, title: String, help: String, value: String, hint: String = "", kind: Int = 0) {
            fields[key] = target.setting(title, help, value, hint, kind)
        }

        col.label("Changes are saved automatically when you leave this screen.", 12f, Ui.DIM, 4)

        target = accordion(col, "PROFILE")
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

        target = accordion(col, 
            "QUIET HOURS",
            "During these hours the app does not check Spectrum, so you are not woken up. Use the 24-hour clock: 0 = midnight, 13 = 1 pm."
        )
        add("quietStart", "Quiet from (hour)", "Start of the quiet period. Example: 1 means 1 am.", p.quietStart.toString(), "1", 1)
        add("quietEnd", "Quiet until (hour)", "End of the quiet period. Example: 7 means 7 am.", p.quietEnd.toString(), "7", 1)

        target = accordion(col, "DAILY MESSAGES", "Two short messages each day. Type the time as HH:MM, for example 07:05. Leave a box empty to turn that message off.")
        add("summaryTime", "Morning summary", "Sent once each morning with today's classes and deadlines.", p.summaryTime, "07:05", 2)
        add("afternoonTime", "Afternoon check-in", "Sent in the afternoon about the rest of today's classes.", p.afternoonTime, "14:00", 2)

        target = accordion(col, "YOUR CLASSES")
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

        target = accordion(col, "OTHER GROUPS' POSTS")
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

        target = accordion(col, "DEADLINES")
        add(
            "deadlineHours", "Remind me before a deadline",
            "Number of hours before the deadline, separated by commas. 24,3 means one reminder a day before and another 3 hours before.",
            p.deadlineHours, "24,3",
        )

        target = accordion(col, "BACKGROUND CHECKS")
        add(
            "pollMinutes", "Check Spectrum every (minutes)",
            "How often the app looks for new materials, announcements and notifications. The lowest is 10. " +
                "Below 15 the app uses alarms, so keep the battery setting on Unrestricted (see Tools) or Android may delay checks while the phone sleeps.",
            p.pollMinutes.toString(), "10", 1,
        )

        target = accordion(col, "TOOLS")
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
            sections[0].first.visibility = View.VISIBLE
            sections[0].second.text = "▾"
        }
    }

    private val sections = ArrayList<Pair<LinearLayout, TextView>>()

    /** A tappable section header; its content is hidden until the header is pressed. Only one section is open at a time. */
    private fun accordion(col: LinearLayout, title: String, intro: String = ""): LinearLayout {
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
            textSize = 16f
            setTextColor(Ui.DIM)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            contentDescription = title
        }
        head.addView(TextView(this).apply {
            text = title.lowercase().replaceFirstChar { it.uppercase() }
            textSize = 15f
            setTextColor(Ui.TEXT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(arrow)
        head.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            android.transition.TransitionManager.beginDelayedTransition(col, android.transition.AutoTransition().setDuration(160))
            for ((b, a) in sections) { b.visibility = View.GONE; a.text = "▸" }
            if (open) { body.visibility = View.VISIBLE; arrow.text = "▾" }
        }
        card.addView(head)
        card.addView(body)
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        if (intro.isNotEmpty()) body.label(intro, 12f, Ui.DIM, 0)
        sections.add(Pair(body, arrow))
        return body
    }

    override fun onResume() {
        super.onResume()
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
