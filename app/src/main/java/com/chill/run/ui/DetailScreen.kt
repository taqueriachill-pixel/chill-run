package com.chill.run.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.AnnounceText
import com.chill.run.core.Fmt
import com.chill.run.core.HeartZones
import com.chill.run.core.PhaseKind
import com.chill.run.core.Sky
import com.chill.run.core.TuneMood
import com.chill.run.data.AppDb
import com.chill.run.data.CourseEntity
import com.chill.run.data.HrImport
import com.chill.run.data.LapEntity
import com.chill.run.data.MediaEntity
import com.chill.run.data.MediaFiles
import com.chill.run.data.PointEntity
import com.chill.run.data.RunEntity
import com.chill.run.data.Settings
import com.chill.run.data.SplitEntity
import com.chill.run.data.deleteRun
import com.chill.run.health.Health
import com.chill.run.movie.MovieMaker
import com.chill.run.run.Weather
import com.chill.run.speech.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class DetailData(
    val run: RunEntity?,
    val points: List<PointEntity>,
    val splits: List<SplitEntity>,
    val laps: List<LapEntity>,
    val course: CourseEntity?,
    val courseRuns: List<RunEntity>,
    val media: List<MediaEntity>,
)

@Composable
fun DetailScreen(runId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { AppDb.get(context).runDao() }
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<DetailData?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(runId, reload) {
        data = withContext(Dispatchers.IO) {
            val r = dao.getRun(runId)
            val course = r?.courseId?.takeIf { it > 0 }?.let { dao.course(it) }
            DetailData(
                run = r,
                points = dao.points(runId),
                splits = dao.splits(runId),
                laps = dao.laps(runId),
                course = course,
                courseRuns = if (course != null) dao.runsOfCourse(course.id) else emptyList(),
                media = dao.media(runId),
            )
        }
    }

    val settings = remember { Settings(context) }
    var declinedMax by remember { mutableIntStateOf(settings.maxHrDeclined) }
    var hrImportMessage by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<MediaEntity?>(null) }
    var movieProgress by remember { mutableStateOf<Int?>(null) }
    var movieMessage by remember { mutableStateOf<String?>(null) }
    var editingMemo by remember { mutableStateOf(false) }
    var busyMemo by remember { mutableStateOf(0L) }
    var memoMessage by remember { mutableStateOf<String?>(null) }

    var makeMovie by remember { mutableStateOf(false) }
    LaunchedEffect(makeMovie) {
        if (!makeMovie) return@LaunchedEffect
        movieMessage = null
        movieProgress = 0
        val result = MovieMaker.make(context, runId) { movieProgress = it }
        movieProgress = null
        makeMovie = false
        movieMessage = result.fold({ "できました。「見る・共有する」から、写真フォルダや SNS に出せます。" }, { "作れませんでした：${it.message}" })
        reload++
    }

    // まだ文字にしていない声のメモを、1つずつ文字にする（走り終わってからまとめて）
    val pendingMemos = data?.media?.count { it.kind == MediaFiles.MEMO && it.textState == 0 } ?: 0
    LaunchedEffect(runId, pendingMemos) {
        val list = data?.media?.filter { it.kind == MediaFiles.MEMO && it.textState == 0 }.orEmpty()
        if (list.isEmpty()) return@LaunchedEffect
        if (!Transcriber.available(context)) {
            memoMessage = "このスマホには端末内の音声認識がないため、自動では文字にできません。録音は再生できます。"
            return@LaunchedEffect
        }
        if (!Perms.has(context, android.Manifest.permission.RECORD_AUDIO)) {
            memoMessage = "文字にするには、マイクの許可が必要です（「許可の状態」で許可できます）。"
            return@LaunchedEffect
        }
        memoMessage = null
        for (m in list) {
            busyMemo = m.id
            val result = Transcriber.transcribe(context, MediaFiles.file(context, m.file))
            withContext(Dispatchers.IO) {
                dao.updateMedia(m.copy(text = result.getOrDefault(""), textState = if (result.isSuccess) 1 else 2))
            }
            if (result.isFailure) memoMessage = "文字にできなかったメモがあります：${result.exceptionOrNull()?.message}"
        }
        busyMemo = 0
        reload++
    }

    /** 種類を変える。コースに入っていれば、そのコースの種類として覚える */
    fun setType(r: RunEntity, course: CourseEntity?, type: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                dao.updateRun(r.copy(type = type, typeSuggestion = ""))
                if (course != null) dao.updateCourse(course.copy(type = type))
            }
            reload++
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("ランの記録", onBack)
        val d = data
        val r = d?.run
        if (d == null || r == null) {
            Text(if (d != null) "この記録は見つかりませんでした。" else "読み込み中…", color = Palette.dim)
            return@Column
        }
        val points = d.points
        val splits = d.splits
        val color = Palette.of(r.type)

        // ロードかトレイルかを大きく
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color).padding(16.dp)) {
            Text(
                if (r.type == "TRAIL") "トレイル・獲得標高 +${Math.round(r.gainM)}m" else "ロード",
                color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black,
            )
            Text(
                formatDate(r.startTime) + (d.course?.let { "　${it.name}" } ?: "") + if (r.goalKind == "INTERVAL") "　インターバル" else "",
                color = Color.White, fontSize = 15.sp,
            )
        }

        // 自動の判定が、選んだ種類と違ったとき
        if (r.typeSuggestion == "TRAIL" || r.typeSuggestion == "ROAD") {
            val toTrail = r.typeSuggestion == "TRAIL"
            Card {
                Text(
                    if (toTrail) "登りや坂が多いランでした。トレイルに変えますか？" else "登りや坂が少ないランでした。ロードに変えますか？",
                    color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton(
                        if (toTrail) "トレイルに変える" else "ロードに変える", Modifier.weight(1f),
                        color = if (toTrail) Palette.trail else Palette.road,
                    ) { setType(r, d.course, r.typeSuggestion) }
                    SmallButton("このまま", Modifier.weight(1f)) { setType(r, d.course, r.type) }
                }
            }
        }

        Card {
            Row { Stat("距離", "${Fmt.km(r.distanceM)} km"); Stat("タイム", Fmt.clock(r.movingMs)) }
            Row {
                Stat("平均ペース", "${Fmt.pace(avgPace(r))}/km")
                Stat("最速の1km", Fmt.pace(splits.filter { it.distanceM >= 999 }.minOfOrNull { it.movingMs / 1000.0 }) + "/km")
            }
            Row {
                Stat("ケイデンス", if (r.steps > 0 && r.movingMs > 0) "${Math.round(r.steps / (r.movingMs / 60000.0))} 歩/分" else "--")
                Stat("消費カロリー（目安）", "${Math.round(r.calories)} kcal")
            }
            Row { Stat("登り", "+${Math.round(r.gainM)} m"); Stat("下り", "-${Math.round(r.lossM)} m") }
            if (r.avgHr > 0) {
                Row { Stat("平均の心拍数", "${r.avgHr} 拍/分"); Stat("最大の心拍数", "${r.maxHr} 拍/分") }
            }
            Text(
                "標高：${r.elevationSource}" + if (r.startAltSource.isNotEmpty()) "（開始地点の合わせ方：${r.startAltSource}）" else "",
                color = Palette.dim, fontSize = 13.sp,
            )
            if (r.hrSource.isNotEmpty()) Text("心拍数：${r.hrSource}", color = Palette.dim, fontSize = 13.sp)
            if (r.note.isNotEmpty()) Text(r.note, color = Palette.warn, fontSize = 13.sp)
        }

        if (points.size >= 2) {
            Card {
                SectionTitle("地図")
                RouteMap(points, media = d.media, onMediaTap = { viewing = it })
            }
            Card {
                SectionTitle("グラフ（横軸は距離）")
                val withAlt = points.filter { it.alt != null }
                if (withAlt.size >= 2) {
                    val xs = DoubleArray(withAlt.size) { withAlt[it].distM }
                    val ys = DoubleArray(withAlt.size) { withAlt[it].alt!! + r.altOffset }
                    DistanceChart(
                        xs, ys, color,
                        topLabel = "標高　いちばん高い ${Math.round(ys.max())}m",
                        bottomLabel = "いちばん低い ${Math.round(ys.min())}m",
                        minRange = 30.0, filled = true,
                    )
                }
                val pace = remember(points) { paceSeries(points) }
                if (pace != null) {
                    DistanceChart(
                        pace.first, pace.second, Palette.warn,
                        topLabel = "ペース　速い ${Fmt.pace(pace.second.min())}",
                        bottomLabel = "遅い ${Fmt.pace(pace.second.max())}",
                        minRange = 60.0, inverted = true,
                    )
                }
                val withHr = points.filter { it.hr != null }
                if (withHr.size >= 2) {
                    val xs = DoubleArray(withHr.size) { withHr[it].distM }
                    val ys = DoubleArray(withHr.size) { withHr[it].hr!!.toDouble() }
                    DistanceChart(
                        xs, ys, Palette.danger,
                        topLabel = "心拍数　いちばん高い ${Math.round(ys.max())}",
                        bottomLabel = "いちばん低い ${Math.round(ys.min())}",
                        minRange = 30.0,
                    )
                }
            }
        }

        // 心拍ゾーンごとの時間
        val hrPoints = points.count { it.hr != null }
        if (hrPoints >= 2) {
            val zones = remember(points) { HeartZones(settings.maxHr, settings.restHr) }
            val zoneMs = remember(points) { zoneTimes(points, zones) }
            val total = zoneMs.sum().coerceAtLeast(1)
            Card {
                SectionTitle("心拍ゾーンごとの時間")
                for (z in 5 downTo 1) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("$z ${HeartZones.names[z]}", color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(1.5f))
                        Text(Fmt.clock(zoneMs[z]), color = Palette.text, fontSize = 14.sp, modifier = Modifier.weight(0.6f))
                        androidx.compose.foundation.layout.Box(Modifier.weight(1.4f)) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.fillMaxWidth((zoneMs[z].toFloat() / total).coerceIn(0.01f, 1f))
                                    .height(14.dp).clip(RoundedCornerShape(4.dp)).background(Palette.zone(z))
                            )
                        }
                    }
                }
                Text(
                    "ゾーンは、いまの設定（最大 ${zones.maxHr}・安静時 ${zones.restHr}）で計算しています。トレーニングの目安で、体調や病気の判断に使うものではありません。",
                    color = Palette.dim, fontSize = 12.sp,
                )
            }
            // これまでの最大より高い値が出たら、更新するかを聞く（一瞬だけの高い値は数えない）
            val sustained = remember(points) { sustainedMax(points) }
            if (sustained > settings.maxHr && sustained > declinedMax) {
                Card {
                    Text("最大心拍数を更新しますか？", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "このランで、設定している最大心拍数（${settings.maxHr}）より高い $sustained が続けて出ました。\n" +
                            "手首で測る心拍数は高く出てしまうこともあるので、いつもよりきつく感じた走りだったときだけ更新してください。",
                        color = Palette.dim, fontSize = 13.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("$sustained に更新する", Modifier.weight(1f), color = Palette.danger) {
                            settings.maxHrManual = sustained
                            declinedMax = sustained
                            reload++
                        }
                        SmallButton("しない", Modifier.weight(1f)) {
                            settings.maxHrDeclined = sustained
                            declinedMax = sustained
                        }
                    }
                }
            }
        }
        if (points.size >= 2 && (r.hrSource.isEmpty() || r.hrSource == "ヘルスコネクト")) {
            Card {
                SectionTitle("心拍数の取り込み")
                Text(
                    "走っている最中に心拍数を受け取れなかったときは、Zepp などがヘルスコネクトに書いた心拍数を、このランの時間帯だけ取り込めます。",
                    color = Palette.dim, fontSize = 13.sp,
                )
                SmallButton("ヘルスコネクトから心拍数を取り込む", Modifier.fillMaxWidth()) {
                    scope.launch {
                        val n = runCatching { withContext(Dispatchers.IO) { HrImport.apply(context, r.id) } }.getOrDefault(0)
                        hrImportMessage = if (n > 0) "取り込みました（$n 地点に心拍数を付けました）。"
                        else "この時間帯の心拍数が、ヘルスコネクトに見つかりませんでした。"
                        reload++
                    }
                }
                hrImportMessage?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
            }
        }

        // ひとことメモと、今日の体調（任意）
        Card {
            SectionTitle("今日の体調（任意）")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("つらい", "重い", "ふつう", "良い", "絶好調").forEachIndexed { i, label ->
                    Chip(label, r.condition == i + 1, Modifier.weight(1f), color = color) {
                        scope.launch {
                            // もう一度押すと、付けていない状態に戻る
                            withContext(Dispatchers.IO) { dao.setMemo(r.id, r.memo, if (r.condition == i + 1) 0 else i + 1) }
                            reload++
                        }
                    }
                }
            }
            SectionTitle("ひとことメモ")
            Text(r.memo.ifBlank { "（まだ書いていません）" }, color = if (r.memo.isBlank()) Palette.dim else Palette.text, fontSize = 16.sp)
            SmallButton(if (r.memo.isBlank()) "メモを書く" else "メモを直す", Modifier.fillMaxWidth()) { editingMemo = true }
        }

        // 10秒の「今日のランムービー」
        if (points.size >= 2) {
            val movie = d.media.lastOrNull { it.kind == MediaFiles.MOVIE }
            Card {
                SectionTitle("今日のランムービー（約10秒・縦長）")
                val progress = movieProgress
                if (progress != null) {
                    Text("作っています… $progress %（30秒〜1分ほどかかります。この画面を開いたままにしてください）", color = Palette.warn, fontSize = 15.sp)
                } else {
                    if (movie != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            MediaThumb(movie, Modifier.weight(0.6f)) { viewing = movie }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallButton("見る・共有する", Modifier.fillMaxWidth()) { viewing = movie }
                                SmallButton("作り直す", Modifier.fillMaxWidth()) { makeMovie = true }
                            }
                        }
                    } else {
                        BigButton("ランムービーを作る", Modifier.fillMaxWidth(), color = color) { makeMovie = true }
                    }
                    // 曲づくりの材料：天気と気温（自動で調べる。外れていたら手で直せる）
                    if (settings.movieMusic) {
                        val sky = TuneMood.skyOf(r.weatherCode)
                        val skyWord = when (sky) { Sky.SUNNY -> "晴れ"; Sky.CLOUDY -> "くもり"; Sky.RAIN -> "雨"; Sky.SNOW -> "雪"; Sky.UNKNOWN -> "分からない" }
                        val temp = r.tempC
                        Text(
                            "曲の材料　天気：$skyWord" +
                                (if (temp != null && !r.weatherManual) "　気温：${Math.round(temp)}℃" else "") +
                                (if (r.weatherManual) "（手で選んだ）" else if (r.weatherCode >= 0) "（自動）" else "（作るときに調べます）"),
                            color = Palette.text, fontSize = 14.sp,
                        )
                        fun setWeather(code: Int, t: Double?) {
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.setWeather(r.id, t, code, true) }
                                reload++
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("晴れ" to 0, "くもり" to 3, "雨" to 61, "雪" to 71).forEach { (label, code) ->
                                Chip(label, r.weatherManual && r.weatherCode == code, Modifier.weight(1f), color = color) { setWeather(code, r.tempC ?: 18.0) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("寒い" to 5.0, "ちょうどいい" to 18.0, "暑い" to 30.0).forEach { (label, t) ->
                                Chip(label, r.weatherManual && temp == t, Modifier.weight(1f), color = color) { setWeather(if (r.weatherCode >= 0) r.weatherCode else 3, t) }
                            }
                        }
                        if (r.weatherManual) {
                            SmallButton("天気を自動に戻す", Modifier.fillMaxWidth()) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { dao.setWeather(r.id, null, -1, false) }
                                    reload++
                                }
                            }
                        }
                        if (movie != null && movie.text.isNotBlank()) Text("いまの曲：${movie.text}", color = Palette.dim, fontSize = 13.sp)
                        Text(
                            "曲は、ロードかトレイルか・今日の体調（またはメモの言葉）・天気・気温・心拍数から、その場で作ります。「作り直す」たびに別の曲になります。${Weather.ATTRIBUTION}",
                            color = Palette.dim, fontSize = 12.sp,
                        )
                    }
                    Text(
                        "地図の上にルートが描かれ、写真・動画（最大4つ）と数字が続きます。" +
                            (if (settings.hideZone != null) "隠す場所の近くのルートは描きません。" else "自宅のまわりを描きたくないときは、設定の「隠す場所」で決められます。"),
                        color = Palette.dim, fontSize = 13.sp,
                    )
                }
                movieMessage?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
            }
        }

        // 走りながら残したもの
        val memos = d.media.filter { it.kind == MediaFiles.MEMO }
        val pictures = d.media.filter { it.kind == MediaFiles.PHOTO || it.kind == MediaFiles.VIDEO }
        if (memos.isNotEmpty()) {
            Card {
                SectionTitle("声のメモ")
                memoMessage?.let { Text(it, color = Palette.warn, fontSize = 13.sp) }
                memos.forEach { m ->
                    MemoRow(
                        media = m,
                        busy = busyMemo == m.id,
                        onEdit = { text ->
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.updateMedia(m.copy(text = text, textState = if (text.isNotBlank()) 1 else m.textState)) }
                                reload++
                            }
                        },
                        onRetry = {
                            scope.launch {
                                withContext(Dispatchers.IO) { dao.updateMedia(m.copy(text = "", textState = 0)) }
                                reload++
                            }
                        },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { MediaFiles.delete(context, m) }
                                reload++
                            }
                        },
                    )
                }
            }
        }
        if (pictures.isNotEmpty()) {
            Card {
                SectionTitle("写真・動画")
                pictures.chunked(3).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { m -> MediaThumb(m, Modifier.weight(1f)) { viewing = m } }
                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("このアプリの中だけに保存しています。写真フォルダや SNS に出したいときは、開いて「共有」を押してください。", color = Palette.dim, fontSize = 12.sp)
            }
        }

        // インターバルの区間ごとの記録
        if (d.laps.isNotEmpty()) {
            Card {
                SectionTitle("インターバルの区間")
                Row { Cell("区間", 1.6f, head = true); Cell("距離", 1f, head = true); Cell("時間", 1f, head = true); Cell("ペース", 1.2f, head = true) }
                d.laps.forEach { lap ->
                    val kind = runCatching { PhaseKind.valueOf(lap.kind) }.getOrDefault(PhaseKind.RUN)
                    val isRun = kind == PhaseKind.RUN
                    val pace = if (lap.distanceM > 5) lap.movingMs / 1000.0 / (lap.distanceM / 1000.0) else null
                    Row {
                        Cell(AnnounceText.phaseName(kind) + if (lap.rep > 0) " ${lap.rep}" else "", 1.6f, strong = isRun)
                        Cell("${Math.round(lap.distanceM)}m", 1f, strong = isRun)
                        Cell(Fmt.clock(lap.movingMs), 1f, strong = isRun)
                        Cell(Fmt.pace(pace), 1.2f, strong = isRun)
                    }
                }
            }
        }

        if (splits.isNotEmpty()) {
            Card {
                SectionTitle("1km ごと")
                Row {
                    Cell("km", 0.8f, head = true); Cell("ペース", 1.2f, head = true); Cell("登り", 1f, head = true)
                    Cell("下り", 1f, head = true); Cell("歩/分", 1f, head = true)
                    if (r.avgHr > 0) Cell("心拍", 1f, head = true)
                }
                splits.forEach { s ->
                    val full = s.distanceM >= 999
                    val pace = if (s.distanceM > 1) s.movingMs / 1000.0 / (s.distanceM / 1000.0) else null
                    Row {
                        Cell(if (full) "${s.idx}" else "%.2f".format(s.distanceM / 1000), 0.8f)
                        Cell(Fmt.pace(pace), 1.2f)
                        Cell("+${Math.round(s.gainM)}", 1f)
                        Cell("-${Math.round(s.lossM)}", 1f)
                        Cell(if (s.steps > 0 && s.movingMs > 0) "${Math.round(s.steps / (s.movingMs / 60000.0))}" else "--", 1f)
                        if (r.avgHr > 0) Cell(if (s.avgHr > 0) "${s.avgHr}" else "--", 1f)
                    }
                }
            }
        }

        // いつものコース
        Card {
            SectionTitle("いつものコース")
            val course = d.course
            if (course == null) {
                Text("同じルートをもう一度走ると、自動で「いつものコース」としてまとまります。", color = Palette.dim, fontSize = 14.sp)
            } else {
                val runs = d.courseRuns
                val nth = runs.indexOfFirst { it.id == r.id } + 1
                val best = runs.filter { it.distanceM > 300 }.minByOrNull { avgPace(it) ?: Double.MAX_VALUE }
                Text("${course.name}　${nth}回目（全部で ${runs.size} 回）", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (best != null) {
                    Text(
                        "このコースのベスト：${Fmt.pace(avgPace(best))}/km（${formatDate(best.startTime)}）" +
                            if (best.id == r.id && runs.size > 1) "　今回がベスト！" else "",
                        color = if (best.id == r.id && runs.size > 1) Palette.warn else Palette.text, fontSize = 14.sp,
                    )
                }
                if (course.type.isNotEmpty()) {
                    Text("このコースは、次からも自動で「${typeLabel(course.type)}」になります。", color = Palette.dim, fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallButton("名前を付ける", Modifier.weight(1f)) { renaming = true }
                    SmallButton("コースから外す", Modifier.weight(1f)) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                // shape を空にしないので、次に似たルートを走れば、また自動でまとまる
                                dao.setCourse(r.id, 0)
                                if (dao.runsOfCourse(course.id).isEmpty()) dao.deleteCourse(course.id)
                            }
                            reload++
                        }
                    }
                }
            }
        }

        Card {
            SectionTitle("ヘルスコネクト")
            Text(if (r.hcSynced) "書き込み済みです。" else "まだ書き込んでいません。", color = Palette.text, fontSize = 15.sp)
            SmallButton(if (r.hcSynced) "もう一度書き込む" else "ヘルスコネクトに書き込む", Modifier.fillMaxWidth()) {
                scope.launch {
                    val ok = runCatching { withContext(Dispatchers.IO) { Health.writeRun(context, r.id) } }
                    message = when {
                        ok.getOrNull() == true -> "ヘルスコネクトに書き込みました。"
                        ok.isFailure -> "書き込めませんでした：${ok.exceptionOrNull()?.message ?: "原因不明"}"
                        else -> "書き込めませんでした。「許可の状態」でヘルスコネクトの許可を確認してください。"
                    }
                    reload++
                }
            }
            message?.let { Text(it, color = Palette.warn, fontSize = 14.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton(if (r.type == "TRAIL") "ロードに変える" else "トレイルに変える", Modifier.weight(1f)) {
                setType(r, d.course, if (r.type == "TRAIL") "ROAD" else "TRAIL")
            }
            SmallButton("この記録を消す", Modifier.weight(1f)) { confirmDelete = true }
        }
        Gap(24)
    }

    // 地図の印や見本を押したとき：写真・動画は大きく表示、声のメモは内容を表示
    viewing?.let { m ->
        if (m.kind == MediaFiles.MEMO) {
            AlertDialog(
                onDismissRequest = { viewing = null },
                title = { Text("声のメモ　${whereWhen(m)}") },
                text = { Text(m.text.ifBlank { "（まだ文字になっていません。下の「声のメモ」の一覧から再生できます）" }) },
                confirmButton = { TextButton(onClick = { viewing = null }) { Text("閉じる") } },
            )
        } else {
            MediaViewer(
                media = m,
                onClose = { viewing = null },
                onDelete = {
                    viewing = null
                    scope.launch {
                        withContext(Dispatchers.IO) { MediaFiles.delete(context, m) }
                        reload++
                    }
                },
            )
        }
    }

    val currentRun = data?.run
    if (editingMemo && currentRun != null) {
        var text by remember(currentRun.id) { mutableStateOf(currentRun.memo) }
        AlertDialog(
            onDismissRequest = { editingMemo = false },
            title = { Text("ひとことメモ") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(500) }, minLines = 3, label = { Text("今日のランはどうだった？") }) },
            confirmButton = {
                TextButton(onClick = {
                    editingMemo = false
                    scope.launch {
                        withContext(Dispatchers.IO) { dao.setMemo(currentRun.id, text.trim(), currentRun.condition) }
                        reload++
                    }
                }) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { editingMemo = false }) { Text("やめる") } },
        )
    }

    val course = data?.course
    if (renaming && course != null) {
        var name by remember(course.id) { mutableStateOf(course.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("コースの名前") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true, label = { Text("例：生田緑地") }) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    val newName = name.trim()
                    if (newName.isNotEmpty()) scope.launch {
                        withContext(Dispatchers.IO) { dao.updateCourse(course.copy(name = newName)) }
                        reload++
                    }
                }) { Text("決定") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("やめる") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("この記録を消しますか？") },
            text = { Text("声のメモ・写真・動画も一緒に消えます。元に戻せません。（ヘルスコネクトに書き込んだ分は残ります）") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            dao.deleteRun(runId)
                            MediaFiles.deleteRunFiles(context, runId)
                        }
                        onBack()
                    }
                }) { Text("消す", color = Palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }
}

