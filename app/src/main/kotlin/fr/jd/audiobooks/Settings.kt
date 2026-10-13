package fr.jd.audiobooks

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Une ligne « libellé + interrupteur ». */
@Composable
private fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

/** Réglages de lecture et de sécurité : enchaînement des tomes, compression de la voix, couleurs, recul après la minuterie, sauvegarde chiffrée. */
@Composable
fun SettingsDialog(store: Store, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var autoNext by remember { mutableStateOf(store.flag("auto_next")) }
    var dyn by remember { mutableStateOf(store.flag("dyn")) }
    var dynColor by remember { mutableStateOf(store.flag("dynamic")) }
    var rewind by remember { mutableStateOf(store.sleepRewindMin()) }
    var backup by remember { mutableStateOf<Boolean?>(null) } // true = exporter, false = importer
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow(stringResource(R.string.set_auto_next), stringResource(R.string.set_auto_next_hint), autoNext) { autoNext = it; store.setFlag("auto_next", it) }
                SwitchRow(stringResource(R.string.set_dynamics), stringResource(R.string.set_dynamics_hint), dyn) {
                    dyn = it; store.setFlag("dyn", it); Dynamics.set(it)
                }
                if (Build.VERSION.SDK_INT >= 31) SwitchRow(stringResource(R.string.set_dynamic_color), stringResource(R.string.set_dynamic_color_hint), dynColor) {
                    dynColor = it; store.setFlag("dynamic", it)
                    (ctx as? Activity)?.recreate() // le thème est relu au redémarrage de l'écran
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.set_sleep_rewind), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.set_sleep_rewind_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 2, 5, 10, 15).forEach { m ->
                        FilterChip(
                            rewind == m, { rewind = m; store.setSleepRewindMin(m) },
                            { Text(if (m == 0) stringResource(R.string.off) else stringResource(R.string.minutes_short, m), maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.set_backup), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.set_backup_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ backup = true }) { Text(stringResource(R.string.backup_export), style = MaterialTheme.typography.labelMedium) }
                    OutlinedButton({ backup = false }) { Text(stringResource(R.string.backup_import), style = MaterialTheme.typography.labelMedium) }
                }
                message?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } }
    )
    backup?.let { export -> BackupDialog(store, export, onDone = { message = it; backup = null }, onDismiss = { backup = null }) }
}

/** Mot de passe puis export ou import d'une sauvegarde chiffrée (voir Backup). Le travail lourd (PBKDF2) se fait hors du thread principal. */
@Composable
fun BackupDialog(store: Store, export: Boolean, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Import : le fichier est choisi d'abord (puis le mot de passe) ; export : le mot de passe d'abord (puis l'emplacement).
    var picked by remember { mutableStateOf<Uri?>(null) }

    fun finish(msg: String) { busy = false; onDone(msg) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
        if (u == null) { onDismiss(); return@rememberLauncherForActivityResult }
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bytes = Backup.export(store.backupSnapshot(), pw.toCharArray())
                    ctx.contentResolver.openOutputStream(u, "wt")?.use { it.write(bytes) } != null
                } catch (e: Exception) { logw("export de la sauvegarde", e); false }
            }
            finish(ctx.getString(if (ok) R.string.backup_exported else R.string.backup_failed))
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) onDismiss() else picked = u
    }
    // Import : on ouvre d'abord le sélecteur de fichier.
    LaunchedEffect(Unit) { if (!export) opener.launch(arrayOf("*/*")) }

    val showPassword = export || picked != null
    if (!showPassword) return

    val tooShort = pw.length < Backup.MIN_PASSWORD
    val mismatch = export && pw != pw2
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (export) R.string.backup_export else R.string.backup_import)) },
        text = {
            Column {
                Text(stringResource(if (export) R.string.backup_export_help else R.string.backup_import_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    pw, { pw = it; error = null }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.backup_password)) }, visualTransformation = PasswordVisualTransformation(),
                    supportingText = if (export) ({ Text(stringResource(R.string.backup_password_hint, Backup.MIN_PASSWORD)) }) else null
                )
                if (export) OutlinedTextField(
                    pw2, { pw2 = it }, Modifier.fillMaxWidth().padding(top = 4.dp), singleLine = true, enabled = !busy,
                    label = { Text(stringResource(R.string.backup_password_again)) }, visualTransformation = PasswordVisualTransformation(),
                    isError = mismatch && pw2.isNotEmpty()
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                error?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && pw.isNotEmpty() && (!export || (!tooShort && !mismatch)), onClick = {
                if (export) saver.launch("JDAudiobook-sauvegarde.jdbackup")
                else {
                    val u = picked ?: return@TextButton
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val raw = ctx.contentResolver.openInputStream(u)?.use { it.readCapped(Backup.MAX_FILE_BYTES) }
                                raw?.let { Backup.import(it, pw.toCharArray()) }
                            } catch (e: Exception) { logw("lecture de la sauvegarde", e); null }
                        }
                        if (result == null) { busy = false; error = ctx.getString(R.string.backup_bad) }
                        else {
                            val n = withContext(Dispatchers.IO) { store.restore(result) }
                            finish(ctx.getString(R.string.backup_restored, n))
                        }
                    }
                }
            }) { Text(stringResource(if (export) R.string.backup_choose_place else R.string.backup_restore)) }
        },
        dismissButton = { TextButton({ onDismiss() }, enabled = !busy) { Text(stringResource(R.string.cancel)) } }
    )
}
