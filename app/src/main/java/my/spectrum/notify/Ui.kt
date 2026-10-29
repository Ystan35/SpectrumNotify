package my.spectrum.notify

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import kotlin.math.abs
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Colours follow the phone's light/dark mode unless Settings > Appearance overrides it.
 *  Call Ui.setup(context) (or Activity.applyBars()) before reading them. */
object Ui {
    var dark = true
    var BG = 0
    var BAR = 0          // card / surface
    var BORDER = 0       // decorative card edges
    var BORDER_STRONG = 0 // edges that ARE the control (inputs, unselected chips): 3:1 or more
    var RAISED = 0       // sheets and pop-ups that sit above a card
    var RAISED_BORDER = 0
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

    /** Alert dialogs in the app's colour mode (the plain Dialog_Alert style is always dark). */
    val dialogTheme: Int
        get() = if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    init { palette(true, false) }

    /** d = dark mode, hc = Settings > High contrast (brighter text and accents, stronger edges). */
    private fun palette(d: Boolean, hc: Boolean) {
        dark = d
        if (d) {
            BG = 0xFF12151C.toInt(); BAR = 0xFF1B1F29.toInt(); BORDER = 0xFF2A3040.toInt()
            BORDER_STRONG = 0xFF646B7D.toInt(); RAISED = 0xFF20242E.toInt(); RAISED_BORDER = 0xFF3A4150.toInt()
            TEXT = 0xFFE4E2DD.toInt(); DIM = 0xFF9BA0AC.toInt(); CYAN = 0xFF8FA8FF.toInt()
            GREEN = 0xFF5FD5A0.toInt(); RED = 0xFFF88C8C.toInt(); YELLOW = 0xFFEFC878.toInt()
            ORANGE = 0xFFF2A96A.toInt(); PURPLE = 0xFFC0A5FF.toInt()
        } else {
            // warm light: cream surfaces instead of plain white, soft blue-grey structure.
            // every text colour reads at 4.5:1 or more on both BG and BAR
            BG = 0xFFF5F3EE.toInt(); BAR = 0xFFFDFBF7.toInt(); BORDER = 0xFFE2DED4.toInt()
            BORDER_STRONG = 0xFF8E8A80.toInt(); RAISED = 0xFFFFFFFF.toInt(); RAISED_BORDER = 0xFFD8D4CA.toInt()
            TEXT = 0xFF26251F.toInt(); DIM = 0xFF6B6A60.toInt(); CYAN = 0xFF4A5FC4.toInt()
            GREEN = 0xFF2E7D5B.toInt(); RED = 0xFFB44A4A.toInt(); YELLOW = 0xFF85600C.toInt()
            ORANGE = 0xFF985417.toInt(); PURPLE = 0xFF7452B8.toInt()
        }
        if (!hc) return
        if (d) {
            // high contrast dark: brighter text/dim, stronger accents (still warm-tinted, never pure white)
            TEXT = 0xFFFFFDF5.toInt(); DIM = 0xFFC9CBD3.toInt(); CYAN = 0xFFB0C2FF.toInt()
            GREEN = 0xFF83E8B8.toInt(); RED = 0xFFFFA8A8.toInt(); YELLOW = 0xFFFFD98F.toInt()
            ORANGE = 0xFFFFC08A.toInt(); PURPLE = 0xFFD6C0FF.toInt()
            BORDER = 0xFF4A5266.toInt(); BORDER_STRONG = 0xFF8A90A0.toInt(); RAISED_BORDER = 0xFF6A7184.toInt()
        } else {
            // high contrast light: near-black warm text, darker greys and accents
            TEXT = 0xFF14130E.toInt(); DIM = 0xFF4A4940.toInt(); CYAN = 0xFF2C3F9E.toInt()
            GREEN = 0xFF1F5C42.toInt(); RED = 0xFF8F3030.toInt(); YELLOW = 0xFF6E4C08.toInt()
            ORANGE = 0xFF83490F.toInt(); PURPLE = 0xFF5A3E92.toInt()
            BORDER = 0xFFBDB8AC.toInt(); BORDER_STRONG = 0xFF5E5B52.toInt(); RAISED_BORDER = 0xFF8E8A80.toInt()
        }
    }

    fun setup(ctx: Context) {
        val p = try { Prefs(ctx) } catch (_: Exception) { null }
        fontScale = when (p?.textScale ?: 0) { 1 -> 1.2f; 2 -> 1.4f; else -> 1.0f }
        touchDp = if (p?.bigTouch == true) 56 else 48
        val hc = p?.highContrast == true
        when (p?.themeMode ?: 0) {
            1 -> palette(false, hc)
            2 -> palette(true, hc)
            else -> {
                val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                palette(night == Configuration.UI_MODE_NIGHT_YES, hc)
            }
        }
        look = "${p?.themeMode ?: 0}:${p?.textScale ?: 0}:${p?.highContrast == true}:${p?.bigTouch == true}:$dark"
    }

