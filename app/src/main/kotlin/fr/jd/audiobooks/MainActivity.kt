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
    }
    // Pas de scan automatique à l'ouverture : la liste vient uniquement du cache. Un scan ne se
    // déclenche que sur une action explicite (bouton "Dossier" la première fois, ou "Rescan").

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            store.root = u.toString()
            books = emptyList()
            scope.launch { rescan() }
        }
    }
    // Import de statistics.xml depuis Smart AudioBook Player (les positions position_sabp.dat, elles,
    // sont reprises automatiquement au scan puisqu'elles vivent directement dans chaque dossier de livre).
    val statsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val (matched, total) = withContext(Dispatchers.IO) { SabpImport.importStatistics(ctx, store, u, books) }
            importMsg = "$matched livre(s) sur $total importé(s) depuis statistics.xml"
        }
    }
    fun open(bk: Book) {
        cur = bk
        scope.launch {
            val art = Covers.get(ctx, bk)?.let { Covers.jpeg(it) }
            while (PlaybackService.player == null) delay(50)
            val p = PlaybackService.player!!
            val s = store.load(bk.path)
            p.setMediaItems(bk.uris.mapIndexed { i, u ->
                MediaItem.Builder().setUri(u).setMediaMetadata(
                    MediaMetadata.Builder().setTitle(bk.names[i]).setArtist(bk.name)
                        .setExtras(Bundle().apply { putString("path", bk.path) }).apply {
                        art?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
                    }.build()).build()
            }, s?.index ?: 0, s?.pos ?: 0)
            p.setPlaybackSpeed(s?.speed ?: 1f)
            p.prepare(); p.play()
        }
    }
    val b = cur
    when {
        showEq -> EqScreen(store) { showEq = false }
        b != null -> PlayerScreen(b, store, { showEq = true }) { PlaybackService.player?.pause(); cur = null }
        showStats -> StatsScreen(store) { showStats = false }
        else -> Column {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text("JD Audiobook Reader", Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), style = MaterialTheme.typography.titleLarge)
            }
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton({ showStats = true }) { Text("Stats") }
                TextButton({ statsPicker.launch(arrayOf("text/xml", "application/xml", "*/*")) }) { Text("Importer") }
                Button({ picker.launch(null) }) { Text("Dossier") }
                TextButton({ scope.launch { rescan() } }, enabled = scanProgress == null) { Text("Rescan") }
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
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, style = MaterialTheme.typography.labelLarge) })
                    }
                }
            }
            LazyColumn {
                items(shown, key = { it.path }) { bk ->
                    val s = store.load(bk.path)
                    val finished = store.finished(bk.path)
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
                                },
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
    val idx = PlaybackService.player?.currentMediaItemIndex ?: 0
    LaunchedEffect(idx) { chaps = withContext(Dispatchers.IO) { Chapters.read(ctx, bk.uris[idx]) } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500); tick++
            PlaybackService.player?.let { store.save(bk.path, it.currentMediaItemIndex, it.currentPosition, it.playbackParameters.speed) }
        }
    }
    tick.let { }
    val p = PlaybackService.player ?: return
    val playing = p.playWhenReady
    val left = (Sleep.endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    val ci = chaps.indexOfLast { it.startMs <= p.currentPosition }.coerceAtLeast(0)
    Column(Modifier.padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(back) { Text("← Bibliothèque") }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Cover(bk, 160.dp) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(bk.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton({ finished = !finished; store.setFinished(bk.path, finished) }) {
                Text(if (finished) "✓ Lu" else "Marquer comme lu")
            }
        }
        Text(bk.names.getOrElse(p.currentMediaItemIndex) { "" } + "  (${p.currentMediaItemIndex + 1}/${bk.uris.size})")
        if (chaps.isNotEmpty()) Text("Chapitre ${ci + 1}/${chaps.size} · ${chaps[ci].title}")
        val dur = p.duration.coerceAtLeast(1)
        Slider(p.currentPosition.toFloat() / dur, { p.seekTo((it * dur).toLong()) })
        Row { Text(fmt(p.currentPosition), Modifier.weight(1f)); Text(fmt(dur)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton({
                if (chaps.isEmpty()) p.seekToPreviousMediaItem()
                else p.seekTo(if (p.currentPosition - chaps[ci].startMs > 3000) chaps[ci].startMs else chaps.getOrNull(ci - 1)?.startMs ?: 0)
            }) { Text("⏮") }
            OutlinedButton({ p.seekBack() }) { Text("-30") }
            Button({ if (playing) p.pause() else p.play() }) { Text(if (playing) "Pause" else "Lire") }
            OutlinedButton({ p.seekForward() }) { Text("+30") }
            OutlinedButton({
                val n = chaps.getOrNull(ci + 1)
                if (n != null) p.seekTo(n.startMs) else p.seekToNextMediaItem()
            }) { Text("⏭") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton({ speedMenu = true }) { Text("×${p.playbackParameters.speed}") }
                DropdownMenu(speedMenu, { speedMenu = false }) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { s ->
                        DropdownMenuItem({ Text("×$s") }, { p.setPlaybackSpeed(s); speedMenu = false })
                    }
                }
            }
            Box {
                OutlinedButton({ sleepMenu = true }) { Text(if (left > 0) "Sommeil ${fmt(left)}" else "Sommeil") }
                DropdownMenu(sleepMenu, { sleepMenu = false }) {
                    listOf(0, 10, 15, 30, 45, 60, 90).forEach { m ->
                        DropdownMenuItem({ Text(if (m == 0) "Désactivé" else "$m min") }, { Sleep.set(m); sleepMenu = false })
                    }
                }
            }
            Text("Silences"); Switch(skipSilence, { skipSilence = it; p.skipSilenceEnabled = it })
            TextButton(openEq) { Text("Égaliseur") }
        }
        Button({
            marks = marks + Mark(p.currentMediaItemIndex, p.currentPosition, "${bk.names[p.currentMediaItemIndex]} ${fmt(p.currentPosition)}")
            store.putMarks(bk.path, marks)
        }) { Text("+ Signet") }
        LazyColumn(Modifier.weight(1f)) {
            if (chaps.isNotEmpty()) {
                item { Text("Chapitres", style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(chaps) { i, c ->
                    Text(
                        "${fmt(c.startMs)}  ${c.title}",
                        Modifier.fillMaxWidth().clickable { p.seekTo(c.startMs) }.padding(vertical = 6.dp),
                        color = if (i == ci) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
            item { Text("Signets", style = MaterialTheme.typography.titleMedium) }
            items(marks) { m ->
                Row(Modifier.fillMaxWidth().clickable { p.seekTo(m.index, m.pos) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, Modifier.weight(1f))
                    TextButton({ marks = marks - m; store.putMarks(bk.path, marks) }) { Text("✕") }
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