/** ゾーンごとの時間（ミリ秒）。地点と地点のあいだの時間を、その地点の心拍数のゾーンに足していく */
private fun zoneTimes(points: List<PointEntity>, zones: HeartZones): LongArray {
    val out = LongArray(6)
    for (i in 1 until points.size) {
        val hr = points[i].hr ?: continue
        val dt = (points[i].movingMs - points[i - 1].movingMs).coerceIn(0, 30_000)
        out[zones.zoneOf(hr)] += dt
    }
    return out
}

/** 続けて出た最大の心拍数（5地点続けてその値以上だったもの）。一瞬だけの高い値は数えない */
private fun sustainedMax(points: List<PointEntity>): Int {
    val hr = points.mapNotNull { it.hr }
    if (hr.size < 5) return 0
    var best = 0
    for (i in 0..hr.size - 5) {
        val low = minOf(hr[i], hr[i + 1], hr[i + 2], hr[i + 3], hr[i + 4])
        if (low > best) best = low
    }
    return best
}

/** 距離ごとのペース（秒/km）。前後をならして、止まっていた所は外す */
private fun paceSeries(points: List<PointEntity>): Pair<DoubleArray, DoubleArray>? {
    if (points.size < 12) return null
    val xs = ArrayList<Double>()
    val ys = ArrayList<Double>()
    val w = 6
    for (i in points.indices) {
        val a = points[(i - w).coerceAtLeast(0)]
        val b = points[(i + w).coerceAtMost(points.lastIndex)]
        val dd = b.distM - a.distM
        val dt = (b.movingMs - a.movingMs) / 1000.0
        if (dd < 5 || dt <= 0) continue
        xs += points[i].distM
        ys += (dt / (dd / 1000.0)).coerceIn(150.0, 1200.0)
    }
    return if (xs.size >= 2) xs.toDoubleArray() to ys.toDoubleArray() else null
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, head: Boolean = false, strong: Boolean = true) {
    Text(
        text,
        modifier = Modifier.weight(weight).padding(vertical = 4.dp),
        color = if (head || !strong) Palette.dim else Palette.text,
        fontSize = if (head) 13.sp else 16.sp,
        textAlign = TextAlign.Center,
    )
}