    /** A dp amount for a touch target, honouring the Big touch targets setting. */
    fun touch(n: Int): Int = maxOf(touchDp, n)

    /** Same colour with a different opacity (0-255). */
    fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}

/** The appearance each open screen was built with, so only screens built with an older look rebuild. */
private val builtLook = java.util.WeakHashMap<Activity, String>()

/** Applies the appearance settings and colours the system bars to match. */
fun Activity.applyBars() {
    // every page draws the shared header; hide the system title bar.
    // requestWindowFeature is only legal before setContentView; guard it so
    // recreate() paths (theme toggle, look change) can never crash here.
    try { requestWindowFeature(Window.FEATURE_NO_TITLE) } catch (_: Exception) { }
    Ui.setup(this)
    builtLook[this] = Ui.look
    window.statusBarColor = Ui.BG
    window.navigationBarColor = Ui.BG
    if (!Ui.dark) {
        var f = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = f
    }
}

/**
 * Call at the top of onResume: rebuilds the screen when the appearance changed while it was in the background.
 * A screen that just rebuilt itself already has the new look, so it is not rebuilt a second time
 * (the old global flag made every theme change redraw twice, ~2-3 s of frozen frames).
 */
fun Activity.recreateIfLookChanged() {
    Ui.setup(this)
    val built = builtLook[this]
    if (built != null && built != Ui.look) recreate()
}

fun Context.dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

// ---------------------------------------------------------------- design tokens

/** The type scale: every text size in the app is one of these roles (always times Ui.fontScale). */
object Type {
    const val DISPLAY = 28f   // greeting, the class on the hero card
    const val HEADLINE = 22f  // page and sheet titles, hero headline
    const val TITLE = 18f     // card titles, large figures
    const val BODY_L = 16f    // setting names, emphasised body
    const val BODY = 14f      // body text, buttons
    const val LABEL = 13f     // chips, meta lines, section labels
    const val CAPTION = 12f   // help text, timestamps; nothing is smaller
}

/** Corner radii in dp: small controls, cards, big surfaces, and fully round (clamped to a pill). */
object Radius {
    const val SM = 8
    const val MD = 14
    const val LG = 22
    const val PILL = 100
}

/** Emoji that some texts start with (shared with notifications), drawn in the app as the matching vector icon. */
private val GLYPH_ICONS = listOf(
    "📍" to R.drawable.ic_place, "💻" to R.drawable.ic_laptop, "🚌" to R.drawable.lk_bus, "🚶" to R.drawable.ic_walk,
)

/** A plain text view in the app's type scale. A leading 📍 💻 🚌 or 🚶 becomes a tinted icon (see [iconText]). */
fun Context.tv(text: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView {
    val g = if (text is String) GLYPH_ICONS.firstOrNull { text.startsWith(it.first) } else null
    if (g != null) return iconText(g.second, text.toString().removePrefix(g.first).trimStart(), size, color, bold)
    return TextView(this).apply {
        this.text = text
        textSize = size * Ui.fontScale
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}

/**
 * Text with a vector icon in front (and optionally one after, e.g. a chevron), both tinted [color] and sized to the text.
 * Used instead of emoji and arrow characters, which look different per phone and are read aloud by TalkBack.
 */
fun Context.iconText(icon: Int, text: CharSequence, size: Float, color: Int, bold: Boolean = false, trailing: Int = 0): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size * Ui.fontScale
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        val px = (size * Ui.fontScale * resources.displayMetrics.scaledDensity * 1.25f).toInt()
        fun d(res: Int) = if (res == 0) null else getDrawable(res)?.mutate()?.apply { setBounds(0, 0, px, px); setTint(color) }
        setCompoundDrawablesRelative(d(icon), null, d(trailing), null)
        compoundDrawablePadding = dp(6)
        gravity = Gravity.CENTER_VERTICAL
    }

/** The one section heading style: small bold capitals in DIM ("DUE SOON", "COURSES"). */
fun Context.sectionLabel(text: String): TextView =
    tv(text.uppercase(), Type.LABEL, Ui.DIM, true).apply {
        letterSpacing = 0.08f
        isAllCaps = false
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

/** Button emphasis: one PRIMARY per card or screen, SECONDARY for the rest, TERTIARY for quiet text actions. */
enum class Emphasis { PRIMARY, SECONDARY, TERTIARY }

/** The one button: a pill at least one touch target tall. [accent] tints it (course colour, CYAN by default). */
fun Context.button(label: String, emphasis: Emphasis, accent: Int = Ui.CYAN, onClick: () -> Unit): TextView {
    val c = this
    return tv(label, Type.BODY, if (emphasis == Emphasis.PRIMARY) onAccent() else accent, true).apply {
        gravity = Gravity.CENTER
        minHeight = c.dp(Ui.touch(48))
        minWidth = c.dp(Ui.touch(48))
        // vertical padding so a label that wraps at large text sizes still fits inside the pill
        setPadding(c.dp(if (emphasis == Emphasis.TERTIARY) 12 else 18), c.dp(6), c.dp(if (emphasis == Emphasis.TERTIARY) 12 else 18), c.dp(6))
        background = GradientDrawable().apply {
            cornerRadius = c.dp(Radius.PILL).toFloat()
            when (emphasis) {
                Emphasis.PRIMARY -> setColor(accent)
                Emphasis.SECONDARY -> { setColor(0); setStroke(c.dp(1), accent) }
                Emphasis.TERTIARY -> setColor(0)
            }
        }
        foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Ui.tint(if (emphasis == Emphasis.PRIMARY) onAccent() else accent, 60)),
            null, GradientDrawable().apply { cornerRadius = c.dp(Radius.PILL).toFloat(); setColor(0xFFFFFFFF.toInt()) },
        )
        isClickable = true
        setOnClickListener { onClick() }
    }
}

