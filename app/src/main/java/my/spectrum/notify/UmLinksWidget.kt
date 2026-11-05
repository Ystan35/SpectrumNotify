package my.spectrum.notify

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/**
 * Home-screen widget: your UM links as a grid of tiles. Resize it and the grid follows (1x2, 2x2, 2x3, 2x4, and the
 * wide ways round). The app icon opens the app, the pencil opens Settings → UM links; every widget shows that same list, updated on each edit.
 */
class UmLinksWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        update(ctx, mgr, ids)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        update(ctx, mgr, intArrayOf(id))
    }

    companion object {
        private const val MAX_CELLS = 4
        private const val PICK_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, UmLinksWidget::class.java))
            if (ids.isNotEmpty()) update(ctx, mgr, ids)
        }

        /** Tiles that fit across / down, from the space the launcher gives (home screens differ, so not a cell count). */
        private fun fit(sizeDp: Int, chrome: Int, tile: Int): Int = ((sizeDp - chrome) / tile).coerceIn(1, MAX_CELLS)

        private fun editor(ctx: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
            ctx, requestCode,
            Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_OPEN, "umlinks")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PICK_FLAGS,
        )

        private fun update(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val links = Shortcuts.all(ctx)
            for (id in ids) {
                val opts = mgr.getAppWidgetOptions(id)
                val cols = fit(opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110), 10, 70)
                // no empty rows: when the links run out, the tiles grow to fill the widget
                val rows = fit(opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110), 36, 72)
                    .coerceAtMost(((links.size + cols - 1) / cols).coerceAtLeast(1))
                val slots = cols * rows
                // a full grid with links left over: the last tile says how many, and opens the list
                val shown = if (links.size > slots) slots - 1 else links.size
                val v = RemoteViews(ctx.packageName, R.layout.widget_links)
                v.setTextViewText(R.id.widget_links_label, if (cols == 1) "" else "LINKS")
                v.setOnClickPendingIntent(R.id.widget_links_edit, editor(ctx, id * 100))
                v.setOnClickPendingIntent(R.id.widget_links_open, PendingIntent.getActivity(
                    ctx, id * 100 + 98,
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PICK_FLAGS,
                ))
                v.removeAllViews(R.id.widget_links_grid)
                for (r in 0 until rows) {
                    val row = RemoteViews(ctx.packageName, R.layout.widget_link_row)
                    row.removeAllViews(R.id.widget_link_row)
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        val t = RemoteViews(ctx.packageName, R.layout.widget_link_tile)
                        when {
                            i < shown -> {
                                val l = links[i]
                                t.setImageViewResource(R.id.widget_tile_icon, Shortcuts.iconRes(l.icon))
                                t.setTextViewText(R.id.widget_tile_name, l.name)
                                t.setContentDescription(R.id.widget_tile, "Open ${l.name}")
                                t.setOnClickPendingIntent(R.id.widget_tile, PendingIntent.getActivity(
                                    ctx, id * 100 + i + 1,
                                    Intent(Intent.ACTION_VIEW, Uri.parse(l.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    PICK_FLAGS,
                                ))
                            }
                            i == shown && (links.size > slots || links.isEmpty()) -> {
                                val label = if (links.isEmpty()) "Add links" else "+${links.size - shown} more"
                                t.setImageViewResource(R.id.widget_tile_icon, R.drawable.lk_link)
                                t.setTextViewText(R.id.widget_tile_name, label)
                                t.setContentDescription(R.id.widget_tile, label)
                                t.setOnClickPendingIntent(R.id.widget_tile, editor(ctx, id * 100 + 99))
                            }
                            else -> t.setViewVisibility(R.id.widget_tile, View.INVISIBLE)
                        }
                        row.addView(R.id.widget_link_row, t)
                    }
                    v.addView(R.id.widget_links_grid, row)
                }
                mgr.updateAppWidget(id, v)
            }
        }
    }
}
