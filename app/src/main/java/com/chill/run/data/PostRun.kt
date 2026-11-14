package com.chill.run.data

import android.content.Context
import com.chill.run.core.Bests
import com.chill.run.core.RouteAnalysis
import com.chill.run.core.Surface

/**
 * 走り終わったあとの調べもの：一覧用の標高グラフ、ルートの形、
 * いつものコースへのまとめ、ロードかトレイルかの判定。
 */
object PostRun {
    suspend fun analyze(context: Context, runId: Long) {
        val dao = AppDb.get(context).runDao()
        var run = dao.getRun(runId) ?: return
        val points = dao.points(runId)
        if (points.size < 2) return
        val settings = Settings(context)

        val lat = DoubleArray(points.size) { points[it].lat }
        val lon = DoubleArray(points.size) { points[it].lon }
        val dist = DoubleArray(points.size) { points[it].distM }
        val hasAlt = points.count { it.alt != null } >= points.size / 2
        var lastAlt = points.firstNotNullOfOrNull { it.alt } ?: 0.0
        val alt = DoubleArray(points.size) { i ->
            points[i].alt?.let { lastAlt = it }
            lastAlt + run.altOffset
        }

        val shape = RouteAnalysis.resample(lat, lon, dist)
        val profile = if (hasAlt) RouteAnalysis.elevationProfile(dist, alt) else emptyList()
        run = run.copy(shape = RouteAnalysis.encode(shape), elevProfile = RouteAnalysis.encodeProfile(profile))

        // いつものコースをさがす。なければ、まだコースに入っていない過去のランと比べて、似ていれば新しいコースにする
        var course: CourseEntity? = null
        if (run.courseId > 0) course = dao.course(run.courseId)
        if (course == null) {
            val courses = dao.courses()
            course = courses.firstOrNull { RouteAnalysis.sameCourse(shape, RouteAnalysis.decode(it.shape)) }
            if (course == null) {
                val twin = dao.finishedRuns().firstOrNull {
                    it.id != runId && it.courseId == 0L && it.shape.isNotEmpty() &&
                        RouteAnalysis.sameCourse(shape, RouteAnalysis.decode(it.shape))
                }
                if (twin != null) {
                    val id = dao.insertCourse(CourseEntity(name = "コース${courses.size + 1}", shape = twin.shape))
                    dao.setCourse(twin.id, id)
                    course = dao.course(id)
                }
            }
        }
        run = run.copy(courseId = course?.id ?: 0)

        // ロードかトレイルか
        val courseType = course?.type.orEmpty()
        run = if (courseType.isNotEmpty()) {
            // 一度決めたコースは、次からも同じ種類にする
            run.copy(type = courseType, typeSuggestion = "")
        } else {
            run.copy(typeSuggestion = suggestion(run, if (hasAlt) RouteAnalysis.steepFraction(dist, alt) else null, settings))
        }
        dao.updateRun(run)
        bests(context, runId, points)
    }

    /** 3つの目安から、選んだ種類と違うときの提案を決める（同じなら空） */
    private fun suggestion(run: RunEntity, steep: Double?, settings: Settings): String {
        val unpaved = run.unpavedFraction?.takeIf { it >= 0 }
        val looksTrail = RouteAnalysis.looksLikeTrail(
            run.distanceM, run.gainM, steep, unpaved,
            settings.trailGainPerKm.toDouble(), settings.trailSteepPercent.toDouble(), settings.trailUnpavedPercent.toDouble(),
        ) ?: return ""
        return when {
            looksTrail && run.type == "ROAD" -> "TRAIL"
            !looksTrail && run.type == "TRAIL" && run.distanceM >= 500 -> "ROAD"
            else -> ""
        }
    }

    /**
     * 地図（OpenStreetMap）で、未舗装の道・登山道・遊歩道を通った割合を調べ、トレイルの判定をやり直す。
     * 通信が要るので、走り終わってから別に呼ぶ。隠す場所（自宅など）のまわりの点は、問い合わせに入れない。
     * @return 調べられたら true
     */
    suspend fun checkSurface(context: Context, runId: Long): Boolean {
        val settings = Settings(context)
        if (!settings.osmSurface) return false
        val dao = AppDb.get(context).runDao()
        val run = dao.getRun(runId) ?: return false
        if (run.unpavedFraction != null || run.distanceM < 500) return false
        val hide = settings.hideZone
        val shape = RouteAnalysis.decode(run.shape).filter { hide == null || !hide.hides(it.lat, it.lon) }
        if (shape.size < 4) return false
        // 長いランは、問い合わせが重くならないよう点を間引く（最大 300 点）
        val step = (shape.size + 299) / 300
        val route = shape.filterIndexed { i, _ -> i % step == 0 }
        val ways = com.chill.run.run.OsmApi.waysAlong(route) ?: return false
        val fraction = Surface.unpavedFraction(route, ways) ?: -1.0
        var updated = (dao.getRun(runId) ?: return false).copy(unpavedFraction = fraction)
        val course = if (updated.courseId > 0) dao.course(updated.courseId) else null
        // 走ってから6時間以内だけ、判定をやり直す（昔の記録で、あとから急に聞かないように）
        val fresh = System.currentTimeMillis() - updated.endTime < 6 * 3600_000L
        if (course?.type.isNullOrEmpty() && fresh) {
            val points = dao.points(runId)
            val hasAlt = points.count { it.alt != null } >= points.size / 2
            val steep = if (hasAlt && points.size >= 2) {
                var lastAlt = points.firstNotNullOfOrNull { it.alt } ?: 0.0
                val alt = DoubleArray(points.size) { i -> points[i].alt?.let { lastAlt = it }; lastAlt }
                RouteAnalysis.steepFraction(DoubleArray(points.size) { points[it].distM }, alt)
            } else null
            updated = updated.copy(typeSuggestion = suggestion(updated, steep, settings))
        }
        dao.updateRun(updated)
        return true
    }

    /** このランの中での、1km・5km・10km の最速を調べて保存する（自己ベスト用） */
    suspend fun bests(context: Context, runId: Long, loaded: List<PointEntity>? = null) {
        val dao = AppDb.get(context).runDao()
        val points = loaded ?: dao.points(runId)
        if (points.size < 2) return
        val dist = DoubleArray(points.size) { points[it].distM }
        val ms = LongArray(points.size) { points[it].movingMs }
        dao.setBests(
            runId,
            Bests.fastest(dist, ms, 1000.0) ?: 0,
            Bests.fastest(dist, ms, 5000.0) ?: 0,
            Bests.fastest(dist, ms, 10000.0) ?: 0,
        )
    }

    /** 段階1 のころの記録など、まだ調べていないランをまとめて調べる（古い順） */
    suspend fun backfill(context: Context) {
        val dao = AppDb.get(context).runDao()
        val runs = dao.finishedRuns().sortedBy { it.startTime }
        runs.filter { it.shape.isEmpty() }.forEach { runCatching { analyze(context, it.id) } }
        // 段階6 より前に記録したランの、1km などの最速を調べる
        runs.filter { it.shape.isNotEmpty() && it.best1kMs == 0L && it.distanceM >= 1000 }.forEach { runCatching { bests(context, it.id) } }
    }
}