/** The one and only app header: a slim brand row (logo + name + theme toggle), no card, so the page title leads.
 *  homeMode = shown on the home screen (not clickable); elsewhere tapping it goes Home. */
fun Activity.headerRow(homeMode: Boolean): LinearLayout {
    val titleRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), 0, 0, 0)
    }
    titleRow.addView(android.widget.ImageView(this).apply {
        setImageResource(R.mipmap.ic_launcher_round)
        contentDescription = "Spectrum Notify"
    }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(10) })
    titleRow.addView(TextView(this).apply {
        text = "Spectrum Notify"
        textSize = Type.BODY_L * Ui.fontScale
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.TEXT)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    titleRow.addView(ImageButton(this).apply {
        setImageResource(if (Ui.dark) R.drawable.ic_sun else R.drawable.ic_moon)
        setColorFilter(Ui.CYAN)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Ui.tint(Ui.CYAN, 60)), null,
            roundBg(0xFFFFFFFF.toInt(), 0, this@headerRow, Radius.PILL),
        )
        contentDescription = if (Ui.dark) "Switch to light colour mode" else "Switch to dark colour mode"
        setOnClickListener {
            val pr = try { Prefs(this@headerRow) } catch (_: Exception) { null }
            if (pr != null) {
                pr.themeMode = if (Ui.dark) 1 else 2
                recreate()
            }
        }
    }, LinearLayout.LayoutParams(dp(Ui.touch(48)), dp(Ui.touch(48))))
    if (!homeMode) {
        // only logo + title go Home; the theme toggle keeps its own click
        titleRow.isClickable = false
        titleRow.isFocusable = false
        (titleRow.getChildAt(0) as View).setOnClickListener { goToHome() }
        (titleRow.getChildAt(1) as View).setOnClickListener { goToHome() }
    }
    return titleRow
}

/** Dark screen with a top bar (back arrow + title) and a scrolling column; returns the column.
 *  Pass [navTo] (the activity of the tab to highlight) to also show the shared bottom nav bar. */
fun Activity.screen(
    title: String,
    actionIcon: Int = 0,
    actionDesc: String = "",
    onAction: (() -> Unit)? = null,
    navTo: Class<*>? = null,
    onRefresh: (() -> Unit)? = null,
): LinearLayout {
    applyBars()
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.BG)
    }

    root.addView(headerRow(homeMode = this is MainActivity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(dp(16), dp(8), dp(12), 0)
    })
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(6))
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
        textSize = Type.HEADLINE * Ui.fontScale
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Ui.TEXT)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (actionIcon != 0 && onAction != null) {
        top.addView(
            ImageButton(this).apply {
                setImageResource(actionIcon)
                setColorFilter(Ui.CYAN)
                background = rippleBg(Ui.BAR, Ui.BORDER, this@screen, Radius.PILL)
                contentDescription = actionDesc
                setOnClickListener { onAction() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
    }
    root.addView(top)
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // tabs: 80dp at the end so the last row scrolls clear of the floating Aa button
        setPadding(dp(20), dp(4), dp(20), if (navTo != null) dp(80) else dp(32))
    }
    val sv = ScrollView(this).apply { addView(col) }
    if (onRefresh != null) attachPullToRefresh(sv, onRefresh)
    root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    if (navTo != null) addNavBar(root, navTo)
    if (navTo != null) {
        // tabs: the floating toolbox group (Aa + back to top) floats over the page
        val frame = FrameLayout(this)
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)
        attachToolbox(frame, sv)
    } else {
        setContentView(root)
    }
    return col
}

