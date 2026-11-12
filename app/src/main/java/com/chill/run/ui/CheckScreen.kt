package com.chill.run.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.Geo
import com.chill.run.health.Health
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 実機チェック：段階1の最初に確かめること（気圧・歩数センサー、日本語の読み上げ、
 * ヘルスコネクトに Zepp の心拍数や AIfit の体重が入っているか）を、この画面でまとめて見る。
 */
@Composable
fun CheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sensorManager = remember { context.getSystemService(SensorManager::class.java) }
    val pressureSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) }
    val stepDetector = remember { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) }
    val stepCounter = remember { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }

    var pressure by remember { mutableStateOf<Float?>(null) }
    var stepsSeen by remember { mutableIntStateOf(0) }
    var ttsText by remember { mutableStateOf("調べています…") }
    var hcText by remember { mutableStateOf<String?>(null) }
    var hcBusy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_PRESSURE -> pressure = event.values[0]
                    Sensor.TYPE_STEP_DETECTOR -> stepsSeen++
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        pressureSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        if (Perms.activity(context)) {
            stepDetector?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }

    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            val t = tts
            ttsText = if (status != TextToSpeech.SUCCESS || t == null) {
                "×　読み上げの準備に失敗しました。"
            } else {
                val lang = t.isLanguageAvailable(Locale.JAPAN)
                val offline = runCatching {
                    t.voices?.count {
                        it.locale.language == "ja" && !it.isNetworkConnectionRequired &&
                            it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
                    } ?: 0
                }.getOrDefault(0)
                val engine = t.defaultEngine ?: "不明"
                when {
                    lang < TextToSpeech.LANG_AVAILABLE -> "×　日本語の読み上げが使えません（エンジン：$engine）。下の「読み上げの設定を開く」から日本語の音声データを入れてください。"
                    offline == 0 -> "△　日本語は使えますが、通信なしで使える声が見つかりません（エンジン：$engine）。山の中で読み上げるには、日本語の音声データのダウンロードが必要です。"
                    else -> "✓　日本語の読み上げが使えます。通信なしで使える声：$offline 個（エンジン：$engine）"
                }
            }
        }
        onDispose { runCatching { tts?.shutdown() } }
    }

    val sensorReport = buildString {
        appendLine("【センサー】")
        appendLine(
            if (pressureSensor == null) "気圧センサー：×　見つかりません（標高は GPS で測ります）"
            else "気圧センサー：✓　${pressureSensor.name}　いま ${pressure?.let { "%.2f hPa（標準大気での高さ %.0fm）".format(it, Geo.pressureToAltitude(it.toDouble())) } ?: "値を待っています…"}"
        )
        appendLine("歩数センサー（1歩ごと）：${if (stepDetector != null) "✓ ${stepDetector.name}" else "×"}")
        appendLine("歩数センサー（合計）：${if (stepCounter != null) "✓ ${stepCounter.name}" else "×"}")
        if (stepDetector != null) {
            appendLine(
                if (Perms.activity(context)) "この画面を開いてから数えた歩数：$stepsSeen 歩（スマホを持って少し歩くと増えます）"
                else "歩数を試すには「許可の状態」で身体活動を許可してください"
            )
        }
    }.trimEnd()

    val fullReport = buildString {
        appendLine("Chill Run 実機チェック")
        appendLine("機種：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine()
        appendLine(sensorReport)
        appendLine()
        appendLine("【読み上げ】")
        appendLine(ttsText)
        appendLine()
        append(hcText ?: "【ヘルスコネクト】まだ調べていません")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("実機チェック", onBack)
        Text(
            "このスマホで何が使えるかを確かめる画面です。いちばん下の「結果をコピー」で、結果をそのまま Claude に貼り付けられます。",
            color = Palette.dim, fontSize = 13.sp,
        )

        Card { Text(sensorReport, color = Palette.text, fontSize = 14.sp, lineHeight = 22.sp) }

        Card {
            SectionTitle("読み上げ")
            Text(ttsText, color = Palette.text, fontSize = 14.sp, lineHeight = 22.sp)
            SmallButton("読み上げの設定を開く", Modifier.fillMaxWidth()) {
                runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }

        Card {
            SectionTitle("ヘルスコネクト")
            Text(
                "Zepp の心拍数や AIfit の体重が入っているかを調べます。先に「許可の状態」でヘルスコネクトを許可してください。",
                color = Palette.dim, fontSize = 13.sp,
            )
            SmallButton(if (hcBusy) "調べています…" else "ヘルスコネクトの中身を調べる", Modifier.fillMaxWidth(), enabled = !hcBusy) {
                hcBusy = true
                scope.launch {
                    hcText = runCatching { Health.diagnose(context) }
                        .getOrElse { "【ヘルスコネクト】調べられませんでした：${it.message}" }
                    hcBusy = false
                }
            }
            hcText?.let { Text(it, color = Palette.text, fontSize = 14.sp, lineHeight = 22.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(if (copied) "コピーしました" else "結果をコピー", Modifier.weight(1f)) {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Chill Run 実機チェック", fullReport))
                copied = true
            }
        }
        Gap(24)
    }
}
