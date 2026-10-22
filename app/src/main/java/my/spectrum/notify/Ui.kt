package my.spectrum.notify

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.ImageButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Colours follow the phone's light/dark mode unless Settings > Appearance overrides it.
 *  Call Ui.setup(context) (or Activity.applyBars()) before reading them. */
object Ui {
    var dark = true
    var BG = 0
    var BAR = 0          // card / surface
    var BORDER = 0
    var TEXT = 0
    var DIM = 0
    var CYAN = 0         // primary accent
    var GREEN = 0
    var RED = 0
    var YELLOW = 0
    var ORANGE = 0
    var PURPLE = 0
    val HERO_A = 0xFF3B5BDB.toInt()
    val HERO_B = 0xFF7048E8.toInt()

    /** Multiply every textSize by this (1.0 / 1.2 / 1.4 from Settings > Appearance). */
    @Volatile var fontScale = 1.0f
    /** Minimum touch target in dp (48 normally, 56 with Big touch targets on). */
    @Volatile var touchDp = 48
    /** Fingerprint of the last applied appearance; activities recreate when it changes. */
    @Volatile var look: String = ""
    /** 1 = dark palette was made with extra contrast. */
    private var contrastDark = -1

    init { palette(true) }

    private fun palette(d: Boolean) {
        dark = d
        if (d) {
            BG = 0xFF12151C.toInt(); BAR = 0xFF1B1F29.toInt(); BORDER = 0xFF2A3040.toInt()
            TEXT = 0xFFE4E2DD.toInt(); DIM = 0xFF9BA0AC.toInt(); CYAN = 0xFF8FA8FF.toInt()
            GREEN = 0xFF5FD5A0.toInt(); RED = 0xFFF88C8C.toInt(); YELLOW = 0xFFEFC878.toInt()
            ORANGE = 0xFFF2A96A.toInt(); PURPLE = 0xFFC0A5FF.toInt()
        } else {
            // warm light: cream surfaces instead of plain white, soft blue-grey structure
            BG = 0xFFF5F3EE.toInt(); BAR = 0xFFFDFBF7.toInt(); BORDER = 0xFFE2DED4.toInt()
            TEXT = 0xFF26251F.toInt(); DIM = 0xFF6B6A60.toInt(); CYAN = 0xFF4A5FC4.toInt()
            GREEN = 0xFF2E7D5B.toInt(); RED = 0xFFB44A4A.toInt(); YELLOW = 0xFF96690F.toInt()
            ORANGE = 0xFFAD621C.toInt(); PURPLE = 0xFF7452B8.toInt()
        }
        if (contrastDark == if (d) 1 else 0) return  // palette already matches contrast setting
        contrastDark = if (d) 1 else 0
        if (d) {
            // high contrast dark: brighter text/dim, stronger accents (still warm-tinted, never pure white)
            TEXT = 0xFFFFFDF5.toInt(); DIM = 0xFFC9CBD3.toInt(); CYAN = 0xFFB0C2FF.toInt()
            GREEN = 0xFF83E8B8.toInt(); RED = 0xFFFFA8A8.toInt(); YELLOW = 0xFFFFD98F.toInt()
            ORANGE = 0xFFFFC08A.toInt(); PURPLE = 0xFFD6C0FF.toInt()
        } else {
            // high contrast light: near-black warm text, darker greys and accents
            TEXT = 0xFF14130E.toInt(); DIM = 0xFF4A4940.toInt(); CYAN = 0xFF2C3F9E.toInt()
            GREEN = 0xFF1F5C42.toInt(); RED = 0xFF8F3030.toInt(); YELLOW = 0xFF6E4C08.toInt()
            ORANGE = 0xFF83490F.toInt(); PURPLE = 0xFF5A3E92.toInt()
        }
    }

    fun setup(ctx: Context) {
        val p = try { Prefs(ctx) } catch (_: Exception) { null }
        fontScale = when (p?.textScale ?: 0) { 1 -> 1.2f; 2 -> 1.4f; else -> 1.0f }
        touchDp = if (p?.bigTouch == true) 56 else 48
        when (p?.themeMode ?: 0) {
            1 -> palette(false)
            2 -> palette(true)
            else -> {
                val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                palette(night == Configuration.UI_MODE_NIGHT_YES)
            }
        }
        look = "${p?.themeMode ?: 0}:${p?.textScale ?: 0}:${p?.highContrast == true}:${p?.bigTouch == true}:$dark"
    }

    /** A dp amount for a touch target, honouring the Big touch targets setting. */
    fun touch(n: Int): Int = maxOf(touchDp, n)

