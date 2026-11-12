package com.chill.run.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.Fmt
import com.chill.run.data.AppDb
import com.chill.run.data.RunEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFmt = DateTimeFormatter.ofPattern("M月d日(E) H:mm", Locale.JAPAN)
fun formatDate(ms: Long): String = dateFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun avgPace(run: RunEntity): Double? =
    if (run.distanceM > 10) run.movingMs / 1000.0 / (run.distanceM / 1000.0) else null

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).runDao() }
    val runs by remember { dao.observeRuns() }.collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("記録の一覧", onBack)
        if (runs.isEmpty()) {
            Text("まだ記録がありません。", color = Palette.dim, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(runs, key = { it.id }) { run -> RunRow(run) { onOpen(run.id) } }
        }
    }
}

@Composable
private fun RunRow(run: RunEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.card).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ロードは海の青、トレイルは森の緑の印
        Box(Modifier.width(8.dp).height(84.dp).background(Palette.of(run.type)))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(typeLabel(run.type), color = Palette.of(run.type), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(formatDate(run.startTime), color = Palette.dim, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${Fmt.km(run.distanceM)} km", color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(Fmt.clock(run.movingMs), color = Palette.text, fontSize = 16.sp)
                Text("${Fmt.pace(avgPace(run))}/km", color = Palette.text, fontSize = 16.sp)
                if (run.gainM >= 1) Text("+${Math.round(run.gainM)}m", color = Palette.dim, fontSize = 16.sp)
            }
        }
    }
}