/** Navigate back to the home screen, clearing whatever sub-page stack is open. */
fun Activity.goToHome() {
    val i = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    startActivity(i)
}

fun LinearLayout.label(t: String, size: Float = Type.CAPTION, color: Int = Ui.DIM, top: Int = 12): TextView {
    val tv = context.tv(t, size, color)
    tv.setPadding(0, context.dp(top), 0, 0)
    addView(tv)
    return tv
}

fun roundBg(fill: Int, stroke: Int, ctx: Context, radius: Int = Radius.SM): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    setStroke(ctx.dp(1), stroke)
    cornerRadius = ctx.dp(radius).toFloat()
}

/** roundBg with a ripple, so taps give visible feedback (HCI: pressed state on every control). */
fun rippleBg(fill: Int, stroke: Int, ctx: Context, radius: Int = Radius.SM): android.graphics.drawable.RippleDrawable {
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
    et.textSize = Type.BODY_L * Ui.fontScale
    et.background = rippleBg(Ui.BAR, Ui.BORDER_STRONG, context)
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

fun LinearLayout.btn(text: String, onClick: () -> Unit): Button = btn(text, Emphasis.SECONDARY, onClick)

/** A full-width button; same look as [button] (SECONDARY unless told otherwise). */
fun LinearLayout.btn(text: String, emphasis: Emphasis, onClick: () -> Unit): Button {
    val b = Button(context)
    b.text = text
    b.isAllCaps = false
    b.stateListAnimator = null
    b.textSize = Type.BODY * Ui.fontScale
    b.setTypeface(b.typeface, Typeface.BOLD)
    b.setTextColor(if (emphasis == Emphasis.PRIMARY) onAccent() else Ui.CYAN)
    b.background = when (emphasis) {
        Emphasis.PRIMARY -> rippleBg(Ui.CYAN, Ui.CYAN, context, Radius.PILL)
        Emphasis.SECONDARY -> rippleBg(Ui.BAR, Ui.CYAN, context, Radius.PILL)
        // no fill or edge (sits on any surface); the mask still shows the ripple
        Emphasis.TERTIARY -> android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Ui.tint(Ui.CYAN, 60)), null,
            roundBg(0xFFFFFFFF.toInt(), 0, context, Radius.PILL),
        )
    }
    b.setOnClickListener { onClick() }
    addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(Ui.touch(48))).apply {
        topMargin = context.dp(10)
    })
    return b
}

/** Adds the one section heading style ([sectionLabel]) with [top] dp above it. */
fun LinearLayout.sectionHeading(t: String, top: Int = 24): TextView =
    context.sectionLabel(t).also { it.setPadding(0, context.dp(top), 0, 0); addView(it) }

/** Section heading on a settings page. */
fun LinearLayout.section(title: String, intro: String = "") {
    val t = context.sectionLabel(title)
    t.setPadding(0, context.dp(28), 0, 0)
    addView(t)
    val line = android.view.View(context)
    line.setBackgroundColor(Ui.BORDER)
    addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = context.dp(6) })
    if (intro.isNotEmpty()) label(intro, Type.CAPTION, Ui.DIM, 8)
}

/** One setting: bold name, plain-language explanation, then the input box. */
fun LinearLayout.setting(title: String, help: String, value: String, hint: String = "", kind: Int = 0): EditText {
    label(title, Type.BODY_L, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
    label(help, Type.CAPTION, Ui.DIM, 2)
    return field(value, hint, false, kind)
}

/** A button with a one-line explanation under it. */
fun LinearLayout.action(text: String, help: String, onClick: () -> Unit): Button {
    val b = btn(text, onClick)
    label(help, Type.CAPTION, Ui.DIM, 4)
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
    size: Float = Type.BODY,
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
            cornerRadius = c.dp(Radius.PILL).toFloat()
            if (selected) setColor(accent) else { setColor(Ui.BAR); setStroke(c.dp(1), Ui.BORDER_STRONG) }
        }
        foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Ui.tint(accent, 80)),
            null,
            GradientDrawable().apply { cornerRadius = c.dp(Radius.PILL).toFloat() },
        )
        isClickable = true
        setOnClickListener { onClick() }
    }
}

/**
 * A segmented control: one outlined bar split into equal parts, the chosen part filled. For switching views
 * (Day / Week / Month); chips are for picking values, so the two never look alike.
 */
