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

@Composable
fun PlayerScreen(bk: Book, store: Store, onCoverChanged: (String?) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var boost by remember { mutableStateOf(store.boost(bk.path)) }
    var boostMenu by remember { mutableStateOf(false) }
    var chaps by remember { mutableStateOf(listOf<Chap>()) }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    var skipSilence by remember { mutableStateOf(PlaybackService.player?.skipSilenceEnabled ?: false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var showBookmarks by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(store.finished(bk.path)) }
    var durs by remember { mutableStateOf(store.durations(bk)) }
    var probing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var probeFailed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var showCoverEdit by remember { mutableStateOf(false) }
    // "loaded" : le lecteur contient bien la playlist de CE livre. Tant que ce n'est pas le cas (ouverture en
    // cours, service en train de démarrer, ancien livre encore chargé), on affiche un écran de chargement
    // au lieu d'un écran vide, et on n'écrit rien dans les positions sauvegardées.
    val loaded = isLoaded(PlaybackService.player, bk)
    val idx = if (loaded) PlaybackService.player?.currentMediaItemIndex ?: 0 else 0
    LaunchedEffect(loaded, idx) {
        // Les chapitres n'existent que dans les conteneurs MP4 (.m4b/.m4a) et les MP3 à balise ID3 : inutile d'ouvrir les autres.
        val e = bk.names.getOrNull(idx)?.substringAfterLast('.', "")?.lowercase()
        chaps = if (loaded && e in setOf("m4b", "m4a", "mp4", "mp3")) withContext(Dispatchers.IO) {
            // Un chapitre sans titre reçoit « Chapitre N » dans la langue de l'appli.
            Chapters.read(ctx, bk.uris[idx]).mapIndexed { i, c -> if (c.title.isBlank()) c.copy(title = ctx.getString(R.string.chapter_n, i + 1)) else c }
        } else emptyList()
    }
    // Cette boucle ne fait que rafraîchir l'affichage : la progression est sauvegardée par PlaybackService.
    val owner = rememberLifecycleOwner()
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (!owner.isVisible()) continue // appli en arrière-plan : on ne rafraîchit rien
            tick++
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
            IconButton({ showCoverEdit = true }) {
                Icon(JdIcons.Image, contentDescription = stringResource(R.string.change_cover), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ showBookmarks = true }) {
                Icon(JdIcons.Bookmark, contentDescription = stringResource(R.string.bookmarks), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
        if (showCoverEdit) CoverEditDialog(bk, store, onSaved = { uri -> showCoverEdit = false; onCoverChanged(uri) }, onDismiss = { showCoverEdit = false })
        // La pochette s'adapte à la place disponible. Les chapitres ne sont plus listés ici : ils sont dans le menu
        // du bas (la ligne du fichier, avec la flèche), à côté de la liste des fichiers.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val coverSize = minOf(maxWidth, maxHeight - 130.dp).coerceIn(120.dp, 400.dp)
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(bk, coverSize)
                    Spacer(Modifier.height(8.dp))
                    Text(bk.name, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
            if (showBookmarks) BookmarksDialog(bk, store, p) { showBookmarks = false }
            if (showFiles) FilePickerDialog(
                bk, durs, fi, store, chaps = chaps, chapIdx = ci,
                onPick = { p.seekTo(it, 0); showFiles = false },
                onPickChap = { p.seekTo(it); showFiles = false },
                onDismiss = { showFiles = false }
            )
            if (chaps.isNotEmpty()) Text(stringResource(R.string.chapter_of, ci + 1, chaps.size, chaps[ci].title), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            val dur = p.duration.coerceAtLeast(1)
            // Pendant le glissement on ne déplace que le curseur ; la lecture saute une seule fois au relâchement.
            Slider(
                drag ?: (p.currentPosition.toFloat() / dur), { drag = it },
                onValueChangeFinished = { drag?.let { d -> p.seekTo((d * dur).toLong()) }; drag = null }
            )
            Row { Text(fmt(drag?.let { (it * dur).toLong() } ?: p.currentPosition), Modifier.weight(1f)); Text(fmt(dur)) }
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
                IconButton({ p.skipBack() }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(JdIcons.Replay, contentDescription = stringResource(R.string.back_n, Skip.seconds), Modifier.size(34.dp))
                        Text("${Skip.seconds}", fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                FilledIconButton({ if (playing) p.pause() else p.play() }, Modifier.size(64.dp)) {
                    Icon(if (playing) JdIcons.Pause else JdIcons.Play, contentDescription = if (playing) stringResource(R.string.pause) else stringResource(R.string.play), Modifier.size(36.dp))
                }
                IconButton({ p.skipForward() }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(JdIcons.Replay, contentDescription = stringResource(R.string.forward_n, Skip.seconds), Modifier.size(34.dp).graphicsLayer { scaleX = -1f })
                        Text("${Skip.seconds}", fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 3.dp))
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
                        else if (Sleep.untilFileEnd) Text(stringResource(R.string.sleep_file_short), Modifier.padding(start = 4.dp), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                    if (left > 0 || Sleep.untilFileEnd) FilledTonalButton({ sleepMenu = true }, Modifier.fillMaxWidth(), contentPadding = btnPad) { sleepBtn() }
                    else OutlinedButton({ sleepMenu = true }, Modifier.fillMaxWidth(), contentPadding = btnPad) { sleepBtn() }
                    DropdownMenu(sleepMenu, { sleepMenu = false }) {
                        listOf(0, 10, 15, 30, 45, 60, 90).forEach { m ->
                            DropdownMenuItem({ Text(if (m == 0) stringResource(R.string.off) else stringResource(R.string.minutes_short, m)) }, { Sleep.set(m); sleepMenu = false })
                        }
                        DropdownMenuItem({ Text(stringResource(R.string.sleep_end_of_file)) }, { Sleep.fileEnd(); sleepMenu = false })
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
