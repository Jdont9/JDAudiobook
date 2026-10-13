package fr.jd.audiobooks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Vérification de mise à jour : uniquement à la demande (bouton de la fenêtre « À propos »), jamais en automatique. */
object Updater {
    const val REPO_URL = "https://github.com/Jdont9/JDAudiobook/releases"
    private const val API_URL = "https://api.github.com/repos/Jdont9/JDAudiobook/releases/latest"

    sealed class Result {
        class UpToDate(val current: String) : Result()
        class Available(val latest: String, val url: String) : Result()
        object NoRelease : Result()
        object Error : Result()
    }

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    /** Empreinte SHA-256 d'un certificat, en hexadécimal séparé par « : » (format de `apksigner verify --print-certs`). */
    internal fun fingerprint(cert: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it) }

    /** Empreinte du certificat qui a signé l'APK installé : à comparer avec celle publiée sur la page de la release,
     *  pour vérifier qu'un APK téléchargé n'a pas été modifié ou signé par quelqu'un d'autre. null si illisible. */
    fun signingFingerprint(ctx: Context): String? = try {
        val pm = ctx.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28)
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        else @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        sig?.let { fingerprint(it.toByteArray()) }
    } catch (e: Exception) { logw("empreinte du certificat illisible", e); null }

    /** Interroge GitHub (dernière release publiée) et compare à la version installée. À appeler hors du thread principal. */
    fun check(current: String): Result = try {
        val c = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "JDAudiobook/$current (Android)")
        }
        try {
            when (c.responseCode) {
                200 -> {
                    // La réponse de GitHub fait quelques Ko : au-delà de 1 Mo, quelque chose cloche.
                    val body = c.inputStream.use { it.readCapped(1_000_000) }
                    if (body == null) Result.Error
                    else {
                        val o = JSONObject(String(body, Charsets.UTF_8))
                        val tag = o.optString("tag_name")
                        if (tag.isEmpty()) Result.NoRelease
                        else if (isNewer(tag, current)) {
                            val url = o.optString("html_url").takeIf { it.startsWith("https://github.com/") } ?: REPO_URL
                            Result.Available(tag.removePrefix("v"), url)
                        } else Result.UpToDate(current)
                    }
                }
                404 -> Result.NoRelease
                else -> Result.Error
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { logw("vérification de mise à jour échouée", e); Result.Error }

    /** Compare deux versions « 1.2.3 » (un éventuel suffixe « -beta » est ignoré). */
    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = Regex("\\d+").findAll(v.substringBefore('-')).map { it.value.toInt() }.toList()
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember { Updater.currentVersion(ctx) }
    val cert = remember { Updater.signingFingerprint(ctx) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Updater.Result?>(null) }
    fun open(url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { logw("impossible d'ouvrir le lien", e) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ctx.getString(R.string.app_name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(ctx.getString(R.string.about_version, version), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(ctx.getString(R.string.about_license), style = MaterialTheme.typography.bodySmall)
                Text(ctx.getString(R.string.about_ai), style = MaterialTheme.typography.bodySmall)
                TextButton({ open(Updater.REPO_URL) }, contentPadding = PaddingValues(0.dp)) { Text(ctx.getString(R.string.about_releases)) }
                Spacer(Modifier.height(4.dp))
                if (checking) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(ctx.getString(R.string.checking), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                result?.let { r ->
                    when (r) {
                        is Updater.Result.UpToDate -> Text(ctx.getString(R.string.up_to_date, r.current), style = MaterialTheme.typography.bodyMedium)
                        is Updater.Result.Available -> {
                            Text(ctx.getString(R.string.update_available, r.latest), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            TextButton({ open(r.url) }, contentPadding = PaddingValues(0.dp)) { Text(ctx.getString(R.string.open_download_page)) }
                        }
                        Updater.Result.NoRelease -> Text(ctx.getString(R.string.update_none), style = MaterialTheme.typography.bodyMedium)
                        Updater.Result.Error -> Text(ctx.getString(R.string.update_error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (cert != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(ctx.getString(R.string.about_cert), style = MaterialTheme.typography.labelSmall)
                    SelectionContainer {
                        Text(cert, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(ctx.getString(R.string.about_manual_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = !checking, onClick = {
                checking = true; result = null
                scope.launch {
                    result = withContext(Dispatchers.IO) { Updater.check(version) }
                    checking = false
                }
            }) { Text(ctx.getString(R.string.check_updates)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(ctx.getString(R.string.close)) } }
    )
}
