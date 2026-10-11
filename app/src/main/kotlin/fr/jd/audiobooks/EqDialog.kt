package fr.jd.audiobooks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Choix de l'égaliseur du livre : un preset, ou la courbe personnalisée (10 bandes). Le son change dès le toucher
 * (on peut comparer sans fermer la fenêtre). onGains(gains, commit) : commit = false pendant le glissement
 * (on applique seulement), true à la fin (on enregistre).
 */
@Composable
fun EqDialog(
    current: String, gains: List<Double>,
    onPick: (String) -> Unit, onGains: (List<Double>, Boolean) -> Unit, onDismiss: () -> Unit
) {
    var sel by remember { mutableStateOf(current) }
    var g by remember { mutableStateOf(gains) }
    val custom = sel == Eq.CUSTOM
    val setup = if (custom) EqCustom.setup(g) else EqPresets.find(sel)?.setup ?: EqSetup.FLAT
    val pick = { id: String -> sel = id; onPick(id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.equalizer)) },
        text = {
            Column {
                EqCurve(setup, Modifier.fillMaxWidth().height(80.dp))
                Text(
                    when {
                        custom -> stringResource(R.string.eq_custom_hint)
                        sel.isEmpty() -> stringResource(R.string.eq_hint)
                        else -> stringResource(R.string.eq_preamp, String.format("%.1f", setup.preamp))
                    },
                    Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Les barres sont HORS de la liste défilante : sinon le défilement volerait le glissement vertical.
                if (custom) EqBands(g) { n, commit -> g = n; onGains(n, commit) }
                if (custom) Text(
                    stringResource(R.string.eq_preamp, String.format("%.1f", setup.preamp)),
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(Modifier.heightIn(max = if (custom) 150.dp else 300.dp)) {
                    item { EqRow(stringResource(R.string.off), sel.isEmpty()) { pick("") } }
                    item { EqRow(stringResource(R.string.eq_custom), custom) { pick(Eq.CUSTOM) } }
                    items(EqPresets.all) { pr -> EqRow(stringResource(pr.nameRes), sel == pr.id) { pick(pr.id) } }
                }
            }
        },
        dismissButton = if (custom) {
            { TextButton({ val z = EqCustom.flat(); g = z; onGains(z, true) }) { Text(stringResource(R.string.eq_reset)) } }
        } else null,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } }
    )
}

@Composable
private fun EqRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Courbe de réponse, de 20 Hz à 20 kHz (échelle logarithmique), ±12 dB ; le préampli n'y figure pas. */
@Composable
private fun EqCurve(setup: EqSetup, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val n = 120
    val pts = remember(setup) { List(n + 1) { i -> EqMath.responseDb(setup, 20.0 * 1000.0.pow(i / n.toDouble())) } }
    Canvas(modifier) {
        val range = EqCustom.RANGE
        fun y(db: Double) = (size.height / 2f) * (1f - (db.coerceIn(-range, range) / range).toFloat())
        drawLine(grid, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.dp.toPx())
        val path = Path()
        pts.forEachIndexed { i, db ->
            val x = size.width * i / n
            if (i == 0) path.moveTo(x, y(db)) else path.lineTo(x, y(db))
        }
        drawPath(path, line, style = Stroke(2.dp.toPx()))
    }
}

/** 10 barres verticales (une par bande) : glisser pour régler, double toucher pour remettre à zéro. */
@Composable
private fun EqBands(gains: List<Double>, onChange: (List<Double>, Boolean) -> Unit) {
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val ref = remember { arrayOf(gains) }
    ref[0] = gains
    var active by remember { mutableStateOf(-1) }
    fun set(i: Int, v: Double, commit: Boolean) {
        val n = ref[0].toMutableList(); n[i] = v; ref[0] = n; onChange(n, commit)
    }
    fun gainAt(y: Float, h: Int): Double =
        (((1f - 2f * y / h) * EqCustom.RANGE).coerceIn(-EqCustom.RANGE, EqCustom.RANGE) * 2).roundToInt() / 2.0
    Column {
        Text(
            if (active >= 0) "${EqCustom.labels[active]} Hz · " + String.format("%+.1f dB", gains[active]) else " ",
            style = MaterialTheme.typography.labelMedium
        )
        Row(Modifier.fillMaxWidth().height(110.dp)) {
            for (i in EqCustom.bands.indices) {
                Canvas(
                    Modifier.weight(1f).fillMaxHeight()
                        .pointerInput(i) {
                            detectTapGestures(
                                onDoubleTap = { active = i; set(i, 0.0, true) },
                                onTap = { active = i; set(i, gainAt(it.y, size.height), true) }
                            )
                        }
                        .pointerInput(i) {
                            detectDragGestures(
                                onDragStart = { active = i; set(i, gainAt(it.y, size.height), false) },
                                onDragEnd = { onChange(ref[0], true) },
                                onDragCancel = { onChange(ref[0], true) }
                            ) { change, _ -> change.consume(); set(i, gainAt(change.position.y, size.height), false) }
                        }
                ) {
                    val cx = size.width / 2f
                    val mid = size.height / 2f
                    val vy = mid * (1f - (gains[i] / EqCustom.RANGE).toFloat())
                    drawLine(track, Offset(cx, 0f), Offset(cx, size.height), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(track, Offset(cx - 5.dp.toPx(), mid), Offset(cx + 5.dp.toPx(), mid), 1.dp.toPx())
                    drawLine(bar, Offset(cx, mid), Offset(cx, vy), 6.dp.toPx(), StrokeCap.Round)
                    drawCircle(bar, 7.dp.toPx(), Offset(cx, vy))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            EqCustom.labels.forEach {
                Text(it, Modifier.weight(1f), fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}
