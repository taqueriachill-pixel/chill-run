package com.chill.run

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.Fmt
import com.chill.run.core.GoalKind
import com.chill.run.core.RunType
import com.chill.run.run.LiveState
import com.chill.run.run.RunHub
import com.chill.run.run.RunService
import com.chill.run.ui.ChillTheme
import com.chill.run.ui.Palette
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
        enableEdgeToEdge()
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
private fun RunScreen(state: LiveState, onPauseToggle: () -> Unit, onFinish: () -> Unit) {
    val accent = Palette.of(state.type)
    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.type == RunType.TRAIL) "TRAIL" else "ROAD",
                    color = accent, fontSize = 22.sp, fontWeight = FontWeight.Black,
                )
                Text(gpsLabel(state.gpsAccuracyM), color = gpsColor(state.gpsAccuracyM), fontSize = 14.sp)
            }
            val status = when {
                state.manualPaused -> "一時停止中"
                state.autoPaused -> "止まっているので時間を止めています"
                else -> goalLine(state)
            }
            Text(
                status,
                color = if (state.manualPaused || state.autoPaused) Palette.warn else Palette.dim,
                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(Fmt.km(state.distanceM), color = Color.White, fontSize = 104.sp, fontWeight = FontWeight.Black, lineHeight = 104.sp)
            Text("km", color = Palette.dim, fontSize = 20.sp)
            Row(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                BigStat("タイム", Fmt.clock(state.movingMs), Modifier.weight(1f))
                BigStat("ペース /km", Fmt.pace(state.pace), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                BigStat("平均ペース", Fmt.pace(state.avgPace), Modifier.weight(1f), small = true)
                BigStat("ケイデンス", state.cadence?.toString() ?: "--", Modifier.weight(1f), small = true)
                BigStat("登り", "+${Math.round(state.gainM)}m", Modifier.weight(1f), small = true)
            }
            Text(
                "標高：${state.elevationSource}" + (state.altitudeM?.let { "　いま ${Math.round(it)}m" } ?: ""),
                color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HoldButton(
                label = if (state.manualPaused) "再開" else "一時停止",
                color = if (state.manualPaused) accent else Color(0xFF3A4A54),
                modifier = Modifier.weight(1f),
                onHold = onPauseToggle,
            )
            HoldButton(label = "終了", color = Palette.danger, modifier = Modifier.weight(1f), onHold = onFinish)
        }
    }
}

private fun goalLine(s: LiveState): String = when (s.goalKind) {
    GoalKind.FREE -> "自由に走る"
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
    acc == null -> Palette.warn
    acc <= 10f -> Palette.trail
    acc <= 30f -> Palette.dim
    else -> Palette.warn
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = if (small) 30.sp else 46.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Palette.dim, fontSize = 14.sp)
    }
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
            .height(104.dp)
            .clip(RoundedCornerShape(22.dp))
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
            Text(label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(
                if (hint) "長く押してください" else "長押し",
                color = if (hint) Color.White else Color(0xCCFFFFFF),
                fontSize = 14.sp, textAlign = TextAlign.Center,
            )
        }
    }
}
