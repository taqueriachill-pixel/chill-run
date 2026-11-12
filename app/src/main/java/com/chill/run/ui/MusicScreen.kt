package com.chill.run.ui

import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.CadenceModel
import com.chill.run.core.Fmt
import com.chill.run.core.TapTempo
import com.chill.run.data.AppDb
import com.chill.run.data.Settings
import com.chill.run.data.TrackEntity
import com.chill.run.music.MusicLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 音楽の設定：曲のフォルダ、BPM の一覧と手での修正、変える幅、目標テンポの決め方 */
@Composable
fun MusicScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val dao = remember { AppDb.get(context).runDao() }
    val scope = rememberCoroutineScope()
    val tracks by remember { dao.observeTracks() }.collectAsState(initial = emptyList())
    val status by MusicLibrary.status.collectAsState()

    var folder by remember { mutableStateOf(settings.musicFolder) }
    var message by remember { mutableStateOf<String?>(null) }
    var range by remember { mutableIntStateOf(settings.tempoRangePercent) }
    var mode by remember { mutableStateOf(settings.tempoMode) }
    var pace by remember { mutableIntStateOf(settings.targetPaceSec) }
    var rest by remember { mutableIntStateOf(settings.restTempo) }
    var volume by remember { mutableIntStateOf(settings.musicVolumePercent) }
    var editing by remember { mutableStateOf<TrackEntity?>(null) }
    var model by remember { mutableStateOf(CadenceModel()) }

    LaunchedEffect(Unit) {
        val splits = withContext(Dispatchers.IO) { dao.recentFullSplits() }
        model = CadenceModel(splits.map { (it.movingMs / 1000.0) to Math.round(it.steps / (it.movingMs / 60000.0)).toInt() })
    }

    fun scan(uri: Uri) {
        scope.launch {
            message = "曲をさがしています…"
            val n = runCatching { MusicLibrary.scan(context, uri) }.getOrElse { message = "フォルダを読めませんでした：${it.message}"; return@launch }
            message = if (n == 0) "このフォルダには曲が見つかりませんでした。" else "$n 曲を見つけました。テンポを調べます（1曲に数秒かかります）。"
            MusicLibrary.analyzeAll(context)
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            settings.musicFolder = uri.toString()
            folder = uri.toString()
            scan(uri)
        }
    }

    val known = tracks.count { it.bpm > 0 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("音楽", onBack)

        Card {
            SectionTitle("曲のフォルダ")
            Text(
                if (folder.isEmpty()) "まだ選んでいません。スマホに入っている自分の曲（MP3・AAC・FLAC など）のフォルダを選んでください。"
                else "選んであります：${Uri.decode(folder).substringAfterLast(':').ifEmpty { "（フォルダ）" }}",
                color = Palette.text, fontSize = 15.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(if (folder.isEmpty()) "フォルダを選ぶ" else "フォルダを選び直す", Modifier.weight(1f)) { folderLauncher.launch(null) }
                if (folder.isNotEmpty()) SmallButton("曲を読み直す", Modifier.weight(1f)) { scan(Uri.parse(folder)) }
            }
            Text("全部で ${tracks.size} 曲、BPM が分かっている曲 $known 曲", color = Palette.dim, fontSize = 13.sp)
            (status ?: message)?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
            Text("※ Spotify などの配信の曲は使えません（ほかのアプリの音は速さを変えられないため）。", color = Palette.dim, fontSize = 12.sp)
        }

        Card {
            SectionTitle("目標テンポの決め方")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("いまの足に合わせる", mode == "FOLLOW", Modifier.weight(1f)) { mode = "FOLLOW"; settings.tempoMode = mode }
                Chip("目標のペースに引っ張る", mode == "PACE", Modifier.weight(1f)) { mode = "PACE"; settings.tempoMode = mode }
            }
            Text(
                if (mode == "FOLLOW") "直近20秒ほどの歩数（ケイデンス）に、曲のテンポを合わせます。"
                else "目標のペースに合うテンポで流します。ペースが目標より遅れているときは、曲を 2.5% 速めにします。",
                color = Palette.dim, fontSize = 13.sp,
            )
            Text("目標のペース（走り出しのテンポの目安にも使います）", color = Palette.dim, fontSize = 13.sp)
            Stepper(
                "${Fmt.pace(pace.toDouble())}/km",
                onMinus = { pace = (pace - 5).coerceAtLeast(180); settings.targetPaceSec = pace },
                onPlus = { pace = (pace + 5).coerceAtMost(600); settings.targetPaceSec = pace },
            )
            Text(
                "このペースに合うテンポ：${Math.round(model.cadenceFor(pace.toDouble()))} 歩/分（" +
                    (if (model.learned) "自分の記録から学習" else "まだ記録が少ないので、一般的な目安") + "）",
                color = Palette.text, fontSize = 14.sp,
            )
            Text("インターバルの「ゆっくり」のときのテンポ", color = Palette.dim, fontSize = 13.sp)
            Stepper(
                "$rest 歩/分",
                onMinus = { rest = (rest - 5).coerceAtLeast(90); settings.restTempo = rest },
                onPlus = { rest = (rest + 5).coerceAtMost(160); settings.restTempo = rest },
            )
        }

        Card {
            SectionTitle("速さを変える幅")
            Stepper(
                "± $range %",
                onMinus = { range = (range - 1).coerceAtLeast(5); settings.tempoRangePercent = range },
                onPlus = { range = (range + 1).coerceAtMost(15); settings.tempoRangePercent = range },
            )
            Text("音程はそのままで、速さだけを変えます。この幅に収まらない曲は選びません。倍・半分のテンポは同じとして扱います（85BPM の曲は 170歩/分に合います）。", color = Palette.dim, fontSize = 13.sp)
            SectionTitle("音楽の音量")
            Stepper(
                "$volume %",
                onMinus = { volume = (volume - 10).coerceAtLeast(30); settings.musicVolumePercent = volume },
                onPlus = { volume = (volume + 10).coerceAtMost(100); settings.musicVolumePercent = volume },
            )
            Text("声のお知らせの間は、音楽を自動で小さくします。", color = Palette.dim, fontSize = 13.sp)
        }

        Card {
            SectionTitle("BPM の一覧（曲を押すと直せます）")
            if (tracks.isEmpty()) Text("曲がありません。", color = Palette.dim, fontSize = 14.sp)
            tracks.take(400).forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = if (t.enabled) Palette.text else Palette.dim, fontSize = 15.sp, maxLines = 1)
                        if (t.artist.isNotEmpty()) Text(t.artist, color = Palette.dim, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(
                        when {
                            t.bpm > 0 -> "%.0f".format(t.bpm) + if (t.bpmManual) " ✎" else ""
                            t.bpmState == 0 -> "調べ中"
                            else -> "不明"
                        },
                        color = if (t.bpm > 0) Palette.text else Palette.warn, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    Switch(checked = t.enabled, onCheckedChange = { on ->
                        scope.launch(Dispatchers.IO) { dao.updateTrack(t.copy(enabled = on)) }
                    })
                }
            }
            if (tracks.size > 400) Text("（多いので、最初の 400 曲だけ出しています）", color = Palette.dim, fontSize = 12.sp)
        }
        Gap(24)
    }

    editing?.let { track ->
        BpmDialog(
            track = track,
            onSave = { bpm ->
                editing = null
                scope.launch(Dispatchers.IO) { dao.updateTrack(track.copy(bpm = bpm, bpmManual = true, bpmState = 1)) }
            },
            onAuto = {
                editing = null
                scope.launch {
                    withContext(Dispatchers.IO) { dao.updateTrack(track.copy(bpm = 0.0, bpmManual = false, bpmState = 0)) }
                    MusicLibrary.analyzeAll(context)
                }
            },
            onClose = { editing = null },
        )
    }
}

