package fr.jd.audiobooks

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

/** Vrai quand le lecteur contient bien la playlist de CE livre (et pas celle d'un livre précédent). */
fun isLoaded(p: Player?, bk: Book): Boolean =
    p != null && p.mediaItemCount == bk.uris.size &&
        p.currentMediaItem?.mediaMetadata?.extras?.getString("path") == bk.path

/** Comparaison "naturelle" : les nombres sont comparés par valeur (2 avant 10), pas lettre à lettre. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            var i2 = i; while (i2 < a.length && a[i2].isDigit()) i2++
            var j2 = j; while (j2 < b.length && b[j2].isDigit()) j2++
            val x = a.substring(i, i2).trimStart('0'); val y = b.substring(j, j2).trimStart('0')
            if (x.length != y.length) return x.length - y.length
            val c = x.compareTo(y); if (c != 0) return c
            i = i2; j = j2
        } else {
            if (a[i] != b[j]) return a[i].compareTo(b[j])
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/** Ordre des fichiers d'un livre : on compare les noms SANS extension (sinon « Complet.opus » passait après
 *  « Complet 2.opus » parce que « . » vient après l'espace), en ordre naturel (2 avant 10). */
fun fileOrder(a: String, b: String): Int {
    val c = naturalCompare(a.substringBeforeLast('.').lowercase(), b.substringBeforeLast('.').lowercase())
    return if (c != 0) c else naturalCompare(a.lowercase(), b.lowercase())
}

/** Retire le préfixe commun à tous les noms (sans jamais couper un mot ou un nombre) pour que la liste soit lisible. */
fun shortNames(names: List<String>): Pair<String, List<String>> {
    if (names.size < 2) return "" to names
    var cut = names[0].length
    for (s in names) {
        var k = 0; val lim = minOf(cut, s.length)
        while (k < lim && s[k] == names[0][k]) k++
        cut = k
    }
    while (cut > 0 && names[0][cut - 1].isLetterOrDigit()) cut--
    if (cut == 0 || names.any { it.length <= cut }) return "" to names
    return names[0].substring(0, cut) to names.map { it.substring(cut) }
}

@Composable
fun FilePickerDialog(bk: Book, durs: List<Long>, current: Int, store: Store, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var showDur by remember { mutableStateOf(store.flag("pick_dur", true)) }
    var showPos by remember { mutableStateOf(store.flag("pick_pos", false)) }
    val (prefix, shorts) = remember(bk.path) { shortNames(bk.names) }
    // Ordre alphabétique de la liste vs ordre numérique : si ça diffère, l'ordre de lecture est probablement faux.
    val orderOk = remember(bk.path) {
        bk.names == bk.names.sortedWith(Comparator { a, b -> fileOrder(a, b) })
    }
    // Position de départ de chaque fichier dans le livre (connue seulement si tous les fichiers précédents le sont).
    val starts = remember(durs) {
        var acc = 0L; var ok = true
        durs.map { d -> val r = if (ok) acc else -1L; if (d > 0) acc += d else ok = false; r }
    }
    val total = if (durs.all { it > 0 }) durs.sum() else -1L
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.open_file)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.n_files, bk.uris.size) + if (total > 0) " · ${fmt(total)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (prefix.isNotEmpty()) Text(
                    "… $prefix", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!orderOk) Text(
                    stringResource(R.string.order_warning),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp), state = state) {
                    itemsIndexed(shorts) { i, name ->
                        val cur = i == current
                        val col = if (cur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${i + 1}", Modifier.width(30.dp), style = MaterialTheme.typography.labelMedium, color = col)
                            Text(
                                name, Modifier.weight(1f), color = col,
                                fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (showPos) Text(
                                if (starts[i] >= 0) fmt(starts[i]) else "…",
                                Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = col
                            )
                            if (showDur) Text(
                                if (durs[i] > 0) fmt(durs[i]) else "…",
                                Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = col
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(showDur, { showDur = it; store.setFlag("pick_dur", it) })
                    Text(stringResource(R.string.duration), Modifier.clickable { showDur = !showDur; store.setFlag("pick_dur", showDur) })
                    Spacer(Modifier.width(12.dp))
                    Checkbox(showPos, { showPos = it; store.setFlag("pick_pos", it) })
                    Text(stringResource(R.string.position), Modifier.clickable { showPos = !showPos; store.setFlag("pick_pos", showPos) })
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
