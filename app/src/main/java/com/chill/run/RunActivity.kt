package com.chill.run

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.AnnounceText
import com.chill.run.core.Fmt
import com.chill.run.core.GoalKind
import com.chill.run.core.PhaseKind
import com.chill.run.core.RunType
import com.chill.run.run.LiveState
import com.chill.run.run.RunHub
import com.chill.run.run.RunService
import com.chill.run.ui.ChillTheme
import com.chill.run.ui.Display
import com.chill.run.ui.FigureRow
import com.chill.run.ui.Palette
import com.chill.run.ui.RouteSketch
import com.chill.run.ui.Topo
import com.chill.run.ui.Type
import com.chill.run.data.AppDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/**
 * 走っている画面。ロックを解除しなくても表示できる。
 * ここから一覧や設定には進めない（ロック中にほかの画面を見せないため）。
 */
class RunActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            ChillTheme {
                val state by RunHub.state.collectAsState()
                LaunchedEffect(state.active, state.finishedRunId) {
                    if (!state.active) openMainAndClose(state.finishedRunId)
                }
                RunScreen(
                    state = state,
                    onPauseToggle = {
                        RunService.send(this, if (state.manualPaused) RunService.ACTION_RESUME else RunService.ACTION_PAUSE)
                    },
                    onFinish = { RunService.send(this, RunService.ACTION_FINISH) },
                    onMemo = { RunService.send(this, RunService.ACTION_MEMO) },
                    onPhoto = { startActivity(CaptureActivity.intent(this, video = false)) },
                    onVideo = { startActivity(CaptureActivity.intent(this, video = true)) },
                    onMusicToggle = { RunService.send(this, RunService.ACTION_MUSIC_TOGGLE) },
                    onMusicNext = { RunService.send(this, RunService.ACTION_MUSIC_NEXT) },
                )
            }
        }
    }

    private fun openMainAndClose(runId: Long?) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (runId != null && runId > 0) intent.putExtra(MainActivity.EXTRA_RUN_ID, runId)
        // ロック中なら、解除してから記録の画面を見せる
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        startActivity(intent)
        finish()
    }
}

@Composable
private fun RunScreen(
    state: LiveState,
    onPauseToggle: () -> Unit,
    onFinish: () -> Unit,
    onMemo: () -> Unit,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onMusicToggle: () -> Unit,
    onMusicNext: () -> Unit,
) {
    val context = LocalContext.current
    val accent = Palette.of(state.type)
    // 走ったルートの形（画面を開き直したときは、記録から読み直す）
    val track = remember { mutableStateListOf<Pair<Double, Double>>() }
    LaunchedEffect(state.runId) {
        if (state.runId > 0 && track.isEmpty()) {
            val pts = withContext(Dispatchers.IO) { runCatching { AppDb.get(context).runDao().points(state.runId) }.getOrDefault(emptyList()) }
            track.addAll(pts.map { it.lat to it.lon })
        }
    }
    LaunchedEffect(state.lat, state.lon) {
        val lat = state.lat ?: return@LaunchedEffect
        val lon = state.lon ?: return@LaunchedEffect
        val last = track.lastOrNull()
        if (last == null || Math.abs(last.first - lat) + Math.abs(last.second - lon) > 0.00004) track.add(lat to lon)
    }
    val km = (state.distanceM / 1000).toInt() + 1

    Column(Modifier.fillMaxSize().background(Palette.bg)) {
        // 上：深い緑に、等高線とルートの形。いまの状態
        Box(Modifier.fillMaxWidth().background(Palette.earth)) {
            Topo(Modifier.matchParentSize(), Color(0xFF435244), rings = 12)
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (state.type == RunType.TRAIL) "TRAIL" else "ROAD") + " · KM $km",
                        color = Palette.bg, fontFamily = Type.narrow, fontWeight = FontWeight(800), fontSize = 16.sp, letterSpacing = 2.sp,
                    )
                    Text(gpsLabel(state.gpsAccuracyM), color = gpsColor(state.gpsAccuracyM), fontSize = 13.sp)
                }
                RouteSketch(Modifier.fillMaxWidth().height(150.dp), track, Palette.signal, Palette.bg)
                val phase = state.phase
                if (phase != null) {
                    val running = phase.kind == PhaseKind.RUN
                    val remaining = state.phaseRemaining ?: 0.0
                    Row(
                        Modifier.fillMaxWidth().background(if (running) Palette.signal else Palette.bg).padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg = if (running) Palette.on(Palette.signal) else Palette.earth
                        Text(AnnounceText.phaseName(phase.kind) + if (phase.rep > 0) " ${phase.rep}/${state.totalReps}" else "", color = fg, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text(
                            if (phase.byDistance) "あと ${Math.round(remaining.coerceAtLeast(0.0))}m"
                            else "あと ${Fmt.clock((remaining.coerceAtLeast(0.0) * 1000).toLong() + 999)}",
                            color = fg, fontSize = 24.sp, fontFamily = Type.narrow, fontWeight = FontWeight(800),
                        )
                    }
                }
                val status = when {
                    state.manualPaused -> "一時停止中"
                    state.autoPaused -> "止まっているので時間を止めています"
                    else -> goalLine(state)
                }
                Text(status, color = if (state.manualPaused || state.autoPaused) Color(0xFFFFB27A) else Color(0xFFCFD6CC), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // 下：生成りの紙に、大きな数字とボタン
        Column(
            Modifier.fillMaxWidth().weight(1f).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("きょり", color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Display(Fmt.km(state.distanceM), 112, color = Palette.earth)
                    Display(" KM", 30, Modifier.padding(bottom = 14.dp), color = Palette.earth)
                }
                val z = state.zone
                FigureRow(
                    listOf(
                        Triple("タイム", Fmt.clock(state.movingMs), Palette.earth),
                        Triple("ペース /km", Fmt.pace(state.pace), Palette.earth),
                        if (state.hrEnabled) Triple(if (z != null && z >= 1) "心拍 Z$z" else "心拍", state.hr?.toString() ?: "--", if (z != null) Palette.zone(z) else Palette.earth)
                        else Triple("平均ペース", Fmt.pace(state.avgPace), Palette.earth),
                    ),
                    valueSize = 32,
                )
                Text(
                    (if (state.hrEnabled) "平均 ${Fmt.pace(state.avgPace)}/km　" else "") +
                        "ケイデンス ${state.cadence ?: "--"}　登り +${Math.round(state.gainM)}m" +
                        (state.altitudeM?.let { "　標高 ${Math.round(it)}m" } ?: "") +
                        if (state.hrEnabled && state.hr == null && state.hrStatus.isNotEmpty()) "\n心拍数：${state.hrStatus}" else "",
                    color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.musicOn) {
                    Row(Modifier.fillMaxWidth().border(1.5.dp, Palette.line).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.musicTitle.ifEmpty { "音楽の準備中…" }, color = Palette.text, fontSize = 14.sp, maxLines = 1)
                            Text(state.musicInfo, color = Palette.dim, fontSize = 11.sp, maxLines = 1)
                        }
                        MusicButton(if (state.musicPlaying) "止める" else "流す", onMusicToggle)
                        MusicButton("次の曲", onMusicNext)
                    }
                }
                // 走りながら残す：メモ・写真・動画（1回押すだけ。汗や手袋でも押しやすい大きさ）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TapButton(
                        label = if (state.recording) "止める" else "メモ",
                        sub = if (state.recording) "録音中 ${state.recordingSec}秒" else if (state.micReady) "声を録音" else "マイクの許可なし",
                        filled = state.recording, modifier = Modifier.weight(1f), onClick = onMemo,
                    )
                    TapButton("写真", "3秒後に撮る", false, Modifier.weight(1f), enabled = !state.recording, onClick = onPhoto)
                    TapButton("動画", "3秒後に撮る", false, Modifier.weight(1f), enabled = !state.recording, onClick = onVideo)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoldButton(
                        label = if (state.manualPaused) "再開" else "一時停止",
                        color = if (state.manualPaused) accent else Palette.signal,
                        modifier = Modifier.weight(1f), onHold = onPauseToggle,
                    )
                    HoldButton(label = "終了", color = Palette.earth, modifier = Modifier.weight(1f), onHold = onFinish)
                }
            }
        }
    }
}

