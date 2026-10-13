package fr.jd.audiobooks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.widget.RemoteViews
import java.io.File

/**
 * Widget de l'écran d'accueil, en deux formats choisis selon la hauteur donnée par le lanceur :
 *  - grand (2 cases de haut ou plus) : pochette arrondie, titre du livre, fichier en cours, temps, barre de progression,
 *    et les commandes précédent / −10 s / lecture-pause / +10 s / suivant ;
 *  - compact (1 case) : pochette, titre, précédent / lecture-pause / suivant, fine barre de progression.
 * Couleurs : celles du système sur Android 12+ (Material You, mode sombre compris), le rouge de l'appli avant.
 * Toucher la pochette ou le titre ouvre l'appli ; sans livre chargé, le bouton lecture reprend le dernier livre.
 */
class JdWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { render(ctx, mgr, it) } }

    // Le widget a été redimensionné : on choisit à nouveau entre le format grand et le format compact.
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) { render(ctx, mgr, id) }

    companion object {
        private const val COMPACT_BELOW_DP = 110
        private var token: String? = null
        private var coverKey: String? = null
        private var coverBmp: Bitmap? = null

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, JdWidget::class.java))
            if (ids.isEmpty()) return // aucun widget posé : rien à dessiner
            ids.forEach { render(ctx, mgr, it) }
        }

        private fun serviceToken(ctx: Context): String = token ?: Store(ctx).serviceToken().also { token = it }

        /** Pochette arrondie, réduite (le widget voyage par IPC : quelques dizaines de Ko, pas l'image entière). Gardée en mémoire par livre. */
        private fun roundedCover(ctx: Context, path: String): Bitmap? {
            val f = Covers.artFileFor(ctx, path)
            if (!f.isFile) return null
            val key = path + "|" + f.lastModified()
            if (key == coverKey) return coverBmp
            val bmp = try { decodeRounded(f, 168) } catch (e: Exception) { logw("pochette du widget", e); null }
            coverKey = key; coverBmp = bmp
            return bmp
        }

        private fun decodeRounded(f: File, size: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (minOf(o.outWidth, o.outHeight) / (s * 2) >= size) s *= 2
            val src = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
            // Recadrage carré centré, mis à la taille, coins arrondis.
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            Canvas(out).drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.17f, size * 0.17f, paint)
            return out
        }

        private fun isCompact(mgr: AppWidgetManager, id: Int): Boolean {
            val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            return h in 1 until COMPACT_BELOW_DP // hauteur inconnue (0) : format grand
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val compact = isCompact(mgr, id)
            val v = RemoteViews(ctx.packageName, if (compact) R.layout.widget_jd_small else R.layout.widget_jd)
            val p = PlaybackService.player
            val loaded = p != null && p.mediaItemCount > 0
            val meta = if (loaded) p?.currentMediaItem?.mediaMetadata else null
            val ex = meta?.extras
            var path = ex?.getString("path")
            val title: String
            var sub = ""
            if (loaded && ex != null) {
                title = ex.getString("book") ?: ctx.getString(R.string.app_name)
                val n = p!!.mediaItemCount
                val file = meta?.title?.toString().orEmpty()
                sub = if (n > 1) "${p.currentMediaItemIndex + 1}/$n · $file" else file
                if (!compact) {
                    val dur = p.duration.takeIf { it > 0 }
                    v.setTextViewText(R.id.w_time, if (dur != null) "${fmt(p.currentPosition)} / ${fmt(dur)}" else fmt(p.currentPosition))
                    v.setProgressBar(R.id.w_progress, 1000, if (dur != null) (p.currentPosition * 1000 / dur).toInt().coerceIn(0, 1000) else 0, false)
                } else {
                    val dur = p.duration.takeIf { it > 0 }
                    v.setProgressBar(R.id.w_progress, 1000, if (dur != null) (p.currentPosition * 1000 / dur).toInt().coerceIn(0, 1000) else 0, false)
                }
            } else {
                // Rien de chargé : on propose de reprendre le dernier livre (le bouton lecture le fait).
                val last = Store(ctx).lastBook()
                path = last
                title = last?.substringAfterLast('/') ?: ctx.getString(R.string.app_name)
                sub = ctx.getString(if (last != null) R.string.widget_tap_resume else R.string.widget_open_hint)
                if (!compact) v.setTextViewText(R.id.w_time, "")
                v.setProgressBar(R.id.w_progress, 1000, 0, false)
            }
            v.setTextViewText(R.id.w_title, title)
            v.setTextViewText(R.id.w_sub, sub)

            val cover = path?.let { roundedCover(ctx, it) }
            if (cover != null) v.setImageViewBitmap(R.id.w_cover, cover) else v.setImageViewResource(R.id.w_cover, R.drawable.widget_cover_ph)

            val playing = loaded && p?.playWhenReady == true
            v.setImageViewResource(R.id.w_play, if (playing) R.drawable.w_pause else R.drawable.w_play)
            v.setContentDescription(R.id.w_play, ctx.getString(if (playing) R.string.pause else R.string.play))

            val tok = serviceToken(ctx)
            fun pi(action: String) = PendingIntent.getService(ctx, action.hashCode(),
                Intent(ctx, PlaybackService::class.java).setAction(action).putExtra(PlaybackService.EXTRA_TOKEN, tok),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_play, pi(PlaybackService.ACTION_PLAY_PAUSE))
            v.setOnClickPendingIntent(R.id.w_prev, pi(PlaybackService.ACTION_PREV))
            v.setOnClickPendingIntent(R.id.w_next, pi(PlaybackService.ACTION_NEXT))
            if (!compact) {
                v.setOnClickPendingIntent(R.id.w_back, pi(PlaybackService.ACTION_BACK10))
                v.setOnClickPendingIntent(R.id.w_fwd, pi(PlaybackService.ACTION_FWD10))
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_root, open)
            v.setOnClickPendingIntent(R.id.w_cover, open)
            mgr.updateAppWidget(id, v)
        }
    }
}
