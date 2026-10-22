package my.spectrum.notify

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
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

/** Colours follow the phone's light/dark mode. Call Ui.setup(context) (or Activity.applyBars()) before reading them. */
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

    init { palette(true) }

    private fun palette(d: Boolean) {
        dark = d
        if (d) {
            BG = 0xFF0E1320.toInt(); BAR = 0xFF171E30.toInt(); BORDER = 0xFF28324A.toInt()
            TEXT = 0xFFE9EDF7.toInt(); DIM = 0xFF9AA6C1.toInt(); CYAN = 0xFF7A9BFF.toInt()
            GREEN = 0xFF4ADE9A.toInt(); RED = 0xFFFF7A7A.toInt(); YELLOW = 0xFFFFC857.toInt()
            ORANGE = 0xFFFFA45C.toInt(); PURPLE = 0xFFB794FF.toInt()
        } else {
            BG = 0xFFF3F5FB.toInt(); BAR = 0xFFFFFFFF.toInt(); BORDER = 0xFFDDE3F0.toInt()
            TEXT = 0xFF161C2E.toInt(); DIM = 0xFF59647E.toInt(); CYAN = 0xFF2F55E4.toInt()
            GREEN = 0xFF12805A.toInt(); RED = 0xFFC93636.toInt(); YELLOW = 0xFF9A6700.toInt()
            ORANGE = 0xFFC25E0B.toInt(); PURPLE = 0xFF6B46C8.toInt()
        }
    }

    fun setup(ctx: Context) {
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        palette(night == Configuration.UI_MODE_NIGHT_YES)
    }

    /** Same colour with a different opacity (0-255). */
    fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}

/** Picks light/dark colours and colours the system bars to match. */
fun Activity.applyBars() {
    Ui.setup(this)
    window.statusBarColor = Ui.BG
    window.navigationBarColor = Ui.BG
    if (!Ui.dark) {
        var f = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = f
    }
}

fun Context.dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

/** Dark screen with a top bar (back arrow + title) and a scrolling column; returns the column. */
fun Activity.screen(
    title: String,
    actionIcon: Int = 0,
    actionDesc: String = "",
    onAction: (() -> Unit)? = null,
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
    top.addView(TextView(this).apply {
        text = title
        textSize = 20f
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
        setPadding(dp(20), dp(4), dp(20), dp(32))
    }
    root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    setContentView(root)
    return col
}

fun LinearLayout.label(t: String, size: Float = 12f, color: Int = Ui.DIM, top: Int = 12): TextView {
    val tv = TextView(context)
    tv.text = t
    tv.textSize = size
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
    et.textSize = 15f
    et.background = rippleBg(Ui.BAR, Ui.BORDER, context)
    et.minHeight = context.dp(48)
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
    b.textSize = 14f
    b.setTextColor(Ui.CYAN)
    b.background = rippleBg(Ui.BAR, Ui.CYAN, context, 24)
    b.setOnClickListener { onClick() }
    addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48)).apply {
        topMargin = context.dp(10)
    })
    return b
}

/** Section heading on a settings page. */
fun LinearLayout.section(title: String, intro: String = "") {
    val t = TextView(context)
    t.text = title
    t.textSize = 13f
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
        textSize = size
        gravity = Gravity.CENTER
        minHeight = c.dp(48)
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
                textSize = 18f
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
        textSize = 16f
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
