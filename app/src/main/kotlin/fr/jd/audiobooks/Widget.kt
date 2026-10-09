package fr.jd.audiobooks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class JdWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { render(ctx, mgr, it) } }

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, JdWidget::class.java))
            ids.forEach { render(ctx, mgr, it) }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_jd)
            val p = PlaybackService.player
            val title = p?.mediaMetadata?.title?.toString() ?: "JD Audiobook Reader"
            v.setTextViewText(R.id.w_title, title)
            p?.mediaMetadata?.artworkUri?.let { v.setImageViewUri(R.id.w_cover, it) }
            v.setImageViewResource(R.id.w_play, if (p?.playWhenReady == true) R.drawable.ic_pause else R.drawable.ic_play)
            fun pi(action: String) = PendingIntent.getService(ctx, action.hashCode(),
                Intent(ctx, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_play, pi(PlaybackService.ACTION_PLAY_PAUSE))
            v.setOnClickPendingIntent(R.id.w_prev, pi(PlaybackService.ACTION_PREV))
            v.setOnClickPendingIntent(R.id.w_next, pi(PlaybackService.ACTION_NEXT))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_cover, open)
            mgr.updateAppWidget(id, v)
        }
    }
}
