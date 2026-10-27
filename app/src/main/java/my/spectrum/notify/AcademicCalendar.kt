package my.spectrum.notify

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The academic calendar, ONE active date set (per user decision — simpler to maintain
 * each semester). Drives "holiday mode": class slots hide outside teaching weeks while
 * notifications stay fully active.
 *
 * Stored in SharedPreferences "acadcal" as ISO dates. Bundled defaults reflect the
 * published UM 2025/26 Session 1 calendar; the user corrects them in-app if UM revises.
 */
object AcademicCalendar {

    data class Dates(
        val label: String,          // e.g. "2025/26 Sem 1"
        val teachStart: LocalDate,
        val teachEnd: LocalDate,    // end of teaching weeks
        val breakStart: LocalDate?, // mid-semester break
        val breakEnd: LocalDate?,
        val examStart: LocalDate?,
        val examEnd: LocalDate?,
        val semEnd: LocalDate,      // everything after = semester over
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("acadcal", Context.MODE_PRIVATE)
    private val F = DateTimeFormatter.ISO_LOCAL_DATE

    /** Published UM 2026/27 Session 1 dates (Senate-amended, Jul 2026; user-editable).
     *  Lectures 05.10.2026–29.11.2026 + 07.12.2026–17.01.2027 (mid-break 30.11–06.12),
     *  revision 18–24 Jan, exams 25.01–14.02.2027 (per AASD amendment; some sources
     *  end exams 04.02 — the longer range is the fail-safe), Sem 1 break 05–28 Feb. */
    fun defaults(): Dates = Dates(
        label = "2026/27 Sem 1",
        teachStart = LocalDate.parse("2026-10-05", F),
        teachEnd = LocalDate.parse("2027-01-17", F),
        breakStart = LocalDate.parse("2026-11-30", F),
        breakEnd = LocalDate.parse("2026-12-06", F),
        examStart = LocalDate.parse("2027-01-25", F),
        examEnd = LocalDate.parse("2027-02-14", F),
        semEnd = LocalDate.parse("2027-02-28", F),
    )

    @Synchronized
    fun get(ctx: Context): Dates {
        val sp = sp(ctx)
        // "init" stores WHICH bundled default the user's saved dates came from. When the app
        // ships newer defaults (new semester) and the user never edited theirs, migrate.
        val shipped = "2026/27 Sem 1"
        val userEdited = sp.getBoolean("userEdited", false)
        val savedInit = sp.getString("init", null)
        if (savedInit == null || (savedInit != shipped && !userEdited)) {
            // first open, or bundled defaults are newer and the user hasn't personalised
            save(ctx, defaults(), userEdited = false)
            return defaults()
        }
        fun d(k: String, def: LocalDate): LocalDate =
            sp.getString(k, null)?.let { runCatching { LocalDate.parse(it, F) }.getOrNull() } ?: def
        fun dOpt(k: String): LocalDate? =
            sp.getString(k, null)?.let { runCatching { LocalDate.parse(it, F) }.getOrNull() }
        return Dates(
            label = sp.getString("label", null) ?: "Semester",
            teachStart = d("teachStart", defaults().teachStart),
            teachEnd = d("teachEnd", defaults().teachEnd),
            breakStart = dOpt("breakStart"),
            breakEnd = dOpt("breakEnd"),
            examStart = dOpt("examStart"),
            examEnd = dOpt("examEnd"),
            semEnd = d("semEnd", defaults().semEnd),
        )
    }

    @Synchronized
    fun save(ctx: Context, dt: Dates, userEdited: Boolean = true) {
        sp(ctx).edit()
            .putString("init", dt.label)
            .putBoolean("userEdited", userEdited)
            .putString("label", dt.label)
            .putString("teachStart", dt.teachStart.format(F))
            .putString("teachEnd", dt.teachEnd.format(F))
            .putString("breakStart", dt.breakStart?.format(F))
            .putString("breakEnd", dt.breakEnd?.format(F))
            .putString("examStart", dt.examStart?.format(F))
            .putString("examEnd", dt.examEnd?.format(F))
            .putString("semEnd", dt.semEnd.format(F))
            .apply()
    }

    private fun within(d: LocalDate, a: LocalDate?, b: LocalDate?): Boolean =
        a != null && b != null && !d.isBefore(a) && !d.isAfter(b)

    /** Teaching weeks: teachStart..teachEnd, minus the mid-semester break.
     *  FAIL-SAFE: if today is past the configured semester end (stale calendar — the user
     *  never updated dates for the new semester), the gate FAILS OPEN and shows classes.
     *  Hiding everything because data went stale is the v0.21.0 regression this prevents. */
    fun inTeaching(ctx: Context, d: LocalDate): Boolean {
        val dt = get(ctx)
        if (d.isAfter(dt.semEnd)) return true  // stale calendar: show classes, never blank
        return !d.isBefore(dt.teachStart) && !d.isAfter(dt.teachEnd) && !isBreak(ctx, d)
    }

    fun isBreak(ctx: Context, d: LocalDate): Boolean = within(d, get(ctx).breakStart, get(ctx).breakEnd)

    fun isExam(ctx: Context, d: LocalDate): Boolean = within(d, get(ctx).examStart, get(ctx).examEnd)

    /** Whole semester still running (teaching + exams)? Past semEnd = semester over. */
    fun isSemesterActive(ctx: Context, d: LocalDate): Boolean {
        val dt = get(ctx)
        return !d.isBefore(dt.teachStart) && !d.isAfter(dt.semEnd)
    }

    /** Human line for the UI: what the calendar says about this date (null = normal teaching day). */
    fun describe(ctx: Context, d: LocalDate): String? {
        val dt = get(ctx)
        return when {
            isBreak(ctx, d) -> "Mid-semester break"
            isExam(ctx, d) -> "Examination week"
            d.isBefore(dt.teachStart) -> "Semester starts ${dt.teachStart.format(DateTimeFormatter.ofPattern("d MMM"))}"
            d.isAfter(dt.semEnd) -> "Semester over"
            else -> null
        }
    }
}
