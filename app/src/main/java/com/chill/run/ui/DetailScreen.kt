package com.chill.run.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.Fmt
import com.chill.run.data.AppDb
import com.chill.run.data.PointEntity
import com.chill.run.data.RunEntity
import com.chill.run.data.SplitEntity
import com.chill.run.data.deleteRun
import com.chill.run.health.Health
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos

@Composable
fun DetailScreen(runId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).runDao() }
    val scope = rememberCoroutineScope()
    var run by remember { mutableStateOf<RunEntity?>(null) }
    var points by remember { mutableStateOf<List<PointEntity>>(emptyList()) }
    var splits by remember { mutableStateOf<List<SplitEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(runId, reload) {
        withContext(Dispatchers.IO) {
            val r = dao.getRun(runId)
            val p = dao.points(runId)
            val s = dao.splits(runId)
            withContext(Dispatchers.Main) {
                run = r; points = p; splits = s; loaded = true
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("ランの記録", onBack)
        val r = run
        if (r == null) {
            Text(if (loaded) "この記録は見つかりませんでした。" else "読み込み中…", color = Palette.dim)
            return@Column
        }
        val color = Palette.of(r.type)

        // ロードかトレイルかを大きく
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color).padding(16.dp)) {
            Text(
                if (r.type == "TRAIL") "トレイル・獲得標高 +${Math.round(r.gainM)}m" else "ロード",
                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black,
            )
            Text(formatDate(r.startTime), color = Color.White, fontSize = 15.sp)
        }

        Card {
            Row { Stat("距離", "${Fmt.km(r.distanceM)} km"); Stat("タイム", Fmt.clock(r.movingMs)) }
            Row {
                Stat("平均ペース", "${Fmt.pace(avgPace(r))}/km")
                Stat("最速の1km", Fmt.pace(splits.filter { it.distanceM >= 999 }.minOfOrNull { it.movingMs / 1000.0 }) + "/km")
            }
            Row {
                Stat("ケイデンス", if (r.steps > 0 && r.movingMs > 0) "${Math.round(r.steps / (r.movingMs / 60000.0))} 歩/分" else "--")
                Stat("消費カロリー（目安）", "${Math.round(r.calories)} kcal")
            }
            Row { Stat("登り", "+${Math.round(r.gainM)} m"); Stat("下り", "-${Math.round(r.lossM)} m") }
            Text(
                "標高：${r.elevationSource}" + if (r.startAltSource.isNotEmpty()) "（開始地点の合わせ方：${r.startAltSource}）" else "",
                color = Palette.dim, fontSize = 13.sp,
            )
            if (r.note.isNotEmpty()) Text(r.note, color = Palette.warn, fontSize = 13.sp)
        }

        if (points.size >= 2) {
            Card {
                SectionTitle("ルートの形（地図は段階2で追加します）")
                RouteShape(points, color)
            }
            if (points.count { it.alt != null } >= 2) {
                Card {
                    SectionTitle("標高")
                    ElevationShape(points, r.altOffset, color)
                }
            }
        }

        if (splits.isNotEmpty()) {
            Card {
                SectionTitle("1km ごと")
                Row {
                    Cell("km", 0.8f, head = true); Cell("ペース", 1.2f, head = true); Cell("登り", 1f, head = true)
                    Cell("下り", 1f, head = true); Cell("歩/分", 1f, head = true)
                }
                splits.forEach { s ->
                    val full = s.distanceM >= 999
                    val pace = if (s.distanceM > 1) s.movingMs / 1000.0 / (s.distanceM / 1000.0) else null
                    Row {
                        Cell(if (full) "${s.idx}" else "%.2f".format(s.distanceM / 1000), 0.8f)
                        Cell(Fmt.pace(pace), 1.2f)
                        Cell("+${Math.round(s.gainM)}", 1f)
                        Cell("-${Math.round(s.lossM)}", 1f)
                        Cell(if (s.steps > 0 && s.movingMs > 0) "${Math.round(s.steps / (s.movingMs / 60000.0))}" else "--", 1f)
                    }
                }
            }
        }

        Card {
            SectionTitle("ヘルスコネクト")
            Text(if (r.hcSynced) "書き込み済みです。" else "まだ書き込んでいません。", color = Palette.text, fontSize = 15.sp)
            SmallButton(if (r.hcSynced) "もう一度書き込む" else "ヘルスコネクトに書き込む", Modifier.fillMaxWidth()) {
                scope.launch {
                    val ok = runCatching { withContext(Dispatchers.IO) { Health.writeRun(context, r.id) } }
                    message = when {
                        ok.getOrNull() == true -> "ヘルスコネクトに書き込みました。"
                        ok.isFailure -> "書き込めませんでした：${ok.exceptionOrNull()?.message ?: "原因不明"}"
                        else -> "書き込めませんでした。「許可の状態」でヘルスコネクトの許可を確認してください。"
                    }
                    reload++
                }
            }
            message?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton(if (r.type == "TRAIL") "ロードに変える" else "トレイルに変える", Modifier.weight(1f)) {
                scope.launch {
                    withContext(Dispatchers.IO) { dao.updateRun(r.copy(type = if (r.type == "TRAIL") "ROAD" else "TRAIL")) }
                    reload++
                }
            }
            SmallButton("この記録を消す", Modifier.weight(1f)) { confirmDelete = true }
        }
        Gap(24)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("この記録を消しますか？") },
            text = { Text("消すと元に戻せません。（ヘルスコネクトに書き込んだ分は残ります）") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { dao.deleteRun(runId) }
                        onBack()
                    }
                }) { Text("消す", color = Palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, weight: Float, head: Boolean = false) {
    Text(
        text,
        modifier = Modifier.weight(weight).padding(vertical = 4.dp),
        color = if (head) Palette.dim else Palette.text,
        fontSize = if (head) 13.sp else 16.sp,
        textAlign = TextAlign.Center,
    )
}

/** ルートの形だけを線で描く（北が上） */
@Composable
private fun RouteShape(points: List<PointEntity>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }
        val maxLon = points.maxOf { it.lon }
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val w = ((maxLon - minLon) * k).coerceAtLeast(1e-6)
        val h = (maxLat - minLat).coerceAtLeast(1e-6)
        val pad = 12f
        val scale = minOf((size.width - pad * 2) / w, (size.height - pad * 2) / h)
        val ox = (size.width - w * scale) / 2
        val oy = (size.height - h * scale) / 2
        val path = Path()
        points.forEachIndexed { i, p ->
            val x = (ox + (p.lon - minLon) * k * scale).toFloat()
            val y = (oy + (maxLat - p.lat) * scale).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val first = points.first()
        val last = points.last()
        drawCircle(Color.White, 9f, Offset((ox + (first.lon - minLon) * k * scale).toFloat(), (oy + (maxLat - first.lat) * scale).toFloat()))
        drawCircle(Palette.warn, 9f, Offset((ox + (last.lon - minLon) * k * scale).toFloat(), (oy + (maxLat - last.lat) * scale).toFloat()))
    }
    Text("白：スタート　オレンジ：ゴール", color = Palette.dim, fontSize = 12.sp)
}

/** 横軸を距離にした標高の線 */
@Composable
private fun ElevationShape(points: List<PointEntity>, offset: Double, color: Color) {
    val withAlt = points.filter { it.alt != null }
    val minAlt = withAlt.minOf { it.alt!! } + offset
    val maxAlt = withAlt.maxOf { it.alt!! } + offset
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val total = withAlt.last().distM.coerceAtLeast(1.0)
        // 平らなコースが大げさな山に見えないよう、縦の幅は最低 20m ぶん取る
        val range = (maxAlt - minAlt).coerceAtLeast(20.0)
        val path = Path()
        withAlt.forEachIndexed { i, p ->
            val x = (p.distM / total * size.width).toFloat()
            val y = (size.height - ((p.alt!! + offset - minAlt) / range * (size.height - 8f)) - 4f).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    Text("いちばん低い ${Math.round(minAlt)}m 〜 いちばん高い ${Math.round(maxAlt)}m", color = Palette.dim, fontSize = 12.sp)
}