    /** Same colour with a different opacity (0-255). */
    fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}

/** Applies the appearance settings and colours the system bars to match.
 *  Sets [lookChanged] when the appearance changed while a screen was open, so its
 *  onResume can rebuild with the new look. */
@Volatile var lookChanged = false

fun Activity.applyBars() {
    val before = Ui.look
    Ui.setup(this)
    window.statusBarColor = Ui.BG
    window.navigationBarColor = Ui.BG
    if (!Ui.dark) {
        var f = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = f
    }
    if (before.isNotEmpty() && before != Ui.look) lookChanged = true
}

/** Call at the top of onResume: rebuilds the screen when the appearance changed while it was open. */
fun Activity.recreateIfLookChanged() {
    if (lookChanged) {
        lookChanged = false
        recreate()
    }
}

fun Context.dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

/** Dark screen with a top bar (back arrow + title) and a scrolling column; returns the column.
 *  Pass [navTo] (the activity of the tab to highlight) to also show the shared bottom nav bar. */
fun Activity.screen(
    title: String,
    actionIcon: Int = 0,
    actionDesc: String = "",
    onAction: (() -> Unit)? = null,
    navTo: Class<*>? = null,
): LinearLayout {
    applyBars()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.BG)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(6), dp(16), dp(6))
    }
    if (navTo == null) {
        top.addView(
            android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Ui.TEXT)
                setBackgroundColor(0)
                contentDescription = "Back"
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
    }
    top.addView(TextView(this).apply {
        text = title
        textSize = 20f * Ui.fontScale
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.TEXT)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (actionIcon != 0 && onAction != null) {
        top.addView(
            ImageButton(this).apply {
                setImageResource(actionIcon)
                setColorFilter(Ui.CYAN)
                background = rippleBg(Ui.BAR, Ui.BORDER, this@screen, 24)
                contentDescription = actionDesc
                setOnClickListener { onAction() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
    }
    root.addView(top)
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(4), dp(20), if (navTo != null) dp(12) else dp(32))
    }
    if (navTo != null) {
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addNavBar(root, navTo)
    } else {
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    setContentView(root)
    return col
}

fun LinearLayout.label(t: String, size: Float = 12f, color: Int = Ui.DIM, top: Int = 12): TextView {
    val tv = TextView(context)
    tv.text = t
    tv.textSize = size * Ui.fontScale
    tv.setTextColor(color)
    tv.setPadding(0, context.dp(top), 0, 0)
    addView(tv)
    return tv
}

fun roundBg(fill: Int, stroke: Int, ctx: Context, radius: Int = 10): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    setStroke(ctx.dp(1), stroke)
    cornerRadius = ctx.dp(radius).toFloat()
}

/** roundBg with a ripple, so taps give visible feedback (HCI: pressed state on every control). */
fun rippleBg(fill: Int, stroke: Int, ctx: Context, radius: Int = 10): android.graphics.drawable.RippleDrawable {
    val bg = roundBg(fill, stroke, ctx, radius)
    return android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(Ui.tint(Ui.CYAN, 60)), bg, bg
    )
}

/** kind: 0 = text, 1 = whole number, 2 = time (HH:MM keyboard). */
fun LinearLayout.field(value: String, hint: String = "", password: Boolean = false, kind: Int = 0): EditText {
    val et = EditText(context)
    et.setText(value)
    et.hint = hint
    et.setTextColor(Ui.TEXT)
    et.setHintTextColor(Ui.DIM)
    et.textSize = 15f * Ui.fontScale
    et.background = rippleBg(Ui.BAR, Ui.BORDER, context)
    et.minHeight = context.dp(Ui.touch(48))
    et.setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
    if (password) {
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    } else {
        when (kind) {
            1 -> et.inputType = InputType.TYPE_CLASS_NUMBER
            2 -> et.inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            else -> et.setSingleLine()
        }
    }
    addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = context.dp(6)
    })
    return et
}

fun LinearLayout.btn(text: String, onClick: () -> Unit): Button {
    val b = Button(context)
    b.text = text
    b.isAllCaps = false
    b.textSize = 14f * Ui.fontScale
    b.setTextColor(Ui.CYAN)
    b.background = rippleBg(Ui.BAR, Ui.CYAN, context, 24)
    b.setOnClickListener { onClick() }
    addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(Ui.touch(48))).apply {
        topMargin = context.dp(10)
    })
    return b
}

