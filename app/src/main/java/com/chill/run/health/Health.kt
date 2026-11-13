package com.chill.run.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.chill.run.data.AppDb
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

/** ヘルスコネクトとのやり取りをまとめたもの */
object Health {
    private val W_SESSION = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    private val W_ROUTE = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
    private val W_DISTANCE = HealthPermission.getWritePermission(DistanceRecord::class)
    private val W_ELEVATION = HealthPermission.getWritePermission(ElevationGainedRecord::class)
    private val W_CADENCE = HealthPermission.getWritePermission(StepsCadenceRecord::class)
    private val W_CALORIES = HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)

    val writePermissions: Set<String> = setOf(W_SESSION, W_ROUTE, W_DISTANCE, W_ELEVATION, W_CADENCE, W_CALORIES)

    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
    )

    val allPermissions: Set<String> = writePermissions + readPermissions

    fun available(context: Context): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    private fun client(context: Context) = HealthConnectClient.getOrCreate(context.applicationContext)

    suspend fun granted(context: Context): Set<String> =
        if (!available(context)) emptySet()
        else runCatching { client(context).permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    /**
     * ランをヘルスコネクトに書き込む。許可のある項目だけ書く。
     * 同じランを書き直しても2つにならないよう、ランごとに決まった番号（clientRecordId）を付ける。
     * @return 書き込めたら true
     */
    suspend fun writeRun(context: Context, runId: Long): Boolean {
        if (!available(context)) return false
        val granted = granted(context)
        if (W_SESSION !in granted) return false
        val dao = AppDb.get(context).runDao()
        val run = dao.getRun(runId) ?: return false
        if (run.endTime <= run.startTime) return false
        val points = dao.points(runId)

        val start = Instant.ofEpochMilli(run.startTime)
        val end = Instant.ofEpochMilli(run.endTime)
        val zone = ZoneId.systemDefault()
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)
        val device = Device(type = Device.TYPE_PHONE, manufacturer = Build.MANUFACTURER, model = Build.MODEL)
        val version = System.currentTimeMillis()
        fun meta(kind: String) = Metadata.activelyRecorded(
            device = device,
            clientRecordId = "chillrun-${run.startTime}-$kind",
            clientRecordVersion = version,
        )

        // ルート：時刻が開始〜終了の内側で、順に増えていく地点だけ（多すぎるときは間引く）
        var lastTime = run.startTime - 1
        val inRange = points.filter { p ->
            val ok = p.time >= run.startTime && p.time < run.endTime && p.time > lastTime
            if (ok) lastTime = p.time
            ok
        }
        val stride = (inRange.size / 4000) + 1
        val routePoints = inRange.filterIndexed { i, _ -> i % stride == 0 }
        val route = if (W_ROUTE in granted && routePoints.size >= 2) {
            ExerciseRoute(routePoints.map { p ->
                ExerciseRoute.Location(
                    time = Instant.ofEpochMilli(p.time),
                    latitude = p.lat,
                    longitude = p.lon,
                    horizontalAccuracy = Length.meters(p.accuracy.toDouble().coerceAtLeast(0.0)),
                    altitude = p.alt?.let { Length.meters(it + run.altOffset) },
                )
            })
        } else null

        val records = ArrayList<Record>()
        records += ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = startOffset,
            endTime = end,
            endZoneOffset = endOffset,
            metadata = meta("session"),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            title = if (run.type == "TRAIL") "トレイルラン（Chill Run）" else "ラン（Chill Run）",
            exerciseRoute = route,
        )
        if (W_DISTANCE in granted && run.distanceM > 0) {
            records += DistanceRecord(
                startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
                distance = Length.meters(run.distanceM), metadata = meta("distance"),
            )
        }
        if (W_ELEVATION in granted && run.gainM > 0) {
            records += ElevationGainedRecord(
                startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
                elevation = Length.meters(run.gainM), metadata = meta("elevation"),
            )
        }
        if (W_CALORIES in granted && run.calories > 0) {
            records += TotalCaloriesBurnedRecord(
                startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
                energy = Energy.kilocalories(run.calories), metadata = meta("calories"),
            )
        }
        if (W_CADENCE in granted) {
            var lastSample = 0L
            val samples = inRange.mapNotNull { p ->
                val c = p.cadence
                if (c != null && c in 1..400 && p.time - lastSample >= 10_000) {
                    lastSample = p.time
                    StepsCadenceRecord.Sample(time = Instant.ofEpochMilli(p.time), rate = c.toDouble())
                } else null
            }
            if (samples.isNotEmpty()) {
                records += StepsCadenceRecord(
                    startTime = start, startZoneOffset = startOffset, endTime = end, endZoneOffset = endOffset,
                    samples = samples, metadata = meta("cadence"),
                )
            }
        }
        client(context).insertRecords(records)
        dao.updateRun(run.copy(hcSynced = true))
        return true
    }

    /** いちばん新しい体重（kg）。なければ null */
    suspend fun latestWeightKg(context: Context): Double? {
        if (!available(context)) return null
        return runCatching {
            val now = Instant.now()
            client(context).readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minusSeconds(90L * 86400), now),
                    ascendingOrder = false,
                    pageSize = 1,
                )
            ).records.firstOrNull()?.weight?.inKilograms
        }.getOrNull()
    }

    /** ある時間帯の心拍数（時刻ミリ秒 と 拍/分）を、古い順に返す。読めなければ空 */
    suspend fun readHeartRate(context: Context, startMs: Long, endMs: Long): List<Pair<Long, Int>> {
        if (!available(context)) return emptyList()
        return runCatching {
            readAll(context, HeartRateRecord::class, Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs))
                .flatMap { r -> r.samples.map { it.time.toEpochMilli() to it.beatsPerMinute.toInt() } }
                .filter { it.second in 30..240 }
                .sortedBy { it.first }
        }.getOrDefault(emptyList())
    }

    /** いちばん新しい安静時の心拍数。なければ null */
    suspend fun latestRestingHr(context: Context): Int? {
        if (!available(context)) return null
        return runCatching {
            val now = Instant.now()
            client(context).readRecords(
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minusSeconds(30L * 86400), now),
                    ascendingOrder = false,
                    pageSize = 1,
                )
            ).records.firstOrNull()?.beatsPerMinute?.toInt()
        }.getOrNull()
    }

    /** ふりかえり用：体重（kg）と体脂肪率（%）を、時刻（ミリ秒）つきで返す。読めなければ空 */
    suspend fun readBody(context: Context, days: Long): Pair<List<Pair<Long, Double>>, List<Pair<Long, Double>>> {
        if (!available(context)) return emptyList<Pair<Long, Double>>() to emptyList()
        val to = Instant.now()
        val from = to.minusSeconds(days * 86400)
        val weight = runCatching {
            readAll(context, WeightRecord::class, from, to).map { it.time.toEpochMilli() to it.weight.inKilograms }
        }.getOrDefault(emptyList())
        val fat = runCatching {
            readAll(context, BodyFatRecord::class, from, to).map { it.time.toEpochMilli() to it.percentage.value }
        }.getOrDefault(emptyList())
        return weight to fat
    }

    private suspend fun <T : Record> readAll(context: Context, type: KClass<T>, from: Instant, to: Instant, max: Int = 20000): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val res = client(context).readRecords(
                ReadRecordsRequest(recordType = type, timeRangeFilter = TimeRangeFilter.between(from, to), pageToken = token)
            )
            out += res.records
            token = res.pageToken
        } while (token != null && out.size < max)
        return out
    }

    private val timeFmt = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault())
    private fun t(i: Instant) = timeFmt.format(i)

    /** 実機チェック：ヘルスコネクトに何がどれくらい入っているかを文章にまとめる */
    suspend fun diagnose(context: Context, days: Long = 30): String {
        if (!available(context)) return "ヘルスコネクトが使えません。"
        val to = Instant.now()
        val from = to.minusSeconds(days * 86400)
        val sb = StringBuilder()
        sb.appendLine("【ヘルスコネクトの中身（直近${days}日）】")
        val granted = granted(context)
        sb.appendLine("許可：${granted.intersect(allPermissions).size}/${allPermissions.size} 個")
        sb.appendLine()

        suspend fun section(title: String, block: suspend () -> String) {
            sb.appendLine("■ $title")
            val text = try {
                block()
            } catch (e: SecurityException) {
                "  読む許可がありません"
            } catch (e: Exception) {
                "  読めませんでした：${e.javaClass.simpleName} ${e.message ?: ""}"
            }
            sb.appendLine(text)
            sb.appendLine()
        }

        section("心拍数") {
            val recs = readAll(context, HeartRateRecord::class, from, to)
            if (recs.isEmpty()) "  データなし"
            else recs.groupBy { it.metadata.dataOrigin.packageName }.map { (origin, list) ->
                val samples = list.flatMap { it.samples }.sortedBy { it.time }
                val gaps = samples.zipWithNext { a, b -> b.time.epochSecond - a.time.epochSecond }.filter { it > 0 }.sorted()
                val median = if (gaps.isEmpty()) null else gaps[gaps.size / 2]
                val shortGaps = gaps.count { it <= 10 }
                val bpm = samples.map { it.beatsPerMinute }
                buildString {
                    appendLine("  書いたアプリ：$origin")
                    appendLine("  記録 ${list.size} 件、測定値 ${samples.size} 個")
                    if (samples.isNotEmpty()) {
                        appendLine("  期間：${t(samples.first().time)} 〜 ${t(samples.last().time)}")
                        appendLine("  値の範囲：${bpm.min()}〜${bpm.max()} 拍/分")
                        appendLine("  間隔（まん中の値）：${median?.let { fmtGap(it) } ?: "不明"}、10秒以内の間隔 ${shortGaps} 回")
                    }
                }.trimEnd()
            }.joinToString("\n")
        }
        section("安静時の心拍数") {
            val recs = readAll(context, RestingHeartRateRecord::class, from, to)
            summarize(recs, { it.time }) { "${it.beatsPerMinute} 拍/分" }
        }
        section("体重") {
            val recs = readAll(context, WeightRecord::class, from, to)
            summarize(recs, { it.time }) { "%.1f kg".format(it.weight.inKilograms) }
        }
        section("体脂肪率") {
            val recs = readAll(context, BodyFatRecord::class, from, to)
            summarize(recs, { it.time }) { "%.1f %%".format(it.percentage.value) }
        }
        section("除脂肪体重（筋肉量に近い値）") {
            val recs = readAll(context, LeanBodyMassRecord::class, from, to)
            summarize(recs, { it.time }) { "%.1f kg".format(it.mass.inKilograms) }
        }
        section("骨量") {
            val recs = readAll(context, BoneMassRecord::class, from, to)
            summarize(recs, { it.time }) { "%.1f kg".format(it.mass.inKilograms) }
        }
        section("体水分量") {
            val recs = readAll(context, BodyWaterMassRecord::class, from, to)
            summarize(recs, { it.time }) { "%.1f kg".format(it.mass.inKilograms) }
        }
        section("基礎代謝") {
            val recs = readAll(context, BasalMetabolicRateRecord::class, from, to)
            summarize(recs, { it.time }) { "%.0f kcal/日".format(it.basalMetabolicRate.inKilocaloriesPerDay) }
        }
        section("運動の記録（同じランが二重にならないかの確認用）") {
            val recs = readAll(context, ExerciseSessionRecord::class, from, to)
            if (recs.isEmpty()) "  データなし"
            else recs.groupBy { it.metadata.dataOrigin.packageName }.map { (origin, list) ->
                val last = list.maxBy { it.startTime }
                "  書いたアプリ：$origin\n  ${list.size} 件、最新 ${t(last.startTime)}〜${t(last.endTime)}（種類番号 ${last.exerciseType}、${last.title ?: "題なし"}）"
            }.joinToString("\n")
        }
        return sb.toString().trimEnd()
    }

    private fun fmtGap(sec: Long): String = if (sec < 120) "${sec}秒" else "${sec / 60}分"

    private fun <T : Record> summarize(recs: List<T>, time: (T) -> Instant, value: (T) -> String): String {
        if (recs.isEmpty()) return "  データなし"
        return recs.groupBy { it.metadata.dataOrigin.packageName }.map { (origin, list) ->
            val last = list.maxBy { time(it) }
            "  書いたアプリ：$origin\n  ${list.size} 件、最新 ${value(last)}（${t(time(last))}）"
        }.joinToString("\n")
    }
}
