package com.chill.run.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
import com.chill.run.health.BleCheck
import com.chill.run.health.Health
import com.chill.run.music.MusicLog
import com.chill.run.run.MemoRecorder
import com.chill.run.speech.Transcriber
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
    var speechSupport by remember { mutableStateOf("調べています…") }
    var speechTest by remember { mutableStateOf<String?>(null) }
    var speechBusy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { speechSupport = Transcriber.supportReport(context) }

    /** 5秒録音して、その録音ファイルを文字にしてみる（走っている最中の声のメモと同じやり方） */
    fun runSpeechTest() {
        val file = java.io.File(context.cacheDir, "speech_test.wav")
        val recorder = MemoRecorder()
        speechBusy = "録音中…（5秒、何か話してください）"
        val started = recorder.start(file, 5000) { _, ok ->
            if (!ok) {
                speechTest = "×　録音できませんでした。"
                speechBusy = null
            } else {
                speechBusy = "文字にしています…"
                scope.launch {
                    val startMs = System.currentTimeMillis()
                    val result = Transcriber.transcribe(context, file)
                    val sec = (System.currentTimeMillis() - startMs) / 1000.0
                    speechTest = result.fold(
                        { "✓　文字にできました（%.1f秒かかりました）：「%s」".format(sec, it) },
                        { "×　文字にできませんでした：${it.message}" },
                    )
                    speechBusy = null
                    copied = false
                    file.delete()
                }
            }
        }
        if (!started) {
            speechTest = "×　録音を始められませんでした。"
            speechBusy = null
        }
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runSpeechTest() else speechTest = "マイクの許可が必要です。"
    }

    var bleText by remember { mutableStateOf<String?>(null) }
    var bleProgress by remember { mutableStateOf<String?>(null) }

    fun runBleCheck() {
        bleProgress = "準備しています…"
        scope.launch {
            bleText = runCatching { BleCheck.run(context) { bleProgress = it } }
                .getOrElse { "【心拍数の機器（Bluetooth）】調べられませんでした：${it.message}" }
            bleProgress = null
            copied = false
        }
    }
    val bleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) runBleCheck()
        else bleText = "【心拍数の機器（Bluetooth）】「付近のデバイス」の許可が必要です。"
    }

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
        appendLine("【音楽の動き（直近）】")
        appendLine(MusicLog.text())
        appendLine()
        appendLine("【声を文字にする（端末内の音声認識）】")
        appendLine(speechSupport)
        appendLine(speechTest ?: "録音からの文字化：まだ試していません")
        appendLine()
        appendLine(hcText ?: "【ヘルスコネクト】まだ調べていません")
        appendLine()
        append(bleText ?: "【心拍数の機器（Bluetooth）】まだ調べていません")
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
            SectionTitle("音楽の動き（直近40件）")
            Text(
                "曲がいつ・なぜ切り替わったかの覚え書きです。おかしな切り替わりがあったら、ランを終了したあとにここを開いて「結果をコピー」で送ってください（アプリを閉じると消えます）。",
                color = Palette.dim, fontSize = 13.sp, lineHeight = 20.sp,
            )
            Text(MusicLog.text(), color = Palette.text, fontSize = 12.sp, lineHeight = 18.sp)
        }

        Card {
            SectionTitle("声を文字にする")
            Text(speechSupport, color = Palette.text, fontSize = 14.sp, lineHeight = 22.sp)
            Text(
                "下のボタンを押して、5秒のあいだ「今日は生田緑地を走っています」のように話してください。録音したものを、通信を使わずに文字にできるかを試します。機内モードにして試すと確実です。",
                color = Palette.dim, fontSize = 13.sp, lineHeight = 20.sp,
            )
            SmallButton(speechBusy ?: "5秒話して試す", Modifier.fillMaxWidth(), enabled = speechBusy == null) {
                if (Perms.microphone(context)) runSpeechTest() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            speechTest?.let { Text(it, color = Palette.text, fontSize = 14.sp, lineHeight = 22.sp) }
            SmallButton("日本語のデータを入れる（「データが入っていません」と出たとき）", Modifier.fillMaxWidth()) {
                Transcriber.requestJapaneseModel(context)
                speechTest = "ダウンロードを頼みました。数分待ってから、この画面を開き直してください。"
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

        Card {
            SectionTitle("心拍数の機器（Bluetooth）")
            Text(
                "PowerBuds が心拍数を直接流しているかを調べます（nRF Connect の代わり）。\n" +
                    "① PowerBuds を両耳に付ける\n② Zepp の「アクティビティ心拍数の共有」をオンにしておく\n③ 下のボタンを押して、1分ほど待つ\n" +
                    "受け取れなかったときは、Zepp でワークアウトを始めてから、もう一度押してください。",
                color = Palette.dim, fontSize = 13.sp, lineHeight = 20.sp,
            )
            SmallButton(bleProgress ?: "心拍数の機器をさがす", Modifier.fillMaxWidth(), enabled = bleProgress == null) {
                val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                if (perms.all { Perms.has(context, it) }) runBleCheck() else bleLauncher.launch(perms)
            }
            bleText?.let { Text(it, color = Palette.text, fontSize = 14.sp, lineHeight = 22.sp) }
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
