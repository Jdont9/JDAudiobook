package fr.jd.audiobooks

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
        enableEdgeToEdge()
        setContent { JdTheme { Surface(Modifier.fillMaxSize()) { App(store) } } }
    }
}

@Composable
fun Cover(bk: Book, size: Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, bk.path) { value = Covers.get(ctx, bk) }
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
    var showEq by remember { mutableStateOf(false) }
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
            importMsg = "$matched livre(s) sur $total importé(s) depuis statistics.xml"
        }
    }
    var openJob by remember { mutableStateOf<Job?>(null) }
    fun open(bk: Book) {
        cur = bk
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
    val b = cur
    when {
        showEq -> EqScreen(store) { showEq = false }
        b != null -> PlayerScreen(b, store, { showEq = true }) { openJob?.cancel(); PlaybackService.player?.pause(); cur = null }
        showStats -> StatsScreen(store) { showStats = false }
        else -> Column {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text("JD Audiobook Reader", Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
            }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                val btnPad = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                TextButton({ showStats = true }, contentPadding = btnPad) { Text("Stats", style = MaterialTheme.typography.labelMedium) }
                TextButton({ statsPicker.launch(arrayOf("text/xml", "application/xml", "*/*")) }, contentPadding = btnPad) { Text("Importer", style = MaterialTheme.typography.labelMedium) }
                OutlinedButton({ picker.launch(null) }, contentPadding = btnPad) { Text("Dossier", style = MaterialTheme.typography.labelMedium) }
                TextButton({ scope.launch { rescan() } }, enabled = scanProgress == null, contentPadding = btnPad) { Text("Rescan", style = MaterialTheme.typography.labelMedium) }
            }
            scanProgress?.let { sp ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Scan en cours… ${sp.folders} dossier(s) explorés · ${sp.books} livre(s) trouvés",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (store.root != null && !ProgressFile.canWrite(ctx, store.root)) {
                Text(
                    "Écriture non autorisée sur ce dossier : appuie sur « Dossier » et choisis-le à nouveau pour enregistrer la progression à côté des fichiers audio.",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
            }
            importMsg?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (books.isEmpty() && scanProgress == null && store.root != null) {
                Text("Aucun livre en cache — appuie sur « Rescan ».", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (store.root == null) {
                Text("Choisis un dossier pour commencer.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }

            val tabs = listOf("TOUS", "NOUVEAUX", "EN COURS", "LUS")
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
            LazyColumn {
                items(shown, key = { it.path }) { bk ->
                    val s = store.load(bk.path)
                    val finished = store.finished(bk.path)
                    val total = remember(bk.path) { store.durations(bk).takeIf { d -> d.all { it > 0 } }?.sum() }
                    Row(
                        Modifier.fillMaxWidth().clickable { open(bk) }.padding(horizontal = 12.dp, vertical = 10.dp),
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
                                    finished -> "Terminé"
                                    s != null -> "Reprise ${s.index + 1}/${bk.uris.size} à ${fmt(s.pos)}"
                                    else -> "${bk.uris.size} fichier(s)"
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
        }
    }
}

@Composable
fun PlayerScreen(bk: Book, store: Store, openEq: () -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var marks by remember { mutableStateOf(store.marks(bk.path)) }
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
            TextButton(back, Modifier.align(Alignment.Start)) { Text("← Bibliothèque") }
            Spacer(Modifier.weight(1f))
            Cover(bk, 160.dp)
            Spacer(Modifier.height(16.dp))
            Text(bk.name, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Chargement…", style = MaterialTheme.typography.bodyMedium)
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
    // Mise en page : barre du haut + liste défilante (pochette, chapitres, signets) + panneau de commandes FIXE en bas.
    // Avant, tout était dans une colonne non défilante sans marge pour la barre de navigation : sur un écran
    // un peu petit, les boutons du bas (dont « + Signet ») étaient poussés hors de l'écran ou sous la barre système.
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(back) { Text("← Bibliothèque") }
            Spacer(Modifier.weight(1f))
            TextButton({ finished = !finished; store.setFinished(bk.path, finished); PlaybackService.saveNow() }) {
                Text(if (finished) "✓ Lu" else "Marquer comme lu")
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(bk, 140.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(bk.name, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            if (chaps.isNotEmpty()) {
                item { Text("Chapitres", style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(chaps) { i, c ->
                    Text(
                        "${fmt(c.startMs)}  ${c.title}",
                        Modifier.fillMaxWidth().clickable { p.seekTo(c.startMs) }.padding(vertical = 8.dp),
                        color = if (i == ci) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            item { Text("Signets (${marks.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (marks.isEmpty()) item {
                Text("Aucun signet. Appuie sur « + Signet » pour marquer la position actuelle.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp))
            }
            items(marks.sortedWith(compareBy({ it.index }, { it.pos }))) { m ->
                Row(Modifier.fillMaxWidth().clickable { p.seekTo(m.index.coerceIn(0, bk.uris.lastIndex), m.pos) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, Modifier.weight(1f).padding(vertical = 8.dp))
                    TextButton({ marks = marks - m; store.putMarks(bk.path, marks) }) { Text("✕") }
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth().clickable { showFiles = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(bk.names.getOrElse(fi) { "" } + "  (${fi + 1}/${bk.uris.size})", Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("▾", style = MaterialTheme.typography.titleMedium)
            }
            if (showFiles) FilePickerDialog(bk, durs, fi, store, onPick = { p.seekTo(it, 0); showFiles = false }, onDismiss = { showFiles = false })
            if (chaps.isNotEmpty()) Text("Chapitre ${ci + 1}/${chaps.size} · ${chaps[ci].title}", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            val dur = p.duration.coerceAtLeast(1)
            Slider(p.currentPosition.toFloat() / dur, { p.seekTo((it * dur).toLong()) })
            Row { Text(fmt(p.currentPosition), Modifier.weight(1f)); Text(fmt(dur)) }
            Text(
                when {
                    total > 0 && elapsed >= 0 -> "Lecture ${fmt(elapsed)} de ${fmt(total)} · ${(elapsed * 100 / total).coerceIn(0, 100)} % · Reste ${fmt((total - elapsed).coerceAtLeast(0))}"
                    probing != null -> "Durée du livre : calcul… ${probing!!.first}/${probing!!.second}"
                    probeFailed -> "Durée du livre : indisponible (fichier illisible)"
                    else -> "Durée du livre : calcul…"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({
                    if (chaps.isEmpty()) p.seekToPreviousMediaItem()
                    else p.seekTo(if (p.currentPosition - chaps[ci].startMs > 3000) chaps[ci].startMs else chaps.getOrNull(ci - 1)?.startMs ?: 0)
                }, contentPadding = btnPad) { Text("⏮") }
                OutlinedButton({ p.seekBack() }, contentPadding = btnPad) { Text("-30") }
                Button({ if (playing) p.pause() else p.play() }) { Text(if (playing) "Pause" else "Lire") }
                OutlinedButton({ p.seekForward() }, contentPadding = btnPad) { Text("+30") }
                OutlinedButton({
                    val n = chaps.getOrNull(ci + 1)
                    if (n != null) p.seekTo(n.startMs) else p.seekToNextMediaItem()
                }, contentPadding = btnPad) { Text("⏭") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton({ speedMenu = true }, Modifier.fillMaxWidth(), contentPadding = btnPad) { Text("×${p.playbackParameters.speed}") }
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { s ->
                            DropdownMenuItem({ Text("×$s") }, { p.setPlaybackSpeed(s); speedMenu = false })
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    OutlinedButton({ sleepMenu = true }, Modifier.fillMaxWidth(), contentPadding = btnPad) { Text(if (left > 0) fmt(left) else "Sommeil", maxLines = 1) }
                    DropdownMenu(sleepMenu, { sleepMenu = false }) {
                        listOf(0, 10, 15, 30, 45, 60, 90).forEach { m ->
                            DropdownMenuItem({ Text(if (m == 0) "Désactivé" else "$m min") }, { Sleep.set(m); sleepMenu = false })
                        }
                    }
                }
                FilterChip(skipSilence, { skipSilence = !skipSilence; p.skipSilenceEnabled = skipSilence }, { Text("Silences") }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton({
                    val i = p.currentMediaItemIndex.coerceIn(0, bk.uris.lastIndex)
                    val pos = p.currentPosition
                    marks = marks + Mark(i, pos, "${bk.names[i]} ${fmt(pos)}")
                    store.putMarks(bk.path, marks)
                    android.widget.Toast.makeText(ctx, "Signet ajouté : ${fmt(pos)}", android.widget.Toast.LENGTH_SHORT).show()
                }, Modifier.weight(1f), contentPadding = btnPad) { Text("+ Signet") }
                OutlinedButton(openEq, Modifier.weight(1f), contentPadding = btnPad) { Text("Égaliseur") }
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
        TextButton(back) { Text("← Bibliothèque") }
        Text("Statistiques", style = MaterialTheme.typography.headlineSmall)
        Text("Aujourd'hui : ${fmt(st.filter { it.day == today }.sumOf { it.wall })}")
        Text("Total écouté : ${fmt(wall)}")
        Text("Contenu écouté : ${fmt(content)}")
        Text("Gagné grâce à la vitesse : ${fmt((content - wall).coerceAtLeast(0))}")
        Text("7 derniers jours", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            perDay.forEachIndexed { i, v ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height((90f * v / max).dp).background(MaterialTheme.colorScheme.primary))
                    Text(days[i].takeLast(2))
                }
            }
        }
        Text("Par livre", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(st.groupBy { it.book }.map { (b, l) -> b to l.sumOf { it.wall } }.sortedByDescending { it.second }) { (b, t) ->
                ListItem(headlineContent = { Text(b.substringAfterLast('/')) }, trailingContent = { Text(fmt(t)) })
            }
        }
    }
}


@Composable
fun EqScreen(store: Store, back: () -> Unit) {
    var enabled by remember { mutableStateOf(store.eqEnabled()) }
    var tick by remember { mutableStateOf(0) }
    val bands = remember { Eq.bands() }
    Column(Modifier.padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(back) { Text("← Lecteur") }
        Text("Égaliseur", style = MaterialTheme.typography.headlineSmall)
        if (bands.isEmpty()) {
            Text("Égaliseur indisponible sur cet appareil ou tant que rien n'est lu.")
            return
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Activer", Modifier.weight(1f))
            Switch(enabled, { enabled = it; Eq.setEnabled(it); store.setEqEnabled(it) })
        }
        val presets = remember { Eq.presets() }
        if (presets.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(presets) { i, name ->
                OutlinedButton({ Eq.usePreset(i.toShort()); store.setEqLevels(Eq.snapshot()); tick++ }) { Text(name) }
            }
        }
        tick.let { }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            bands.forEach { (b, lo, hi) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
                    Text("${Eq.level(b) / 100}dB", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = Eq.level(b).toFloat(),
                        onValueChange = { v -> Eq.setLevel(b, v.toInt().toShort()); store.setEqLevels(Eq.snapshot()); tick++ },
                        valueRange = lo.toFloat()..hi.toFloat(),
                        modifier = Modifier.graphicsLayer { rotationZ = 270f }.width(140.dp))
                    Text("${Eq.freq(b)}Hz", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
