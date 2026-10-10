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
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*

private const val ROOT_ID = "root"

class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val h = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val store by lazy { Store(this) }
    private var playTicks = 0

    // La progression est sauvegardée ICI, dans le service : elle ne dépend plus de l'écran lecteur (qui peut être
    // fermé/détruit alors que la lecture continue, ou ne jamais avoir existé en Android Auto).
    // file = true : écrit aussi position.jd.json dans le dossier du livre (en arrière-plan).
    private fun persist(file: Boolean) {
        val pl = player ?: return
        // Fin du livre : bookEnded() a déjà tout enregistré (remise au début, « lu »), on ne l'écrase pas.
        if (pl.playbackState == Player.STATE_ENDED) return
        val item = pl.currentMediaItem ?: return
        val ex = item.mediaMetadata.extras ?: return
        val path = ex.getString("path") ?: return
        val i = pl.currentMediaItemIndex
        val pos = pl.currentPosition
        store.save(path, i, pos, pl.playbackParameters.speed)
        if (file) {
            store.setLastBook(path)
            store.flushTime()
            ProgressFile.writeAsync(
                this, store.root, ex.getString("dir"), ex.getString("book") ?: "",
                ProgressFile.Data(i, item.mediaMetadata.title?.toString(), pos, pl.playbackParameters.speed, store.finished(path), System.currentTimeMillis())
            )
        }
    }

    // Dernier fichier du livre terminé : le livre est marqué « lu » et la position remise au début (sinon la prochaine
    // ouverture reprendrait tout à la fin et s'arrêterait aussitôt).
    private fun bookEnded() {
        val pl = player ?: return
        val ex = pl.currentMediaItem?.mediaMetadata?.extras ?: return
        val path = ex.getString("path") ?: return
        val speed = pl.playbackParameters.speed
        store.flushTime()
        store.setFinished(path, true)
        store.save(path, 0, 0L, speed)
        ProgressFile.writeAsync(
            this, store.root, ex.getString("dir"), ex.getString("book") ?: "",
            ProgressFile.Data(0, pl.getMediaItemAt(0).mediaMetadata.title?.toString(), 0L, speed, true, System.currentTimeMillis())
        )
    }

    // Après un déplacement (curseur, chapitre) : on attend que ça se stabilise avant d'écrire.
    private val seekPersist = Runnable { persist(true) }

    companion object {
        var player: ExoPlayer? = null
        // Suivi de la pause pour le retour en arrière automatique. En compagnon (pas un champ d'instance)
        // pour pouvoir être remis à zéro depuis l'extérieur (MainActivity.open()) : sans ça, ouvrir un tout
        // nouveau livre juste après avoir mis un autre livre en pause déclenchait le recul sur la position
        // qu'on vient d'importer/reprendre, l'écrasant par une position plus ancienne.
        private var pausedAt = 0L
        fun markFreshStart() { pausedAt = 0L }
        private var self: PlaybackService? = null
        /** Sauvegarde immédiate (position + fichier du dossier), p. ex. après « Marquer comme lu ». */
        fun saveNow() { self?.persist(true) }
        const val ACTION_PLAY_PAUSE = "fr.jd.audiobooks.PLAY_PAUSE"
        const val ACTION_NEXT = "fr.jd.audiobooks.NEXT"
        const val ACTION_PREV = "fr.jd.audiobooks.PREV"
        /** Jeton joint par le widget : le service est exporté (Android Auto, notification…), donc ces commandes
         *  ne sont acceptées que si elles portent le jeton secret de l'appli. */
        const val EXTRA_TOKEN = "fr.jd.audiobooks.TOKEN"
        private val ACTIONS = setOf(ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREV)
        /** Clients médias autorisés en plus des composants système de confiance (isTrusted) et de l'appli elle-même. */
        private val ALLOWED_CLIENTS = setOf(
            "com.google.android.projection.gearhead",   // Android Auto
            "com.google.android.googlequicksearchbox",  // Assistant Google
            "com.google.android.carassistant",          // Assistant en voiture
            "com.android.car.media",                    // Android Automotive
            "com.android.systemui",                     // écran de verrouillage / volet de notifications
            "com.android.bluetooth",                    // commandes Bluetooth (voiture, casque)
            "com.google.android.bluetooth",             // idem sur les Pixel (module Bluetooth mis à jour par Google Play)
            "com.google.android.wearable.app"           // montre Wear OS
        )
    }

    private fun isAllowedClient(c: MediaSession.ControllerInfo): Boolean =
        c.isTrusted || c.packageName == packageName || c.packageName in ALLOWED_CLIENTS

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in ACTIONS && intent?.getStringExtra(EXTRA_TOKEN) != store.serviceToken()) {
            logw("Commande ignorée (jeton absent ou faux) : ${intent?.action}")
            return super.onStartCommand(intent, flags, startId)
        }
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> player?.let {
                // Lecteur vide (service redémarré, mini-lecteur fermé) : le widget reprend le dernier livre.
                if (it.mediaItemCount == 0) resumeLastBook() else if (it.playWhenReady) it.pause() else it.play()
            }
            ACTION_NEXT -> player?.seekToNextMediaItem()
            ACTION_PREV -> player?.seekToPreviousMediaItem()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun resumeLastBook() {
        val path = store.lastBook() ?: return
        scope.launch {
            try {
                val bk = withContext(Dispatchers.IO) { store.library().firstOrNull { it.path == path } } ?: return@launch
                val l = loadBook(bk)
                val pl = player ?: return@launch
                markFreshStart()
                pl.setMediaItems(l.items, l.index, l.pos)
                pl.setPlaybackSpeed(safeSpeed(l.speed))
                pl.prepare()
                pl.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { logw("reprise du dernier livre", e) }
        }
    }

    /** Future alimenté par une coroutine : une exception ou une annulation termine le future (au lieu de le laisser
     *  attendre indéfiniment, ce qui figeait Android Auto). */
    private fun <T> futureOf(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch {
            try { f.set(block()) }
            catch (e: CancellationException) { f.cancel(false); throw e }
            catch (e: Throwable) { f.setException(e) }
        }
        return f
    }

    // ---- Construction des MediaItem pour la bibliothèque (arborescence Android Auto) ----
    private suspend fun bookItem(bk: Book): MediaItem {
        val art = Covers.artUri(this, bk)
        return MediaItem.Builder().setMediaId("book:${bk.path}").setMediaMetadata(
            MediaMetadata.Builder().setTitle(bk.name).setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .apply { art?.let { setArtworkUri(it) } }.build()
        ).build()
    }

    private fun trackItem(bk: Book, i: Int, art: Uri?): MediaItem =
        MediaItem.Builder().setMediaId("track:${bk.path}|$i").setUri(bk.uris[i]).setMediaMetadata(
            MediaMetadata.Builder().setTitle(bk.names[i]).setArtist(bk.name)
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .setExtras(ProgressFile.extras(bk))
                .apply { art?.let { setArtworkUri(it) } }.build()
        ).build()

    private class Loaded(val items: List<MediaItem>, val index: Int, val pos: Long, val speed: Float)

    /** Playlist complète d'un livre + position de reprise. requestedIdx : fichier choisi explicitement (Android Auto). */
    private suspend fun loadBook(bk: Book, requestedIdx: Int? = null): Loaded {
        val art = Covers.artUri(this, bk)
        val items = bk.uris.indices.map { trackItem(bk, it, art) }
        val saved = withContext(Dispatchers.IO) { store.load(bk.path) }
        val idx = (requestedIdx ?: saved?.index ?: 0).coerceIn(0, bk.uris.lastIndex)
        val pos = if (requestedIdx != null && requestedIdx != saved?.index) 0L else saved?.pos ?: 0L
        return Loaded(items, idx, pos, saved?.speed ?: 1f)
    }

    private val libraryCallback = object : MediaLibrarySession.Callback {
        // Seuls l'appli, le système et les clients connus (Android Auto, Assistant, Bluetooth, montre) peuvent parcourir
        // la bibliothèque ou piloter la lecture : une autre appli installée ne peut plus lister tes livres.
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (isAllowedClient(controller)) super.onConnect(session, controller)
            else {
                logw("Client média refusé : ${controller.packageName}")
                MediaSession.ConnectionResult.reject()
            }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItem.Builder().setMediaId(ROOT_ID)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(getString(R.string.app_name)).setIsBrowsable(true).setIsPlayable(false).build())
                        .build(), params
                )
            )

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = futureOf {
            val items = withContext(Dispatchers.IO) {
                when {
                    parentId == ROOT_ID -> store.library().map { bookItem(it) }
                    parentId.startsWith("book:") -> {
                        val path = parentId.removePrefix("book:")
                        val bk = store.library().firstOrNull { it.path == path }
                        if (bk == null) emptyList() else {
                            val art = Covers.artUri(this@PlaybackService, bk)
                            bk.uris.indices.map { trackItem(bk, it, art) }
                        }
                    }
                    else -> emptyList()
                }
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = futureOf {
            val item = withContext(Dispatchers.IO) {
                if (mediaId.startsWith("track:")) {
                    val rest = mediaId.removePrefix("track:")
                    val path = rest.substringBeforeLast('|'); val idx = rest.substringAfterLast('|').toInt()
                    val bk = store.library().firstOrNull { it.path == path }
                    bk?.let { trackItem(it, idx, Covers.artUri(this@PlaybackService, it)) }
                } else if (mediaId.startsWith("book:")) {
                    store.library().firstOrNull { it.path == mediaId.removePrefix("book:") }?.let { bookItem(it) }
                } else null
            }
            if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        // Résout un élément choisi dans Android Auto (un chapitre) en la playlist complète du livre,
        // pour garder précédent/suivant et la reprise à la bonne position.
        override fun onSetMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val requested = mediaItems.firstOrNull()?.mediaId ?: ""
            val isTrack = requested.startsWith("track:")
            if (!isTrack && !requested.startsWith("book:"))
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            return futureOf {
                val path = if (isTrack) requested.removePrefix("track:").substringBeforeLast('|') else requested.removePrefix("book:")
                val idx = if (isTrack) requested.substringAfterLast('|').toInt() else null
                val bk = withContext(Dispatchers.IO) { store.library().firstOrNull { it.path == path } }
                if (bk == null) MediaItemsWithStartPosition(ImmutableList.of(), 0, 0)
                else {
                    val l = loadBook(bk, idx)
                    markFreshStart() // nouveau livre choisi depuis Android Auto : pas de recul automatique
                    MediaItemsWithStartPosition(ImmutableList.copyOf(l.items), l.index, l.pos)
                }
            }
        }
    }

    /** Session exposée à la notification, à l'écran de verrouillage et à Android Auto : les boutons de saut utilisent
     *  la durée réglée par l'utilisateur (Skip). L'appli, elle, pilote directement l'ExoPlayer. */
    private class SkipPlayer(p: Player) : ForwardingPlayer(p) {
        override fun getSeekBackIncrement(): Long = Skip.ms
        override fun getSeekForwardIncrement(): Long = Skip.ms
        override fun seekBack() { wrappedPlayer.skipBack() }
        override fun seekForward() { wrappedPlayer.skipForward() }
    }

    override fun onCreate() {
        super.onCreate()
        Skip.load(store)
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true)
            // Garde le processeur éveillé pendant la lecture écran éteint (fichiers locaux) : sans ça, certains
            // téléphones saccadent ou coupent. Le verrou est relâché dès que la lecture s'arrête.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(30_000).setSeekForwardIncrementMs(30_000).build()
        p.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(sessionId: Int) { Boost.attach(sessionId) }
            override fun onEvents(pl: Player, e: Player.Events) { JdWidget.updateAll(this@PlaybackService) }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) bookEnded() }
            // Retour arrière automatique à la reprise, proportionnel à la durée de pause
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Gain de volume réglé pour ce livre.
                mediaItem?.mediaMetadata?.extras?.getString("path")?.let { Boost.set(store.boost(it)) }
                // Changement de fichier en cours de lecture (pas le simple chargement d'une playlist).
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) persist(true)
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    // Déplacement manuel pendant la pause (chapitre, curseur) : la reprise ne doit pas reculer encore.
                    if (!p.playWhenReady) pausedAt = 0L
                    h.removeCallbacks(seekPersist); h.postDelayed(seekPersist, 1000)
                }
            }
            override fun onPlayWhenReadyChanged(pwr: Boolean, reason: Int) {
                // Minuterie « fin du fichier » arrivée au bout, ou lecture reprise pendant le fondu : minuterie terminée.
                if ((!pwr && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) || (pwr && Sleep.fading)) Sleep.cancel()
                val now = SystemClock.elapsedRealtime()
                if (!pwr) { pausedAt = now; persist(true) }
                else if (pausedAt > 0) {
                    val s = (now - pausedAt) / 1000
                    val back = when { s < 5 -> 0L; s < 300 -> 3_000L; s < 3600 -> 10_000L; else -> 20_000L }
                    if (back > 0) p.seekTo((p.currentPosition - back).coerceAtLeast(0))
                }
            }
        })
        h.postDelayed(object : Runnable {
            override fun run() {
                if (p.isPlaying) {
                    p.mediaMetadata.extras?.getString("path")?.let {
                        store.addTime(it, 1000, (1000 * p.playbackParameters.speed).toLong())
                    }
                    playTicks++
                    // Position : toutes les 5 s ; statistiques : toutes les 20 s ; fichier du dossier (souvent synchronisé
                    // par un service cloud, donc ménagé) : toutes les 60 s, en plus de la pause et des changements de fichier.
                    if (playTicks % 60 == 0) persist(true)
                    else {
                        if (playTicks % 20 == 0) store.flushTime()
                        if (playTicks % 5 == 0) persist(false)
                    }
                }
                h.postDelayed(this, 1000)
            }
        }, 1000)
        player = p
        self = this
        session = MediaLibrarySession.Builder(this, SkipPlayer(p), libraryCallback).build()
    }

    override fun onGetSession(c: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        persist(true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persist(true)
        Sleep.cancel()
        Boost.release()
        self = null
        h.removeCallbacksAndMessages(null)
        scope.cancel()
        session?.release(); player?.release(); player = null
        super.onDestroy()
    }
}

