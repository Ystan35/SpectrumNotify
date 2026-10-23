package my.spectrum.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Fires ~10 min before class, at the summary time and at the afternoon time. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                Checker.fromCache(app)   // instant, uses cached calendar and links
                try { NextClassWidget.updateAll(app) } catch (_: Exception) { }
                Scheduler.startupCheck(app)   // then refresh; also re-arms the next alarms
            } catch (e: Exception) {
                Log.w("SpectrumNotify", "alarm: $e")
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Every 10-14 minutes (when set below 15): arms the next alarm first, then starts a check. */
class PollAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Scheduler.armPoll(app)
        Scheduler.pollNow(app)
    }
}

/** Re-arms polling and alarms after reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Scheduler.ensurePolling(app)
        Scheduler.pollNow(app)
    }
}
