package fr.jd.audiobooks

import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.media3.common.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/** Sélecteur de dossier qui demande aussi l'écriture (pour le fichier de progression à côté des fichiers audio). */
class OpenTreeRW : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: android.content.Context, input: android.net.Uri?): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
}

fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        startService(Intent(this, PlaybackService::class.java))
        val store = Store(this)
        Skip.load(store)
        Thread { store.cleanupLegacy(); store.compactStats() }.start() // anciens réglages égaliseur/signets ; vieilles stats regroupées par mois
        enableEdgeToEdge()
        setContent { JdTheme { Surface(Modifier.fillMaxSize()) { App(store) } } }
    }
}

@Composable
fun App(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Affichage instantané depuis le cache (s'il existe) pendant qu'un scan frais tourne en arrière-plan ;
    // la liste affichée se met à jour dès que ce scan se termine, sans jamais bloquer l'écran.
    // (le cache est déjà en mémoire après une rotation ou si le service l'a chargé ; sinon il est lu hors du thread principal)
    var books by remember { mutableStateOf(store.cachedBooksInMemory() ?: emptyList()) }
    var cacheLoaded by remember { mutableStateOf(books.isNotEmpty()) }
    LaunchedEffect(Unit) {
        if (!cacheLoaded) {
            val l = withContext(Dispatchers.IO) { store.cachedBooks() }
            if (books.isEmpty() && l != null) books = l
            cacheLoaded = true
        }
    }
    var scanProgress by remember { mutableStateOf<ScanProgress?>(null) }
    // État conservé à la rotation de l'écran (et à la recréation de l'activité) : livre ouvert, écran affiché, recherche, tri.
    var curPath by rememberSaveable { mutableStateOf<String?>(null) }
    val cur = books.firstOrNull { it.path == curPath }
    var showStats by rememberSaveable { mutableStateOf(false) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortRecent by rememberSaveable { mutableStateOf(store.flag("sort_recent")) }
    var importMsg by remember { mutableStateOf<String?>(null) }

    suspend fun rescan() {
        scanProgress = ScanProgress(0, 0)
        val result = withContext(Dispatchers.IO) { store.scan { sp -> scanProgress = sp } }
        books = result
        scanProgress = null
    }
    // Pas de scan automatique à l'ouverture : la liste vient uniquement du cache. Un scan ne se
    // déclenche que sur une action explicite (bouton "Dossier" la première fois, ou "Rescan").

    val picker = rememberLauncherForActivityResult(OpenTreeRW()) { u ->
        if (u != null) {
            val old = store.root
            try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            catch (e: Exception) { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            // Ancien dossier abandonné : on rend l'autorisation (Android en limite le nombre).
            if (old != null && old != u.toString()) try {
                ctx.contentResolver.releasePersistableUriPermission(
                    android.net.Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) { logw("ancienne autorisation non rendue", e) }
            store.root = u.toString()
            books = emptyList()
            scope.launch { rescan() }
        }
    }
    // Import de statistics.xml depuis Smart AudioBook Player (les positions position.sabp.dat, elles,
    // sont reprises automatiquement au scan puisqu'elles vivent directement dans chaque dossier de livre).
    val statsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val (matched, total) = withContext(Dispatchers.IO) { SabpImport.importStatistics(ctx, store, u, books) }
            importMsg = ctx.getString(R.string.import_done, matched, total)
        }
    }
    var openJob by remember { mutableStateOf<Job?>(null) }
    fun open(bk: Book) {
        curPath = bk.path
        showPlayer = true
        openJob?.cancel()
        openJob = scope.launch {
            // Une exception ici (fichier de progression bizarre, lecteur indisponible…) ne doit pas faire planter l'appli.
            try {
                var waited = 0
                while (PlaybackService.player == null) {
                    // Filet de sécurité : si le service n'a pas démarré (ou a été arrêté), on le relance.
                    if (waited % 1000 == 0) try { ctx.startService(Intent(ctx, PlaybackService::class.java)) } catch (e: Exception) { logw("démarrage du service", e) }
                    if (waited >= 10_000) { // le service ne démarre pas : on le dit au lieu d'attendre indéfiniment
                        Toast.makeText(ctx, R.string.service_unavailable, Toast.LENGTH_LONG).show()
                        curPath = null; showPlayer = false
                        return@launch
                    }
                    delay(50); waited += 50
                }
                val p = PlaybackService.player!!
                // On arrête d'abord la lecture en cours et on enregistre tout de suite l'ancien livre à sa vraie position
                // (avant que la playlist ne soit remplacée, et avant de relire la position du livre qu'on ouvre).
                p.playWhenReady = false
                PlaybackService.saveNow()
                // La pochette n'est plus bloquante : si elle est longue à lire (pochette intégrée dans un gros
                // fichier, stockage froid), on lance la lecture sans elle plutôt que de laisser l'écran vide.
                val artJob = async(Dispatchers.IO) { Covers.artUri(ctx, bk) }
                // Fichier de progression du dossier (position.jd.json) : s'il est plus récent que la sauvegarde locale
                // (livre repris sur un autre appareil, par ex.), il est appliqué avant de lire la position.
                val syncJob = async(Dispatchers.IO) {
                    ProgressFile.read(ctx, store.root, bk)?.let { store.applyProgressFile(bk.path, bk.names, it) }
                }
                val art = withTimeoutOrNull(2000) { artJob.await() }
                withTimeoutOrNull(1500) { syncJob.await() }
                // Lecture de la position seulement ici : l'écran lecteur n'écrit rien tant que le livre n'est pas chargé.
                val s = store.load(bk.path)
                PlaybackService.markFreshStart(s?.updated ?: 0L) // recul selon le temps écoulé depuis la dernière écoute de CE livre
                p.setMediaItems(bk.uris.mapIndexed { i, u ->
                    MediaItem.Builder().setUri(u).setMediaMetadata(
                        MediaMetadata.Builder().setTitle(bk.names[i]).setArtist(bk.name)
                            .setExtras(ProgressFile.extras(bk)).apply {
                            art?.let { setArtworkUri(it) }
                        }.build()).build()
                }, (s?.index ?: 0).coerceIn(0, bk.uris.lastIndex), s?.pos ?: 0)
                // La sélection d'un livre ne doit jamais lancer la lecture automatiquement.
                // On prépare le lecteur sur la position sauvegardée, puis seul le bouton « Lire »
                // (ou une commande externe comme Android Auto) démarre effectivement la lecture.
                p.setPlaybackSpeed(safeSpeed(s?.speed ?: 1f))
                p.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logw("ouverture du livre ${bk.path}", e)
                Toast.makeText(ctx, R.string.open_failed, Toast.LENGTH_LONG).show()
                curPath = null; showPlayer = false
            }
        }
    }
    // Afficher un livre : si le lecteur le contient déjà (mini-lecteur, livre en cours), on rouvre l'écran
    // sans rien recharger ; sinon on le charge.
    fun show(bk: Book) {
        if (isLoaded(PlaybackService.player, bk)) { curPath = bk.path; showPlayer = true } else open(bk)
    }
    // Retour vers la bibliothèque : la lecture continue (mini-lecteur). Si le livre n'est pas encore chargé, on annule l'ouverture.
    fun leavePlayer() {
        val c = cur
        if (c != null && isLoaded(PlaybackService.player, c)) PlaybackService.saveNow()
        else { openJob?.cancel(); PlaybackService.player?.pause(); curPath = null }
        showPlayer = false
    }
    // Geste / bouton retour du système : revient à la bibliothèque au lieu de fermer l'appli.
    // Après la recréation de l'activité (rotation, processus tué) : si l'écran lecteur était ouvert mais que le lecteur ne
    // contient plus ce livre, on le recharge.
    LaunchedEffect(books.isNotEmpty()) {
        val c = cur
        if (showPlayer && c != null && !isLoaded(PlaybackService.player, c)) open(c)
    }
    BackHandler(enabled = showPlayer || showStats) { if (showPlayer) leavePlayer() else showStats = false }
    val b = cur
    when {
        b != null && showPlayer -> PlayerScreen(b, store) { leavePlayer() }
        showStats -> StatsScreen(store) { showStats = false }
        else -> LibraryScreen(
            store, books, scanProgress, cacheLoaded, importMsg, curPath,
            query, { query = it }, sortRecent,
            { sortRecent = !sortRecent; store.setFlag("sort_recent", sortRecent) },
            onShow = { show(it) },
            onShowStats = { showStats = true },
            onImportStats = { statsPicker.launch(arrayOf("text/xml", "application/xml", "*/*")) },
            onPickFolder = { picker.launch(null) },
            onRefresh = { scope.launch { rescan() } },
            onCloseMini = {
                PlaybackService.player?.let { pl -> pl.pause(); PlaybackService.saveNow(); pl.clearMediaItems() }
                curPath = null
            },
            onCoverFound = { path, uri -> if (uri != null) books = books.map { if (it.path == path) it.copy(cover = uri) else it } }
        )
    }
}
