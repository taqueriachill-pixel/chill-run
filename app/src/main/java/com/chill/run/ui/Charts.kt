package com.chill.run.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 横軸を距離にしたグラフ。縦の細い線は 1km ごと。
 * @param minRange 縦の幅の最小値（平らなコースが大げさに見えないように）
 * @param filled 線の下を塗るか（標高の山の形）
 */
@Composable
fun DistanceChart(
    xs: DoubleArray,
    ys: DoubleArray,
    color: Color,
    topLabel: String,
    bottomLabel: String,
    minRange: Double = 0.0,
    filled: Boolean = false,
    inverted: Boolean = false,
) {
    if (xs.size < 2) return
    val total = (xs.last() - xs.first()).coerceAtLeast(1.0)
    val lo = ys.min()
    val hi = ys.max()
    val range = (hi - lo).coerceAtLeast(minRange).coerceAtLeast(1e-6)
    val base = lo - (range - (hi - lo)) / 2
    Column {
        Text(topLabel, color = Palette.dim, fontSize = 12.sp)
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val w = size.width
            val h = size.height
            // 1km ごとの縦線
            val kms = (total / 1000).toInt()
            if (kms in 1..60) for (k in 1..kms) {
                val x = (k * 1000.0 / total * w).toFloat()
                drawLine(Palette.line, Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
            }
            fun px(i: Int) = ((xs[i] - xs.first()) / total * w).toFloat()
            fun py(i: Int): Float {
                val f = ((ys[i] - base) / range).coerceIn(0.0, 1.0)
                val up = if (inverted) 1 - f else f
                return (h - 4f - up * (h - 8f)).toFloat()
            }
            val line = Path()
            for (i in xs.indices) if (i == 0) line.moveTo(px(i), py(i)) else line.lineTo(px(i), py(i))
            if (filled) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(px(xs.lastIndex), h)
                    lineTo(px(0), h)
                    close()
                }
                drawPath(area, color.copy(alpha = 0.28f))
            }
            drawPath(line, color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(bottomLabel, color = Palette.dim, fontSize = 12.sp)
            Text("%.1f km".format(total / 1000), color = Palette.dim, fontSize = 12.sp)
        }
    }
}

/** 一覧の各行に出す、標高の小さなグラフ（山の形）。平らか山あり谷ありかが一目で分かる */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        // 高低差 30m 未満のコースは、ほぼ平らに見えるようにする
        val range = (hi - lo).coerceAtLeast(30f)
        val w = size.width
        val h = size.height
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * w / (values.size - 1)
            val y = h - 2f - (v - lo) / range * (h - 4f)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(area, color.copy(alpha = 0.35f))
        drawPath(line, color, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
