package com.chill.run.data

import android.content.Context
import androidx.room.withTransaction
import com.chill.run.health.Health

/**
 * 走り終わってから、ヘルスコネクトに入っている心拍数（Zepp などが書いたもの）を、
 * ランの時間帯だけ取り込んで記録に付ける。リアルタイムで受け取れなかったときの方法。
 */
object HrImport {
    /** @return 心拍数を付けられた地点の数。0 なら、その時間帯の心拍数が見つからなかった */
    suspend fun apply(context: Context, runId: Long): Int {
        val db = AppDb.get(context)
        val dao = db.runDao()
        val run = dao.getRun(runId) ?: return 0
        val points = dao.points(runId)
        if (points.isEmpty()) return 0
        val samples = Health.readHeartRate(context, run.startTime - 60_000, run.endTime + 60_000)
        if (samples.isEmpty()) return 0

        // 地点ごとに、時刻がいちばん近い心拍数（30秒以内）を付ける
        val assigned = arrayOfNulls<Int>(points.size)
        var j = 0
        for (i in points.indices) {
            val t = points[i].time
            while (j < samples.size - 1 && Math.abs(samples[j + 1].first - t) <= Math.abs(samples[j].first - t)) j++
            if (Math.abs(samples[j].first - t) <= 30_000) assigned[i] = samples[j].second
        }
        val values = assigned.filterNotNull()
        if (values.isEmpty()) return 0

        val splits = dao.splits(runId)
        db.withTransaction {
            for (i in points.indices) dao.setPointHr(points[i].id, assigned[i])
            // 1km ごとの平均
            var from = 0.0
            for (s in splits) {
                val to = from + s.distanceM
                val inSplit = points.indices.filter { points[it].distM > from && points[it].distM <= to }.mapNotNull { assigned[it] }
                dao.updateSplit(s.copy(avgHr = if (inSplit.isEmpty()) 0 else Math.round(inSplit.average()).toInt()))
                from = to
            }
            dao.updateRun(
                dao.getRun(runId)!!.copy(avgHr = Math.round(values.average()).toInt(), maxHr = values.max(), hrSource = "ヘルスコネクト")
            )
        }
        return values.size
    }
}
