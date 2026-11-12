package com.chill.run.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.chill.run.core.PhaseKind
import com.chill.run.data.AppDb
import com.chill.run.data.CourseEntity
import com.chill.run.data.LapEntity
import com.chill.run.data.PointEntity
import com.chill.run.data.RunEntity
import com.chill.run.data.SplitEntity
import com.chill.run.data.deleteRun
import com.chill.run.health.Health
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
            )
        }
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
            Text(
                "標高：${r.elevationSource}" + if (r.startAltSource.isNotEmpty()) "（開始地点の合わせ方：${r.startAltSource}）" else "",
                color = Palette.dim, fontSize = 13.sp,
            )
            if (r.note.isNotEmpty()) Text(r.note, color = Palette.warn, fontSize = 13.sp)
        }

        if (points.size >= 2) {
            Card {
                SectionTitle("地図")
                RouteMap(points)
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
            text = { Text("消すと元に戻せません。（ヘルスコネクトに書き込んだ分は残ります）") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { dao.deleteRun(runId) }
                        onBack()
                    }
                }) { Text("消す", color = Palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }
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
