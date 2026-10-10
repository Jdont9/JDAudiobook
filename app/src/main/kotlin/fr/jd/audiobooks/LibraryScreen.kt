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

/** Écran bibliothèque : barre d'actions (engrenage), onglets, recherche, liste des livres, mini-lecteur et boîtes de dialogue.
 *  L'état partagé (livres, recherche, tri, navigation) reste dans App ; ici seulement l'état purement visuel. */
@Composable
fun LibraryScreen(
    store: Store, books: List<Book>, scanProgress: ScanProgress?, cacheLoaded: Boolean, importMsg: String?, curPath: String?,
    query: String, onQuery: (String) -> Unit, sortRecent: Boolean, onToggleSort: () -> Unit,
    onShow: (Book) -> Unit, onShowStats: () -> Unit, onImportStats: () -> Unit, onPickFolder: () -> Unit, onRefresh: () -> Unit,
    onCloseMini: () -> Unit, onCoverFound: (String, String?) -> Unit
) {
    val ctx = LocalContext.current
    var showSkip by remember { mutableStateOf(false) }
    var showCovers by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    Column {
        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
                // Menu masqué par défaut : il s'affiche à la demande avec l'engrenage.
                Box {
                    IconButton({ menuOpen = true }) { Icon(JdIcons.Settings, contentDescription = stringResource(R.string.settings_menu)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.statistics)) }, leadingIcon = { Icon(JdIcons.BarChart, contentDescription = null) },
                            onClick = { menuOpen = false; onShowStats() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.find_missing_covers)) }, leadingIcon = { Icon(JdIcons.Image, contentDescription = null) },
                            enabled = books.isNotEmpty(), onClick = { menuOpen = false; showCovers = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.import_stats)) }, leadingIcon = { Icon(JdIcons.Download, contentDescription = null) },
                            onClick = { menuOpen = false; onImportStats() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.skip_duration_item, Skip.seconds)) }, leadingIcon = { Icon(JdIcons.Replay, contentDescription = null) },
                            onClick = { menuOpen = false; showSkip = true }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(if (sortRecent) R.string.sort_to_library else R.string.sort_to_recent)) },
                            leadingIcon = { Icon(JdIcons.ArrowDropDown, contentDescription = null) },
                            onClick = { menuOpen = false; onToggleSort() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.choose_folder)) }, leadingIcon = { Icon(JdIcons.Folder, contentDescription = null) },
                            onClick = { menuOpen = false; onPickFolder() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.refresh_library)) }, leadingIcon = { Icon(JdIcons.Refresh, contentDescription = null) },
                            enabled = scanProgress == null, onClick = { menuOpen = false; onRefresh() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.about)) }, leadingIcon = { Icon(JdIcons.Info, contentDescription = null) },
                            onClick = { menuOpen = false; showAbout = true }
                        )
                    }
                }
            }
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
        if (books.isEmpty() && scanProgress == null && store.root != null && cacheLoaded) {
            Text(stringResource(R.string.no_books_cached), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if (store.root == null) {
            Text(stringResource(R.string.pick_folder_hint), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }

        val tabs = listOf(stringResource(R.string.tab_all), stringResource(R.string.tab_new), stringResource(R.string.tab_in_progress), stringResource(R.string.tab_finished))
        var tab by remember { mutableStateOf(0) }
        val q = fold(query.trim())
        // Filtrage, recherche et tri (qui lisent les préférences livre par livre) : recalculés seulement quand
        // quelque chose change, et au retour du lecteur (showPlayer), pas à chaque recomposition.
        val shown = remember(books, tab, q, sortRecent, curPath) {
            val byTab = when (tab) {
                1 -> books.filter { !store.hasSaved(it.path) && !store.finished(it.path) }
                2 -> books.filter { store.hasSaved(it.path) && !store.finished(it.path) }
                3 -> books.filter { store.finished(it.path) }
                else -> books
            }
            val found = if (q.isEmpty()) byTab else byTab.filter { fold(it.path).contains(q) }
            if (sortRecent) {
                val updated = found.associate { it.path to (store.load(it.path)?.updated ?: 0L) }
                found.sortedByDescending { updated[it.path] ?: 0L }
            } else found
        }
        if (books.isNotEmpty()) {
            TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, style = MaterialTheme.typography.labelSmall, maxLines = 1) })
                }
            }
        }
        if (books.size > 6) OutlinedTextField(
            query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), singleLine = true,
            placeholder = { Text(stringResource(R.string.search_hint)) }
        )
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.path }) { bk ->
                val s = store.load(bk.path)
                val finished = store.finished(bk.path)
                val total = remember(bk.path) { store.durations(bk).takeIf { d -> d.all { it > 0 } }?.sum() }
                Row(
                    Modifier.fillMaxWidth().clickable { onShow(bk) }.padding(horizontal = 12.dp, vertical = 10.dp),
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
        MiniPlayer(books, onOpen = onShow, onClose = onCloseMini)
        if (showCovers) MissingCoversDialog(books, store, onCover = onCoverFound, onDismiss = { showCovers = false })
        if (showAbout) AboutDialog { showAbout = false }
        if (showSkip) SkipDialog(onPick = { Skip.seconds = it; store.setSkipSeconds(it); showSkip = false }, onDismiss = { showSkip = false })
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

/** Mini-lecteur affiché en bas de la bibliothèque dès qu'un livre est chargé dans le lecteur. */
@Composable
fun MiniPlayer(books: List<Book>, onOpen: (Book) -> Unit, onClose: () -> Unit) {
    rememberTick().let { }
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
                IconButton({ p.skipBack() }) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(JdIcons.Replay, contentDescription = stringResource(R.string.back_n, Skip.seconds), Modifier.size(28.dp))
                        Text("${Skip.seconds}", fontSize = 8.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
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
