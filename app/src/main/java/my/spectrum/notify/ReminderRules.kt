package my.spectrum.notify

import java.time.ZonedDateTime
import java.util.Locale

/** The two small decisions behind "remind me about X on 14 Nov, 2 days before". Plain functions, so they can be tested. */
object ReminderRules {
    /** "2 days before", "an hour before", "3 hrs earlier". */
    val OFFSET = Regex("""\b(a|an|one|\d+)\s*(days?|hours?|hrs?)\s+(before|earlier)\b""", RegexOption.IGNORE_CASE)

    private val DAY_WORD = Regex(
        """\b(the day after tomorrow|tomorrow|tonight|today|this weekend|next week|""" +
            """(?:next\s+|this\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thu(?:rs?)?|fri|sat|sun))\b""",
        RegexOption.IGNORE_CASE)

    /**
     * The day the sentence names, wherever it stands ("remind me tomorrow in 12"); null when it names none. The language
     * model sometimes tags no date, and then Oli used to pick today: the word "tomorrow" was lost.
     */
    fun dayWord(text: String): String? = DAY_WORD.find(text)?.value?.lowercase(Locale.ROOT)

    /** True for the hours just after midnight, when "tomorrow" is ambiguous: people still up call the day they wake into "tomorrow". */
    fun lateNight(hour: Int): Boolean = hour in 0..4

    /** A time written as a bare "12" or "12:00" (no am/pm): noon or midnight? Other bare hours are left to the model. */
    private val BARE_TWELVE = Regex("""\b12(?:[:.]00)?\b(?!\s*(?:am|pm|noon|midnight|hrs?\b|:\d))""", RegexOption.IGNORE_CASE)
    fun bareTwelve(text: String, hasClock: Boolean): Boolean = !hasClock && BARE_TWELVE.containsMatchIn(text) && !Regex("""\b12\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** [text] with its first bare "12" written as [with] ("12pm" / "12am"). */
    fun withTwelve(text: String, with: String): String = BARE_TWELVE.replaceFirst(text, with)

    /** The alert offset the sentence asks for, in minutes; null when it names none. */
    fun offsetMinutes(text: String): Int? {
        val m = OFFSET.find(text) ?: return null
        val n = m.groupValues[1].lowercase(Locale.ROOT).let { if (it in setOf("a", "an", "one")) 1L else it.toLong() }
        return (if (m.groupValues[2].startsWith("d", true)) n * 24 * 60 else n * 60).toInt()
    }

    /**
     * [offsetMin] when the alert still lies ahead, else 0. The scheduler skips an alert whose time has passed, so keeping
     * it would promise a reminder that never comes ("tomorrow's event, 2 days before").
     */
    fun usableOffset(eventAt: ZonedDateTime, offsetMin: Int, now: ZonedDateTime): Int =
        if (offsetMin > 0 && eventAt.minusMinutes(offsetMin.toLong()).isAfter(now)) offsetMin else 0
}
