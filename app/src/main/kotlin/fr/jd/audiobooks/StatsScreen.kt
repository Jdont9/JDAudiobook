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
fun StatsScreen(store: Store, back: () -> Unit) {
    val st = remember { store.stats() }
    val f = SimpleDateFormat("yyyyMMdd", Locale.US)
    val today = f.format(Date())
    val days = (6 downTo 0).map { d -> f.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -d) }.time) }
    val perDay = days.map { d -> st.filter { it.day == d }.sumOf { it.wall } }
    val max = perDay.max().coerceAtLeast(1)
    val wall = st.sumOf { it.wall }
    val content = st.sumOf { it.content }
    val ctx = LocalContext.current
    // Jours avec de l'écoute : séries et calendrier de chaleur (les statistiques importées de Smart Player comptent au 1er du mois).
    val perDayAll = remember(st) { st.filter { it.wall > 0 && it.day.length == 8 }.groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.wall } } }
    val listened = remember(perDayAll) { perDayAll.keys }
    val now = remember { Date() }
    val streak = remember(listened) { StatsMath.currentStreak(listened, now) }
    val longest = remember(listened) { StatsMath.longestStreak(listened) }
    val weeks = 10
    // Calendrier de chaleur : 10 semaines (colonnes) × 7 jours (lignes, lundi en haut), la dernière colonne finit aujourd'hui.
    val cal = remember(perDayAll) {
        val todayCal = Calendar.getInstance()
        val dow = (todayCal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // lundi = 0
        val startOffset = -((weeks - 1) * 7 + dow)
        List(weeks) { w -> List(7) { d -> StatsMath.dayOffset(now, startOffset + w * 7 + d) } }
    }
    val maxDay = (perDayAll.filterKeys { k -> cal.any { it.contains(k) } }.values.maxOrNull() ?: 0L)
    Column(Modifier.padding(16.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BackButton(back)
        Text(stringResource(R.string.statistics), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.stats_today, fmt(st.filter { it.day == today }.sumOf { it.wall })))
        Text(stringResource(R.string.stats_total, fmt(wall)))
        Text(stringResource(R.string.stats_content, fmt(content)))
        Text(stringResource(R.string.stats_saved, fmt((content - wall).coerceAtLeast(0))))
        Text(stringResource(R.string.stats_7days), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            perDay.forEachIndexed { i, v ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height((90f * v / max).dp).background(MaterialTheme.colorScheme.primary))
                    Text(days[i].takeLast(2))
                }
            }
        }
        Text(stringResource(R.string.stats_streak, streak, longest))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            cal.forEach { week ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    week.forEach { day ->
                        val lvl = if (day > today) -1 else StatsMath.level(perDayAll[day] ?: 0L, maxDay)
                        Box(
                            Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).background(
                                when (lvl) {
                                    -1 -> androidx.compose.ui.graphics.Color.Transparent
                                    0 -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f + 0.25f * (lvl - 1))
                                }
                            )
                        )
                    }
                }
            }
        }
        TextButton({
            val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_TEXT, StatsMath.csv(st))
                .putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.app_name) + " - " + ctx.getString(R.string.statistics))
            try { ctx.startActivity(Intent.createChooser(send, null)) } catch (e: Exception) { logw("partage CSV", e) }
        }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.stats_export_csv)) }
        Text(stringResource(R.string.stats_by_book), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f)) {
            items(st.groupBy { it.book }.map { (b, l) -> b to l.sumOf { it.wall } }.sortedByDescending { it.second }) { (b, t) ->
                ListItem(headlineContent = { Text(b.substringAfterLast('/')) }, trailingContent = { Text(fmt(t)) })
            }
        }
    }
}
