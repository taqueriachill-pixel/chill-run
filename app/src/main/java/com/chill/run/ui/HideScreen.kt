package com.chill.run.ui

import android.annotation.SuppressLint
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.HideZone
import com.chill.run.data.Settings

/** 隠す場所（自宅など）を決める画面。ここから決めた半径の中のルートは、ランムービーに描かない */
@SuppressLint("MissingPermission")
@Composable
fun HideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var zone by remember { mutableStateOf(settings.hideZone) }
    var radius by remember { mutableIntStateOf(zone?.radiusM?.toInt() ?: 200) }
    val state = remember { MapState(zoom = 15.5) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val z = zone
        if (z != null) {
            state.moveTo(z.lat, z.lon)
        } else if (Perms.location(context)) {
            runCatching {
                val lm = context.getSystemService(LocationManager::class.java)
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }.getOrNull()?.let { state.moveTo(it.latitude, it.longitude) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("隠す場所", onBack)
        Text(
            "自宅など、人に知られたくない場所を決めておくと、ランムービーではその近くのルートを描きません。アプリの中の記録の画面では、これまでどおり全部見られます。",
            color = Palette.dim, fontSize = 13.sp,
        )
        TileMap(state, Modifier.fillMaxWidth().height(380.dp)) { proj ->
            // いま決めてある場所（赤）
            zone?.let { z ->
                val c = proj.toScreen(z.lat, z.lon)
                val r = proj.pixelsPerMeter(z.lat) * z.radiusM.toFloat()
                drawCircle(Palette.danger.copy(alpha = 0.18f), r, c)
                drawCircle(Palette.danger, r, c, style = Stroke(width = 5f))
            }
            // 地図の真ん中（これから決める場所）
            val c = proj.toScreen(state.centerLat, state.centerLon)
            val r = proj.pixelsPerMeter(state.centerLat) * radius
            drawCircle(Palette.road, r, c, style = Stroke(width = 4f))
            drawCircle(Color.White, 12f, c); drawCircle(Palette.road, 8f, c)
        }
        Text("青い円＝地図の真ん中（これから決める場所）　赤い円＝いま決めてある場所", color = Palette.dim, fontSize = 12.sp)
        Card {
            SectionTitle("隠す広さ（半径）")
            Stepper(
                "$radius m",
                onMinus = { radius = (radius - 50).coerceAtLeast(100) },
                onPlus = { radius = (radius + 50).coerceAtMost(1000) },
            )
            BigButton("地図の真ん中を、隠す場所にする", Modifier.fillMaxWidth()) {
                val z = HideZone(state.centerLat, state.centerLon, radius.toDouble())
                settings.hideZone = z
                zone = z
                message = "決めました。これから作るランムービーに反映されます（前に作ったムービーは、作り直すと反映されます）。"
            }
            if (zone != null) {
                SmallButton("隠す場所をなくす", Modifier.fillMaxWidth()) {
                    settings.hideZone = null
                    zone = null
                    message = "なくしました。"
                }
            }
            message?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
        }
        Gap(24)
    }
}
