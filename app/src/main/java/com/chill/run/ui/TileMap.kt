package com.chill.run.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.MapMath
import com.chill.run.core.Tile
import com.chill.run.data.TileStore
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** 地図の、いま見ている場所と大きさ */
@Stable
class MapState(lat: Double = 35.6812, lon: Double = 139.7671, zoom: Double = 14.0) {
    var centerX by mutableDoubleStateOf(MapMath.lonToX(lon))
    var centerY by mutableDoubleStateOf(MapMath.latToY(lat))
    var zoom by mutableDoubleStateOf(zoom)
    var initialized by mutableStateOf(false)

    val centerLat get() = MapMath.yToLat(centerY)
    val centerLon get() = MapMath.xToLon(centerX)

    fun moveTo(lat: Double, lon: Double, zoom: Double = this.zoom) {
        centerX = MapMath.lonToX(lon)
        centerY = MapMath.latToY(lat)
        this.zoom = zoom
    }
}

/** 地図の上に絵を描くときに使う、緯度経度 → 画面の位置 の変換 */
class MapProjection(private val state: MapState, private val world: Double, private val width: Float, private val height: Float) {
    fun toScreen(lat: Double, lon: Double): Offset = Offset(
        ((MapMath.lonToX(lon) - state.centerX) * world + width / 2).toFloat(),
        ((MapMath.latToY(lat) - state.centerY) * world + height / 2).toFloat(),
    )

    /** いまの大きさで、1メートルが画面の何ピクセルか */
    fun pixelsPerMeter(lat: Double): Float =
        (world / (40_075_016.686 * kotlin.math.cos(Math.toRadians(lat)))).toFloat()
}

/**
 * 国土地理院の地図を表示する。指でずらす・2本指で拡大縮小・右下のボタンで拡大縮小。
 * overlay で、地図の上にルートなどを描ける。onReady は、大きさが分かった最初の1回だけ呼ばれる。
 */
@Composable
fun TileMap(
    state: MapState,
    modifier: Modifier = Modifier,
    onReady: (widthPx: Double, heightPx: Double, tilePx: Double) -> Unit = { _, _, _ -> },
    overlay: DrawScope.(MapProjection) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // 地図の文字が小さくなりすぎないよう、画面の細かさに合わせてタイルを大きめに描く
    val tilePx = 256.0 * (density * 0.7).coerceAtLeast(1.0)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val tiles = remember { mutableStateMapOf<Tile, ImageBitmap>() }
    val requested = remember { HashSet<Tile>() }
    val scope = rememberCoroutineScope()

    val tz = state.zoom.roundToInt().coerceIn(MapMath.MIN_ZOOM, MapMath.MAX_ZOOM)
    val tileDisp = tilePx * 2.0.pow(state.zoom - tz)
    val world = tileDisp * 2.0.pow(tz)
    val left = state.centerX * world - size.width / 2.0
    val top = state.centerY * world - size.height / 2.0
    val n = 1 shl tz
    val visible = ArrayList<Tile>()
    if (size.width > 0) {
        val x0 = floor(left / tileDisp).toInt()
        val x1 = floor((left + size.width) / tileDisp).toInt()
        val y0 = floor(top / tileDisp).toInt()
        val y1 = floor((top + size.height) / tileDisp).toInt()
        for (x in x0..x1) for (y in y0..y1) if (x in 0 until n && y in 0 until n) visible += Tile(tz, x, y)
    }

    LaunchedEffect(size) {
        if (size.width > 0 && !state.initialized) {
            state.initialized = true
            onReady(size.width.toDouble(), size.height.toDouble(), tilePx)
        }
    }

    LaunchedEffect(visible) {
        // 画面の外に出た地図は手放す
        if (tiles.size > 80) tiles.keys.filter { it !in visible }.forEach { tiles.remove(it) }
        for (t in visible) {
            if (t in tiles) continue
            val cached = TileStore.cached(t)
            if (cached != null) {
                tiles[t] = cached
            } else if (requested.add(t)) {
                // 地図をずらしても読み込みが途中で止まらないよう、画面全体の作業場で読み込む
                scope.launch {
                    try {
                        val bmp = TileStore.load(context, t)
                        if (bmp != null) tiles[t] = bmp
                    } finally {
                        requested.remove(t)
                    }
                }
            }
        }
    }

    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFE9E6DF)).onSizeChanged { size = it }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoomChange, _ ->
                    val oldZoom = state.zoom
                    val newZoom = (oldZoom + log2(zoomChange.toDouble())).coerceIn(MapMath.MIN_ZOOM.toDouble(), 18.9)
                    val oldWorld = tilePx * 2.0.pow(oldZoom)
                    val newWorld = tilePx * 2.0.pow(newZoom)
                    // 指の位置を中心に拡大縮小し、指の動きのぶんだけずらす
                    val cx = centroid.x - this.size.width / 2.0
                    val cy = centroid.y - this.size.height / 2.0
                    val focusX = state.centerX + cx / oldWorld
                    val focusY = state.centerY + cy / oldWorld
                    state.zoom = newZoom
                    state.centerX = (focusX - (cx + pan.x) / newWorld).coerceIn(0.0, 1.0)
                    state.centerY = (focusY - (cy + pan.y) / newWorld).coerceIn(0.0, 1.0)
                }
            }
        ) {
            val dst = IntSize(ceil(tileDisp).toInt() + 1, ceil(tileDisp).toInt() + 1)
            for (t in visible) {
                val image = tiles[t] ?: continue
                drawImage(
                    image = image,
                    dstOffset = IntOffset(floor(t.x * tileDisp - left).toInt(), floor(t.y * tileDisp - top).toInt()),
                    dstSize = dst,
                    filterQuality = FilterQuality.Medium,
                )
            }
            overlay(MapProjection(state, world, this.size.width, this.size.height))
        }
        Text(
            TileStore.ATTRIBUTION,
            color = Color(0xFF333333), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Column(Modifier.align(Alignment.BottomEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MapButton("＋") { state.zoom = (state.zoom + 1).coerceAtMost(18.9) }
            MapButton("−") { state.zoom = (state.zoom - 1).coerceAtLeast(MapMath.MIN_ZOOM.toDouble()) }
        }
    }
}

@Composable
private fun MapButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xE6FFFFFF))
            .pointerInput(Unit) { androidx.compose.foundation.gestures.detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color(0xFF222222), fontSize = 22.sp) }
}