/** Section heading on a settings page. */
fun LinearLayout.section(title: String, intro: String = "") {
    val t = TextView(context)
    t.text = title
    t.textSize = 13f * Ui.fontScale
    t.setTextColor(Ui.CYAN)
    t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
    t.setPadding(0, context.dp(28), 0, 0)
    addView(t)
    val line = android.view.View(context)
    line.setBackgroundColor(Ui.BORDER)
    addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = context.dp(6) })
    if (intro.isNotEmpty()) label(intro, 12f, Ui.DIM, 8)
}

/** One setting: bold name, plain-language explanation, then the input box. */
fun LinearLayout.setting(title: String, help: String, value: String, hint: String = "", kind: Int = 0): EditText {
    label(title, 15f, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
    label(help, 12f, Ui.DIM, 2)
    return field(value, hint, false, kind)
}

/** A button with a one-line explanation under it. */
fun LinearLayout.action(text: String, help: String, onClick: () -> Unit): Button {
    val b = btn(text, onClick)
    label(help, 12f, Ui.DIM, 4)
    return b
}

// ---------------------------------------------------------------- chips, swatches, time fields

/** Text on top of an accent-coloured fill (selected chip, "NOW" badge). */
fun onAccent(): Int = if (Ui.dark) Ui.BG else 0xFFFFFFFF.toInt()

/** A selectable chip: filled with the accent when selected, outlined otherwise. */
fun Context.chip(
    label: String,
    selected: Boolean,
    accent: Int = Ui.CYAN,
    size: Float = 14f,
    padH: Int = 14,
    dot: Int = 0,
    onClick: () -> Unit,
): TextView {
    val c = this
    return TextView(c).apply {
        if (dot != 0) {
            val sp = android.text.SpannableString("\u25CF  $label")
            sp.setSpan(android.text.style.ForegroundColorSpan(dot), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = sp
        } else {
            text = label
        }
        textSize = size * Ui.fontScale
        gravity = Gravity.CENTER
        minHeight = c.dp(Ui.touch(48))
        minWidth = c.dp(48)
        setPadding(c.dp(padH), 0, c.dp(padH), 0)
        setTextColor(if (selected) onAccent() else Ui.TEXT)
        if (selected) setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = c.dp(20).toFloat()
            if (selected) setColor(accent) else { setColor(Ui.BAR); setStroke(c.dp(1), Ui.BORDER) }
        }
        foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Ui.tint(accent, 80)),
            null,
            GradientDrawable().apply { cornerRadius = c.dp(20).toFloat() },
        )
        isClickable = true
        setOnClickListener { onClick() }
    }
}

/**
 * A row of equal-width chips. multi = any number can be on (days); otherwise exactly one (type, view).
 * onChange gets the selected positions. Returns the row.
 */
fun LinearLayout.chips(labels: List<String>, initial: Set<Int>, multi: Boolean, onChange: (Set<Int>) -> Unit): LinearLayout {
    val ctx = context
    val sel = initial.toMutableSet()
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    fun draw() {
        row.removeAllViews()
        labels.forEachIndexed { i, l ->
            val c = ctx.chip(l, i in sel, size = 13f, padH = 2) {
                if (multi) { if (!sel.add(i)) sel.remove(i) } else { sel.clear(); sel.add(i) }
                draw()
                onChange(sel.toSet())
            }
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = ctx.dp(6)
            })
        }
    }
    draw()
    addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = ctx.dp(8)
    })
    return row
}

/**
 * Two rows of round colour swatches, one per palette colour. Returns a function that moves the tick to another index.
 */
fun LinearLayout.swatches(selected: Int, onPick: (Int) -> Unit): (Int) -> Unit {
    val ctx = context
    val n = Timetable.PALETTE_SIZE
    val perRow = (n + 1) / 2
    var cur = selected
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun draw() {
        box.removeAllViews()
        var row: LinearLayout? = null
        for (i in 0 until n) {
            if (i % perRow == 0) {
                row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = ctx.dp(8)
                })
            }
            val on = i == cur
            val sw = TextView(ctx).apply {
                text = if (on) "\u2713" else ""
                textSize = 18f * Ui.fontScale
                gravity = Gravity.CENTER
                setTextColor(onAccent())
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Timetable.colorOf(i))
                    if (on) setStroke(ctx.dp(3), Ui.TEXT)
                }
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Ui.tint(onAccent(), 80)), null,
                    GradientDrawable().apply { shape = GradientDrawable.OVAL },
                )
                contentDescription = "Colour ${i + 1}" + if (on) ", selected" else ""
                isClickable = true
                setOnClickListener { cur = i; draw(); onPick(i) }
            }
            row!!.addView(sw, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply {
                if (i % perRow > 0) leftMargin = ctx.dp(8)
            })
        }
    }
    draw()
    addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = ctx.dp(8)
    })
    return { i -> cur = i; draw() }
}