/** BPM を手で直す。曲を流しながら、拍に合わせてタップして測ることもできる */
@Composable
private fun BpmDialog(track: TrackEntity, onSave: (Double) -> Unit, onAuto: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var bpm by remember(track.id) { mutableDoubleStateOf(if (track.bpm > 0) track.bpm else 120.0) }
    val tap = remember(track.id) { TapTempo() }
    var playing by remember { mutableStateOf(false) }
    val player = remember(track.id) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(track.id) { onDispose { player.value?.release(); player.value = null } }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(track.title, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Stepper(
                    "%.0f BPM".format(bpm),
                    onMinus = { bpm = (bpm - 1).coerceAtLeast(40.0) },
                    onPlus = { bpm = (bpm + 1).coerceAtMost(240.0) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("半分", Modifier.weight(1f)) { bpm = (bpm / 2).coerceAtLeast(40.0) }
                    SmallButton("倍", Modifier.weight(1f)) { bpm = (bpm * 2).coerceAtMost(240.0) }
                }
                SmallButton(if (playing) "曲を止める" else "曲を流す", Modifier.fillMaxWidth()) {
                    if (playing) {
                        player.value?.release(); player.value = null; playing = false
                    } else runCatching {
                        val mp = MediaPlayer()
                        mp.setDataSource(context, Uri.parse(track.uri))
                        mp.setOnCompletionListener { it.release(); player.value = null; playing = false }
                        mp.prepare()
                        mp.start()
                        player.value = mp
                        playing = true
                    }
                }
                BigButton("拍に合わせて、ここをタップ", Modifier.fillMaxWidth()) {
                    tap.tap(SystemClock.elapsedRealtime())?.let { bpm = Math.round(it).toDouble().coerceIn(40.0, 240.0) }
                }
                Text("曲を流しながら、4回以上タップすると BPM が出ます。", color = Palette.dim, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(bpm) }) { Text("この BPM にする") } },
        dismissButton = {
            Row {
                TextButton(onClick = onAuto) { Text("自動で調べ直す") }
                TextButton(onClick = onClose) { Text("やめる") }
            }
        },
    )
}
