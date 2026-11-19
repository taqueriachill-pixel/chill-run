package com.chill.run.data

import android.content.Context
import androidx.room.withTransaction
import com.chill.run.core.MPoint
import com.chill.run.core.RunMerge
import com.chill.run.core.RunTracker

/**
 * ランニングマシンの記録を、マシンに表示された距離に合わせて直す。
 * 歩数から出した距離を、ぜんぶ同じ割合でのばす（ちぢめる）。1km ごとの区切りと自己ベストは作り直す。
 * 直した割合は覚えておき、次からの歩幅の見積もりに少しずつ反映する。
 */
object IndoorFix {
    suspend fun apply(context: Context, runId: Long, machineKm: Double): Double {
        val db = AppDb.get(context)
        val dao = db.runDao()
        val run = dao.getRun(runId) ?: error("記録が見つかりません")
        require(run.type == "TREADMILL") { "ランニングマシンの記録ではありません" }
        require(run.distanceM > 10 && machineKm in 0.1..100.0) { "距離が正しくありません" }
        val ratio = machineKm * 1000.0 / run.distanceM
        db.withTransaction {
            val pts = dao.points(runId)
            val fixed = pts.map { it.copy(id = 0, distM = it.distM * ratio) }
            dao.deletePoints(runId)
            dao.insertPoints(fixed)
            dao.deleteSplits(runId)
            dao.insertSplits(RunMerge.splits(fixed.map { MPoint(it.time, it.distM, it.movingMs, it.alt, it.hr, it.cadence) }).map {
                SplitEntity(runId = runId, idx = it.idx, distanceM = it.distanceM, movingMs = it.movingMs, gainM = 0.0, lossM = 0.0, steps = it.steps, avgHr = it.avgHr)
            })
            val settings = Settings(context)
            dao.updateRun(
                run.copy(
                    distanceM = machineKm * 1000.0,
                    calories = RunTracker.calories(settings.weightKg.toDouble(), machineKm * 1000.0, 0.0),
                    note = listOf(run.note, "距離はマシンの表示（${"%.2f".format(machineKm)}km）に合わせました").filter { it.isNotBlank() }.joinToString("\n"),
                )
            )
            // 次からの歩幅：直した割合の半分だけ寄せる（1回の入れまちがいで大きくずれないように）
            settings.indoorStrideFactor = settings.indoorStrideFactor * (1 + (ratio - 1) * 0.5)
        }
        runCatching { PostRun.bests(context, runId) }
        return ratio
    }
}