/** A field that shows HH:MM and opens the 24-hour clock dialog when tapped. set() changes the time from code. */
class TimeField(private val ctx: Context, minutes: Int, private val onPick: (Int) -> Unit) {
    var minutes: Int = minutes
        private set

    val view: TextView = TextView(ctx).apply {
        textSize = 16f * Ui.fontScale
        setTextColor(Ui.TEXT)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = ctx.dp(48)
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        background = rippleBg(Ui.BAR, Ui.BORDER, ctx)
        isClickable = true
        setOnClickListener {
            TimePickerDialog(ctx, { _, h, m ->
                set(h * 60 + m)
                onPick(this@TimeField.minutes)
            }, this@TimeField.minutes / 60, this@TimeField.minutes % 60, true).show()
        }
    }

    init { show() }

    private fun show() {
        view.text = Timetable.fmtMin(minutes)
        view.contentDescription = "Time " + Timetable.fmtMin(minutes) + ", tap to change"
    }

    fun set(m: Int) {
        minutes = m
        show()
    }
}

fun Context.timeField(minutes: Int, onPick: (Int) -> Unit): TimeField = TimeField(this, minutes, onPick)

// ---------------------------------------------------------------- bottom navigation

/** One tab of the shared bottom bar. */
class NavTab(val icon: Int, val label: String, val cls: Class<*>, val withDot: Boolean = false)

/**
 * The shared bottom navigation bar. [active] is the currently shown tab: it gets a short
 * indicator bar above the icon plus cyan icon/label; the others stay dim until touched
 * (every tab has a ripple, so touch feedback is always visible).
 */
fun Activity.bottomNav(active: Class<*>): LinearLayout {
    val tabs = listOf(
        NavTab(R.drawable.ic_courses, "Courses", CoursesActivity::class.java),
        NavTab(R.drawable.ic_timetable, "Timetable", TimetableActivity::class.java),
        NavTab(R.drawable.ic_cat_class, "Attendance", AttendanceActivity::class.java),
        NavTab(R.drawable.ic_bell, "Notifications", NotificationsActivity::class.java, withDot = true),
        NavTab(R.drawable.ic_settings, "Settings", SettingsActivity::class.java),
    )
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Ui.BAR)
        minimumHeight = dp(64)
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }
    var dot: View? = null
    for (t in tabs) {
        val isActive = t.cls == active
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            contentDescription = t.label + if (isActive) ", current screen" else ""
            background = rippleBg(Ui.BAR, 0, this@bottomNav, 12)
            setOnClickListener {
                if (!isActive) startActivity(Intent(this@bottomNav, t.cls))
            }
        }
        // short indicator above the active icon (3dp pill, 28dp wide)
        if (isActive) {
            item.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Ui.CYAN) }
            }, LinearLayout.LayoutParams(dp(28), dp(3)).apply { bottomMargin = dp(3) })
        } else {
            item.addView(View(this), LinearLayout.LayoutParams(dp(28), dp(3)).apply { bottomMargin = dp(3) })
        }
        val holder = android.widget.FrameLayout(this)
        holder.addView(android.widget.ImageView(this).apply {
            setImageResource(t.icon)
            setColorFilter(if (isActive) Ui.CYAN else Ui.DIM)
        }, android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        if (t.withDot) {
            dot = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.RED) }
                visibility = View.GONE
            }
            holder.addView(dot, android.widget.FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(8)
            })
        }
        item.addView(holder, LinearLayout.LayoutParams(dp(46), dp(28)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        item.addView(TextView(this).apply {
            text = t.label
            textSize = if (isActive) 12f * Ui.fontScale else 11f * Ui.fontScale
            setTextColor(if (isActive) Ui.CYAN else Ui.DIM)
            if (isActive) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(2), dp(2), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    bar.tag = dot
    return bar
}

/** The unread dot on the bar's bell tab; returns null if this page's bar has no bell. */
fun LinearLayout.navBellDot(): View = tag as? View ?: View(this.context)

/** Standard top divider + nav bar, added to a page column (the weighted-content pattern). */
fun Activity.addNavBar(page: LinearLayout, active: Class<*>) {
    page.addView(View(this).apply { setBackgroundColor(Ui.BORDER) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    page.addView(bottomNav(active), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}
