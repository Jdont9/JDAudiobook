package fr.jd.audiobooks

import android.net.Uri
import android.os.*
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*

private const val ROOT_ID = "root"

class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val h = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        var player: ExoPlayer? = null
        // Suivi de la pause pour le retour en arrière automatique. En compagnon (pas un champ d'instance)
        // pour pouvoir être remis à zéro depuis l'extérieur (MainActivity.open()) : sans ça, ouvrir un tout
        // nouveau livre juste après avoir mis un autre livre en pause déclenchait le recul sur la position
        // qu'on vient d'importer/reprendre, l'écrasant par une position plus ancienne.
        private var pausedAt = 0L
        fun markFreshStart() { pausedAt = 0L }
        const val ACTION_PLAY_PAUSE = "fr.jd.audiobooks.PLAY_PAUSE"
        const val ACTION_NEXT = "fr.jd.audiobooks.NEXT"
        const val ACTION_PREV = "fr.jd.audiobooks.PREV"
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> player?.let { if (it.playWhenReady) it.pause() else it.play() }
            ACTION_NEXT -> player?.seekToNextMediaItem()
            ACTION_PREV -> player?.seekToPreviousMediaItem()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // ---- Construction des MediaItem pour la bibliothèque (arborescence Android Auto) ----
    private suspend fun bookItem(bk: Book): MediaItem {
        val art = Covers.get(this, bk)?.let { Covers.jpeg(it) }
        return MediaItem.Builder().setMediaId("book:${bk.path}").setMediaMetadata(
            MediaMetadata.Builder().setTitle(bk.name).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .apply { art?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }.build()
        ).build()
    }

    private fun trackItem(bk: Book, i: Int, art: ByteArray?): MediaItem =
        MediaItem.Builder().setMediaId("track:${bk.path}|$i").setUri(bk.uris[i]).setMediaMetadata(
            MediaMetadata.Builder().setTitle(bk.names[i]).setArtist(bk.name)
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .setExtras(android.os.Bundle().apply { putString("path", bk.path) })
                .apply { art?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }.build()
        ).build()

    private val libraryCallback = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItem.Builder().setMediaId(ROOT_ID)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("JD Audiobook Reader").setIsBrowsable(true).setIsPlayable(false).build())
                        .build(), params
                )
            )

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFutureCompat<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val store = Store(this@PlaybackService)
                val items = withContext(Dispatchers.IO) {
                    when {
                        parentId == ROOT_ID -> store.library().map { bookItem(it) }
                        parentId.startsWith("book:") -> {
                            val path = parentId.removePrefix("book:")
                            val bk = store.library().firstOrNull { it.path == path }
                            if (bk == null) emptyList() else {
                                val art = Covers.get(this@PlaybackService, bk)?.let { Covers.jpeg(it) }
                                bk.uris.indices.map { trackItem(bk, it, art) }
                            }
                        }
                        else -> emptyList()
                    }
                }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFutureCompat<LibraryResult<MediaItem>>()
            scope.launch {
                val store = Store(this@PlaybackService)
                val item = withContext(Dispatchers.IO) {
                    if (mediaId.startsWith("track:")) {
                        val (path, idx) = mediaId.removePrefix("track:").split("|")
                        val bk = store.library().firstOrNull { it.path == path }
                        val art = bk?.let { Covers.get(this@PlaybackService, it) }?.let { Covers.jpeg(it) }
                        bk?.let { trackItem(it, idx.toInt(), art) }
                    } else if (mediaId.startsWith("book:")) {
                        store.library().firstOrNull { it.path == mediaId.removePrefix("book:") }?.let { bookItem(it) }
                    } else null
                }
                future.set(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
            return future
        }

        // Résout un élément choisi dans Android Auto (un chapitre) en la playlist complète du livre,
        // pour garder précédent/suivant et la reprise à la bonne position.
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val requested = mediaItems.firstOrNull()?.mediaId ?: ""
            if (!requested.startsWith("track:") && !requested.startsWith("book:"))
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            val future = SettableFutureCompat<MediaItemsWithStartPosition>()
            scope.launch {
                val store = Store(this@PlaybackService)
                val (path, idx) = if (requested.startsWith("track:"))
                    requested.removePrefix("track:").split("|").let { it[0] to it[1].toInt() }
                else requested.removePrefix("book:") to 0
                val bk = withContext(Dispatchers.IO) { store.library().firstOrNull { it.path == path } }
                if (bk == null) { future.set(MediaItemsWithStartPosition(ImmutableList.of(), 0, 0)); return@launch }
                val art = withContext(Dispatchers.IO) { Covers.get(this@PlaybackService, bk)?.let { Covers.jpeg(it) } }
                val items = bk.uris.indices.map { trackItem(bk, it, art) }
                val saved = store.load(bk.path)
                val startIdx = if (requested.startsWith("track:")) idx else saved?.index ?: 0
                val startPos = if (requested.startsWith("track:") && idx != saved?.index) 0L else saved?.pos ?: 0L
                markFreshStart() // nouveau livre choisi depuis Android Auto : pas de recul automatique
                future.set(MediaItemsWithStartPosition(ImmutableList.copyOf(items), startIdx, startPos))            }
            return future
        }
    }

    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(30_000).setSeekForwardIncrementMs(30_000).build()
        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(sessionId: Int) { Eq.attach(sessionId, Store(this@PlaybackService)) }
            override fun onEvents(pl: Player, e: Player.Events) { JdWidget.updateAll(this@PlaybackService) }
            // Retour arrière automatique à la reprise, proportionnel à la durée de pause
            override fun onPlayWhenReadyChanged(pwr: Boolean, reason: Int) {
                val now = SystemClock.elapsedRealtime()
                if (!pwr) pausedAt = now
                else if (pausedAt > 0) {
                    val s = (now - pausedAt) / 1000
                    val back = when { s < 5 -> 0L; s < 300 -> 3_000L; s < 3600 -> 10_000L; else -> 20_000L }
                    if (back > 0) p.seekTo((p.currentPosition - back).coerceAtLeast(0))
                }
            }
        })
        val store = Store(this)
        h.postDelayed(object : Runnable {
            override fun run() {
                if (p.isPlaying) p.mediaMetadata.extras?.getString("path")?.let {
                    store.addTime(it, 1000, (1000 * p.playbackParameters.speed).toLong())
                }
                h.postDelayed(this, 1000)
            }
        }, 1000)
        player = p
        session = MediaLibrarySession.Builder(this, p, libraryCallback).build()
    }

    override fun onGetSession(c: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        scope.cancel()
        session?.release(); player?.release(); player = null
        super.onDestroy()
    }
}

