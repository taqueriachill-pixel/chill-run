package com.chill.run.data

import android.content.Context
import com.chill.run.core.RouteAnalysis

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
            val steep = if (hasAlt) RouteAnalysis.steepFraction(dist, alt) else 0.0
            val looksTrail = hasAlt && RouteAnalysis.looksLikeTrail(
                run.distanceM, run.gainM, steep, settings.trailGainPerKm.toDouble(), settings.trailSteepPercent.toDouble(),
            )
            val suggestion = when {
                looksTrail && run.type == "ROAD" -> "TRAIL"
                !looksTrail && hasAlt && run.type == "TRAIL" && run.distanceM >= 500 -> "ROAD"
                else -> ""
            }
            run.copy(typeSuggestion = suggestion)
        }
        dao.updateRun(run)
    }

    /** 段階1 のころの記録など、まだ調べていないランをまとめて調べる（古い順） */
    suspend fun backfill(context: Context) {
        val dao = AppDb.get(context).runDao()
        dao.finishedRuns().filter { it.shape.isEmpty() }.sortedBy { it.startTime }.forEach {
            runCatching { analyze(context, it.id) }
        }
    }
}
