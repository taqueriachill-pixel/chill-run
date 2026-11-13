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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.chill.run.core.HeartZones
import com.chill.run.core.PhraseBook
import com.chill.run.core.RunConfig
import com.chill.run.core.TrackerEvent
import com.chill.run.data.Backup
import com.chill.run.data.PhraseStore
import com.chill.run.data.Settings
import com.chill.run.health.Health
import com.chill.run.run.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var autoMovie by remember { mutableStateOf(settings.autoMovie) }
    var backupMedia by remember { mutableStateOf(true) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }
    var restored by remember { mutableStateOf(false) }

    val backupExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            backupBusy = true
            backupMessage = "書き出しています…"
            scope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { Backup.export(context, uri, backupMedia) } }
                backupBusy = false
                backupMessage = result.fold(
                    { "書き出しました（ラン ${it.runs} 件、メモ・写真・動画 ${it.files} 個）。" },
                    { "書き出せませんでした：${it.message}" },
                )
            }
        }
    }
    val backupImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }
    var videoStabilize by remember { mutableStateOf(settings.videoStabilize) }
    var sayHeart by remember { mutableStateOf(settings.sayHeart) }
    var restHr by remember { mutableIntStateOf(settings.restHr) }
    var maxManual by remember { mutableIntStateOf(settings.maxHrManual) }
    var zoneTargets by remember {
        mutableStateOf(listOf(settings.jogZoneLo, settings.jogZoneHi, settings.tempoZoneLo, settings.tempoZoneHi, settings.intervalZoneLo, settings.intervalZoneHi))
    }
    var hrMessage by remember { mutableStateOf<String?>(null) }

    /** 目標ゾーンを変える。下が上を超えないようにそろえる */
    fun setZone(index: Int, value: Int) {
        val v = value.coerceIn(1, 5)
        val list = zoneTargets.toMutableList()
        list[index] = v
        val pair = index / 2 * 2
        if (index % 2 == 0 && list[pair + 1] < v) list[pair + 1] = v
        if (index % 2 == 1 && list[pair] > v) list[pair] = v
        zoneTargets = list
        settings.jogZoneLo = list[0]; settings.jogZoneHi = list[1]
        settings.tempoZoneLo = list[2]; settings.tempoZoneHi = list[3]
        settings.intervalZoneLo = list[4]; settings.intervalZoneHi = list[5]
    }
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
            SwitchRow("心拍数とゾーン、心拍数からの注意（機器を選んであるとき）", sayHeart) { sayHeart = it; settings.sayHeart = it }
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
            SectionTitle("心拍数")
            Text(
                if (settings.hrDeviceAddress.isEmpty()) "心拍数の機器：まだ選んでいません" else "心拍数の機器：${settings.hrDeviceName.ifEmpty { "（名前なし）" }}",
                color = Palette.text, fontSize = 16.sp,
            )
            SmallButton("心拍数の機器を選ぶ・試す", Modifier.fillMaxWidth()) { onNavigate("hrdevice") }

            Text("安静時の心拍数（朝、起きてすぐの値が目安）", color = Palette.dim, fontSize = 13.sp)
            Stepper(
                "$restHr 拍/分",
                onMinus = { restHr = (restHr - 1).coerceAtLeast(35); settings.restHr = restHr },
                onPlus = { restHr = (restHr + 1).coerceAtMost(100); settings.restHr = restHr },
            )
            SmallButton("ヘルスコネクトの安静時心拍数を使う", Modifier.fillMaxWidth()) {
                scope.launch {
                    val v = Health.latestRestingHr(context)
                    if (v != null && v in 35..100) {
                        restHr = v; settings.restHr = v
                        hrMessage = "ヘルスコネクトの値（$v 拍/分）を入れました。"
                    } else hrMessage = "ヘルスコネクトに安静時の心拍数が見つかりませんでした。手で入れてください。"
                }
            }
            hrMessage?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }

            Text("最大心拍数", color = Palette.dim, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("年齢からの目安（${settings.maxHrFromAge}）", maxManual == 0, Modifier.weight(1f)) { maxManual = 0; settings.maxHrManual = 0 }
                Chip("自分で決める", maxManual > 0, Modifier.weight(1f)) {
                    if (maxManual == 0) { maxManual = settings.maxHrFromAge; settings.maxHrManual = maxManual }
                }
            }
            if (maxManual > 0) {
                Stepper(
                    "$maxManual 拍/分",
                    onMinus = { maxManual = (maxManual - 1).coerceAtLeast(120); settings.maxHrManual = maxManual },
                    onPlus = { maxManual = (maxManual + 1).coerceAtMost(230); settings.maxHrManual = maxManual },
                )
            }

            // いまの設定での、ゾーンごとの心拍数
            val zones = HeartZones(if (maxManual > 0) maxManual else settings.maxHrFromAge, restHr)
            Text("心拍ゾーン（カルボーネン法）", color = Palette.dim, fontSize = 13.sp)
            for (z in 1..5) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("$z", color = Palette.zone(z), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, modifier = Modifier.weight(0.3f))
                    Text(HeartZones.names[z], color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1.6f))
                    Text("${zones.rangeText(z)} 拍/分", color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1.2f))
                }
            }

            Text("メニューごとの目標ゾーン", color = Palette.dim, fontSize = 13.sp)
            listOf("ゆっくりジョグ", "ペース走", "インターバルの「走る」").forEachIndexed { i, label ->
                Text(label, color = Palette.text, fontSize = 15.sp)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("−", Modifier.weight(0.7f)) { setZone(i * 2, zoneTargets[i * 2] - 1) }
                    Text("下 ${zoneTargets[i * 2]}", color = Palette.text, fontSize = 16.sp, modifier = Modifier.weight(0.9f))
                    SmallButton("＋", Modifier.weight(0.7f)) { setZone(i * 2, zoneTargets[i * 2] + 1) }
                    SmallButton("−", Modifier.weight(0.7f)) { setZone(i * 2 + 1, zoneTargets[i * 2 + 1] - 1) }
                    Text("上 ${zoneTargets[i * 2 + 1]}", color = Palette.text, fontSize = 16.sp, modifier = Modifier.weight(0.9f))
                    SmallButton("＋", Modifier.weight(0.7f)) { setZone(i * 2 + 1, zoneTargets[i * 2 + 1] + 1) }
                }
            }

            // 安全のための表示（仕様 4-4）
            Text(
                "【大事なこと】\n" +
                    "・心拍ゾーンはトレーニングの目安です。体調や病気の判断に使うものではありません。\n" +
                    "・胸の痛み、強い息切れ、めまい、ふらつきがあれば、数字に関係なくすぐに止まって休んでください。\n" +
                    "・年齢からの最大心拍数の目安は、人によって大きくずれることがあります。\n" +
                    "・手首で測る心拍数は、走っている最中は腕の振りや汗でずれることがあります。",
                color = Palette.warn, fontSize = 13.sp, lineHeight = 20.sp,
            )
        }

        Card {
            SectionTitle("音楽")
            Text("曲のフォルダ、BPM の一覧と修正、速さを変える幅、目標テンポの決め方。", color = Palette.dim, fontSize = 13.sp)
            SmallButton("音楽の設定", Modifier.fillMaxWidth()) { onNavigate("music") }
        }

        Card {
            SectionTitle("走りながら残す")
            Text("動画の長さ", color = Palette.dim, fontSize = 13.sp)
            Stepper(
                "$videoSeconds 秒",
                onMinus = { videoSeconds = (videoSeconds - 1).coerceAtLeast(3); settings.videoSeconds = videoSeconds },
                onPlus = { videoSeconds = (videoSeconds + 1).coerceAtMost(5); settings.videoSeconds = videoSeconds },
            )
            SwitchRow("動画の手ブレ補正（映る範囲が少しせまくなります）", videoStabilize) { videoStabilize = it; settings.videoStabilize = it }
            Text("声のメモは最長60秒。写真・動画・録音は、このアプリの中だけに保存します。", color = Palette.dim, fontSize = 13.sp)
        }

        Card {
            SectionTitle("地図")
            Text("走る前に、よく走るあたりの地図をスマホに取っておけます（山の中で通信がなくても見られます）。", color = Palette.dim, fontSize = 13.sp)
            SmallButton("地図の一時保存", Modifier.fillMaxWidth()) { onNavigate("mapsave") }
        }

        Card {
            SectionTitle("ランムービーと、隠す場所")
            SwitchRow("走り終わったら、ランムービーを自動で作る", autoMovie) { autoMovie = it; settings.autoMovie = it }
            Text(
                if (settings.hideZone == null) "隠す場所：決めていません" else "隠す場所：決めてあります（半径 ${Math.round(settings.hideZone!!.radiusM)}m）",
                color = Palette.text, fontSize = 15.sp,
            )
            SmallButton("隠す場所（自宅など）を決める", Modifier.fillMaxWidth()) { onNavigate("hide") }
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
            SectionTitle("書き出し・読み込み（全データのバックアップ）")
            Text(
                "記録・設定・励ましの言葉を、1つのファイル（zip）にまとめます。機種変更のときや、もしものときのために、ときどき書き出して Google ドライブなどに置いておくと安心です。",
                color = Palette.dim, fontSize = 13.sp,
            )
            SwitchRow("声のメモ・写真・動画も入れる（ファイルが大きくなります）", backupMedia) { backupMedia = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(if (backupBusy) "作業中…" else "書き出す", Modifier.weight(1f), enabled = !backupBusy) {
                    val stamp = java.time.LocalDate.now().toString()
                    backupExport.launch("chill-run-backup-$stamp.zip")
                }
                SmallButton("読み込む", Modifier.weight(1f), enabled = !backupBusy) { backupImport.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            }
            backupMessage?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
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

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("バックアップを読み込みますか？") },
            text = { Text("いまこのスマホに入っている記録と設定は、すべてバックアップの中身に置きかわります。元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    backupBusy = true
                    backupMessage = "読み込んでいます…"
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.IO) { Backup.import(context, uri) } }
                        backupBusy = false
                        result.onSuccess {
                            backupMessage = "読み込みました（ラン ${it.runs} 件、メモ・写真・動画 ${it.files} 個）。"
                            restored = true
                        }.onFailure { backupMessage = "読み込めませんでした。いまの記録は変えていません：${it.message}" }
                    }
                }) { Text("置きかえる", color = Palette.danger) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("やめる") } },
        )
    }
    if (restored) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("読み込みました") },
            text = { Text("新しい中身を使うために、いったんアプリを閉じます。もう一度 Chill Run を開いてください。") },
            confirmButton = {
                TextButton(onClick = {
                    (context as? android.app.Activity)?.finishAffinity()
                    kotlin.system.exitProcess(0)
                }) { Text("アプリを閉じる") }
            },
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
