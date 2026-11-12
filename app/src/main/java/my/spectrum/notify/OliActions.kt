package my.spectrum.notify

import java.util.Locale

/**
 * What Oli reads out of a sentence when you ask it to DO something ("use this Teams link for WIX1003", "set my Occ for
 * GIG1017 to 2"). Plain functions, so they can be tested; Assistant adds the course lookup, the Yes step and the Undo.
 */
object OliActions {
    enum class LinkKind { MEETING, ATTENDANCE, UNKNOWN }

    class LinkAsk(val url: String, val kind: LinkKind)

    private val URL = Regex("""https://\S+""", RegexOption.IGNORE_CASE)

    /** The sentence without its web addresses: their letters must not be read as a course name ("zoom.us/j/abc"). */
    fun withoutUrls(q: String) = q.replace(URL, " ")
    private val MEETING_HOST = Regex("""(teams\.microsoft\.com|teams\.live\.com|zoom\.us|meet\.google\.com|webex\.com|whereby\.com)""", RegexOption.IGNORE_CASE)

    /** Words that say "change something" rather than "show me" ("give me the maya link" must stay a question). */
    private val DO_WORD = Regex("""\b(add|set|save|use|update|change|put|paste|switch|confirm|mark|here'?s|this is)\b|\bi'?m in\b|\bi am in\b|\bmy occ\b""", RegexOption.IGNORE_CASE)

    /** The link in the sentence and what it looks like; null when there is no link or the sentence does not ask for a change. */
    fun linkAsk(q: String): LinkAsk? {
        if (!DO_WORD.containsMatchIn(q) || QUESTION.containsMatchIn(withoutUrls(q))) return null
        val url = URL.find(q)?.value?.trimEnd('.', ',', ')', '!', '?', ';') ?: return null
        val kind = when {
            "/mod/attendance/" in url -> LinkKind.ATTENDANCE
            MEETING_HOST.containsMatchIn(url) -> LinkKind.MEETING
            else -> LinkKind.UNKNOWN
        }
        return LinkAsk(url, kind)
    }

    private val OCC = Regex("""\bocc(?:urrence)?\.?\s*(\d{1,2}[a-z]?)\b""", RegexOption.IGNORE_CASE)

    /** Verbs that really change an Occ; "my occ" or "this is" alone do not (they appear in plain questions). */
    private val OCC_VERB = Regex("""\b(set|change|switch|update|confirm|put)\b|\bi'?m in\b|\bi am in\b""", RegexOption.IGNORE_CASE)
    private val QUESTION = Regex("""\?|^\s*(when|where|what|which|who|how|why|should|is|are|can|could|do|does|did)\b""", RegexOption.IGNORE_CASE)

    /** "set my occ for gig1017 to 2": the word occ somewhere, and a number after "to" at the end. */
    private val OCC_TO = Regex("""\bto\s+(?:occ\.?\s*)?(\d{1,2}[a-z]?)\s*[.!]?\s*$""", RegexOption.IGNORE_CASE)
    private val OCC_WORD = Regex("""\bocc(?:urrence)?\b""", RegexOption.IGNORE_CASE)

    /** The Occ number asked for ("occ 2", "occ6", "occurrence 12a", "my occ for X to 2"), only when the sentence asks for a change; else null. */
    fun occAsk(q: String): String? {
        if (!OCC_VERB.containsMatchIn(q) || QUESTION.containsMatchIn(withoutUrls(q))) return null
        if (Regex("""\b(online|offline|in.?person|physical)\b""", RegexOption.IGNORE_CASE).containsMatchIn(q)) return null   // "set occ 2 tutorial to online" is a mode change
        OCC.find(q)?.let { return it.groupValues[1].lowercase(Locale.ROOT) }
        if (!OCC_WORD.containsMatchIn(q)) return null
        return OCC_TO.find(q.trim())?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    }
}
