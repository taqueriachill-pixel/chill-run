package com.chill.run.data

import android.content.Context
import androidx.room.withTransaction
import com.chill.run.core.MPoint
import com.chill.run.core.RunMerge
import java.io.File

/**
 * 2つの記録を1つにつなげる（「間違えて終了した」「休んでから続きを走った」ときのため）。
 * 早いほうの記録に、あとの記録の地点・区切り・メモ・写真・動画を足して、あとの記録は消す。
 * つなげたあと、ロードかトレイルか・コース・自己ベストなどは調べ直す。ランムービーは作り直しが必要。
 * @return 残した記録の番号
 */
object MergeRuns {
    /** つなげる相手の候補：前後12時間以内に終わった・始まった記録 */
    suspend fun candidates(context: Context, runId: Long): List<RunEntity> {
        val dao = AppDb.get(context).runDao()
        val run = dao.getRun(runId) ?: return emptyList()
        val window = 12 * 3600_000L
        return dao.finishedRuns().filter {
            it.id != runId && it.endTime > 0 &&
                (it.startTime in (run.startTime - window)..(run.endTime + window))
        }.sortedBy { it.startTime }
    }

    suspend fun merge(context: Context, idOne: Long, idTwo: Long): Long {
        val db = AppDb.get(context)
        val dao = db.runDao()
        val one = dao.getRun(idOne) ?: error("記録が見つかりません")
        val two = dao.getRun(idTwo) ?: error("記録が見つかりません")
        val (a, b) = if (one.startTime <= two.startTime) one to two else two to one

        db.withTransaction {
            val pa = dao.points(a.id)
            val pb = dao.points(b.id)
            // あとの記録の地点を、前の記録の続きにずらす（高さの合わせ方も前の記録にそろえる）
            val shifted = RunMerge.shift(
                pb.map { MPoint(it.time, it.distM, it.movingMs, it.alt, it.hr, it.cadence) },
                a.distanceM, a.movingMs, b.altOffset - a.altOffset,
            )
            val moved = pb.mapIndexed { i, p ->
                p.copy(id = 0, runId = a.id, distM = shifted[i].distM, movingMs = shifted[i].movingMs, alt = shifted[i].alt)
            }
            dao.deletePoints(b.id)
            dao.insertPoints(moved)

            // 1km ごとの区切りを作り直す
            val all = pa.map { MPoint(it.time, it.distM, it.movingMs, it.alt, it.hr, it.cadence) } + shifted
            dao.deleteSplits(a.id)
            dao.deleteSplits(b.id)
            dao.insertSplits(RunMerge.splits(all).map {
                SplitEntity(runId = a.id, idx = it.idx, distanceM = it.distanceM, movingMs = it.movingMs, gainM = it.gainM, lossM = it.lossM, steps = it.steps, avgHr = it.avgHr)
            })

            // インターバルの区間は、うしろに足す
            val lapsB = dao.laps(b.id)
            if (lapsB.isNotEmpty()) {
                val base = dao.laps(a.id).size
                dao.deleteLaps(b.id)
                dao.insertLaps(lapsB.map { it.copy(id = 0, runId = a.id, idx = base + it.idx) })
            }

            // メモ・写真・動画は、ファイルごと前の記録へ移す。ランムービーは中身が古くなるので消す
            for (m in dao.media(a.id) + dao.media(b.id)) {
                if (m.kind == MediaFiles.MOVIE) { MediaFiles.delete(context, m); continue }
                if (m.runId != b.id) continue
                val src = MediaFiles.file(context, m.file)
                val rel = "${a.id}/m${b.id}_${src.name}"
                val dst = MediaFiles.file(context, rel)
                dst.parentFile?.mkdirs()
                val ok = src.exists() && (src.renameTo(dst) || runCatching { src.copyTo(dst, overwrite = true); src.delete() }.isSuccess)
                dao.updateMedia(m.copy(runId = a.id, file = if (ok) rel else m.file))
            }

            dao.updateRun(
                a.copy(
                    endTime = maxOf(a.endTime, b.endTime),
                    distanceM = a.distanceM + b.distanceM,
                    movingMs = a.movingMs + b.movingMs,
                    gainM = a.gainM + b.gainM,
                    lossM = a.lossM + b.lossM,
                    steps = a.steps + b.steps,
                    calories = a.calories + b.calories,
                    avgHr = RunMerge.avgHr(a.avgHr, a.movingMs, b.avgHr, b.movingMs),
                    maxHr = maxOf(a.maxHr, b.maxHr),
                    hrSource = a.hrSource.ifEmpty { b.hrSource },
                    note = listOf(a.note, b.note).filter { it.isNotBlank() }.joinToString("\n"),
                    memo = listOf(a.memo, b.memo).filter { it.isNotBlank() }.joinToString("\n"),
                    condition = maxOf(a.condition, b.condition),
                    tempC = a.tempC ?: b.tempC,
                    weatherCode = if (a.weatherCode >= 0) a.weatherCode else b.weatherCode,
                    // ルートが変わるので、形・コース・未舗装の割合・種類の提案は調べ直す
                    shape = "", elevProfile = "", courseId = 0, typeSuggestion = "", unpavedFraction = null,
                )
            )
            dao.deleteRun(b.id)
        }
        File(File(context.filesDir, "media"), b.id.toString()).deleteRecursively()
        runCatching { PostRun.analyze(context, a.id) }
        runCatching { PostRun.bests(context, a.id) }
        return a.id
    }
}
