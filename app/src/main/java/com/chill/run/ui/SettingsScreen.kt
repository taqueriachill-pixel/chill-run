package com.chill.run.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.AnnounceOptions
import com.chill.run.core.AnnounceText
import com.chill.run.core.AnnounceUnit
import com.chill.run.core.PhraseBook
import com.chill.run.core.RunConfig
import com.chill.run.core.TrackerEvent
import com.chill.run.data.PhraseStore
import com.chill.run.data.Settings
import com.chill.run.health.Health
import com.chill.run.run.Speaker
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()

    var autoPause by remember { mutableStateOf(settings.autoPause) }
    var announceOn by remember { mutableStateOf(settings.announceOn) }
    var interval by remember { mutableStateOf(settings.announceInterval) }
    var sayPace by remember { mutableStateOf(settings.sayPace) }
    var sayCompare by remember { mutableStateOf(settings.sayCompare) }
    var sayCheer by remember { mutableStateOf(settings.sayCheer) }
    var rate by remember { mutableFloatStateOf(settings.ttsRate) }
    var volume by remember { mutableFloatStateOf(settings.ttsVolume) }
    var birthYear by remember { mutableIntStateOf(settings.birthYear) }
    var weight by remember { mutableFloatStateOf(settings.weightKg) }
    var phraseMessage by remember { mutableStateOf<String?>(null) }
    var weightMessage by remember { mutableStateOf<String?>(null) }
    var customPhrases by remember { mutableStateOf(PhraseStore.isCustom(context)) }
    var videoSeconds by remember { mutableIntStateOf(settings.videoSeconds) }
    var trailGain by remember { mutableIntStateOf(settings.trailGainPerKm) }
    var trailSteep by remember { mutableIntStateOf(settings.trailSteepPercent) }

    val speaker = remember { Speaker(context, settings.ttsRate, settings.ttsVolume) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            phraseMessage = runCatching { PhraseStore.export(context, uri) }
                .fold({ "書き出しました。" }, { "書き出せませんでした：${it.message}" })
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            phraseMessage = runCatching { PhraseStore.import(context, uri) }
                .fold({ "読み込みました（言葉 $it 個）。" }, { "読み込めませんでした。ファイルの形を確認してください：${it.message}" })
            customPhrases = PhraseStore.isCustom(context)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("設定", onBack)

        Card {
            SectionTitle("記録")
            SwitchRow("止まっている間は時間を止める（自動の一時停止）", autoPause) { autoPause = it; settings.autoPause = it }
        }

        Card {
            SectionTitle("声のお知らせ")
            SwitchRow("声でお知らせする", announceOn) { announceOn = it; settings.announceOn = it }
            Text("間隔", color = Palette.dim, fontSize = 13.sp)
            Settings.intervalChoices.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { (key, label) ->
                        Chip(label, interval == key, Modifier.weight(1f)) { interval = key; settings.announceInterval = key }
                    }
                    repeat(3 - rowItems.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
                }
            }
            Text("伝えること（距離は必ず伝えます）", color = Palette.dim, fontSize = 13.sp)
            SwitchRow("ペース", sayPace) { sayPace = it; settings.sayPace = it }
            SwitchRow("前の区間と比べて速いか遅いか", sayCompare) { sayCompare = it; settings.sayCompare = it }
            SwitchRow("励ましの言葉", sayCheer) { sayCheer = it; settings.sayCheer = it }
            Text("声の速さ：%.1f 倍".format(rate), color = Palette.text, fontSize = 15.sp)
            Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.6f..1.6f, onValueChangeFinished = { settings.ttsRate = rate })
            Text("声の大きさ：%d%%".format(Math.round(volume * 100)), color = Palette.text, fontSize = 15.sp)
            Slider(value = volume, onValueChange = { volume = it }, valueRange = 0.2f..1f, onValueChangeFinished = { settings.ttsVolume = volume })
            SmallButton("声をためす", Modifier.fillMaxWidth()) {
                speaker.configure(rate, volume)
                val sample = AnnounceText.interval(
                    TrackerEvent.Interval(3, 3000.0, 1_116_000, 372.0, 380.0),
                    RunConfig(announceUnit = AnnounceUnit.DISTANCE, announceEvery = 1000.0),
                    AnnounceOptions(sayPace, sayCompare, sayCheer),
                    PhraseBook(PhraseStore.load(context)),
                )
                speaker.say(sample, flush = true)
            }
            if (speaker.japaneseAvailable == false) {
                Text("日本語の読み上げが使えないようです。「実機チェック」の案内を見てください。", color = Palette.warn, fontSize = 14.sp)
            }
        }

        Card {
            SectionTitle("励ましの言葉")
            Text(
                if (customPhrases) "自分で読み込んだ言葉を使っています。" else "はじめから入っている言葉を使っています。",
                color = Palette.text, fontSize = 15.sp,
            )
            Text("書き出したファイルを書き換えて、読み込むと反映されます。", color = Palette.dim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("書き出し", Modifier.weight(1f)) { exportLauncher.launch("chill-run-phrases.json") }
                SmallButton("読み込み", Modifier.weight(1f)) { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            }
            if (customPhrases) {
                SmallButton("はじめの言葉に戻す", Modifier.fillMaxWidth()) {
                    PhraseStore.reset(context); customPhrases = false; phraseMessage = "はじめの言葉に戻しました。"
                }
            }
            phraseMessage?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
        }

        Card {
            SectionTitle("走りながら残す")
            Text("動画の長さ", color = Palette.dim, fontSize = 13.sp)
            Stepper(
                "$videoSeconds 秒",
                onMinus = { videoSeconds = (videoSeconds - 1).coerceAtLeast(3); settings.videoSeconds = videoSeconds },
                onPlus = { videoSeconds = (videoSeconds + 1).coerceAtMost(5); settings.videoSeconds = videoSeconds },
            )
            Text("声のメモは最長60秒。写真・動画・録音は、このアプリの中だけに保存します。", color = Palette.dim, fontSize = 13.sp)
        }

        Card {
            SectionTitle("地図")
            Text("走る前に、よく走るあたりの地図をスマホに取っておけます（山の中で通信がなくても見られます）。", color = Palette.dim, fontSize = 13.sp)
            SmallButton("地図の一時保存", Modifier.fillMaxWidth()) { onNavigate("mapsave") }
        }

        Card {
            SectionTitle("トレイルの判定の目安")
            Text(
                "走り終わったあと、次の2つの両方に当てはまると「トレイルに変えますか？」と聞きます。",
                color = Palette.dim, fontSize = 13.sp,
            )
            Text("1km あたりの登り", color = Palette.dim, fontSize = 13.sp)
            Stepper(
                "$trailGain m 以上",
                onMinus = { trailGain = (trailGain - 1).coerceAtLeast(3); settings.trailGainPerKm = trailGain },
                onPlus = { trailGain = (trailGain + 1).coerceAtMost(80); settings.trailGainPerKm = trailGain },
            )
            Text("急な坂（勾配 10% 以上）を通った割合", color = Palette.dim, fontSize = 13.sp)
            Stepper(
                "$trailSteep % 以上",
                onMinus = { trailSteep = (trailSteep - 1).coerceAtLeast(1); settings.trailSteepPercent = trailSteep },
                onPlus = { trailSteep = (trailSteep + 1).coerceAtMost(60); settings.trailSteepPercent = trailSteep },
            )
        }

        Card {
            SectionTitle("プロフィール")
            Text("生まれた年", color = Palette.dim, fontSize = 13.sp)
            Stepper(
                "$birthYear 年",
                onMinus = { birthYear = (birthYear - 1).coerceAtLeast(1920); settings.birthYear = birthYear },
                onPlus = { birthYear = (birthYear + 1).coerceAtMost(2020); settings.birthYear = birthYear },
            )
            Text("体重（消費カロリーの目安に使います）", color = Palette.dim, fontSize = 13.sp)
            Stepper(
                "%.1f kg".format(weight),
                onMinus = { weight = (weight - 0.5f).coerceAtLeast(30f); settings.weightKg = weight },
                onPlus = { weight = (weight + 0.5f).coerceAtMost(150f); settings.weightKg = weight },
            )
            SmallButton("ヘルスコネクトの体重を使う", Modifier.fillMaxWidth()) {
                scope.launch {
                    val w = Health.latestWeightKg(context)
                    if (w != null) {
                        weight = (Math.round(w * 10) / 10f)
                        settings.weightKg = weight
                        weightMessage = "ヘルスコネクトの体重（%.1f kg）を入れました。".format(w)
                    } else {
                        weightMessage = "ヘルスコネクトに体重が見つかりませんでした（許可か、AIfit の同期を確認してください）。"
                    }
                }
            }
            weightMessage?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
        }
        Gap(24)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
