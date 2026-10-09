package fr.jd.audiobooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import java.text.SimpleDateFormat
import java.util.*

/** Choix de la durée du saut avant/arrière. */
@Composable
fun SkipDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.skip_duration)) },
        text = {
            Column {
                Skip.choices.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = s == Skip.seconds, onClick = { onPick(s) })
                        Text(stringResource(R.string.seconds_short, s))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

/** Signets d'un livre : ajouter la position actuelle, revenir à un signet, en supprimer. */
@Composable
fun BookmarksDialog(bk: Book, store: Store, p: Player, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(store.bookmarks(bk.path)) }
    val sorted = list.sortedWith(compareBy<Bookmark>({ bk.names.indexOf(it.file) }, { it.pos }))
    val df = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks)) },
        text = {
            Column {
                TextButton({
                    val name = bk.names.getOrNull(p.currentMediaItemIndex)
                    if (name != null) {
                        store.addBookmark(bk.path, Bookmark(name, p.currentPosition, System.currentTimeMillis()))
                        list = store.bookmarks(bk.path)
                    }
                }) { Text(stringResource(R.string.add_bookmark)) }
                if (sorted.isEmpty()) Text(stringResource(R.string.no_bookmarks), style = MaterialTheme.typography.bodySmall)
                else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(sorted) { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val i = bk.names.indexOf(b.file)
                                if (i >= 0) { p.seekTo(i, b.pos); onDismiss() }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${b.file} · ${fmt(b.pos)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(df.format(Date(b.created)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton({ store.removeBookmark(bk.path, b); list = store.bookmarks(bk.path) }) { Text("✕") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