fun LinearLayout.segmented(labels: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
    val ctx = context
    val bar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
        background = roundBg(Ui.BAR, Ui.BORDER_STRONG, ctx, Radius.MD)
    }
    labels.forEachIndexed { i, l ->
        val on = i == selected
        bar.addView(ctx.tv(l, Type.BODY, if (on) onAccent() else Ui.TEXT, on).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dp(Ui.touch(48) - 6)
            background = if (on) roundBg(Ui.CYAN, Ui.CYAN, ctx, Radius.MD - 3)
                else android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Ui.tint(Ui.CYAN, 60)), null,
                    roundBg(0xFFFFFFFF.toInt(), 0, ctx, Radius.MD - 3),
                )
            isClickable = true
            isSelected = on
            contentDescription = l + if (on) ", selected" else ""
            setOnClickListener { if (!on) onPick(i) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = ctx.dp(8)
    })
    return bar
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
            val c = ctx.chip(l, i in sel, size = Type.LABEL, padH = 2) {
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
                textSize = Type.TITLE * Ui.fontScale
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
        textSize = Type.BODY_L * Ui.fontScale
        setTextColor(Ui.TEXT)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = ctx.dp(48)
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        background = rippleBg(Ui.BAR, Ui.BORDER_STRONG, ctx)
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
        NavTab(R.drawable.ic_home, "Home", MainActivity::class.java),
        NavTab(R.drawable.ic_courses, "Courses", CoursesActivity::class.java),
        NavTab(R.drawable.ic_timetable, "Timetable", TimetableActivity::class.java),
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
    val activeIdx = tabs.indexOfFirst { it.cls == active }
    for ((idx, t) in tabs.withIndex()) {
        val isActive = t.cls == active
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            contentDescription = t.label + if (isActive) ", current screen" else ""
            background = rippleBg(Ui.BAR, 0, this@bottomNav, Radius.MD)
            setOnClickListener {
                if (!isActive) {
                    val i = Intent(this@bottomNav, t.cls)
                    if (t.cls == MainActivity::class.java) {
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(i)
                    // tabs replace each other instead of piling up (Back from any tab goes Home)
                    if (active != MainActivity::class.java && t.cls != MainActivity::class.java) finish()
                    // slide toward the side the tab sits on, like switching tabs, not opening a page
                    if (idx > activeIdx) overridePendingTransition(R.anim.tab_in_right, R.anim.tab_out_left)
                    else overridePendingTransition(R.anim.tab_in_left, R.anim.tab_out_right)
                }
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
            textSize = Type.CAPTION * Ui.fontScale
            setTextColor(if (isActive) Ui.CYAN else Ui.DIM)
            if (isActive) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            minHeight = dp(16)
            setPadding(0, dp(2), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    bar.tag = dot
    return bar
}

/** A styled dropdown (the same look as the UM-timetable picker). */
fun Activity.spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): android.widget.Spinner {
    val sp = android.widget.Spinner(this)
    val ad = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        private fun style(v: View, bg: Int): View {
            (v as TextView).apply {
                setTextColor(Ui.TEXT)
                textSize = Type.BODY * Ui.fontScale
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(bg)
            }
            return v
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = style(super.getView(position, convertView, parent), 0)
            (v as TextView).apply {
                // the closed box: snug around its text; the Spinner itself keeps the 48dp touch height
                setPadding(dp(12), dp(6), dp(10), dp(6))
                compoundDrawablePadding = dp(4)
                text = getItem(position) ?: ""
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_expand_more, 0)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.DIM)
            }
            return v
        }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = style(super.getDropDownView(position, convertView, parent), Ui.BAR)
    }
    ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    sp.adapter = ad
    sp.background = roundBg(Ui.BAR, Ui.BORDER_STRONG, this)
    sp.setPopupBackgroundDrawable(roundBg(Ui.BAR, Ui.BORDER, this))
    sp.setSelection(selected, false)
    sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }
    return sp
}

/** Pull-down-to-refresh for any ScrollView: pull past 120dp at the very top to fire [onRefresh].
 *  Shows a small "pull / release" hint bar that grows with the finger. */
fun Activity.attachPullToRefresh(sv: ScrollView, onRefresh: () -> Unit) {
    var startY = 0f
    var pulling = false
    var fired = false
    var hint: TextView? = null
    var dist = 0f
    sv.setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.rawY; pulling = sv.scrollY == 0; fired = false; dist = 0f
                false
            }
            MotionEvent.ACTION_MOVE -> {
                if (pulling && !fired) {
                    if (sv.scrollY > 0) { pulling = false; hint?.let { (it.parent as? ViewGroup)?.removeView(it) }; return@setOnTouchListener false }
                    dist = ev.rawY - startY
                    if (dist > dp(24)) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        if (hint == null) {
                            hint = TextView(this).apply {
                                gravity = Gravity.CENTER
                                textSize = Type.CAPTION * Ui.fontScale
                                setTextColor(Ui.CYAN)
                                minHeight = dp(36)
                            }
                            (v.parent as? ViewGroup)?.addView(hint, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0)))
                        }
                        hint?.text = if (dist > dp(120)) "Release to check Spectrum" else "Pull down to check…"
                        hint?.layoutParams = (hint?.layoutParams ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)).also { it.height = (dist * 0.5f).toInt().coerceAtMost(dp(60)) }
                        hint?.requestLayout()
                    }
                    return@setOnTouchListener true   // consume while pulling so the list does not fight
                }
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling && !fired && dist > dp(120)) {
                    fired = true
                    onRefresh()
                }
                pulling = false
                hint?.let { h ->
                    h.animate().alpha(0f).setDuration(250).withEndAction { (h.parent as? ViewGroup)?.removeView(h) }.start()
                }
                hint = null
                false
            }
            else -> false
        }
    }
}

