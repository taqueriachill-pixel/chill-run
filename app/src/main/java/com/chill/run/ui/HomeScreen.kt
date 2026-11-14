package com.chill.run.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.chill.run.RunActivity
import com.chill.run.core.GoalKind
import com.chill.run.core.RunType
import com.chill.run.data.AppDb
import com.chill.run.data.Settings
import com.chill.run.run.RunHub
import com.chill.run.run.RunService

@SuppressLint("MissingPermission")
@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val live by RunHub.state.collectAsState()

    var type by remember { mutableStateOf(runCatching { RunType.valueOf(settings.lastType) }.getOrDefault(RunType.ROAD)) }
    var goalKind by remember { mutableStateOf(runCatching { GoalKind.valueOf(settings.lastGoalKind) }.getOrDefault(GoalKind.FREE)) }
    var goalKm by remember { mutableFloatStateOf(settings.lastGoalKm) }
    var goalMin by remember { mutableIntStateOf(settings.lastGoalMinutes) }
    var hasLocation by remember { mutableStateOf(Perms.location(context)) }
    var gpsAccuracy by remember { mutableStateOf<Float?>(null) }
    var aim by remember { mutableStateOf(settings.lastAim) }
    val hasHrDevice = settings.hrDeviceAddress.isNotEmpty()
    val dao = remember { AppDb.get(context).runDao() }
    val allTracks by remember { dao.observeTracks() }.collectAsState(initial = emptyList())
    val trackCount = allTracks.count { it.enabled && it.bpm > 0 }
    var musicOn by remember { mutableStateOf(settings.musicOn) }
    val menus by remember { dao.observeMenus() }.collectAsState(initial = emptyList())
    var menuId by remember { mutableLongStateOf(settings.lastMenuId) }
    val menu = menus.firstOrNull { it.id == menuId } ?: menus.firstOrNull()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasLocation = Perms.location(context) }

    fun startRun() {
        settings.lastType = type.name
        settings.lastGoalKind = goalKind.name
        settings.lastGoalKm = goalKm
        settings.lastGoalMinutes = goalMin
        val goalValue = when (goalKind) {
            GoalKind.FREE, GoalKind.INTERVAL -> 0.0
            GoalKind.DISTANCE -> goalKm * 1000.0
            GoalKind.TIME -> goalMin * 60.0
        }
        val plan = if (goalKind == GoalKind.INTERVAL) menu?.toPlan() ?: return else null
        if (menu != null) settings.lastMenuId = menu.id
        settings.lastAim = aim
        RunService.start(context, type, goalKind, goalValue, plan, if (hasHrDevice) aim else "")
        context.startActivity(Intent(context, RunActivity::class.java))
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = Perms.location(context)
    }

    // スタート前に GPS を温めておく（走り出しの位置がずれにくくなる）
    if (hasLocation && !live.active) {
        DisposableEffect(Unit) {
            val lm = context.getSystemService(LocationManager::class.java)
            val listener = LocationListener { loc -> gpsAccuracy = if (loc.hasAccuracy()) loc.accuracy else null }
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper()) }
            onDispose { runCatching { lm.removeUpdates(listener) } }
        }
    }

    val accent = Palette.of(type)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Chill Run", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Palette.text)

        if (live.active) {
            Card {
                Text("いま記録中です", color = Palette.warn, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                BigButton("走っている画面に戻る", Modifier.fillMaxWidth(), color = Palette.of(live.type)) {
                    context.startActivity(Intent(context, RunActivity::class.java))
                }
            }
        } else {
            Card {
                SectionTitle("種類")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip("ロード", type == RunType.ROAD, Modifier.weight(1f), color = Palette.road) { type = RunType.ROAD }
                    Chip("トレイル", type == RunType.TRAIL, Modifier.weight(1f), color = Palette.trail) { type = RunType.TRAIL }
                }
                SectionTitle("メニュー")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("自由に走る", goalKind == GoalKind.FREE, Modifier.weight(1f), color = accent) { goalKind = GoalKind.FREE }
                    Chip("目標の距離", goalKind == GoalKind.DISTANCE, Modifier.weight(1f), color = accent) { goalKind = GoalKind.DISTANCE }
                    Chip("目標の時間", goalKind == GoalKind.TIME, Modifier.weight(1f), color = accent) { goalKind = GoalKind.TIME }
                }
                Chip("インターバル", goalKind == GoalKind.INTERVAL, Modifier.fillMaxWidth(), color = accent) { goalKind = GoalKind.INTERVAL }
                when (goalKind) {
                    GoalKind.DISTANCE -> Stepper(
                        "%.1f km".format(goalKm),
                        onMinus = { goalKm = (goalKm - 0.5f).coerceAtLeast(0.5f) },
                        onPlus = { goalKm = (goalKm + 0.5f).coerceAtMost(100f) },
                    )
                    GoalKind.TIME -> Stepper(
                        "$goalMin 分",
                        onMinus = { goalMin = (goalMin - 5).coerceAtLeast(5) },
                        onPlus = { goalMin = (goalMin + 5).coerceAtMost(600) },
                    )
                    GoalKind.FREE -> {}
                    GoalKind.INTERVAL -> {
                        if (menus.isEmpty()) {
                            Text("まだメニューがありません。まず1つ作ってください。", color = Palette.dim, fontSize = 14.sp)
                        } else {
                            menus.chunked(2).forEach { rowItems ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowItems.forEach { m ->
                                        Chip(m.name, m.id == menu?.id, Modifier.weight(1f), color = accent) { menuId = m.id }
                                    }
                                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                            if (menu != null) Text(menu.summary(), color = Palette.text, fontSize = 15.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (menu != null) SmallButton("このメニューを直す", Modifier.weight(1f)) { onNavigate("interval/${menu.id}") }
                            SmallButton("新しく作る", Modifier.weight(1f)) { onNavigate("interval/0") }
                        }
                    }
                }
            }

            // 心拍数の機器を選んであるときだけ：今日のねらい（心拍ゾーンで、ペースが合っているかを声で知らせる）
            if (hasHrDevice && goalKind != GoalKind.INTERVAL) {
                Card {
                    SectionTitle("今日のねらい（心拍数でペースを見張る）")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("なし", aim == "", Modifier.weight(1f), color = accent) { aim = "" }
                        Chip("ゆっくりジョグ", aim == "JOG", Modifier.weight(1f), color = accent) { aim = "JOG" }
                        Chip("ペース走", aim == "TEMPO", Modifier.weight(1f), color = accent) { aim = "TEMPO" }
                    }
                    Text(
                        when (aim) {
                            "JOG" -> "目標はゾーン ${zoneText(settings.jogZoneLo, settings.jogZoneHi)}。上がりすぎが1分続くと知らせます。"
                            "TEMPO" -> "目標はゾーン ${zoneText(settings.tempoZoneLo, settings.tempoZoneHi)}。上がりすぎ・余裕がありすぎを知らせます。"
                            else -> "心拍数の注意は出しません（1km ごとの心拍数とゾーンは伝えます）。"
                        } + "　走る前に、時計を「走るモード」にしてください。",
                        color = Palette.dim, fontSize = 13.sp,
                    )
                }
            } else if (hasHrDevice) {
                Text(
                    "インターバルの「走る」は、ゾーン ${zoneText(settings.intervalZoneLo, settings.intervalZoneHi)} が目標です。走る前に、時計を「走るモード」にしてください。",
                    color = Palette.dim, fontSize = 13.sp,
                )
            }

            Card {
                SectionTitle("音楽")
                if (trackCount == 0) {
                    Text("曲のフォルダを選ぶと、走るテンポに合わせて音楽を流せます。", color = Palette.dim, fontSize = 13.sp)
                } else {
                    SwitchRow("音楽を流す（テンポを足に合わせる）", musicOn) { musicOn = it; settings.musicOn = it }
                    Text("BPM の分かる曲：$trackCount 曲", color = Palette.dim, fontSize = 13.sp)
                }
                SmallButton("曲とテンポの設定", Modifier.fillMaxWidth()) { onNavigate("music") }
            }

            if (!hasLocation) {
                Card {
                    Text("位置情報の許可が必要です（「正確な位置」「アプリの使用中のみ」）。", color = Palette.warn, fontSize = 15.sp)
                    BigButton("許可する", Modifier.fillMaxWidth(), color = Palette.warn) { permissionLauncher.launch(Perms.basic) }
                }
            } else {
                Text(
                    when (val a = gpsAccuracy) {
                        null -> "GPS をさがしています…（空の見える場所で少し待つと正確になります）"
                        else -> if (a <= 10f) "GPS 良好（±${Math.round(a)}m）" else "GPS 受信中（±${Math.round(a)}m）もう少し待つと正確になります"
                    },
                    color = if ((gpsAccuracy ?: 99f) <= 10f) Palette.trail else Palette.dim, fontSize = 14.sp,
                )
                BigButton(
                    "スタート", Modifier.fillMaxWidth(), color = accent,
                    enabled = goalKind != GoalKind.INTERVAL || menu != null,
                ) { startRun() }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("記録の一覧", Modifier.weight(1f)) { onNavigate("history") }
            SmallButton("ふりかえり", Modifier.weight(1f)) { onNavigate("review") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("設定", Modifier.weight(1f)) { onNavigate("settings") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton("許可の状態", Modifier.weight(1f)) { onNavigate("permissions") }
            SmallButton("実機チェック", Modifier.weight(1f)) { onNavigate("check") }
        }
    }
}

fun zoneText(lo: Int, hi: Int) = if (lo == hi) "$lo" else "$lo〜$hi"