/** Minuterie de sommeil : durée fixe (avec fondu sonore sur les 15 dernières secondes) ou « fin du fichier en cours ». */
object Sleep {
    private const val FADE_MS = 15_000L
    private const val FADE_STEPS = 30
    private val h = Handler(Looper.getMainLooper())
    private var r: Runnable? = null
    private var f: Runnable? = null
    var endAt = 0L
    var untilFileEnd = false
    var fading = false

    fun set(min: Int) {
        cancel()
        if (min <= 0) return
        val total = min * 60_000L
        endAt = SystemClock.elapsedRealtime() + total
        r = Runnable { startFade() }.also { h.postDelayed(it, (total - FADE_MS).coerceAtLeast(0)) }
    }

    /** Pause à la fin du fichier en cours (ExoPlayer s'arrête tout seul à la fin de l'élément). */
    fun fileEnd() {
        cancel()
        untilFileEnd = true
        PlaybackService.player?.pauseAtEndOfMediaItems = true
    }

    private fun startFade() {
        val p = PlaybackService.player ?: run { endAt = 0; return }
        fading = true
        var i = 0
        val step = object : Runnable {
            override fun run() {
                i++
                if (i >= FADE_STEPS) {
                    p.pause(); p.volume = 1f
                    fading = false; endAt = 0; f = null
                } else {
                    p.volume = 1f - i / FADE_STEPS.toFloat()
                    h.postDelayed(this, FADE_MS / FADE_STEPS)
                }
            }
        }
        f = step
        h.post(step)
    }

    fun cancel() {
        r?.let { h.removeCallbacks(it) }
        f?.let { h.removeCallbacks(it) }
        r = null; f = null
        if (fading) PlaybackService.player?.volume = 1f
        fading = false; endAt = 0
        if (untilFileEnd) { untilFileEnd = false; PlaybackService.player?.pauseAtEndOfMediaItems = false }
    }
}
