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

class SettingsActivity : BaseActivity() {
    companion object { const val EXTRA_OPEN = "open_section" }
    private lateinit var p: Prefs
    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var alarmBtn: Button
    private var alarmHelp: View? = null
    private lateinit var profileStatus: TextView
    private lateinit var siteField: EditText
    private lateinit var tokenField: EditText
    /** Settings → UM links list, redrawn when you come back (a course may have been added meanwhile). */
    private lateinit var linksBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // opened from an "Online again / Keep in person" notification button
        AppLink.handle(this, intent)
        p = Prefs(this)
        val col = screen("Settings", navTo = SettingsActivity::class.java, onRefresh = { Scheduler.pollNow(this) })
        var target: LinearLayout = col   // the open-able body of the current section

        fun add(key: String, title: String, help: String, value: String, hint: String = "", kind: Int = 0) {
            val f = target.setting(title, help, value, hint, kind)
            fields[key] = f
            // stored on every change; alarms are re-armed once, when you leave the screen
            f.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val v = s?.toString()?.trim() ?: ""
                    when (key) {
                        "manualCourses" -> p.manualCourses = v
                        "skipClasses" -> p.skipClasses = v
                        "myGroups" -> if (v != p.myGroups) p.myGroups = v
                    }
                }
            })
        }

        col.label("Changes are saved as you make them.", Type.CAPTION, Ui.DIM, 4)

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
                results.label("Nothing matches \"$q\".", Type.LABEL, Ui.DIM, 8)
                return
            }
            for (s in hits) {
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    isClickable = true
                    background = rippleBg(Ui.BAR, 0, this@SettingsActivity, Radius.MD)
                }
                if (s.icon != 0) {
                    r.addView(ImageView(this).apply {
                        setImageResource(s.icon)
                        setColorFilter(Ui.CYAN)
                    }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) })
                }
                r.addView(TextView(this).apply {
                    text = sectionTitle(s.name)
                    textSize = Type.BODY * Ui.fontScale
                    setTextColor(Ui.TEXT)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(TextView(this).apply {
                    text = "Open"
                    setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0)
                    compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.CYAN)
                    textSize = Type.LABEL * Ui.fontScale
                    setTextColor(Ui.CYAN)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                r.setOnClickListener {
                    openSection(s, col)
                    searchBox?.setText("")
                    // highlight the section briefly so the user can spot where it opened
                    s.body.post {
                        s.body.background = roundBg(Ui.tint(Ui.CYAN, 30), Ui.CYAN, this@SettingsActivity, Radius.MD)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            s.body.background = null
                        }, 1200)
                    }
                }
                results.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
        }

        col.label("Search settings", Type.LABEL, Ui.TEXT, 12).setTypeface(null, android.graphics.Typeface.BOLD)
        searchBox = col.field("", "e.g. quiet, filter, theme, check", kind = 0)
        searchResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(searchResults, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        searchBox?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { buildSearch() }
        })

        target = accordion(col, "PROFILE", icon = R.drawable.ic_account,
            contents = listOf("Site URL", "Log in / log out", "Token"),
            summary = if (p.token.isEmpty()) "Not logged in" else "Logged in" + (if (p.userName.isNotEmpty()) " as ${p.userName}" else ""))
        profileStatus = target.label("", Type.BODY_L, Ui.TEXT, 8)
        target.label("Spectrum site URL", Type.CAPTION, Ui.DIM, 12)
        siteField = target.field(p.site)
        target.btn("Log in with Spectrum") {
            p.site = siteField.text.toString()
            startActivity(Intent(this, LoginWebActivity::class.java))
        }
        target.btn("Log in with browser instead") {
            p.site = siteField.text.toString()
            Login.start(this)
        }
        target.label("Or paste a token manually", Type.LABEL, Ui.TEXT, 16)
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

        // pickers, switches and chips save the moment you change them — nothing to type, nothing to get wrong
        fun heading(title: String, help: String) {
            target.label(title, Type.BODY_L, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
            target.label(help, Type.CAPTION, Ui.DIM, 2)
        }
        val hours = (0..23).map { h ->
            Timetable.fmtMin(h * 60) + when (h) { 0 -> " (midnight)"; 12 -> " (noon)"; else -> "" }
        }
        fun hourPicker(title: String, help: String, value: Int, onPick: (Int) -> Unit) {
            heading(title, help)
            target.addView(spinner(hours, value.coerceIn(0, 23)) { h -> onPick(h); Scheduler.scheduleAll(this) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        /** A daily message: on/off switch plus a clock picker; switching off keeps the time for next time. */
        fun dailyPicker(title: String, help: String, value: String, default: String, store: (String) -> Unit) {
            heading(title, help)
            var minutes = parseHm(value.ifEmpty { default })?.let { it.first * 60 + it.second } ?: 0
            val sw = android.widget.Switch(this).apply {
                text = "Send this message"
                textSize = Type.BODY * Ui.fontScale
                setTextColor(Ui.TEXT)
                minHeight = dp(Ui.touch(48))
                isChecked = value.isNotEmpty()
            }
            val tf = timeField(minutes) { m ->
                minutes = m
                sw.isChecked = true
                store(Timetable.fmtMin(m))
                Scheduler.scheduleAll(this)
            }
            sw.setOnCheckedChangeListener { _, on ->
                store(if (on) Timetable.fmtMin(minutes) else "")
                tf.view.alpha = if (on) 1f else 0.5f
                Scheduler.scheduleAll(this)
            }
            tf.view.alpha = if (sw.isChecked) 1f else 0.5f
            target.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            target.addView(tf.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }

        target = accordion(col, "QUIET HOURS",
            "During these hours nothing pops up: no checks, class alerts, daily messages or event reminders. " +
                "Anything that would have alerted you still goes into the Notifications list.",
            icon = R.drawable.ic_moon, contents = listOf("Quiet from", "Quiet until"),
            summary = "%02d:00 – %02d:00".format(p.quietStart, p.quietEnd))
        hourPicker("Quiet from", "Start of the quiet period.", p.quietStart) { p.quietStart = it }
        hourPicker("Quiet until", "End of the quiet period. Pick the same hour twice to turn quiet hours off.", p.quietEnd) { p.quietEnd = it }

        target = accordion(col, "DAILY MESSAGES",
            "Two short messages each day. Tap a time to change it; switch a message off if you do not want it.",
            icon = R.drawable.ic_cat_news, contents = listOf("Morning summary", "Afternoon check-in", "Umbrella reminder"),
            summary = "Morning " + p.summaryTime.ifEmpty { "off" } + "  ·  Afternoon " + p.afternoonTime.ifEmpty { "off" } +
                (if (p.umbrellaAlert) "  ·  Umbrella" else ""))
        dailyPicker("Morning summary", "Sent once each morning with today's classes and deadlines.", p.summaryTime, "07:05") { p.summaryTime = it }
        dailyPicker("Afternoon check-in", "Sent in the afternoon about the rest of today's classes.", p.afternoonTime, "14:00") { p.afternoonTime = it }
        heading("Umbrella reminder", "When rain is ${Weather.RAIN_PCT}% likely or more at UM around your classes: a line in the morning summary, " +
            "one \"Bring an umbrella\" notification that day (never more than one), and a note in the leave-by alert.")
        target.addView(android.widget.Switch(this).apply {
            text = "Remind me to bring an umbrella"
            textSize = Type.BODY * Ui.fontScale
            setTextColor(Ui.TEXT)
            minHeight = dp(Ui.touch(48))
            isChecked = p.umbrellaAlert
            setOnCheckedChangeListener { _, on -> p.umbrellaAlert = on }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        target.label(Weather.CREDIT + " The app only uses it as a hint; check the sky before you go.", Type.CAPTION, Ui.DIM, 4)
        target.addView(button("Open-Meteo.com", Emphasis.TERTIARY) {
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open-meteo.com/"))) } catch (_: Exception) { }
        }.apply { contentDescription = "Open the Open-Meteo website (weather data source)" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(-12) })

        target = accordion(col, "YOUR CLASSES", key = "yourclasses", icon = R.drawable.ic_cat_class,
            contents = listOf("My Occ", "Attendance reminder", "Classes to ignore", "Teams-recording courses"))
        heading("Attendance reminder", "A second alert when each class starts, with a Sign attendance button, so you do not forget. " +
            "Not sent for classes where the lecturer takes attendance in class or attendance is by Teams recording.")
        target.addView(android.widget.Switch(this).apply {
            text = "Remind me when a class starts"
            textSize = Type.BODY * Ui.fontScale
            setTextColor(Ui.TEXT)
            minHeight = dp(Ui.touch(48))
            isChecked = p.attStartAlert
            setOnCheckedChangeListener { _, on -> p.attStartAlert = on; Scheduler.scheduleAll(this@SettingsActivity) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        // My Occ per course as a dropdown (like the UM-timetable picker):
        // catalog occs first; if the course is missing from the catalog, fall back to learned occs.
        val learned = Groups.learnedPerCourse(this)
        val courses = LinkedHashSet<String>()
        Courses.all(this).forEach { courses.add(it.code.uppercase()) }
        learned.keys.forEach { courses.add(it.uppercase()) }
        if (courses.isEmpty()) {
            target.label("Your courses appear here after the first check (or pick one from the UM timetable below).", Type.LABEL, Ui.DIM, 8)
        }
        for (code in courses) {
            val catOccs = Catalog.find(this, code)?.occs() ?: emptyList()
            val occs = if (catOccs.isNotEmpty()) catOccs else (learned[code.uppercase()] ?: learned[code] ?: emptyList())
            val current = Regex(Regex.escape(code) + ":occ([0-9]+[a-z]?)", RegexOption.IGNORE_CASE).find(p.myGroups)?.groupValues?.get(1) ?: ""
            // show the course name too — users rarely recognise codes alone
            val cname = Courses.all(this).firstOrNull { it.code.equals(code, true) }?.name
                ?: Catalog.find(this, code)?.name ?: ""
            target.label(if (cname.isNotEmpty()) "$code · $cname" else code, Type.BODY, Ui.TEXT, 14).setTypeface(null, android.graphics.Typeface.BOLD)
            val names = listOf("Not set") + occs.map { "Occ $it" }
            val sel = occs.indexOf(current) + 1
            val sp = spinner(names, if (sel >= 0) sel else 0) { pos ->
                val keep = p.myGroups.split(",").map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("$code:", true) }
                p.myGroups = (if (pos <= 0) keep else keep + "$code:occ${occs[pos - 1]}").joinToString(", ")
                fields["myGroups"]?.setText(p.myGroups)
            }
            target.addView(sp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            if (catOccs.isEmpty() && occs.isNotEmpty()) {
                target.label("Occs learned from Spectrum (course not in the bundled UM list).", Type.CAPTION, Ui.DIM, 2)
            }
        }
        target.action("Pick a course from the UM timetable", "Adds the class times and sets your Occ here.") {
            startActivity(android.content.Intent(this, CatalogActivity::class.java))
        }
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
        // advanced escape hatch
        target.label("Advanced: raw group text", Type.LABEL, Ui.TEXT, 18).setTypeface(null, android.graphics.Typeface.BOLD)
        add(
            "myGroups", "Raw groups",
            "Format: COURSE:occN, comma separated. Write off to disable the filter.",
            p.myGroups, "WIX1003:occ2, WIX1003:t3",
        )

        target = accordion(col, "ONLINE OR IN PERSON",
            "When online classes end (e.g. the air quality is fine again), mark courses as in person: Join class and meeting links " +
                "go away for them and the room is shown. Attendance reminders stay.",
            key = "inperson", icon = R.drawable.ic_cat_class, contents = listOf("In person", "Online again"))
        ClassModeEditor.render(this, target)

        target = accordion(col, "DEADLINES", icon = R.drawable.ic_cat_deadline,
            contents = listOf("Remind me before a deadline"))
        heading("Remind me before a deadline",
            "Tap every time you want a reminder, for example 24h and 3h: one a day before, another 3 hours before. None selected = no deadline reminders.")
        val dlHours = listOf(1, 3, 6, 12, 24, 48, 72)
        target.chips(dlHours.map { "${it}h" }, dlHours.indices.filter { dlHours[it] in p.deadlineList }.toSet(), true) { sel ->
            p.deadlineHours = sel.sorted().joinToString(",") { dlHours[it].toString() }
        }

        target = accordion(col, "BACKGROUND CHECKS", icon = R.drawable.ic_refresh,
            contents = listOf("Check every (minutes)"), summary = "Every ${p.pollMinutes} min")
        heading("Check Spectrum every",
            "How often the app looks for new materials, announcements and notifications. " +
                "Below 15 minutes the app uses alarms, so keep the battery setting on Unrestricted (see App & maintenance) or Android may delay checks while the phone sleeps.")
        val pollOpts = listOf(10, 15, 30, 60)
        val pollSel = pollOpts.indices.minByOrNull { Math.abs(pollOpts[it] - p.pollMinutes) } ?: 0
        target.chips(pollOpts.map { "$it min" }, setOf(pollSel), false) { sel ->
            sel.firstOrNull()?.let { p.pollMinutes = pollOpts[it]; Scheduler.ensurePolling(this) }
        }

        target = accordion(col, "UM SHUTTLE",
            "Home shows which shuttle to take to your next class, or when walking is quicker, and an alert tells you when to leave.",
            key = "shuttle", icon = R.drawable.lk_bus,
            contents = listOf("Home stop", "Leave-by alert", "Shuttle times"))
        val shPlaces = Shuttle.placesForPicker(this).filter { it.isStop }
        heading("Home stop", "Where your day starts, usually your residential college. Your first class of the day is planned from here; " +
            "later classes from the class before.")
        // the same searchable picker as the Shuttle screen (short names like KK12), bus stops only
        val homeNow = shPlaces.firstOrNull { it.id == Shuttle.homeId(this) }
        target.addView(pickerField(homeNow?.label() ?: "Not set", "Home stop", only = { it.isStop }, none = "Not set") { id ->
            Shuttle.setHomeId(this, id)
            Scheduler.scheduleAll(this)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        heading("Leave-by alert", "How long before you must set off (for the bus, or on foot) the alert comes.")
        val leadOpts = listOf(0, 5, 10, 15)
        target.chips(leadOpts.map { if (it == 0) "Off" else "$it min" }, setOf(leadOpts.indexOf(Shuttle.leadMin(this)).coerceAtLeast(0)), false) { sel ->
            sel.firstOrNull()?.let { Shuttle.setLeadMin(this, leadOpts[it]); Scheduler.scheduleAll(this) }
        }
        target.label("Which stop each course uses is guessed from its code; change it in the class editor (Timetable → a class → Edit).", Type.CAPTION, Ui.DIM, 10)
        target.btn("Open shuttle times") { startActivity(Intent(this, ShuttleActivity::class.java)) }

        target = accordion(col, "UM LINKS",
            "Your shortcuts in the toolbox (the Aa button). Pick an icon, rename, change the address, " +
                "reorder with the up and down arrows, or add a Spectrum course page.",
            key = "umlinks", icon = R.drawable.lk_link,
            contents = listOf("Shortcuts", "Icons", "Add link", "Reorder", "Course pages", "Reset"))
        linksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        target.addView(linksBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        LinksEditor.renderSection(this, linksBox)

        target = accordion(col, "APPEARANCE & ACCESSIBILITY",
            "Change how the app looks. Tap a chip to apply it right away — the screen redraws with your choice.",
            icon = R.drawable.ic_palette, contents = listOf("Colour mode", "Text size", "High contrast"), key = "appearance",
            summary = listOf("Phone", "Light", "Dark")[p.themeMode] + " · " + listOf("Normal", "Large", "Extra large")[p.textScale] + " text" +
                (if (p.highContrast) " · High contrast" else ""),
        )
        target.label("Colour mode", Type.BODY_L, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
        target.chips(listOf("Phone", "Light", "Dark"), setOf(p.themeMode), false) { sel ->
            val v = sel.firstOrNull() ?: 0
            if (v != p.themeMode) { p.themeMode = v; recreate() }
        }
        target.label("Text size", Type.BODY_L, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
        target.chips(listOf("Normal", "Large", "Extra large"), setOf(p.textScale), false) { sel ->
            val v = sel.firstOrNull() ?: 0
            if (v != p.textScale) { p.textScale = v; recreate() }
        }
        target.label("Easier to see and touch", Type.BODY_L, Ui.TEXT, 20).setTypeface(null, android.graphics.Typeface.BOLD)
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
        target.label("High contrast uses stronger text colours. Big touch targets make every button and box 56 dp tall.", Type.CAPTION, Ui.DIM, 8)
        target = accordion(col, "APP & MAINTENANCE", icon = R.drawable.ic_settings,
            contents = listOf("Check now", "Update", "Test notification", "Alarms", "Battery", "Hidden deadlines", "Diagnostic lines"))
        target.action("Check now", "Looks at Spectrum right away. A new notification appears in the list if something is new.") {
            save()
            Scheduler.pollNow(this)
            Toast.makeText(this, "Checking Spectrum...", Toast.LENGTH_SHORT).show()
        }
        val hiddenCount = getSharedPreferences("health", MODE_PRIVATE)
            .getStringSet("hiddenEvents", emptySet())!!.size
        if (hiddenCount > 0) {
            target.action("Restore hidden deadlines ($hiddenCount)", "Undoes \"Hide this\" — the deadlines are fetched and reminded again on the next check.") {
                getSharedPreferences("health", MODE_PRIVATE).edit().remove("hiddenEvents").apply()
                Toast.makeText(this, "Restored — hidden deadlines return on the next check.", Toast.LENGTH_LONG).show()
                recreate()
            }
        }
        target.action("Check for app update", "Compares this build with the latest release on GitHub. Shows the result here; a notification appears if a newer version exists.") {
            Thread {
                val msg = Updater.check(this, manual = true)
                runOnUiThread { Toast.makeText(this, msg ?: "You are on the latest version.", Toast.LENGTH_LONG).show() }
            }.start()
        }
        target.action("Send test notification", "Sends one test message so you can see how notifications look and that tapping opens the app.") {
            Notifier.push(this, "Test", "Notifications are working.\nTap the button below to open Spectrum.", true, listOf(Pair("Open Spectrum", p.site)), ignoreQuiet = true)
        }
        heading("Diagnostic lines",
            "Also lists technical lines in Notifications (\"Check OK\", skipped items). Useful when reporting a problem.")
        target.addView(android.widget.Switch(this).apply {
            text = "Show diagnostic lines"
            textSize = Type.BODY * Ui.fontScale
            setTextColor(Ui.TEXT)
            minHeight = dp(Ui.touch(48))
            isChecked = p.debugLog
            setOnCheckedChangeListener { _, on ->
                p.debugLog = on
                // Off is immediate: lines already saved go too, not just new ones
                if (!on) NotifyLog.purgeDebug(this@SettingsActivity)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        alarmBtn = target.action("Allow exact alarms", "Needed so class reminders arrive about 10 minutes before the class, on time.") {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        alarmHelp = target.getChildAt(target.childCount - 1)
        target.action("Allow background checks", "Shows Android's prompt to let Spectrum Notify run in the background (Unrestricted battery), so the checks are not stopped.") {
            askBatteryUnrestricted()
        }
        // not logged in yet: open the Profile section so the login buttons are in view
        if (p.token.isEmpty() && sections.isNotEmpty()) {
            sections[0].body.visibility = View.VISIBLE
            sections[0].arrow.rotation = 90f
        }
        // deep links: open a specific section (toolbox -> appearance, startup -> your classes)
        intent.getStringExtra(EXTRA_OPEN)?.let { key ->
            sections.firstOrNull { it.key == key }?.let {
                it.body.visibility = View.VISIBLE
                it.arrow.rotation = 90f
                // bring the opened section into view (deep links land on sections far down the page)
                val card = it.body.parent as? View
                card?.post {
                    var v: View? = card
                    var y = 0
                    while (v != null && v !is android.widget.ScrollView) { y += v.top; v = v.parent as? View }
                    (v as? android.widget.ScrollView)?.smoothScrollTo(0, (y - dp(16)).coerceAtLeast(0))
                }
            }
        }
    }

    /** "UM LINKS" → "UM links": sentence case for headings, keeping the UM acronym. */
    private fun sectionTitle(t: String): String =
        t.lowercase().replaceFirstChar { it.uppercase() }.replace(Regex("""\bUm\b"""), "UM")

    /** One settings section: its views plus the metadata the search and headers show. */
    class Section(
        val body: LinearLayout,
        val arrow: ImageView,
        val name: String,
        val icon: Int,
        val contents: List<String>,
        val key: String,
    )

    private val sections = ArrayList<Section>()

    /** Opens one section and closes the others (shared by headers and search results). */
    private fun openSection(s: Section, col: LinearLayout) {
        android.transition.TransitionManager.beginDelayedTransition(col, android.transition.AutoTransition().setDuration(160))
        for (x in sections) { x.body.visibility = View.GONE; x.arrow.rotation = 0f }
        s.body.visibility = View.VISIBLE
        s.arrow.rotation = 90f
    }

    /** A tappable section header with icon, name and a one-line contents list; one open at a time. */
    private fun accordion(
        col: LinearLayout,
        title: String,
        intro: String = "",
        key: String = "",
        icon: Int = 0,
        contents: List<String> = emptyList(),
        summary: String = "",
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Ui.BAR, Ui.BORDER, this@SettingsActivity, Radius.MD)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), 0, dp(14), dp(16))
        }
        val arrow = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(Ui.DIM)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(14), 0, dp(14), 0)
            isClickable = true
            contentDescription = "$title: " + summary.ifEmpty { contents.joinToString(", ") }
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
                text = sectionTitle(title)
                textSize = Type.BODY_L * Ui.fontScale
                setTextColor(Ui.TEXT)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (summary.isNotEmpty() || contents.isNotEmpty()) addView(TextView(this@SettingsActivity).apply {
                // the current values when the section has them ("01:00–07:00"), else what is inside
                text = summary.ifEmpty { contents.joinToString("  ·  ") }
                textSize = Type.CAPTION * Ui.fontScale
                setTextColor(Ui.DIM)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(arrow, LinearLayout.LayoutParams(dp(24), dp(24)))
        val s = Section(body, arrow, title, icon, contents, key)
        head.setOnClickListener {
            if (body.visibility == View.VISIBLE) {
                // tapping the open section closes it again
                android.transition.TransitionManager.beginDelayedTransition(col, android.transition.AutoTransition().setDuration(160))
                body.visibility = View.GONE
                arrow.rotation = 0f
            } else openSection(s, col)
        }
        card.addView(head)
        card.addView(body)
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        if (intro.isNotEmpty()) body.label(intro, Type.CAPTION, Ui.DIM, 0)
        sections.add(s)
        return body
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        LinksEditor.renderSection(this, linksBox)
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

    /** The remaining free-text boxes (course lists). Times, hours and choices save as soon as they change. */
    private fun save() {
        p.manualCourses = f("manualCourses")
        p.skipClasses = f("skipClasses")
        // p.myGroups is owned by the Courses page now — never written here
        Scheduler.ensurePolling(this)
        Scheduler.scheduleAll(this)
    }
}
