package fr.jd.audiobooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/** Saisie (ou modification) de la note d'un signet. */
@Composable
private fun NoteDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark_note_title)) },
        text = {
            OutlinedTextField(
                text, { text = it.take(BookmarkCodec.MAX_NOTE) }, Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text(stringResource(R.string.bookmark_note_hint)) }, maxLines = 3
            )
        },
        confirmButton = { TextButton({ onSave(text.trim()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** Signets d'un livre : ajouter la position actuelle (avec une note facultative), revenir à un signet, modifier sa note, en supprimer. */
@Composable
fun BookmarksDialog(bk: Book, store: Store, p: Player, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(store.bookmarks(bk.path)) }
    val sorted = list.sortedWith(compareBy<Bookmark>({ bk.names.indexOf(it.file) }, { it.pos }))
    val df = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    // Signet en cours de saisie de note : la position est celle du toucher sur « ajouter », pas celle de la validation.
    // Après chaque modification : relire la liste et écrire tout de suite position.jd.json (sinon les signets
    // n'y arriveraient qu'à la prochaine pause).
    fun changed() { list = store.bookmarks(bk.path); PlaybackService.saveNow() }
    var draft by remember { mutableStateOf<Bookmark?>(null) }
    var draftIsNew by remember { mutableStateOf(true) }
    draft?.let { d ->
        NoteDialog(d.note, onSave = { note ->
            if (draftIsNew) store.addBookmark(bk.path, d.copy(note = note)) else store.replaceBookmark(bk.path, d, d.copy(note = note))
            changed(); draft = null
        }, onDismiss = { draft = null })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmarks)) },
        text = {
            Column {
                TextButton({
                    val name = bk.names.getOrNull(p.currentMediaItemIndex)
                    if (name != null) { draftIsNew = true; draft = Bookmark(name, p.currentPosition, System.currentTimeMillis()) }
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
                                val where = "${b.file} · ${fmt(b.pos)}"
                                if (b.note.isEmpty()) {
                                    Text(where, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                    Text(df.format(Date(b.created)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text(b.note, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                    Text("$where · ${df.format(Date(b.created))}", style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton({ draftIsNew = false; draft = b }) { Text("✎") }
                            TextButton({ store.removeBookmark(bk.path, b); changed() }) { Text("✕") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } }
    )
}
