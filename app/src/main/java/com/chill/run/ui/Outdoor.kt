package com.chill.run.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.Progress
import com.chill.run.core.ProgressResult
import com.chill.run.core.RunFact
import com.chill.run.data.AppDb
import com.chill.run.data.RunEntity
import com.chill.run.data.Settings
import java.time.ZoneId

fun RunEntity.toFact() = RunFact(id, startTime, distanceM, gainM, best1kMs, best5kMs, best10kMs)

/** すべての記録から、週の目標・続いた週・レベルを計算する（記録が増えると計算しなおす） */
@Composable
fun rememberProgress(): ProgressResult? {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val dao = remember { AppDb.get(context).runDao() }
    val runs by remember { dao.observeRuns() }.collectAsState(initial = null)
    val goal = settings.weeklyGoalDays
    return remember(runs, goal) {
        runs?.let { list -> Progress.compute(list.map { it.toFact() }, goal, ZoneId.systemDefault(), System.currentTimeMillis()) }
    }
}

/** 等高線の模様（飾り。地図のデータではない） */
@Composable
fun Topo(modifier: Modifier, color: Color, rings: Int = 11, cx: Float = 0.68f, cy: Float = 0.42f) {
    Canvas(modifier) {
        val c = Offset(size.width * cx, size.height * cy)
        rotate(-14f, c) {
            for (i in 0 until rings) {
                val rx = (40 + i * 26).dp.toPx() * 0.95f
                val ry = (24 + i * 16).dp.toPx() * 0.95f
                drawOval(color, topLeft = Offset(c.x - rx, c.y - ry), size = Size(rx * 2, ry * 2), style = Stroke(width = 1.dp.toPx()))
            }
        }
    }
}

/** 走ったルートの形だけを、枠いっぱいに描く（地図なし）。最後の点に丸を付ける */
@Composable
fun RouteSketch(modifier: Modifier, points: List<Pair<Double, Double>>, color: Color, headFill: Color) {
    Canvas(modifier) {
        if (points.size < 2) {
            points.firstOrNull()?.let { drawCircle(headFill, 9.dp.toPx(), center); drawCircle(color, 9.dp.toPx(), center, style = Stroke(4.dp.toPx())) }
            return@Canvas
        }
        val lat0 = points.sumOf { it.first } / points.size
        val k = Math.cos(Math.toRadians(lat0))
        val xs = points.map { it.second * k }
        val ys = points.map { -it.first }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = 24.dp.toPx()
        val spanX = (maxX - minX).coerceAtLeast(1e-6)
        val spanY = (maxY - minY).coerceAtLeast(1e-6)
        val scale = minOf((size.width - pad * 2) / spanX, (size.height - pad * 2) / spanY)
        val offX = (size.width - spanX * scale) / 2
        val offY = (size.height - spanY * scale) / 2
        fun p(i: Int) = Offset((offX + (xs[i] - minX) * scale).toFloat(), (offY + (ys[i] - minY) * scale).toFloat())
        val path = Path().apply { moveTo(p(0).x, p(0).y); for (i in 1 until points.size) lineTo(p(i).x, p(i).y) }
        drawPath(path, color, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val head = p(points.size - 1)
        drawCircle(headFill, 9.dp.toPx(), head)
        drawCircle(color, 9.dp.toPx(), head, style = Stroke(4.dp.toPx()))
    }
}

/** 太い線の下に、ラベルと数字を横に並べる（雑誌の誌面のような数字の行） */
@Composable
fun FigureRow(items: List<Triple<String, String, Color>>, unitSize: Int = 15, valueSize: Int = 34) {
    Column(Modifier.fillMaxWidth()) {
        Rule()
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (label, value, color) ->
                Column(Modifier.weight(1f)) {
                    Text(label, color = Palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    val parts = value.split(' ', limit = 2)
                    Row {
                        Text(parts[0], color = color, fontSize = valueSize.sp, fontFamily = Type.narrow, fontWeight = FontWeight(800))
                        if (parts.size > 1) Text(" " + parts[1], color = color, fontSize = unitSize.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = (valueSize - unitSize - 2).dp))
                    }
                }
            }
        }
    }
}

/** レベルの進み具合の細い棒 */
@Composable
fun LevelBar(p: ProgressResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(Palette.line)) {
            Box(Modifier.fillMaxWidth(p.expInLevel.toFloat() / p.expForNext).height(6.dp).background(Palette.earth))
        }
        Text("あと ${p.expForNext - p.expInLevel} でレベル ${p.level + 1}", color = Palette.dim, fontSize = 12.sp)
    }
}
