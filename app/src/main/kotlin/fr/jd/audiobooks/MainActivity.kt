package fr.jd.audiobooks

import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        startService(Intent(this, PlaybackService::class.java))
        val store = Store(this)
        Thread { store.cleanupLegacy() }.start() // supprime une fois les anciens réglages égaliseur/signets
        enableEdgeToEdge()
        setContent { JdTheme { Surface(Modifier.fillMaxSize()) { App(store) } } }
    }
}

@Composable
fun Cover(bk: Book, size: Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, bk.path, bk.cover, Covers.version) { value = Covers.get(ctx, bk) }
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun App(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Affichage instantané depuis le cache (s'il existe) pendant qu'un scan frais tourne en arrière-plan ;
    // la liste affichée se met à jour dès que ce scan se termine, sans jamais bloquer l'écran.
    var books by remember { mutableStateOf(store.cachedBooks() ?: emptyList()) }
    var scanProgress by remember { mutableStateOf<ScanProgress?>(null) }
    var cur by remember { mutableStateOf<Book?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    var showCovers by remember { mutableStateOf(false) }
    var importMsg by remember { mutableStateOf<String?>(null) }

    suspend fun rescan() {
        scanProgress = ScanProgress(0, 0)
        val result = withContext(Dispatchers.IO) { store.scan { sp -> scanProgress = sp } }
        books = result
        scanProgress = null
        // Diagnostic Smart Player retiré de l'UI maintenant que l'import est confirmé fonctionnel ;
        // store.lastSabpDiag reste calculé si besoin de redéboguer un jour.
    }
    // Pas de scan automatique à l'ouverture : la liste vient uniquement du cache. Un scan ne se
    // déclenche que sur une action explicite (bouton "Dossier" la première fois, ou "Rescan").

    val picker = rememberLauncherForActivityResult(OpenTreeRW()) { u ->
        if (u != null) {
            try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            catch (e: Exception) { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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
        cur = bk
        showPlayer = true
        openJob?.cancel()
        openJob = scope.launch {
            var waited = 0
            while (PlaybackService.player == null) {
                // Filet de sécurité : si le service n'a pas démarré (ou a été arrêté), on le relance.
                if (waited % 1000 == 0) try { ctx.startService(Intent(ctx, PlaybackService::class.java)) } catch (e: Exception) { }
                delay(50); waited += 50
            }
            val p = PlaybackService.player!!
            // On arrête d'abord la lecture en cours et on enregistre tout de suite l'ancien livre à sa vraie position
            // (avant que la playlist ne soit remplacée, et avant de relire la position du livre qu'on ouvre).
            p.playWhenReady = false
            PlaybackService.saveNow()
            // La pochette n'est plus bloquante : si elle est longue à lire (pochette intégrée dans un gros
            // fichier, stockage froid), on lance la lecture sans elle plutôt que de laisser l'écran vide.
            val artJob = async(Dispatchers.IO) { Covers.get(ctx, bk)?.let { Covers.jpeg(it) } }
            // Fichier de progression du dossier (position.jd.json) : s'il est plus récent que la sauvegarde locale
            // (livre repris sur un autre appareil, par ex.), il est appliqué avant de lire la position.
            val syncJob = async(Dispatchers.IO) {
                ProgressFile.read(ctx, store.root, bk)?.let { store.applyProgressFile(bk.path, bk.names, it) }
            }
            val art = withTimeoutOrNull(2000) { artJob.await() }
            withTimeoutOrNull(1500) { syncJob.await() }
            // Lecture de la position seulement ici : l'écran lecteur n'écrit rien tant que le livre n'est pas chargé.
            val s = store.load(bk.path)
            PlaybackService.markFreshStart() // on ouvre un livre choisi explicitement : jamais de recul automatique ici
            p.setMediaItems(bk.uris.mapIndexed { i, u ->
                MediaItem.Builder().setUri(u).setMediaMetadata(
                    MediaMetadata.Builder().setTitle(bk.names[i]).setArtist(bk.name)
                        .setExtras(ProgressFile.extras(bk)).apply {
                        art?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                    }.build()).build()
            }, (s?.index ?: 0).coerceIn(0, bk.uris.lastIndex), s?.pos ?: 0)
            // La sélection d'un livre ne doit jamais lancer la lecture automatiquement.
            // On prépare le lecteur sur la position sauvegardée, puis seul le bouton « Lire »
            // (ou une commande externe comme Android Auto) démarre effectivement la lecture.
            p.setPlaybackSpeed(s?.speed ?: 1f)
            p.prepare()
        }
    }
    // Afficher un livre : si le lecteur le contient déjà (mini-lecteur, livre en cours), on rouvre l'écran
    // sans rien recharger ; sinon on le charge.
    fun show(bk: Book) {
        if (isLoaded(PlaybackService.player, bk)) { cur = bk; showPlayer = true } else open(bk)
    }
    // Retour vers la bibliothèque : la lecture continue (mini-lecteur). Si le livre n'est pas encore chargé, on annule l'ouverture.
    fun leavePlayer() {
        val c = cur
        if (c != null && isLoaded(PlaybackService.player, c)) PlaybackService.saveNow()
        else { openJob?.cancel(); PlaybackService.player?.pause(); cur = null }
        showPlayer = false
    }
    // Geste / bouton retour du système : revient à la bibliothèque au lieu de fermer l'appli.
    BackHandler(enabled = showPlayer || showStats) { if (showPlayer) leavePlayer() else showStats = false }
    val b = cur
    when {
        b != null && showPlayer -> PlayerScreen(b, store) { leavePlayer() }
        showStats -> StatsScreen(store) { showStats = false }
        else -> Column {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text(stringResource(R.string.app_name), Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton({ showStats = true }) { Icon(JdIcons.BarChart, contentDescription = stringResource(R.string.statistics)) }
                IconButton({ showCovers = true }, enabled = books.isNotEmpty()) { Icon(JdIcons.Image, contentDescription = stringResource(R.string.find_missing_covers)) }
                IconButton({ statsPicker.launch(arrayOf("text/xml", "application/xml", "*/*")) }) { Icon(JdIcons.Download, contentDescription = stringResource(R.string.import_stats)) }
                IconButton({ picker.launch(null) }) { Icon(JdIcons.Folder, contentDescription = stringResource(R.string.choose_folder)) }
                IconButton({ scope.launch { rescan() } }, enabled = scanProgress == null) { Icon(JdIcons.Refresh, contentDescription = stringResource(R.string.refresh_library)) }
            }
            scanProgress?.let { sp ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.scan_progress, sp.folders, sp.books),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (store.root != null && !ProgressFile.canWrite(ctx, store.root)) {
                Text(
                    stringResource(R.string.write_not_allowed),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
            }
            importMsg?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (books.isEmpty() && scanProgress == null && store.root != null) {
                Text(stringResource(R.string.no_books_cached), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (store.root == null) {
                Text(stringResource(R.string.pick_folder_hint), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }

            val tabs = listOf(stringResource(R.string.tab_all), stringResource(R.string.tab_new), stringResource(R.string.tab_in_progress), stringResource(R.string.tab_finished))
            var tab by remember { mutableStateOf(0) }
            val shown = when (tab) {
                1 -> books.filter { !store.hasSaved(it.path) && !store.finished(it.path) }
                2 -> books.filter { store.hasSaved(it.path) && !store.finished(it.path) }
                3 -> books.filter { store.finished(it.path) }
                else -> books
            }
            if (books.isNotEmpty()) {
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, style = MaterialTheme.typography.labelSmall, maxLines = 1) })
                    }
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.path }) { bk ->
                    val s = store.load(bk.path)
                    val finished = store.finished(bk.path)
                    val total = remember(bk.path) { store.durations(bk).takeIf { d -> d.all { it > 0 } }?.sum() }
                    Row(
                        Modifier.fillMaxWidth().clickable { show(bk) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(Modifier.width(84.dp)) {
                            Cover(bk, 84.dp)
                            if (finished) Box(
                                Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                                    .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) { Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(bk.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            val series = bk.path.substringBeforeLast('/', "")
                            if (series.isNotEmpty()) Text(
                                series.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(28.dp))
                            Text(
                                when {
                                    finished -> stringResource(R.string.status_finished)
                                    s != null -> stringResource(R.string.resume_at, s.index + 1, bk.uris.size, fmt(s.pos))
                                    else -> stringResource(R.string.n_files, bk.uris.size)
                                } + (total?.let { " · ${fmt(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
            MiniPlayer(books, onOpen = { show(it) }, onClose = {
                PlaybackService.player?.let { pl -> pl.pause(); PlaybackService.saveNow(); pl.clearMediaItems() }
                cur = null
            })
            if (showCovers) MissingCoversDialog(books, store, onCover = { path, uri ->
                if (uri != null) books = books.map { if (it.path == path) it.copy(cover = uri) else it }
            }, onDismiss = { showCovers = false })
        }
    }
}

/** Mini-lecteur affiché en bas de la bibliothèque dès qu'un livre est chargé dans le lecteur. */
@Composable
fun MiniPlayer(books: List<Book>, onOpen: (Book) -> Unit, onClose: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(500); tick++ } }
    tick.let { }
    val p = PlaybackService.player ?: return
    if (p.mediaItemCount == 0) return
    val path = p.currentMediaItem?.mediaMetadata?.extras?.getString("path") ?: return
    val bk = books.firstOrNull { it.path == path } ?: return
    val playing = p.playWhenReady
    val dur = p.duration.takeIf { it > 0 } ?: 1L
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(progress = { (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.fillMaxWidth().clickable { onOpen(bk) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(bk, 48.dp)
                Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
                    Text(bk.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${bk.names.getOrElse(p.currentMediaItemIndex) { "" }} · ${fmt(p.currentPosition)}",
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton({ p.seekBack() }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(JdIcons.Replay, contentDescription = stringResource(R.string.back_30), Modifier.size(28.dp))
                        Text("30", fontSize = 8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                FilledIconButton({ if (playing) p.pause() else p.play() }, Modifier.size(44.dp)) {
                    Icon(if (playing) JdIcons.Pause else JdIcons.Play, contentDescription = if (playing) stringResource(R.string.pause) else stringResource(R.string.play), Modifier.size(26.dp))
                }
                IconButton(onClose) { Icon(JdIcons.Close, contentDescription = stringResource(R.string.close_player)) }
            }
        }
    }
}

@Composable
fun PlayerScreen(bk: Book, store: Store, back: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var boost by remember { mutableStateOf(store.boost(bk.path)) }
    var boostMenu by remember { mutableStateOf(false) }
    var chaps by remember { mutableStateOf(listOf<Chap>()) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var skipSilence by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(store.finished(bk.path)) }
    var durs by remember { mutableStateOf(store.durations(bk)) }
    var probing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var probeFailed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    // "loaded" : le lecteur contient bien la playlist de CE livre. Tant que ce n'est pas le cas (ouverture en
    // cours, service en train de démarrer, ancien livre encore chargé), on affiche un écran de chargement
    // au lieu d'un écran vide, et on n'écrit rien dans les positions sauvegardées.
    val loaded = isLoaded(PlaybackService.player, bk)
    val idx = if (loaded) PlaybackService.player?.currentMediaItemIndex ?: 0 else 0
    LaunchedEffect(loaded, idx) {
        // Les chapitres n'existent que dans les conteneurs MP4 (.m4b/.m4a) : inutile d'ouvrir les autres fichiers.
        val e = bk.names.getOrNull(idx)?.substringAfterLast('.', "")?.lowercase()
        chaps = if (loaded && e in setOf("m4b", "m4a", "mp4")) withContext(Dispatchers.IO) { Chapters.read(ctx, bk.uris[idx]) } else emptyList()
    }
    // Cette boucle ne fait que rafraîchir l'affichage : la progression est sauvegardée par PlaybackService.
    LaunchedEffect(Unit) {
        while (true) {
            delay(500); tick++
            val pl = PlaybackService.player
            if (pl != null && isLoaded(pl, bk)) {
                if (pl.playbackState == Player.STATE_READY) {
                    ready = true
                    // Le lecteur connaît la durée exacte du fichier en cours : on s'en sert (gratuit) pour corriger/compléter.
                    val d = pl.duration; val i = pl.currentMediaItemIndex
                    if (d > 0 && i in durs.indices && kotlin.math.abs(durs[i] - d) > 500) {
                        durs = durs.toMutableList().also { it[i] = d }
                        store.putDurations(bk, durs)
                    }
                }
            }
        }
    }
    // Durée totale du livre : calculée en arrière-plan, un fichier à la fois, SEULEMENT une fois la lecture
    // prête (donc sans ralentir l'ouverture), puis mémorisée : les ouvertures suivantes sont instantanées.
    LaunchedEffect(bk.path, ready) {
        if (!ready) return@LaunchedEffect
        delay(1500)
        val todo = durs.indices.filter { durs[it] <= 0 }
        if (todo.isEmpty()) return@LaunchedEffect
        var done = 0; var failed = 0
        probing = 0 to todo.size
        try {
            for (i in todo) {
                val d = withContext(Dispatchers.IO) { Durations.probe(ctx, bk.uris[i]) }
                if (d > 0) { if (durs[i] <= 0) durs = durs.toMutableList().also { it[i] = d } } else failed++
                done++; probing = done to todo.size
            }
        } finally {
            withContext(NonCancellable) { store.putDurations(bk, durs) }
        }
        probeFailed = failed > 0
        probing = null
    }
    tick.let { }
    val p = PlaybackService.player
    if (!loaded || p == null) {
        Column(Modifier.fillMaxSize().padding(16.dp).statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            BackButton(back, Modifier.align(Alignment.Start))
            Spacer(Modifier.weight(1f))
            Cover(bk, 160.dp)
            Spacer(Modifier.height(16.dp))
            Text(bk.name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
        }
        return
    }
    val playing = p.playWhenReady
    val left = (Sleep.endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    val ci = chaps.indexOfLast { it.startMs <= p.currentPosition }.coerceAtLeast(0)
    val fi = p.currentMediaItemIndex.coerceIn(0, bk.uris.lastIndex)
    val total = if (durs.all { it > 0 }) durs.sum() else -1L
    val before = durs.take(fi)
    val elapsed = if (before.all { it > 0 }) before.sum() + p.currentPosition else -1L
    val btnPad = PaddingValues(horizontal = 8.dp)
    // Mise en page : barre du haut + liste défilante (pochette, chapitres) + panneau de commandes FIXE en bas.
    // Avant, tout était dans une colonne non défilante sans marge pour la barre de navigation : sur un écran
    // un peu petit, les boutons du bas étaient poussés hors de l'écran ou sous la barre système.
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackButton(back)
            Spacer(Modifier.weight(1f))
            IconButton(
                { finished = !finished; store.setFinished(bk.path, finished); PlaybackService.saveNow() },
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (finished) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
                )
            ) {
                Icon(
                    JdIcons.Check, contentDescription = if (finished) stringResource(R.string.mark_read_undo) else stringResource(R.string.mark_read),
                    tint = if (finished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // La pochette s'adapte à la place disponible : grande quand il n y a pas de chapitres (plus de grand vide),
        // plus petite sinon pour laisser voir la liste.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val busy = chaps.isNotEmpty()
        val coverSize = (if (busy) minOf(maxWidth * 0.6f, maxHeight * 0.4f) else minOf(maxWidth, maxHeight - 130.dp)).coerceIn(120.dp, 400.dp)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(bk, coverSize)
                    Spacer(Modifier.height(8.dp))
                    Text(bk.name, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (chaps.isNotEmpty()) {
                item { Text(stringResource(R.string.chapters), style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(chaps) { i, c ->
                    Text(
                        "${fmt(c.startMs)}  ${c.title}",
                        Modifier.fillMaxWidth().clickable { p.seekTo(c.startMs) }.padding(vertical = 8.dp),
                        color = if (i == ci) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth().clickable { showFiles = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(bk.names.getOrElse(fi) { "" } + "  (${fi + 1}/${bk.uris.size})", Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Icon(JdIcons.ArrowDropDown, contentDescription = stringResource(R.string.choose_file))
            }
            if (showFiles) FilePickerDialog(bk, durs, fi, store, onPick = { p.seekTo(it, 0); showFiles = false }, onDismiss = { showFiles = false })
            if (chaps.isNotEmpty()) Text(stringResource(R.string.chapter_of, ci + 1, chaps.size, chaps[ci].title), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            val dur = p.duration.coerceAtLeast(1)
            Slider(p.currentPosition.toFloat() / dur, { p.seekTo((it * dur).toLong()) })
            Row { Text(fmt(p.currentPosition), Modifier.weight(1f)); Text(fmt(dur)) }
            Text(
                when {
                    total > 0 && elapsed >= 0 -> stringResource(R.string.listened_of, fmt(elapsed), fmt(total), (elapsed * 100 / total).coerceIn(0, 100), fmt((total - elapsed).coerceAtLeast(0)))
                    probing != null -> stringResource(R.string.duration_probing, probing!!.first, probing!!.second)
                    probeFailed -> stringResource(R.string.duration_unavailable)
                    else -> stringResource(R.string.duration_calculating)
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton({
                    if (chaps.isEmpty()) p.seekToPreviousMediaItem()
                    else p.seekTo(if (p.currentPosition - chaps[ci].startMs > 3000) chaps[ci].startMs else chaps.getOrNull(ci - 1)?.startMs ?: 0)
                }) { Icon(JdIcons.SkipPrevious, contentDescription = stringResource(R.string.previous), Modifier.size(28.dp)) }
                IconButton({ p.seekBack() }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(JdIcons.Replay, contentDescription = stringResource(R.string.back_30), Modifier.size(34.dp))
                        Text("30", fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                FilledIconButton({ if (playing) p.pause() else p.play() }, Modifier.size(64.dp)) {
                    Icon(if (playing) JdIcons.Pause else JdIcons.Play, contentDescription = if (playing) stringResource(R.string.pause) else stringResource(R.string.play), Modifier.size(36.dp))
                }
                IconButton({ p.seekForward() }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(JdIcons.Replay, contentDescription = stringResource(R.string.forward_30), Modifier.size(34.dp).graphicsLayer { scaleX = -1f })
                        Text("30", fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                IconButton({
                    val n = chaps.getOrNull(ci + 1)
                    if (n != null) p.seekTo(n.startMs) else p.seekToNextMediaItem()
                }) { Icon(JdIcons.SkipNext, contentDescription = stringResource(R.string.next), Modifier.size(28.dp)) }
            }
            // Réglages : vitesse, minuterie de sommeil, saut des silences, volume (une seule rangée d'icônes)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton({ speedMenu = true }, Modifier.fillMaxWidth(), contentPadding = btnPad) { Text("×${p.playbackParameters.speed}", maxLines = 1) }
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { s ->
                            DropdownMenuItem({ Text("×$s") }, { p.setPlaybackSpeed(s); speedMenu = false })
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    val sleepBtn: @Composable () -> Unit = {
                        Icon(JdIcons.Moon, contentDescription = stringResource(R.string.sleep_timer), Modifier.size(20.dp))
                        if (left > 0) Text(fmt(left), Modifier.padding(start = 4.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                    if (left > 0) FilledTonalButton({ sleepMenu = true }, Modifier.fillMaxWidth(), contentPadding = btnPad) { sleepBtn() }
                    else OutlinedButton({ sleepMenu = true }, Modifier.fillMaxWidth(), contentPadding = btnPad) { sleepBtn() }
                    DropdownMenu(sleepMenu, { sleepMenu = false }) {
                        listOf(0, 10, 15, 30, 45, 60, 90).forEach { m ->
                            DropdownMenuItem({ Text(if (m == 0) stringResource(R.string.off) else stringResource(R.string.minutes_short, m)) }, { Sleep.set(m); sleepMenu = false })
                        }
                    }
                }
                FilterChip(
                    skipSilence, { skipSilence = !skipSilence; p.skipSilenceEnabled = skipSilence },
                    { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Icon(JdIcons.GraphicEq, contentDescription = stringResource(R.string.skip_silence), Modifier.size(20.dp)) } },
                    Modifier.weight(1f)
                )
                Box(Modifier.weight(1f)) {
                    val volBtn: @Composable () -> Unit = {
                        Icon(JdIcons.VolumeUp, contentDescription = stringResource(R.string.volume_boost), Modifier.size(20.dp))
                        if (boost > 0) Text("+$boost", Modifier.padding(start = 4.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                    if (boost > 0) FilledTonalButton({ boostMenu = true }, Modifier.fillMaxWidth(), contentPadding = btnPad) { volBtn() }
                    else OutlinedButton({ boostMenu = true }, Modifier.fillMaxWidth(), contentPadding = btnPad) { volBtn() }
                    DropdownMenu(boostMenu, { boostMenu = false }) {
                        Boost.levels.forEach { db ->
                            DropdownMenuItem({ Text(if (db == 0) stringResource(R.string.normal) else "+$db dB") }, {
                                boost = db; Boost.set(db); store.setBoost(bk.path, db); boostMenu = false
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatsScreen(store: Store, back: () -> Unit) {
    val st = remember { store.stats() }
    val f = SimpleDateFormat("yyyyMMdd", Locale.US)
    val today = f.format(Date())
    val days = (6 downTo 0).map { d -> f.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -d) }.time) }
    val perDay = days.map { d -> st.filter { it.day == d }.sumOf { it.wall } }
    val max = perDay.max().coerceAtLeast(1)
    val wall = st.sumOf { it.wall }
    val content = st.sumOf { it.content }
    Column(Modifier.padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackButton(back)
        Text(stringResource(R.string.statistics), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.stats_today, fmt(st.filter { it.day == today }.sumOf { it.wall })))
        Text(stringResource(R.string.stats_total, fmt(wall)))
        Text(stringResource(R.string.stats_content, fmt(content)))
        Text(stringResource(R.string.stats_saved, fmt((content - wall).coerceAtLeast(0))))
        Text(stringResource(R.string.stats_7days), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            perDay.forEachIndexed { i, v ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height((90f * v / max).dp).background(MaterialTheme.colorScheme.primary))
                    Text(days[i].takeLast(2))
                }
            }
        }
        Text(stringResource(R.string.stats_by_book), style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(st.groupBy { it.book }.map { (b, l) -> b to l.sumOf { it.wall } }.sortedByDescending { it.second }) { (b, t) ->
                ListItem(headlineContent = { Text(b.substringAfterLast('/')) }, trailingContent = { Text(fmt(t)) })
            }
        }
    }
}