private fun goalLine(s: LiveState): String = when (s.goalKind) {
    GoalKind.FREE -> "自由に走る"
    GoalKind.INTERVAL -> if (s.intervalDone) "インターバル終了（このまま走り続けられます）" else "インターバル"
    GoalKind.DISTANCE ->
        if (s.goalReached) "目標 ${Fmt.km(s.goalValue)}km 達成！"
        else "目標 ${Fmt.km(s.goalValue)}km ・ あと ${Fmt.km((s.goalValue - s.distanceM).coerceAtLeast(0.0))}km"
    GoalKind.TIME ->
        if (s.goalReached) "目標 ${Fmt.clock((s.goalValue * 1000).toLong())} 達成！"
        else "目標 ${Fmt.clock((s.goalValue * 1000).toLong())} ・ あと ${Fmt.clock(((s.goalValue * 1000).toLong() - s.movingMs).coerceAtLeast(0))}"
}

private fun gpsLabel(acc: Float?): String = when {
    acc == null -> "GPS をさがしています…"
    acc <= 10f -> "GPS 良好（±${Math.round(acc)}m）"
    acc <= 30f -> "GPS ふつう（±${Math.round(acc)}m）"
    else -> "GPS 弱い（±${Math.round(acc)}m）"
}

private fun gpsColor(acc: Float?): Color = when {
    acc == null -> Color(0xFFFFB27A)
    acc <= 10f -> Color(0xFF9FD8A8)
    acc <= 30f -> Color(0xFFCFD6CC)
    else -> Color(0xFFFFB27A)
}

/** 間違えて押さないよう、長押しで動くボタン。短く押したときは使い方を出す */
@Composable
private fun HoldButton(label: String, color: Color, modifier: Modifier = Modifier, onHold: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var hint by remember { mutableStateOf(false) }
    // 画面は毎秒描き直されるので、押している途中で途切れないよう最新の動きだけ覚えておく
    val currentOnHold by rememberUpdatedState(onHold)
    LaunchedEffect(hint) {
        if (hint) {
            delay(1500)
            hint = false
        }
    }
    Box(
        modifier = modifier
            .height(92.dp)
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { hint = true },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnHold()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = Palette.on(color), fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(
                if (hint) "長く押してください" else "長押し",
                color = Palette.on(color),
                fontSize = 14.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

/** 1回押すだけで動く大きなボタン（メモ・写真・動画）。録音中のメモだけ塗りつぶす */
@Composable
private fun TapButton(label: String, sub: String, filled: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val fg = when { filled -> Color.White; enabled -> Palette.earth; else -> Palette.dim }
    Box(
        modifier = modifier
            .height(92.dp)
            .background(if (filled) Palette.danger else Color.Transparent)
            .border(2.dp, if (filled) Palette.danger else if (enabled) Palette.earth else Palette.line)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = fg, fontSize = 23.sp, fontWeight = FontWeight.Black)
            Text(sub, color = fg, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MusicButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(56.dp).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Palette.earth, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}
