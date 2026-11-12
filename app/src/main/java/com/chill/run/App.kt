package com.chill.run

import android.app.Application
import com.chill.run.core.ElevationGain
import com.chill.run.core.RunTracker
import com.chill.run.data.AppDb
import com.chill.run.data.PointEntity
import com.chill.run.data.Settings
import com.chill.run.data.SplitEntity
import com.chill.run.data.deleteRun
import com.chill.run.run.RunHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    /** 画面や常駐サービスが閉じても、保存などを最後までやり切るための作業場 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.IO) { recoverUnfinishedRuns() }
    }

    /** アプリが途中で落ちたときの記録を、保存済みの地点から仕上げる */
    private suspend fun recoverUnfinishedRuns() {
        if (RunHub.state.value.active) return
        val dao = AppDb.get(this).runDao()
        val weight = Settings(this).weightKg.toDouble()
        for (run in dao.unfinishedRuns()) {
            if (RunHub.state.value.active) return
            val points = dao.points(run.id)
            val last = points.lastOrNull()
            if (last == null || last.distM < 20) {
                dao.deleteRun(run.id)
                continue
            }
            val gain = ElevationGain(if (run.elevationSource == "GPS") 6.0 else 2.5)
            points.forEach { p -> p.alt?.let(gain::add) }
            dao.insertSplits(splitsFromPoints(run.id, points))
            dao.updateRun(
                run.copy(
                    endTime = last.time,
                    distanceM = last.distM,
                    movingMs = last.movingMs,
                    gainM = gain.gain,
                    lossM = gain.loss,
                    calories = RunTracker.calories(weight, last.distM, gain.gain),
                    note = "（記録が途中で止まったため、保存できた地点までを残しました）",
                )
            )
        }
    }

    private fun splitsFromPoints(runId: Long, points: List<PointEntity>): List<SplitEntity> {
        val out = ArrayList<SplitEntity>()
        var startDist = 0.0
        var startMs = 0L
        for (p in points) {
            if (p.distM - startDist >= 1000.0) {
                out += SplitEntity(runId = runId, idx = out.size + 1, distanceM = 1000.0, movingMs = p.movingMs - startMs, gainM = 0.0, lossM = 0.0, steps = 0)
                startDist += 1000.0
                startMs = p.movingMs
            }
        }
        val last = points.last()
        if (last.distM - startDist >= 50) {
            out += SplitEntity(runId = runId, idx = out.size + 1, distanceM = last.distM - startDist, movingMs = last.movingMs - startMs, gainM = 0.0, lossM = 0.0, steps = 0)
        }
        return out
    }
}
