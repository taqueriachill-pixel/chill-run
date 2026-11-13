package com.chill.run.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chill.run.core.Fmt
import com.chill.run.core.Review
import com.chill.run.data.AppDb
import com.chill.run.data.CourseEntity
import com.chill.run.data.HrSplit
import com.chill.run.data.RunEntity
import com.chill.run.health.Health
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private class ReviewData(
    val runs: List<RunEntity>,
    val courses: List<CourseEntity>,
    val hrSplits: List<HrSplit>,
    val weight: List<Pair<Long, Double>>,
    val fat: List<Pair<Long, Double>>,
)

private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** ふりかえり：カレンダー、週・月のまとめ、自己ベスト、体の変化、同じペースでの心拍数の変化 */
@Composable
fun ReviewScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    var data by remember { mutableStateOf<ReviewData?>(null) }
    var month by remember { mutableStateOf(YearMonth.now()) }

    LaunchedEffect(Unit) {
        val zone = ZoneId.systemDefault()
        val lastMonthStart = YearMonth.now().minusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val (runs, courses, splits) = withContext(Dispatchers.IO) {
            val dao = AppDb.get(context).runDao()
            Triple(dao.finishedRuns(), dao.courses(), dao.hrSplitsSince(lastMonthStart))
        }
        data = ReviewData(runs, courses, splits, emptyList(), emptyList())
        // 体重と体脂肪率は、ヘルスコネクトから（読めなければ空のまま）
        val (weight, fat) = Health.readBody(context, 120)
        data = ReviewData(runs, courses, splits, weight, fat)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("ふりかえり", onBack)
        val d = data
        if (d == null) {
            Text("読み込み中…", color = Palette.dim)
            return@Column
        }
        val today = LocalDate.now()
        val byDay = remember(d.runs) { d.runs.groupBy { dayOf(it.startTime) } }
        val thisMonthRuns = d.runs.filter { YearMonth.from(dayOf(it.startTime)) == YearMonth.now() }
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisWeekRuns = d.runs.filter { !dayOf(it.startTime).isBefore(weekStart) }

        // 続けたことをほめる
        Card {
            Text(Review.cheer(thisMonthRuns.size, thisWeekRuns.size), color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        // カレンダー
        Card {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SmallButton("← 前の月") { month = month.minusMonths(1) }
                Text("${month.year}年${month.monthValue}月", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                SmallButton("次の月 →", enabled = month < YearMonth.now()) { month = month.plusMonths(1) }
            }
            Row { listOf("月", "火", "水", "木", "金", "土", "日").forEach { Text(it, color = Palette.dim, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center) } }
            val first = month.atDay(1)
            val lead = first.dayOfWeek.value - 1
            val cells = lead + month.lengthOfMonth()
            for (week in 0 until (cells + 6) / 7) {
                Row {
                    for (dow in 0 until 7) {
                        val dayNumber = week * 7 + dow - lead + 1
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                            if (dayNumber in 1..month.lengthOfMonth()) {
                                val date = month.atDay(dayNumber)
                                val runs = byDay[date].orEmpty()
                                // 走った日は、ロードなら青、トレイルなら緑の丸
                                val color = when {
                                    runs.isEmpty() -> Color.Transparent
                                    runs.any { it.type == "TRAIL" } -> Palette.trail
                                    else -> Palette.road
                                }
                                Box(
                                    Modifier.fillMaxSize().clip(CircleShape).background(color)
                                        .clickable(enabled = runs.isNotEmpty()) { onOpen(runs.maxBy { it.distanceM }.id) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$dayNumber",
                                        color = if (runs.isNotEmpty()) Color.White else if (date == today) Palette.warn else Palette.dim,
                                        fontSize = 15.sp, fontWeight = if (runs.isNotEmpty() || date == today) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text("青＝ロード　緑＝トレイル　走った日を押すと、その記録が開きます", color = Palette.dim, fontSize = 12.sp)
        }

        // 月と週のまとめ
        val monthRuns = d.runs.filter { YearMonth.from(dayOf(it.startTime)) == month }
        SummaryCard("${month.monthValue}月のまとめ", monthRuns)
        if (month == YearMonth.now()) SummaryCard("今週のまとめ（月曜から）", thisWeekRuns)

        // 自己ベスト
        Card {
            SectionTitle("自己ベスト（押すと、その記録が開きます）")
            BestTime("1km の最速", d.runs.filter { it.best1kMs > 0 }.minByOrNull { it.best1kMs }, { it.best1kMs }, 1, onOpen)
            BestTime("5km の最速", d.runs.filter { it.best5kMs > 0 }.minByOrNull { it.best5kMs }, { it.best5kMs }, 5, onOpen)
            BestTime("10km の最速", d.runs.filter { it.best10kMs > 0 }.minByOrNull { it.best10kMs }, { it.best10kMs }, 10, onOpen)
            d.runs.maxByOrNull { it.distanceM }?.let { BestRow("いちばん長い距離", "${Fmt.km(it.distanceM)} km", it, onOpen) }
            d.runs.filter { it.gainM >= 1 }.maxByOrNull { it.gainM }?.let { BestRow("いちばん登った日", "+${Math.round(it.gainM)} m", it, onOpen) }
            if (d.runs.isEmpty()) Text("まだ記録がありません。", color = Palette.dim, fontSize = 14.sp)
        }
        if (d.courses.isNotEmpty()) {
            Card {
                SectionTitle("コースごとのベスト")
                d.courses.forEach { course ->
                    val runs = d.runs.filter { it.courseId == course.id && it.distanceM > 300 }
                    val best = runs.minByOrNull { avgPace(it) ?: Double.MAX_VALUE }
                    if (best != null) BestRow("${course.name}（${runs.size}回）", "${Fmt.pace(avgPace(best))}/km", best, onOpen)
                }
            }
        }

        // 同じペースでの心拍数の変化
        val zone = ZoneId.systemDefault()
        val monthStart = YearMonth.now().atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val paceHr = remember(d.hrSplits) {
            Review.samePaceHr(
                d.hrSplits.filter { it.startTime >= monthStart }.map { it.movingMs / 1000.0 to it.avgHr },
                d.hrSplits.filter { it.startTime < monthStart }.map { it.movingMs / 1000.0 to it.avgHr },
            )
        }
        Card {
            SectionTitle("同じペースでの心拍数の変化")
            if (paceHr == null) {
                Text("今月と先月の両方に、同じくらいのペースで心拍数を取れた 1km の記録が 2つ以上たまると、ここに出ます。", color = Palette.dim, fontSize = 14.sp)
            } else {
                val pace = Fmt.pace(paceHr.paceSecPerKm.toDouble())
                Text(
                    when {
                        paceHr.diff <= -2 -> "$pace/km で走ったときの平均の心拍数が、先月より ${-paceHr.diff} 下がった。同じペースが楽になってきている。"
                        paceHr.diff >= 2 -> "$pace/km で走ったときの平均の心拍数は、先月より ${paceHr.diff} 高い。暑さや疲れ、寝不足でも上がるので、気にしすぎなくて大丈夫。"
                        else -> "$pace/km で走ったときの平均の心拍数は、先月とほぼ同じ。"
                    },
                    color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Text("今月 ${paceHr.thisAvg}（${paceHr.thisCount}区間）／先月 ${paceHr.lastAvg}（${paceHr.lastCount}区間）", color = Palette.dim, fontSize = 13.sp)
            }
            Text("トレーニングの目安です。体調や病気の判断に使うものではありません。", color = Palette.dim, fontSize = 12.sp)
        }

        // 体の変化
        Card {
            SectionTitle("体の変化（7日間の平均の線。直近120日）")
            val weight = remember(d.weight) { Review.sevenDayAverage(d.weight.map { dayOf(it.first).toEpochDay() to it.second }) }
            val fat = remember(d.fat) { Review.sevenDayAverage(d.fat.map { dayOf(it.first).toEpochDay() to it.second }) }
            if (weight.isEmpty() && fat.isEmpty()) {
                Text(
                    "体重・体脂肪率が、ヘルスコネクトに見つかりませんでした。体組成計のアプリ（AIfit）をヘルスコネクトにつなぐと、ここに出ます。",
                    color = Palette.dim, fontSize = 14.sp,
                )
            } else {
                val fromDay = today.minusDays(119).toEpochDay()
                val weekly = remember(d.runs) {
                    d.runs.groupBy { dayOf(it.startTime).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay() }
                        .mapValues { e -> e.value.sumOf { it.distanceM } / 1000.0 }
                }
                if (weight.isNotEmpty()) TrendChart("体重", "kg", weight, weekly, fromDay, today.toEpochDay(), Palette.road)
                if (fat.isNotEmpty()) TrendChart("体脂肪率", "%", fat, weekly, fromDay, today.toEpochDay(), Palette.warn)
                Text("線＝7日間の平均　うしろの棒＝その週に走った距離", color = Palette.dim, fontSize = 12.sp)
                // 今月のひとこと。増えた・減ったを責めない言い方にする
                val monthKm = thisMonthRuns.sumOf { it.distanceM } / 1000.0
                val monthStartDay = YearMonth.now().atDay(1).toEpochDay()
                val fatStart = fat.lastOrNull { it.first <= monthStartDay } ?: fat.firstOrNull { it.first >= monthStartDay }
                val fatNow = fat.lastOrNull()
                val message = buildString {
                    append("今月は ${Math.round(monthKm)}km 走った。")
                    if (fatStart != null && fatNow != null && fatNow.first > fatStart.first) {
                        val diff = fatNow.second - fatStart.second
                        append(
                            if (Math.abs(diff) < 0.2) "体脂肪率は、月の初めとほぼ同じ。"
                            else "体脂肪率は、月の初めから %+.1f%%。".format(diff)
                        )
                    }
                    append("体は日によって上下するので、長い目で見よう。")
                }
                Text(message, color = Palette.text, fontSize = 15.sp)
            }
        }
        Gap(24)
    }
}

@Composable
private fun SummaryCard(title: String, runs: List<RunEntity>) {
    val road = runs.filter { it.type != "TRAIL" }.sumOf { it.distanceM } / 1000.0
    val trail = runs.filter { it.type == "TRAIL" }.sumOf { it.distanceM } / 1000.0
    Card {
        SectionTitle(title)
        Row { Stat("距離", "%.1f km".format(road + trail)); Stat("回数", "${runs.size} 回") }
        Row { Stat("獲得標高", "+${Math.round(runs.sumOf { it.gainM })} m"); Stat("走った時間", Fmt.clock(runs.sumOf { it.movingMs })) }
        // 距離を、ロード（青）とトレイル（緑）で色分け
        if (road + trail > 0) {
            Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(6.dp))) {
                if (road > 0) Box(Modifier.weight(road.toFloat()).fillMaxSize().background(Palette.road))
                if (trail > 0) Box(Modifier.weight(trail.toFloat()).fillMaxSize().background(Palette.trail))
            }
            Text("ロード %.1f km　トレイル %.1f km".format(road, trail), color = Palette.dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BestTime(label: String, run: RunEntity?, time: (RunEntity) -> Long, km: Int, onOpen: (Long) -> Unit) {
    if (run == null) return
    val ms = time(run)
    BestRow(label, Fmt.clock(ms) + if (km > 1) "（${Fmt.pace(ms / 1000.0 / km)}/km）" else "", run, onOpen)
}

@Composable
private fun BestRow(label: String, value: String, run: RunEntity, onOpen: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(run.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.of(run.type)))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(label, color = Palette.text, fontSize = 15.sp)
            Text(formatDate(run.startTime), color = Palette.dim, fontSize = 12.sp)
        }
        Text(value, color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** 7日平均の線と、うしろに週ごとの距離の棒を重ねたグラフ */
@Composable
private fun TrendChart(
    title: String,
    unit: String,
    points: List<Pair<Long, Double>>,
    weeklyKm: Map<Long, Double>,
    fromDay: Long,
    toDay: Long,
    color: Color,
) {
    val shown = points.filter { it.first in fromDay..toDay }
    if (shown.isEmpty()) return
    val lo = shown.minOf { it.second }
    val hi = shown.maxOf { it.second }
    // 少しの変化が大げさに見えないよう、縦の幅は最低でも 2（kg または %）取る
    val range = (hi - lo).coerceAtLeast(2.0)
    val base = lo - (range - (hi - lo)) / 2
    val latest = shown.last().second
    Text("$title　いま %.1f %s（7日平均）".format(latest, unit), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val span = (toDay - fromDay).coerceAtLeast(1).toFloat()
        fun x(day: Long) = (day - fromDay) / span * size.width
        // 週ごとの距離の棒
        val maxKm = weeklyKm.filterKeys { it + 6 >= fromDay && it <= toDay }.values.maxOrNull() ?: 0.0
        if (maxKm > 0) for ((weekStart, km) in weeklyKm) {
            if (weekStart + 6 < fromDay || weekStart > toDay) continue
            val h = (km / maxKm * size.height * 0.9).toFloat()
            val left = x(weekStart).coerceAtLeast(0f)
            val right = x(weekStart + 7).coerceAtMost(size.width)
            drawRect(Palette.line, Offset(left + 2f, size.height - h), Size((right - left - 4f).coerceAtLeast(1f), h))
        }
        val path = Path()
        shown.forEachIndexed { i, (day, v) ->
            val px = x(day)
            val py = (size.height - 6f - (v - base) / range * (size.height - 12f)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (shown.size == 1) drawCircle(color, 7f, Offset(x(shown[0].first), size.height / 2))
    }
    Text("%.1f 〜 %.1f %s".format(lo, hi, unit), color = Palette.dim, fontSize = 12.sp)
}
