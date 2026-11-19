package com.chill.run.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.chill.run.core.RouteAnalysis
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
    val courses by remember { dao.observeCourses() }.collectAsState(initial = emptyList())
    val courseNames = remember(courses) { courses.associate { it.id to it.name } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("きろく", onBack)
        // 今月の合計と、続いた週
        val progress = rememberProgress()
        val ym = remember { java.time.YearMonth.now() }
        val month = runs.filter { java.time.YearMonth.from(Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault())) == ym }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Display("${ym.monthValue}月", 44, color = Palette.earth)
            Display("${"%.1f".format(month.sumOf { it.distanceM } / 1000)} KM", 44, color = Palette.earth, narrow = true)
        }
        FigureRow(
            listOf(
                Triple("今月の回数", "${month.size} 回", Palette.earth),
                Triple("今週", "${progress?.weekDays ?: 0}/${progress?.weekGoal ?: 2} 日", Palette.earth),
                Triple("れんぞく", "${progress?.streakWeeks ?: 0} 週", Palette.signal),
            ),
            valueSize = 26,
        )
        Gap(10)
        if (runs.isEmpty()) {
            Text("まだ記録がありません。", color = Palette.dim, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(runs, key = { it.id }) { run -> RunRow(run, courseNames[run.courseId]) { onOpen(run.id) } }
        }
    }
}

@Composable
private fun RunRow(run: RunEntity, courseName: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Palette.card).border(1.dp, Palette.line, RoundedCornerShape(4.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ロードは深い緑、トレイルはオレンジの印
        Box(Modifier.width(8.dp).height(84.dp).background(Palette.of(run.type)))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(typeLabel(run.type), color = Palette.of(run.type), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(formatDate(run.startTime), color = Palette.dim, fontSize = 14.sp)
                if (courseName != null) Text(courseName, color = Palette.dim, fontSize = 14.sp, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("${Fmt.km(run.distanceM)} km", color = Palette.text, fontSize = 28.sp, fontFamily = Type.narrow, fontWeight = FontWeight(800))
                Text(Fmt.clock(run.movingMs), color = Palette.text, fontSize = 15.sp)
                Text("${Fmt.pace(avgPace(run))}/km", color = Palette.text, fontSize = 15.sp)
            }
        }
        // 標高の小さなグラフ（山の形）と、登りの合計
        val profile = remember(run.elevProfile) { RouteAnalysis.decodeProfile(run.elevProfile) }
        Column(Modifier.padding(end = 12.dp), horizontalAlignment = Alignment.End) {
            Sparkline(profile, Palette.of(run.type), Modifier.width(72.dp).height(34.dp))
            Text("+${Math.round(run.gainM)}m", color = Palette.dim, fontSize = 13.sp)
        }
    }
}
