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

    fun pollNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<PollWorker>().setConstraints(net).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("poll_now", ExistingWorkPolicy.REPLACE, req)
    }

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
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
        val cal = Checker.myCalendar(st, p)
        for (e in cal) {
            if (!Checker.isAttEvent(e)) continue
            val start = e.optLong("timestart")
            val at = start - 590  // fires just inside the 10-minute window
            if (at <= now || at > now + 3 * 86400) continue
            setAlarm(ctx, at * 1000, (e.optLong("id") * 31 + start).hashCode())
        }
        parseHm(p.summaryTime)?.let { setAlarm(ctx, nextDaily(it), 1) }
        parseHm(p.afternoonTime)?.let { setAlarm(ctx, nextDaily(it), 2) }
    }
}
