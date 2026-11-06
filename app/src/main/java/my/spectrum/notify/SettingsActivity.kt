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
    private lateinit var alarmBtn: Button
    private var alarmHelp: View? = null
    private lateinit var profileStatus: TextView
    private lateinit var siteField: EditText
    /** Settings → UM links list, redrawn when you come back (a course may have been added meanwhile). */
    private lateinit var linksBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // opened from an "Online again / Keep in person" notification button
        AppLink.handle(this, intent)
        p = Prefs(this)
        val col = screen("Settings", navTo = SettingsActivity::class.java, onRefresh = { Scheduler.pollNow(this) })
        var target: LinearLayout = col   // the open-able body of the current section

        col.label("Changes are saved as you make them.", Type.CAPTION, Ui.DIM, 4)
        // group headings between the cards, so twelve cards read as five short lists
        fun group(t: String) = col.addView(sectionLabel(t).apply { setPadding(dp(4), dp(24), 0, 0) })

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
        searchBox = col.field("", "e.g. quiet, filter, theme, check", kind = 0).apply { tag = "guide:settingsSearch" }
        searchResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(searchResults, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        searchBox?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { buildSearch() }
        })

        target = accordion(col, "PROFILE", icon = R.drawable.ic_account,
            contents = listOf("Site URL", "Log in / log out"),
            summary = if (p.token.isEmpty()) "Not logged in" else "Logged in" + (if (p.userName.isNotEmpty()) " as ${p.userName}" else ""))
        profileStatus = target.label("", Type.BODY_L, Ui.TEXT, 8)
        target.label("Spectrum site URL", Type.CAPTION, Ui.DIM, 12)
        siteField = target.field(p.site)
        target.btn("Log in with Spectrum", Emphasis.PRIMARY) {
            p.site = siteField.text.toString()
            startActivity(Intent(this, LoginWebActivity::class.java))
        }
        target.btn("Log in with browser instead") {
            p.site = siteField.text.toString()
            Login.start(this)
        }
        target.btn("Log out") {
            p.clearToken()
            CookieManager.getInstance().removeAllCookies(null)
            NotifyLog.sys(this, "Logged out")
            refreshProfile()
        }.apply {
            // red, so it never reads as another way in
            setTextColor(Ui.RED)
            background = rippleBg(Ui.BAR, Ui.RED, this@SettingsActivity, Radius.PILL)
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

        group("Classes")
        target = accordion(col, "YOUR CLASSES", key = "yourclasses", icon = R.drawable.ic_cat_class,
            contents = listOf("My Occ", "Attendance reminder"),
            summary = "Occ, attendance reminder " + if (p.attStartAlert) "on" else "off")
        heading("Attendance reminder", "Sign attendance alerts during class, when signing works: when it starts, and 10 minutes before it ends. " +
            "Not sent for classes where the lecturer takes attendance in class or attendance is by Teams recording.")
        target.addView(android.widget.Switch(this).apply {
            text = "Remind me to sign attendance"
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
            val names = listOf("Not set") + occs.map { "Occ $it" }
            val sel = occs.indexOf(current) + 1
            val sp = spinner(names, if (sel >= 0) sel else 0) { pos ->
                val keep = p.myGroups.split(",").map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("$code:", true) }
                p.myGroups = (if (pos <= 0) keep else keep + "$code:occ${occs[pos - 1]}").joinToString(", ")
                // the timetable follows: that Occ's classes from the UM list, as Courses does
                if (pos <= 0) Timetable.clearCatalog(this, code) else if (catOccs.isNotEmpty()) Timetable.autoSetup(this, code, occs[pos - 1])
            }
            // one row per course: the short name ("WIX1001 · CM") on the left, a small Occ dropdown on the right (ticket SN-TG4DM)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            // the same name style as the Online or in person rows
            row.addView(tv(Shorts.display(this, code), Type.BODY, Ui.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(sp, LinearLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })  // same size as Online or in person
            target.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            if (catOccs.isEmpty() && occs.isNotEmpty()) {
                target.label("Occs learned from Spectrum (course not in the bundled UM list).", Type.CAPTION, Ui.DIM, 2)
            }
        }
        if (Prefs.isUm(this)) target.action("Pick a course from the UM timetable", "Adds the class times and sets your Occ here.") {
            startActivity(android.content.Intent(this, CatalogActivity::class.java))
        }
        // the typed fields (classes to ignore, Teams-recording courses, raw groups) are gone (ticket feedback): Occ above,
        // Edit class (attendance: Lecturer) and Remove from my timetable cover them. What was saved still applies.

        target = accordion(col, "ONLINE OR IN PERSON",
            "The long-term setting for each course: it stays until you change it here. For one class, or just one week, " +
                "tap the class in Timetable instead.",
            key = "inperson", icon = R.drawable.ic_laptop, contents = listOf("In person", "Online again"),
            summary = Courses.all(this).count { Timetable.inPerson(this, it.code) }.let { if (it == 0) "All as Spectrum says" else "$it in person" })
        ClassModeEditor.render(this, target)

        target = accordion(col, "DEADLINES", icon = R.drawable.ic_cat_deadline,
            contents = listOf("Remind me before a deadline", "Hidden deadlines"),
            summary = if (p.deadlineList.isEmpty()) "No reminders" else p.deadlineList.sortedDescending().joinToString(", ") { "${it}h" } + " before")
        heading("Remind me before a deadline",
            "Tap every time you want a reminder, for example 24h and 3h: one a day before, another 3 hours before. None selected = no deadline reminders.")
        val dlHours = listOf(1, 3, 6, 12, 24, 48, 72)
        target.chips(dlHours.map { "${it}h" }, dlHours.indices.filter { dlHours[it] in p.deadlineList }.toSet(), true) { sel ->
            p.deadlineHours = sel.sorted().joinToString(",") { dlHours[it].toString() }
        }
        hiddenTaskList(target)

        group("Alerts")
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

        group("Getting around")
        target = accordion(col, "UM SHUTTLE",
            "Home shows which shuttle to take to your next class, or when walking is quicker, and an alert tells you when to leave.",
            key = "shuttle", icon = R.drawable.lk_bus,
            contents = listOf("Use UM shuttle", "Home stop", "Leave-by alert", "Shuttle times"),
            summary = if (Shuttle.enabled(this)) "" else "Off")
        val shuttleSwitch = android.widget.Switch(this).apply {
            text = "Use the UM shuttle"; setTextColor(Ui.TEXT); textSize = Type.BODY_L * Ui.fontScale
            minHeight = dp(Ui.touch(48)); isChecked = Shuttle.enabled(this@SettingsActivity)
        }
        target.addView(shuttleSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val shuttleBox = target
        val shuttleFrom = shuttleBox.childCount
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
        // not at UM: off hides the rest of this section, the toolbox entry, Home's bus line and the leave-by alerts
        fun showShuttle(on: Boolean) { for (i in shuttleFrom until shuttleBox.childCount) shuttleBox.getChildAt(i).visibility = if (on) View.VISIBLE else View.GONE }
        showShuttle(shuttleSwitch.isChecked)
        shuttleSwitch.setOnCheckedChangeListener { _, on -> Shuttle.setEnabled(this, on); showShuttle(on); Scheduler.scheduleAll(this) }

        target = accordion(col, "LINKS",
            "Your shortcuts in the toolbox (the Aa button). Pick an icon, rename, change the address, " +
                "drag the handle to reorder, or add a Spectrum course page.",
            key = "umlinks", icon = R.drawable.lk_link,
            contents = listOf("Shortcuts", "Icons", "Add link", "Reorder", "Course pages", "Reset"))
        linksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        target.addView(linksBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        LinksEditor.renderSection(this, linksBox)

        group("App")
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
        target = accordion(col, "HELP & FEEDBACK", icon = R.drawable.lk_help, contents = listOf("Guide", "Feedback"),
            summary = "Tour of the app, report a problem")
        target.action("Show the guide again", "The short tour of Home and the tabs, from the start.") {
            Guide.restart(this)
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        target.action("Send feedback", "Report a problem, suggest an idea or ask a question. You get a ticket number, see its status and get a notification when it is fixed.") {
            FeedbackActivity.open(this)
        }
        target = accordion(col, "APP & MAINTENANCE", icon = R.drawable.ic_settings,
            contents = listOf("Check now", "Update", "Test notification", "Alarms", "Battery", "Diagnostic lines"),
            summary = "Version " + packageManager.getPackageInfo(packageName, 0).versionName)
        target.action("Check now", "Looks at Spectrum right away. A new notification appears in the list if something is new.") {
            save()
            Scheduler.pollNow(this)
            Toast.makeText(this, "Checking Spectrum...", Toast.LENGTH_SHORT).show()
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
    /**
     * Tasks you hid ("Hide this (wrong?)"), one card each with its own Restore. Ones already past their due time
     * are dropped here (and after each check), so the list only holds tasks still to come.
     */
    private fun hiddenTaskList(box: LinearLayout) {
        val now = System.currentTimeMillis() / 1000
        val all = Checker.hiddenTasks(this)
        all.filter { it.due in 1 until now }.forEach { t -> t.keys.forEach { NotifyLog.unhideEvent(this, it) } }
        val tasks = all.filter { it.due !in 1 until now }
        if (tasks.isEmpty()) {
            box.label("Hidden deadlines", Type.LABEL, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
            box.label("None. \"Hide this (wrong?)\" on a task puts it here, so you can bring it back.", Type.CAPTION, Ui.DIM, 2)
            return
        }
        // folded by default: a tap on the header shows the list (ticket SN-GPWAE)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val arrow = ImageView(this).apply { setImageResource(R.drawable.ic_chevron_right); setColorFilter(Ui.DIM) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(Ui.touch(48))
            isClickable = true
            background = rippleBg(0, 0, this@SettingsActivity, Radius.SM)
            contentDescription = "Hidden deadlines, ${tasks.size}. Tap to show or hide"
        }
        head.addView(tv("Hidden deadlines (${tasks.size})", Type.LABEL, Ui.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(arrow, LinearLayout.LayoutParams(dp(22), dp(22)))
        head.setOnClickListener {
            val open = list.visibility != View.VISIBLE
            list.visibility = if (open) View.VISIBLE else View.GONE
            arrow.rotation = if (open) 90f else 0f
        }
        box.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        box.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.label("Not on Home, in Notifications or in reminders. Restore one to see it again after the next check.", Type.CAPTION, Ui.DIM, 2)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", java.util.Locale.ENGLISH)
        for (t in tasks) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(10), dp(10))
                background = roundBg(Ui.BAR, Ui.BORDER, this@SettingsActivity, Radius.MD)
            }
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(tv(t.name, Type.BODY, Ui.TEXT, true).apply { maxLines = 2 })
            // a guessed time (older hides, see Checker.pruneHidden) is not shown as a due date
            val due = if (t.due > now + 15 * 86400 - 3600 || t.due == 0L) "" else "Due " + java.time.Instant.ofEpochSecond(t.due).atZone(zone()).format(fmt)
            val sub = listOf((Regex("""[A-Z]{3,4}\d{4}""").find(t.course)?.value?.let { Shorts.display(this, it) } ?: t.course), due).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) text.addView(tv(sub, Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) })
            card.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(button("Restore", Emphasis.SECONDARY) {
                t.keys.forEach { NotifyLog.unhideEvent(this, it) }
                list.removeView(card)
                Toast.makeText(this, "${t.name} shows again after the next check.", Toast.LENGTH_SHORT).show()
            }.apply { contentDescription = "Restore ${t.name}" })
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

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


    /** The remaining free-text boxes (course lists). Times, hours and choices save as soon as they change. */
    private fun save() {
        // the typed fields are gone; their saved values are left as they are
        Scheduler.ensurePolling(this)
        Scheduler.scheduleAll(this)
    }
}
