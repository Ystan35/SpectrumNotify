package my.spectrum.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: the next class (course, time, countdown), from cached data only.
 * Refreshes after every background check, class alarm, boot, and daily — no network of its own.
 */
class NextClassWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        update(ctx, mgr, ids)
    }

    companion object {
        /** Refresh every placed widget. Safe to call from anywhere (poll end, alarms, boot). */
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NextClassWidget::class.java))
            if (ids.isNotEmpty()) update(ctx, mgr, ids)
        }

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val loggedIn = Prefs(ctx).token.isNotEmpty()
            val next = try { Checker.overview(ctx).classes.firstOrNull() } catch (_: Exception) { null }
            for (id in ids) {
                // size-aware content: 2x1 (short cells) hides location + buttons,
                // 2x2 and bigger shows everything including Join / Attend / Page
                val opts = mgr.getAppWidgetOptions(id)
                val minHeightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72)
                val compact = minHeightDp < 110
                val v = RemoteViews(ctx.packageName, R.layout.widget_next_class)
                v.setOnClickPendingIntent(R.id.widget_root, open)
                when {
                    !loggedIn -> {
                        v.setTextViewText(R.id.widget_label, "SPECTRUM NOTIFY")
                        v.setTextViewText(R.id.widget_course, ctx.getString(R.string.widget_not_logged_in))
                        v.setTextViewText(R.id.widget_time, "")
                        v.setTextViewText(R.id.widget_countdown, "")
                    }
                    next == null -> {
                        v.setTextViewText(R.id.widget_label, "ALL CLEAR")
                        v.setTextViewText(R.id.widget_course, ctx.getString(R.string.widget_no_class))
                        v.setTextViewText(R.id.widget_time, "")
                        v.setTextViewText(R.id.widget_countdown, "")
                    }
                    else -> {
                        val now = System.currentTimeMillis() / 1000
                        val live = now >= next.start
                        v.setTextViewText(R.id.widget_label, if (live) "HAPPENING NOW" else "UP NEXT")
                        v.setTextViewText(R.id.widget_course, Shorts.display(ctx, next.course))
                        v.setTextViewText(
                            R.id.widget_time,
                            "${Timetable.DAY_FULL[localDt(next.start).dayOfWeek.value - 1]} · ${hhmm(next.start)}–${hhmm(next.end)}",
                        )
                        v.setTextViewText(
                            R.id.widget_countdown,
                            if (live) "Ends ${relIn(next.end - now)}" else "Starts ${relIn(next.start - now)}",
                        )
                        // location: user override -> online auto -> catalog room
                        val locPref = Timetable.locationOf(ctx, next.course)
                        val locLine = when {
                            locPref.startsWith("online:") -> "💻 Online"
                            locPref.startsWith("room:") -> "📍 " + locPref.removePrefix("room:")
                            else -> ""
                        }
                        v.setTextViewText(R.id.widget_location, locLine)
                        v.setViewVisibility(R.id.widget_location, if (locLine.isEmpty() || compact) android.view.View.GONE else android.view.View.VISIBLE)
                        // buttons, shown only when the widget has room (2x2 and up):
                        // Join / Attend / Page — the same resolver the app screens use
                        if (compact) {
                            v.setViewVisibility(R.id.widget_actions, android.view.View.GONE)
                        } else {
                            v.setViewVisibility(R.id.widget_actions, android.view.View.VISIBLE)
                            val (meeting, att, coursePage) = Checker.linksForCourse(ctx, next.course, next.courseId, Checker.groupTokens(next.name))
                            if (meeting.isNotEmpty()) {
                                v.setTextViewText(R.id.widget_meeting, "Join")
                                v.setOnClickPendingIntent(
                                    R.id.widget_meeting,
                                    PendingIntent.getActivity(
                                        ctx, 2,
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(meeting))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                                    ),
                                )
                                v.setViewVisibility(R.id.widget_meeting, android.view.View.VISIBLE)
                            } else {
                                v.setViewVisibility(R.id.widget_meeting, android.view.View.GONE)
                            }
                            val attUrl = att.ifEmpty { next.courseUrl }
                            if (attUrl.isNotEmpty()) {
                                v.setTextViewText(R.id.widget_attendance, "Attend ↗")
                                v.setOnClickPendingIntent(
                                    R.id.widget_attendance,
                                    PendingIntent.getActivity(
                                        ctx, 1,
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(attUrl))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                                    ),
                                )
                                v.setViewVisibility(R.id.widget_attendance, android.view.View.VISIBLE)
                            } else {
                                v.setViewVisibility(R.id.widget_attendance, android.view.View.GONE)
                            }
                            if (coursePage.isNotEmpty()) {
                                v.setTextViewText(R.id.widget_coursepage, "Page")
                                v.setOnClickPendingIntent(
                                    R.id.widget_coursepage,
                                    PendingIntent.getActivity(
                                        ctx, 3,
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(coursePage))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                                    ),
                                )
                                v.setViewVisibility(R.id.widget_coursepage, android.view.View.VISIBLE)
                            } else {
                                v.setViewVisibility(R.id.widget_coursepage, android.view.View.GONE)
                            }
                        }
                    }
                }
                mgr.updateAppWidget(id, v)
            }
        }
    }
}