/** The unread dot on the bar's bell tab; returns null if this page's bar has no bell. */
fun LinearLayout.navBellDot(): View = tag as? View ?: View(this.context)

/** Standard top divider + nav bar, added to a page column (the weighted-content pattern). */
/**
 * A bar above the bottom navigation: "[message]   UNDO". Disappears after 6 s; tapping Undo runs [onUndo].
 * Used right after Hide / Remove instead of a confirm dialog and "restore it from Settings".
 */
fun Activity.undoBar(message: String, onUndo: () -> Unit) {
    val root = findViewById<FrameLayout>(android.R.id.content) ?: return
    root.findViewWithTag<View>("undoBar")?.let { root.removeView(it) }
    val bar = LinearLayout(this).apply {
        tag = "undoBar"
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(4), dp(4), dp(4))
        background = roundBg(if (Ui.dark) 0xFFE8EAF0.toInt() else 0xFF2A2F3A.toInt(), 0, this@undoBar, Radius.MD)
        elevation = dp(6).toFloat()
        isClickable = true   // the bar itself swallows taps meant for the list behind it
    }
    val fg = if (Ui.dark) 0xFF12151C.toInt() else 0xFFFFFFFF.toInt()
    bar.addView(TextView(this).apply {
        text = message
        textSize = Type.BODY * Ui.fontScale
        setTextColor(fg)
        maxLines = 2
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val dismiss = Runnable { root.removeView(bar) }
    bar.addView(TextView(this).apply {
        text = "UNDO"
        textSize = Type.BODY * Ui.fontScale
        setTextColor(if (Ui.dark) Ui.HERO_A else 0xFF9DB2FF.toInt())
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        minHeight = dp(Ui.touch(48)); minWidth = dp(Ui.touch(64))
        isClickable = true
        contentDescription = "Undo: $message"
        setOnClickListener { bar.removeCallbacks(dismiss); root.removeView(bar); onUndo() }
    })
    root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
        setMargins(dp(12), 0, dp(12), dp(88))   // clear of the bottom navigation
    })
    bar.announceForAccessibility("$message. Undo available.")
    bar.postDelayed(dismiss, 6000)
}

/**
 * Lets background checks run: Android's own one-tap "allow in background" prompt (this is exactly what
 * [batteryUnrestricted] reads), falling back to the battery list on phones that block the prompt.
 */
fun Activity.askBatteryUnrestricted() {
    try {
        startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    } catch (_: Exception) {
        try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (_: Exception) { android.widget.Toast.makeText(this, "Open Settings → Apps → Spectrum Notify → Battery and choose Unrestricted", android.widget.Toast.LENGTH_LONG).show() }
    }
}

/** True when Android reports the app as not battery-optimised (Unrestricted). */
fun Context.batteryUnrestricted(): Boolean =
    getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

