package fr.jd.audiobooks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Calculs de cadrage de la pochette (purs : testables sans Android). */
internal object CoverCrop {
    /** Décalage de l'image dans la fenêtre carrée de côté [side], ramené pour que l'image la recouvre toujours. */
    fun clampOffset(o: Float, side: Float, extent: Float): Float =
        if (extent <= side) (side - extent) / 2f else o.coerceIn(side - extent, 0f)

    /** Carré visible, en pixels de l'image d'origine : [gauche, haut, côté]. [scale] = pixels écran par pixel image. */
    fun srcSquare(ox: Float, oy: Float, scale: Float, side: Float, w: Int, h: Int): IntArray {
        val s = (side / scale).coerceAtMost(minOf(w, h).toFloat())
        val si = s.roundToInt().coerceAtLeast(1)
        val l = (-ox / scale).coerceIn(0f, (w - si).toFloat()).roundToInt().coerceAtMost(w - si)
        val t = (-oy / scale).coerceIn(0f, (h - si).toFloat()).roundToInt().coerceAtMost(h - si)
        return intArrayOf(l, t, si)
    }
}

/**
 * Changer la pochette d'un livre : 1) coller un lien (image directe, ou page web qui déclare son image),
 * 2) cadrer l'image dans un carré (glisser pour déplacer, pincer pour zoomer), 3) enregistrer.
 * [onSaved] reçoit l'URI du cover.jpg écrit dans le dossier du livre, ou null s'il n'a été gardé que dans l'appli.
 */
@Composable
fun CoverEditDialog(bk: Book, store: Store, onSaved: (String?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }

    val b = bmp
    if (b != null) { CoverCropDialog(bk, store, b, onSaved, onDismiss); return }
    run {
        AlertDialog(
            onDismissRequest = { if (!busy) onDismiss() },
            title = { Text(ctx.getString(R.string.change_cover)) },
            text = {
                Column {
                    Text(ctx.getString(R.string.change_cover_help, bk.name), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        link, { link = it; err = null }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                        isError = err != null, supportingText = err?.let { { Text(it) } }
                    )
                    TextButton({ clipboard.getText()?.text?.let { link = it.trim(); err = null } }, enabled = !busy) {
                        Text(ctx.getString(R.string.paste))
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && link.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            CoverFetch.fromUrl(link)?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) }
                        }
                        if (r == null) err = ctx.getString(R.string.no_image_on_link) else bmp = r
                        busy = false
                    }
                }) { Text(ctx.getString(R.string.next)) }
            },
            dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(ctx.getString(R.string.cancel)) } }
        )
    }
}

/** Étape 2 : cadrage. scale = pixels écran par pixel image ; (ox, oy) = position du coin haut-gauche de l'image. */
@Composable
private fun CoverCropDialog(bk: Book, store: Store, b: Bitmap, onSaved: (String?) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val w = b.width
    val h = b.height
    val img = remember(b) { b.asImageBitmap() }
    var side by remember { mutableStateOf(0f) }
    var minScale by remember { mutableStateOf(1f) }
    var scale by remember { mutableStateOf(1f) }
    var ox by remember { mutableStateOf(0f) }
    var oy by remember { mutableStateOf(0f) }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(side) {
        if (side > 0f && !ready) {
            minScale = maxOf(side / w, side / h)
            scale = minScale
            ox = (side - w * scale) / 2f
            oy = (side - h * scale) / 2f
            ready = true
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(ctx.getString(R.string.change_cover)) },
        text = {
            Column {
                Canvas(
                    Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().background(Color.Black)
                        .onSizeChanged { side = it.width.toFloat() }
                        .pointerInput(ready) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                if (ready && !saving) {
                                    val ns = (scale * zoom).coerceIn(minScale, minScale * 8f)
                                    val k = ns / scale
                                    val nx = centroid.x - (centroid.x - ox) * k + pan.x
                                    val ny = centroid.y - (centroid.y - oy) * k + pan.y
                                    scale = ns
                                    ox = CoverCrop.clampOffset(nx, side, w * ns)
                                    oy = CoverCrop.clampOffset(ny, side, h * ns)
                                }
                            }
                        }
                ) {
                    if (ready) drawImage(
                        img, srcSize = IntSize(w, h),
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize((w * scale).roundToInt(), (h * scale).roundToInt()),
                        filterQuality = FilterQuality.Medium
                    )
                }
                Text(ctx.getString(R.string.crop_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = ready && !saving, onClick = {
                saving = true
                val r = CoverCrop.srcSquare(ox, oy, scale, side, w, h)
                scope.launch {
                    val uri = withContext(Dispatchers.IO) {
                        var c = Bitmap.createBitmap(b, r[0], r[1], r[2], r[2])
                        val out = minOf(r[2], 1024)
                        if (out != r[2]) c = Bitmap.createScaledBitmap(c, out, out, true)
                        val jpeg = ByteArrayOutputStream().also { c.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
                        store.clearCoverTried(bk.path)
                        CoverFetch.save(ctx, store, bk, jpeg)
                    }
                    // Régénère la petite copie servie à la notification / Android Auto (supprimée par save()).
                    Covers.artUri(ctx, if (uri != null) bk.copy(cover = uri) else bk)
                    onSaved(uri)
                }
            }) { Text(ctx.getString(R.string.save)) }
        },
        dismissButton = { TextButton(onDismiss, enabled = !saving) { Text(ctx.getString(R.string.cancel)) } }
    )
}
