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
        Text(stringResource(R.string.stats_by_book), style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(st.groupBy { it.book }.map { (b, l) -> b to l.sumOf { it.wall } }.sortedByDescending { it.second }) { (b, t) ->
                ListItem(headlineContent = { Text(b.substringAfterLast('/')) }, trailingContent = { Text(fmt(t)) })
            }
        }
    }
}
