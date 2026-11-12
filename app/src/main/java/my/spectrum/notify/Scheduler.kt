package my.spectrum.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object Scheduler {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private const val POLL_CODE = 77

    private fun pollIntent(ctx: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(ctx, POLL_CODE, Intent(ctx, PollAlarmReceiver::class.java), flags or PendingIntent.FLAG_IMMUTABLE)

    /**
     * Background checks. 15 minutes or more: WorkManager periodic job (Android's minimum is 15).
     * 10-14 minutes: a self-re-arming alarm that starts a check each time (see PollAlarmReceiver).
     * Class-time precision comes from the exact alarms in scheduleAll.
     */
    fun ensurePolling(ctx: Context) {
        val p = Prefs(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (p.pollMinutes >= 15) {
            cancelPollAlarm(ctx)
            val req = PeriodicWorkRequestBuilder<PollWorker>(p.pollMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(net)
                .build()
            wm.enqueueUniquePeriodicWork("poll", ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork("poll")
            if (pollIntent(ctx, PendingIntent.FLAG_NO_CREATE) == null) armPoll(ctx)   // already armed: leave it
        }
    }

    /** Arms the next 10-14 minute check. Called again by each alarm, so the chain keeps going. */
    fun armPoll(ctx: Context) {
        val p = Prefs(ctx)
        if (p.pollMinutes >= 15) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pollIntent(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + p.pollMinutes * 60_000L, pi)
        } catch (e: Exception) {
        }
    }

    private fun cancelPollAlarm(ctx: Context) {
        val pi = pollIntent(ctx, PendingIntent.FLAG_NO_CREATE) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    /**
     * On-startup catch-up: when the app opens (or boots) and the user is logged in but the
     * last check is stale (>30 min), fire one immediately. This re-detects courses and Occs
     * without the user pressing anything.
     */
    fun startupCheck(ctx: Context) {
        val p = Prefs(ctx)
        if (p.token.isEmpty() || p.site.isEmpty()) return
        if (p.lastRunTs == 0L || System.currentTimeMillis() / 1000 - p.lastRunTs > 30 * 60) {
            pollNow(ctx)
        }
    }

    fun pollNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<PollWorker>().setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("poll_now", ExistingWorkPolicy.REPLACE, req)
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    /** Cancels an armed alarm for [cls] with request code [code], if one exists. */
    private fun cancelAlarm(ctx: Context, cls: Class<*>, code: Int) {
        val pi = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, cls), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    private fun setAlarm(ctx: Context, atMs: Long, code: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    private fun nextDaily(hm: Pair<Int, Int>): Long {
        val now = ZonedDateTime.now(zone())
        var t = now.withHour(hm.first).withMinute(hm.second).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    /** Alarms for: ~10 min before each class (from cached calendar), the morning summary and the afternoon check-in. */
    fun scheduleAll(ctx: Context) {
        val p = Prefs(ctx)
        val st = State(ctx)
        val now = System.currentTimeMillis() / 1000
        // without the classes you removed on the Timetable
        val cal = Checker.activeCalendar(ctx, st, p)
        val codes = LinkedHashSet<Int>()
        for (e in cal) {
            if (!Checker.isAttEvent(e)) continue
            val start = Alerts.classTimes(ctx, e).first   // honours your Time Override
            // holiday mode: no class alarms on days outside teaching weeks (decided by the class's own date)
            if (!AcademicCalendar.inTeaching(ctx, localDt(start).toLocalDate())) continue
            val end = Alerts.classTimes(ctx, e).second
            val at = start - 590  // fires just inside the 10-minute window
            // kept until the class ends: the last-chance attendance alarm comes during it
            if (end <= now || at > now + 3 * 86400) continue
            val code = (e.optLong("id") * 31 + start).hashCode()
            if (at > now) {
                setAlarm(ctx, at * 1000, code)
                codes.add(code)
            }
            // second alarm at the start itself: "sign your attendance" (Alerts.checkAttendanceStart).
            // Armed on its own: a re-run inside the last 10 minutes (every check, and the 10-minute alarm
            // itself) must keep it, or the clean-up below cancels it
            if (p.attStartAlert) {
                if (start + 20 > now) {
                    val code2 = code xor 0x2A2A2A
                    setAlarm(ctx, (start + 20) * 1000, code2)
                    codes.add(code2)
                }
                // and the last-chance one, 10 minutes before the class ends (Alerts.checkAttendanceStart)
                if (end - start > 25 * 60 && end - 600 + 20 > now) {
                    val code4 = code xor 0x3C3C3C
                    setAlarm(ctx, (end - 600 + 20) * 1000, code4)
                    codes.add(code4)
                }
            }
            // third: the leave-by alert for the shuttle or the walk (Alerts.checkLeaveBy), also armed on its own
            val lead = Shuttle.leadMin(ctx)
            if (lead > 0) {
                try {
                    Alerts.leaveBy(ctx, e)?.let { (leave, _) ->
                        val alertAt = leave - lead * 60L
                        // inside the window already: fire soon, unless this alert was sent
                        val sent = "leaveby:${e.optLong("id")}:$leave" in st.strings("leaveby_sent")
                        if (leave > now && (alertAt > now || !sent)) {
                            val code3 = code xor 0x5B5B5B
                            setAlarm(ctx, maxOf(alertAt, now + 5) * 1000, code3)
                            codes.add(code3)
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        // the same two attendance alerts for classes Spectrum's calendar does not list
        if (p.attStartAlert) for (n in 0L..2L) {
            val date = java.time.LocalDate.now(zone()).plusDays(n)
            for (s in Alerts.slotsWithoutAttEvent(ctx, p, cal, date)) {
                val start = date.atTime(s.start / 60, s.start % 60).atZone(zone()).toEpochSecond()
                val end = date.atTime(s.end / 60, s.end % 60).atZone(zone()).toEpochSecond()
                val code = "slotatt:${s.key}:$date".hashCode()
                if (start + 20 > now) { setAlarm(ctx, (start + 20) * 1000, code); codes.add(code) }
                if (end - start > 25 * 60 && end - 600 + 20 > now) {
                    setAlarm(ctx, (end - 600 + 20) * 1000, code xor 0x3C3C3C); codes.add(code xor 0x3C3C3C)
                }
            }
        }
        parseHm(p.summaryTime)?.let { setAlarm(ctx, nextDaily(it), 1); codes.add(1) }
        parseHm(p.afternoonTime)?.let { setAlarm(ctx, nextDaily(it), 2); codes.add(2) }
        // classes that were removed, skipped or moved since last time must not still fire
        for (old in p.classAlarmCodes.split(",").mapNotNull { it.toIntOrNull() }) {
            if (old !in codes) cancelAlarm(ctx, AlarmReceiver::class.java, old)
        }
        p.classAlarmCodes = codes.joinToString(",")
        scheduleEventReminders(ctx)
    }

    // ------------------------------------------------------------ custom event reminders

    /** Arms exact alarms for every event reminder due in the next 8 days (each check arms the ones after that). */
    fun scheduleEventReminders(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val zoneId = zone()
        val now = java.time.ZonedDateTime.now(zoneId)
        val p = Prefs(ctx)
        // deleted or edited events must not keep their old reminders: clear last round's codes first
        for (old in 9000 until p.eventAlarmEnd) cancelAlarm(ctx, EventAlarmReceiver::class.java, old)
        var code = 9000
        for (e in Events.all(ctx)) {
            if (e.reminders.isEmpty()) continue
            // far enough ahead that a reminder weeks before the event is seen
            val ahead = 8L + e.reminders.max() / (24 * 60) + 1
            for ((d, _) in e.upcoming(now.toLocalDate(), ahead)) for (before in e.reminders) {
                val from = d.atTime(e.remindFrom() / 60, e.remindFrom() % 60).atZone(zoneId).toInstant().toEpochMilli()
                val at = from - before * 60_000L
                if (at <= System.currentTimeMillis() || at > System.currentTimeMillis() + 8 * 86_400_000L) continue
                val pi = PendingIntent.getBroadcast(
                    ctx, code++,
                    Intent(ctx, EventAlarmReceiver::class.java)
                        .putExtra("eventId", e.id)
                        .putExtra("title", e.title)
                        .putExtra("date", d.toString())
                        .putExtra("time", if (e.allDay) "all day" else EventsUi.fmtMin(e.startTime)),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                try {
                    if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } catch (e2: SecurityException) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            }
        }
        p.eventAlarmEnd = code
    }
}
