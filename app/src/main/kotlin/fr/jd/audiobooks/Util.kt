package fr.jd.audiobooks

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val TAG = "JDAudiobook"

/** Trace une erreur absorbée (avant, beaucoup de « catch » vides rendaient les pannes invisibles). */
internal fun logw(what: String, e: Throwable? = null) { Log.w(TAG, what, e) }

/** Vitesse de lecture utilisable (0,1 à 5) ; toute autre valeur (0, négative, NaN, énorme) donne 1. */
fun safeSpeed(s: Float): Float = if (s.isNaN() || s !in 0.1f..5f) 1f else s

/**
 * Lit « index|position|vitesse|horodatage » sans jamais lever d'exception : une valeur corrompue donne null
 * (ou une vitesse de 1), jamais un plantage à l'ouverture du livre.
 */
fun parseSaved(raw: String?): Saved? {
    if (raw == null) return null
    val parts = raw.split("|")
    val index = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val pos = parts.getOrNull(1)?.toLongOrNull() ?: return null
    val speed = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
    return Saved(index.coerceAtLeast(0), pos.coerceAtLeast(0L), safeSpeed(speed), parts.getOrNull(3)?.toLongOrNull() ?: 0L)
}

/** « 20240315 » -> « 20240301 » : sert à regrouper les vieilles statistiques par mois. */
fun monthStart(day: String): String = if (day.length == 8) day.substring(0, 6) + "01" else day

/** Lit tout le flux, mais renvoie null si la taille dépasse [max] octets. */
fun InputStream.readCapped(max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > max) return null
    }
    return out.toByteArray()
}

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return c as? LifecycleOwner
}

/** L'activité qui affiche l'écran (son cycle de vie dit si l'appli est visible), ou null si introuvable. */
@Composable
fun rememberLifecycleOwner(): LifecycleOwner? {
    val ctx = LocalContext.current
    return remember(ctx) { ctx.findLifecycleOwner() }
}

/** Vrai tant que l'appli est visible ; en cas de doute (pas d'activité trouvée), vrai : on rafraîchit comme avant. */
fun LifecycleOwner?.isVisible(): Boolean = this == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

/**
 * Compteur qui avance toutes les [periodMs] ms pour rafraîchir l'affichage (position de lecture), mais seulement
 * tant que l'appli est visible : en arrière-plan, plus aucune recomposition inutile.
 */
@Composable
fun rememberTick(periodMs: Long = 500): Int {
    val owner = rememberLifecycleOwner()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(owner) {
        while (true) {
            delay(periodMs)
            if (owner.isVisible()) tick++
        }
    }
    return tick
}
