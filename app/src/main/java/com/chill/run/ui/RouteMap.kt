package com.chill.run.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.MapMath
import com.chill.run.data.PointEntity

private val SLOW = Color(0xFF2E6FE6)  // 遅いところは青
private val FAST = Color(0xFFFF8A1F)  // 速いところはオレンジ

private class RouteData(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val fraction: FloatArray,
    /** 何km 目か と、その地点 */
    val marks: List<Triple<Int, Double, Double>>,
)

/** 国土地理院の地図の上に、ルートを速さで色分けして描く。1km ごとに小さな数字を置く */
@Composable
fun RouteMap(points: List<PointEntity>, modifier: Modifier = Modifier) {
    val data = remember(points) {
        val dist = DoubleArray(points.size) { points[it].distM }
        val ms = LongArray(points.size) { points[it].movingMs }
        val allFractions = MapMath.speedFractions(dist, ms)
        val marks = MapMath.kmMarks(dist).map { (km, i) -> Triple(km, points[i].lat, points[i].lon) }
        // 描く点が多すぎると重くなるので、1500 点くらいまでに間引く
        val stride = points.size / 1500 + 1
        val idx = points.indices.filter { it % stride == 0 || it == points.lastIndex }
        RouteData(
            DoubleArray(idx.size) { points[idx[it]].lat },
            DoubleArray(idx.size) { points[idx[it]].lon },
            FloatArray(idx.size) { allFractions[idx[it]] },
            marks,
        )
    }
    val state = remember(points) { MapState() }
    val measurer = rememberTextMeasurer()
    val markStyle = TextStyle(color = Color(0xFF1B2A33), fontSize = 11.sp, fontWeight = FontWeight.Bold)

    Column {
        TileMap(
            state = state,
            modifier = modifier.fillMaxWidth().height(340.dp),
            onReady = { w, h, tilePx ->
                if (data.lat.isNotEmpty()) {
                    val minLat = data.lat.min(); val maxLat = data.lat.max()
                    val minLon = data.lon.min(); val maxLon = data.lon.max()
                    val zoom = MapMath.fitZoom(minLat, maxLat, minLon, maxLon, w * 0.82, h * 0.82, tilePx)
                    state.moveTo((minLat + maxLat) / 2, (minLon + maxLon) / 2, zoom)
                }
            },
        ) { proj ->
            val n = data.lat.size
            if (n >= 2) {
                val pts = Array(n) { proj.toScreen(data.lat[it], data.lon[it]) }
                // 地図の上でも見やすいよう、白いふちを先に描く
                val outline = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until n) lineTo(pts[i].x, pts[i].y)
                }
                drawPath(outline, Color.White, style = Stroke(width = 13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                for (i in 1 until n) {
                    drawLine(lerp(SLOW, FAST, data.fraction[i]), pts[i - 1], pts[i], strokeWidth = 8f, cap = StrokeCap.Round)
                }
                for ((km, la, lo) in data.marks) {
                    val c = proj.toScreen(la, lo)
                    drawCircle(Color.White, 22f, c)
                    drawCircle(Color(0xFF1B2A33), 22f, c, style = Stroke(width = 3f))
                    val layout = measurer.measure(km.toString(), markStyle)
                    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
                }
                // スタートは白地に緑、ゴールは白地に赤
                drawCircle(Color.White, 17f, pts[0]); drawCircle(Palette.trail, 11f, pts[0])
                drawCircle(Color.White, 17f, pts[n - 1]); drawCircle(Palette.danger, 11f, pts[n - 1])
            }
        }
        Text(
            "線の色：青＝ゆっくり　オレンジ＝速い　／　緑の丸＝スタート　赤の丸＝ゴール　／　数字＝1km ごと",
            color = Palette.dim, fontSize = 12.sp,
        )
    }
}
