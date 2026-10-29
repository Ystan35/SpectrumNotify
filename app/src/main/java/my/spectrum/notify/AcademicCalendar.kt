package my.spectrum.notify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The academic calendar: every semester of the session (UM bachelor calendar, Senate-amended
 * 22 Jul 2026), each correctable in-app. Drives "holiday mode" (class slots hide outside teaching
 * weeks while notifications stay fully active) and which semester a timetable entry belongs to —
 * you take different subjects each semester, so one semester's classes must not run into the next.
 *
 * Stored in SharedPreferences "acadcal": one JSON override per bundled semester ("ov:<label>"),
 * keyed by the bundled label even when you rename it.
 */
object AcademicCalendar {

    data class Dates(
        val label: String,          // e.g. "2026/27 Sem 1"
        val teachStart: LocalDate,
        val teachEnd: LocalDate,    // end of teaching weeks
        val breakStart: LocalDate?, // mid-semester break
        val breakEnd: LocalDate?,
        val examStart: LocalDate?,
        val examEnd: LocalDate?,
        val semEnd: LocalDate,      // everything after = semester over
        /** Other no-class periods inside teaching, e.g. UM iFest. */
        val extraBreaks: List<Pair<LocalDate, LocalDate>> = emptyList(),
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("acadcal", Context.MODE_PRIVATE)
    private val F = DateTimeFormatter.ISO_LOCAL_DATE
    private fun d(s: String): LocalDate = LocalDate.parse(s, F)

    /**
     * UM 2026/27 academic session, bachelor level (Lampiran D, amended, Senate 22 Jul 2026).
     * Sem I: lectures 05.10.2026–17.01.2027 (mid-break 30.11–06.12), revision 18–24 Jan,
     * exams 25.01–04.02.2027, break 05–28 Feb. Sem II: lectures 01.03–13.06.2027 (mid-break
     * 19–25 Apr, UM iFest 08–11 Jun no T&L), revision 14–20 Jun, exams 21.06–04.07, break to 25 Jul.
     * Special: lectures 19.07–05.09.2027, exam 06–12 Sep, break to 19 Sep.
     */
    fun bundled(): List<Dates> = listOf(
        Dates("2026/27 Sem 1", d("2026-10-05"), d("2027-01-17"), d("2026-11-30"), d("2026-12-06"),
            d("2027-01-25"), d("2027-02-04"), d("2027-02-28")),
        Dates("2026/27 Sem 2", d("2027-03-01"), d("2027-06-13"), d("2027-04-19"), d("2027-04-25"),
            d("2027-06-21"), d("2027-07-04"), d("2027-07-25"),
            extraBreaks = listOf(Pair(d("2027-06-08"), d("2027-06-11")))),
        Dates("2026/27 Special", d("2027-07-19"), d("2027-09-05"), null, null,
            d("2027-09-06"), d("2027-09-12"), d("2027-09-19")),
    )

    /** Kept for callers that want the single bundled default (the first semester). */
    fun defaults(): Dates = bundled().first()

    private fun toJson(dt: Dates): String = JSONObject()
        .put("label", dt.label)
        .put("teachStart", dt.teachStart.format(F)).put("teachEnd", dt.teachEnd.format(F))
        .put("breakStart", dt.breakStart?.format(F) ?: "").put("breakEnd", dt.breakEnd?.format(F) ?: "")
        .put("examStart", dt.examStart?.format(F) ?: "").put("examEnd", dt.examEnd?.format(F) ?: "")
        .put("semEnd", dt.semEnd.format(F))
        .put("extra", JSONArray().also { a -> dt.extraBreaks.forEach { a.put(it.first.format(F) + "/" + it.second.format(F)) } })
        .toString()

    private fun fromJson(s: String, base: Dates): Dates? = try {
        val o = JSONObject(s)
        fun opt(k: String): LocalDate? = o.optString(k, "").takeIf { it.isNotEmpty() }?.let { runCatching { d(it) }.getOrNull() }
        Dates(
            o.optString("label", base.label).ifEmpty { base.label },
            opt("teachStart") ?: base.teachStart, opt("teachEnd") ?: base.teachEnd,
            opt("breakStart"), opt("breakEnd"), opt("examStart"), opt("examEnd"),
            opt("semEnd") ?: base.semEnd,
            o.optJSONArray("extra")?.strs()?.mapNotNull { r ->
                val p = r.split("/")
                if (p.size == 2) runCatching { Pair(d(p[0]), d(p[1])) }.getOrNull() else null
            } ?: base.extraBreaks,
        )
    } catch (_: Exception) { null }

    /**
     * Dates saved by v0.21–v0.25 (one semester, flat keys) become the override of the bundled
     * semester they overlap, if you had edited them. Runs once.
     */
    private fun migrateLegacy(ctx: Context) {
        val sp = sp(ctx)
        if (sp.getBoolean("v2", false)) return
        val ed = sp.edit().putBoolean("v2", true)
        if (sp.getBoolean("userEdited", false)) {
            fun g(k: String): LocalDate? = sp.getString(k, null)?.let { runCatching { d(it) }.getOrNull() }
            val ts = g("teachStart"); val te = g("teachEnd"); val se = g("semEnd")
            val base = bundled().firstOrNull { b -> ts != null && !ts.isBefore(b.teachStart.minusDays(60)) && !ts.isAfter(b.teachEnd) }
            if (base != null && ts != null && te != null && se != null) {
                ed.putString("ov:${base.label}", toJson(Dates(
                    sp.getString("label", null) ?: base.label, ts, te,
                    g("breakStart"), g("breakEnd"), g("examStart"), g("examEnd"), se, base.extraBreaks,
                )))
            }
        }
        ed.apply()
    }

    /** Built once and reused: the month grid asks for every day cell, this used to re-read and re-parse each time. */
    @Volatile private var cache: List<Dates>? = null

    /** Every semester, your corrections applied, in date order. */
    @Synchronized
    fun semesters(ctx: Context): List<Dates> {
        cache?.let { return it }
        migrateLegacy(ctx)
        val sp = sp(ctx)
        return bundled().map { b -> sp.getString("ov:${b.label}", null)?.let { fromJson(it, b) } ?: b }
            .sortedBy { it.teachStart }
            .also { cache = it }
    }

    /** The semester a date belongs to (teaching start to semester end); the later-starting one wins an overlap. */
    fun forDate(ctx: Context, d: LocalDate): Dates? =
        semesters(ctx).lastOrNull { !d.isBefore(it.teachStart) && !d.isAfter(it.semEnd) }

    /** Index of [forDate]'s semester in [semesters], for storing corrections under the bundled label. */
    private fun bundledLabelOf(ctx: Context, dt: Dates): String {
        val all = semesters(ctx)
        val i = all.indexOfFirst { it.teachStart == dt.teachStart && it.semEnd == dt.semEnd }.coerceAtLeast(0)
        return bundled().sortedBy { it.teachStart }.getOrNull(i)?.label ?: dt.label
    }

    /**
     * The semester you are planning for: the one being taught now, or — during revision, exams or a
     * break — the next one. New timetable entries belong to it, and the Timetable screen shows it.
     */
    fun planning(ctx: Context, today: LocalDate = LocalDate.now(zone())): Dates {
        val all = semesters(ctx)
        return all.firstOrNull { !today.isAfter(it.teachEnd) } ?: all.last()
    }

    /** Label of [planning]: the tag stored on new timetable entries. */
    fun planningLabel(ctx: Context): String = bundledLabelOf(ctx, planning(ctx))

    /** The tag for entries on [d] (bundled label, stable across renames), or null outside every semester. */
    fun labelFor(ctx: Context, d: LocalDate): String? = forDate(ctx, d)?.let { bundledLabelOf(ctx, it) }

    /** The semester shown in the editor: the one you are planning for. */
    @Synchronized
    fun get(ctx: Context): Dates = planning(ctx)

    /** Saves corrections for the semester the editor showed ([get]). */
    @Synchronized
    fun save(ctx: Context, dt: Dates, @Suppress("UNUSED_PARAMETER") userEdited: Boolean = true) {
        val key = bundledLabelOf(ctx, get(ctx))
        val base = bundled().first { it.label == key }
        sp(ctx).edit().putString("ov:$key", toJson(dt.copy(extraBreaks = dt.extraBreaks.ifEmpty { base.extraBreaks }))).apply()
        cache = null
    }

    private fun within(d: LocalDate, a: LocalDate?, b: LocalDate?): Boolean =
        a != null && b != null && !d.isBefore(a) && !d.isAfter(b)

    /** Teaching weeks of the date's semester, minus its breaks.
     *  FAIL-SAFE: if TODAY is past the last known semester (the bundled calendar ran out), the gate
     *  FAILS OPEN and shows classes — hiding everything because data went stale is the v0.21.0
     *  regression this prevents. While a semester is still on, later dates are NOT teaching:
     *  the next semester has different subjects. */
    fun inTeaching(ctx: Context, d: LocalDate): Boolean {
        val all = semesters(ctx)
        val last = all.last()
        if (d.isAfter(last.semEnd)) return LocalDate.now(zone()).isAfter(last.semEnd)
        val s = forDate(ctx, d) ?: return false
        return !d.isBefore(s.teachStart) && !d.isAfter(s.teachEnd) && !isBreak(ctx, d)
    }

    fun isBreak(ctx: Context, d: LocalDate): Boolean {
        val s = forDate(ctx, d) ?: return false
        return within(d, s.breakStart, s.breakEnd) || s.extraBreaks.any { within(d, it.first, it.second) }
    }

    fun isExam(ctx: Context, d: LocalDate): Boolean = forDate(ctx, d)?.let { within(d, it.examStart, it.examEnd) } ?: false

    /** Is a semester running on this date (teaching + exams + its break)? */
    fun isSemesterActive(ctx: Context, d: LocalDate): Boolean = forDate(ctx, d) != null

    /** Human line for the UI: what the calendar says about this date (null = normal teaching day). */
    fun describe(ctx: Context, d: LocalDate): String? {
        val all = semesters(ctx)
        val s = forDate(ctx, d)
        val m = DateTimeFormatter.ofPattern("d MMM")
        return when {
            s == null && d.isBefore(all.first().teachStart) -> "Semester starts ${all.first().teachStart.format(m)}"
            s == null -> "Semester over"
            within(d, s.breakStart, s.breakEnd) -> "Mid-semester break"
            s.extraBreaks.any { within(d, it.first, it.second) } -> "No classes (university event)"
            isExam(ctx, d) -> "Examination weeks"
            d.isAfter(s.examEnd ?: s.teachEnd) -> "Semester break · ${s.label}"
            d.isAfter(s.teachEnd) -> "Revision week"
            else -> null
        }
    }
}
