package com.chill.run.ui

import android.annotation.SuppressLint
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.MapMath
import com.chill.run.data.AppDb
import com.chill.run.data.TileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 走る前に、よく走るあたりの地図をスマホに取っておく画面 */
@SuppressLint("MissingPermission")
@Composable
fun MapSaveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { MapState(zoom = 13.0) }
    var radiusKm by remember { mutableIntStateOf(3) }
    var usage by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    // 最初は、いまいる場所（分からなければ、いちばん新しいランのスタート地点）を真ん中にする
    LaunchedEffect(Unit) {
        val here = if (Perms.location(context)) runCatching {
            val lm = context.getSystemService(LocationManager::class.java)
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull() else null
        if (here != null) {
            state.moveTo(here.latitude, here.longitude)
        } else {
            val first = withContext(Dispatchers.IO) {
                val dao = AppDb.get(context).runDao()
                dao.finishedRuns().firstOrNull()?.let { dao.points(it.id).firstOrNull() }
            }
            if (first != null) state.moveTo(first.lat, first.lon)
        }
    }
    LaunchedEffect(refresh) { usage = TileStore.usage(context) }

    // 保存するのは、広い範囲が見えるズーム12 から、道が細かく見えるズーム17 まで
    val tiles = remember(state.centerX, state.centerY, radiusKm) {
        MapMath.tilesAround(state.centerLat, state.centerLon, radiusKm * 1000.0, 12..17)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("地図の一時保存", onBack)
        Text(
            "地図を指でずらして、保存したい場所を真ん中（円の中）に合わせてください。一度見た場所の地図も、自動で取っておかれます。",
            color = Palette.dim, fontSize = 13.sp,
        )
        TileMap(state, Modifier.fillMaxWidth().height(360.dp)) { proj ->
            val c = proj.toScreen(state.centerLat, state.centerLon)
            val r = proj.pixelsPerMeter(state.centerLat) * radiusKm * 1000f
            drawCircle(Palette.road.copy(alpha = 0.12f), r, c)
            drawCircle(Palette.road, r, c, style = Stroke(width = 5f))
            drawCircle(Color.White, 12f, c); drawCircle(Palette.road, 8f, c)
        }
        Card {
            SectionTitle("保存する広さ（真ん中からの距離）")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 3, 5).forEach { km ->
                    Chip("半径 ${km}km", radiusKm == km, Modifier.weight(1f), enabled = job == null) { radiusKm = km }
                }
            }
            Text("地図 ${tiles.size} 枚、およそ ${tiles.size * 20 / 1000 + 1} MB。Wi-Fi での保存がおすすめです。", color = Palette.text, fontSize = 14.sp)
            if (job == null) {
                BigButton("この範囲を保存する", Modifier.fillMaxWidth()) {
                    message = null
                    val list = tiles
                    job = scope.launch {
                        var done = 0
                        var failed = 0
                        progress = "0 / ${list.size} 枚"
                        // 国土地理院のサーバーに負担をかけないよう、少しずつ取ってくる
                        for (chunk in list.chunked(4)) {
                            if (!isActive) break
                            val results = coroutineScope { chunk.map { t -> async { TileStore.ensureSaved(context, t) } }.awaitAll() }
                            done += results.count { it }
                            failed += results.count { !it }
                            progress = "$done / ${list.size} 枚" + if (failed > 0) "（失敗 $failed 枚）" else ""
                            if (failed > 30 && done == 0) break
                        }
                        message = when {
                            failed == 0 -> "保存しました（${done} 枚）。この範囲は通信なしでも見られます。"
                            done == 0 -> "保存できませんでした。通信の状態を確認してください。"
                            else -> "${done} 枚を保存しました。${failed} 枚は取れなかったので、もう一度押すと続きから保存します。"
                        }
                        progress = null
                        job = null
                        refresh++
                    }
                }
            } else {
                Text("保存しています… ${progress ?: ""}", color = Palette.warn, fontSize = 15.sp)
                SmallButton("やめる", Modifier.fillMaxWidth()) {
                    job?.cancel(); job = null; progress = null
                    message = "途中でやめました。保存できた分は残っています。"
                    refresh++
                }
            }
            message?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
        }
        Card {
            SectionTitle("スマホに取ってある地図")
            val u = usage
            Text(
                if (u == null) "調べています…" else "${u.second} 枚、%.1f MB".format(u.first / 1_000_000.0),
                color = Palette.text, fontSize = 15.sp,
            )
            SmallButton("取ってある地図を全部消す", Modifier.fillMaxWidth(), enabled = job == null) {
                scope.launch {
                    TileStore.clear(context)
                    message = "消しました。"
                    refresh++
                }
            }
        }
        Text(TileStore.ATTRIBUTION, color = Palette.dim, fontSize = 12.sp)
        Gap(24)
    }
}