/** Petit pont future sans dépendance à Guava's SettableFuture (non exposé publiquement dans le BOM Media3). */
private class SettableFutureCompat<T> : ListenableFuture<T> {
    private var value: T? = null
    private var done = false
    private val listeners = mutableListOf<Pair<Runnable, java.util.concurrent.Executor>>()
    @Synchronized fun set(v: T) {
        value = v; done = true
        listeners.forEach { (r, e) -> e.execute(r) }
        listeners.clear()
    }
    override fun addListener(listener: Runnable, executor: java.util.concurrent.Executor) {
        val fire = synchronized(this) { if (done) true else { listeners.add(listener to executor); false } }
        if (fire) executor.execute(listener)
    }
    override fun cancel(mayInterruptIfRunning: Boolean) = false
    override fun isCancelled() = false
    override fun isDone() = done
    override fun get(): T { while (!done) Thread.sleep(1); return value!! }
    override fun get(timeout: Long, unit: java.util.concurrent.TimeUnit): T = get()
}

object Sleep {
    private val h = Handler(Looper.getMainLooper())
    private var r: Runnable? = null
    var endAt = 0L
    fun set(min: Int) {
        cancel()
        if (min > 0) {
            endAt = SystemClock.elapsedRealtime() + min * 60_000L
            r = Runnable { PlaybackService.player?.pause(); endAt = 0 }
            h.postDelayed(r!!, min * 60_000L)
        }
    }
    fun cancel() { r?.let { h.removeCallbacks(it) }; endAt = 0 }
}