fun Activity.addNavBar(page: LinearLayout, active: Class<*>) {
    page.addView(View(this).apply { setBackgroundColor(Ui.BORDER) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    page.addView(bottomNav(active), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}


// ---------------------------------------------------------------- floating toolbox (all tabs)

/** The draggable "Aa" toolbox button + its quick sheet. Lives on every tab. */
fun Activity.attachToolbox(root: FrameLayout, scroll: ScrollView? = null) {
    val fab = TextView(this).apply {
        text = "Aa"
        textSize = Type.TITLE * Ui.fontScale
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(Ui.CYAN)
        background = rippleBg(Ui.BAR, Ui.CYAN, this@attachToolbox, Radius.PILL)
        contentDescription = "Toolbox: appearance, UM links, academic calendar. Tap to open, hold and drag to move."
        elevation = dp(6).toFloat()
    }
    val fabLp = FrameLayout.LayoutParams(dp(Ui.touch(48)), dp(Ui.touch(48)), Gravity.BOTTOM or Gravity.END).apply {
        rightMargin = dp(14); bottomMargin = dp(78)
    }
    root.addView(fab, fabLp)

    // "back to top" ↑ sits just above Aa as one group: it follows Aa when dragged and only shows
    // once the page is scrolled down about a screen
    val upSize = dp(Ui.touch(44))
    val up = ImageView(this).apply {
        setImageResource(R.drawable.ic_arrow_up)
        setColorFilter(Ui.CYAN)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = rippleBg(Ui.BAR, Ui.CYAN, this@attachToolbox, Radius.PILL)
        contentDescription = "Back to top"
        elevation = dp(6).toFloat()
        visibility = View.GONE
        alpha = 0f
    }
    val upLp = FrameLayout.LayoutParams(upSize, upSize, Gravity.BOTTOM or Gravity.END)
    fun sync() {
        upLp.rightMargin = fabLp.rightMargin + (fabLp.width - upSize) / 2
        upLp.bottomMargin = fabLp.bottomMargin + fabLp.height + dp(10)
        if (up.parent != null) root.updateViewLayout(up, upLp)
    }
    sync()
    root.addView(up, upLp)
    if (scroll != null) {
        fun show(on: Boolean) {
            if (on == (up.visibility == View.VISIBLE)) return
            up.animate().cancel()
            if (on) { up.visibility = View.VISIBLE; up.animate().alpha(1f).setDuration(150).start() }
            else up.animate().alpha(0f).setDuration(150).withEndAction { up.visibility = View.GONE }.start()
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> show(y > scroll.height * 0.8f) }
        up.setOnClickListener { scroll.smoothScrollTo(0, 0); show(false) }
    }
    setupFabDrag(fab, fabLp, root, onMoved = { sync() }) { showQuickSheet(root, fabLp) }
}

fun Activity.showQuickSheet(root: FrameLayout, fabLp: FrameLayout.LayoutParams) {
    if (root.findViewWithTag<View>("quicksheet") != null) return
    val p = Prefs(this)

    val sheet = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(10))
    }
    // one row style for everything: icon, label, optional value, trailing icon (chevron = in the app, open = leaves it)
    fun row(icon: Int, name: String, value: String = "", trailing: Int = R.drawable.ic_chevron_right, desc: String = name, onTap: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(Ui.touch(48))
            setPadding(dp(8), 0, dp(8), 0)
            isClickable = true
            // no fill of its own (the sheet is the surface); the mask still shows the ripple
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.tint(Ui.CYAN, 60)), null,
                roundBg(0xFFFFFFFF.toInt(), 0, this@showQuickSheet, Radius.SM),
            )
            contentDescription = desc + if (value.isNotEmpty()) ": $value" else ""
            setOnClickListener { onTap() }
        }
        r.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(Ui.CYAN)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        r.addView(tv(name, Type.BODY, Ui.TEXT), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value.isNotEmpty()) r.addView(tv(value, Type.LABEL, Ui.CYAN, true).apply { setPadding(dp(8), 0, dp(4), 0) })
        r.addView(ImageView(this).apply {
            setImageResource(trailing)
            setColorFilter(Ui.DIM)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        sheet.addView(r)
    }
    /** Section label; [action] puts a small text button at its right end (always in view with its section). */
    fun header(t: String, first: Boolean = false, action: String = "", actionDesc: String = action, onAction: () -> Unit = {}) {
        val h = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(if (first) 6 else 10), 0, 0)
        }
        h.addView(sectionLabel(t), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action.isNotEmpty()) h.addView(iconText(R.drawable.ic_edit, action, Type.LABEL, Ui.CYAN, true).apply {
            minHeight = dp(Ui.touch(48))
            setPadding(dp(12), 0, dp(8), 0)
            isClickable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.tint(Ui.CYAN, 60)), null,
                roundBg(0xFFFFFFFF.toInt(), 0, this@showQuickSheet, Radius.PILL),
            )
            contentDescription = actionDesc
            setOnClickListener { onAction() }
        })
        sheet.addView(h)
    }

    header("Appearance", first = true)
    row(if (Ui.dark) R.drawable.ic_moon else R.drawable.ic_sun, "Colour mode", listOf("Phone", "Light", "Dark")[p.themeMode],
        desc = "Colour mode, tap to change") {
        p.themeMode = (p.themeMode + 1) % 3
        recreate()
    }
    row(R.drawable.ic_text_size, "Text size", listOf("Normal", "Large", "Extra large")[p.textScale],
        desc = "Text size, tap to change") {
        p.textScale = (p.textScale + 1) % 3
        recreate()
    }
    row(R.drawable.ic_palette, "High contrast", if (p.highContrast) "On" else "Off",
        desc = "High contrast, tap to change") {
        p.highContrast = !p.highContrast
        recreate()
    }
    row(R.drawable.ic_settings, "More appearance settings") {
        closeQuickSheet(root)
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN, "appearance"))
    }

    header("Go to")
    row(R.drawable.lk_bus, "UM Shuttle", desc = "UM Shuttle: plan a trip, next buses and routes") {
        closeQuickSheet(root)
        startActivity(Intent(this, ShuttleActivity::class.java))
    }
    row(R.drawable.lk_calendar, "Academic calendar", trailing = R.drawable.ic_open_new, desc = "Academic calendar 2026/2027, opens the PDF") {
        closeQuickSheet(root)
        startActivity(Intent(this, CalendarWebActivity::class.java))
    }
    row(R.drawable.ic_date_range, "Semester dates") {
        closeQuickSheet(root)
        AcademicCalendarEditor.open(this) { recreate() }
    }

    // Edit sits in the heading: at the bottom of the list it was out of sight whenever the sheet had to scroll
    header("UM links", action = "Edit", actionDesc = "Edit UM links") {
        closeQuickSheet(root)
        // the editor lives in Settings → UM links (icons, order, course pages)
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN, "umlinks"))
    }
    for (l in Shortcuts.all(this)) {
        row(Shortcuts.iconRes(l.icon), l.name, trailing = R.drawable.ic_open_new, desc = "Open ${l.name}") {
            closeQuickSheet(root)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { }
        }
    }

    // scrolls when the links make it taller than the space next to the button
    val sheetBox = ScrollView(this).apply {
        tag = "quicksheet"
        isVerticalScrollBarEnabled = false
        background = roundBg(Ui.RAISED, Ui.RAISED_BORDER, this@showQuickSheet, Radius.LG)
        elevation = dp(8).toFloat()
        clipToOutline = true
        addView(sheet)
    }

    val dim = View(this).apply {
        tag = "quicksheetdim"
        setBackgroundColor(0x66000000)
        elevation = dp(7).toFloat()
    }
    dim.setOnClickListener { closeQuickSheet(root) }
    root.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    // Anchor the sheet to the toolbox wherever it was dragged: horizontal edge with more
    // room wins (sheet is 230dp wide), and it opens above the button unless the button is
    // near the top, in which case it opens below.
    val sheetW = dp(260)
    val sheetHGuess = dp(420)
    val spaceRight = root.width - fabLp.rightMargin - fabLp.width
    val spaceLeft = fabLp.rightMargin
    val onRight = spaceRight >= sheetW || spaceRight >= spaceLeft
    val fabTopY = root.height - fabLp.bottomMargin - fabLp.height
    val openBelow = fabTopY < sheetHGuess
    val lp = FrameLayout.LayoutParams(sheetW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        if (onRight) {
            gravity = Gravity.END
            rightMargin = dp(14)
        } else {
            gravity = Gravity.START
            leftMargin = dp(14)
        }
        if (openBelow) {
            gravity = gravity or Gravity.TOP
            topMargin = fabTopY + fabLp.height + dp(10)
            bottomMargin = dp(16)   // the far margin caps the height; the sheet scrolls past it
        } else {
            gravity = gravity or Gravity.BOTTOM
            bottomMargin = fabLp.bottomMargin + fabLp.height + dp(10)
            topMargin = dp(16)
        }
    }
    root.addView(sheetBox, lp)
}

fun Activity.closeQuickSheet(root: FrameLayout) {
    root.findViewWithTag<View>("quicksheet")?.let { root.removeView(it) }
    root.findViewWithTag<View>("quicksheetdim")?.let { root.removeView(it) }
}

/** Tap opens the quick sheet; holding still and dragging moves the button and snaps it to the nearest edge. */
fun Activity.setupFabDrag(fab: TextView, fabLp: FrameLayout.LayoutParams, root: FrameLayout, onMoved: () -> Unit = {}, onTap: () -> Unit) {
    var downX = 0f
    var downY = 0f
    var startX = 0
    var startY = 0
    var moved = false
    var dragging = false
    var pressTime = 0L
    fab.setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                startX = fabLp.rightMargin; startY = fabLp.bottomMargin
                moved = false; dragging = false; pressTime = System.currentTimeMillis()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - downX).toInt()
                val dy = (ev.rawY - downY).toInt()
                if (!dragging && (abs(dx) > dp(8) || abs(dy) > dp(8))) dragging = true
                if (dragging) {
                    moved = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    // margins are measured from BOTTOM/END, so the button tracks the finger
                    fabLp.rightMargin = (startX - dx).coerceIn(0, root.width - v.width)
                    fabLp.bottomMargin = (startY - dy).coerceIn(dp(64), root.height - v.width)
                    root.updateViewLayout(v, fabLp)
                    onMoved()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    // snap to the nearer horizontal edge
                    val toEnd = fabLp.rightMargin < (root.width - v.width) / 2
                    fabLp.rightMargin = if (toEnd) dp(14) else root.width - v.width - dp(14)
                    root.updateViewLayout(v, fabLp)
                    onMoved()
                    moved = false  // a finished drag never counts as a tap
                } else if (System.currentTimeMillis() - pressTime < 600) {
                    onTap()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; true }
            else -> false
        }
    }
}
