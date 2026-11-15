package com.chill.run.core

/** つなげるときに使う、ルートの1地点の数字 */
data class MPoint(val time: Long, val distM: Double, val movingMs: Long, val alt: Double?, val hr: Int?, val cadence: Int?)

/** つなげたあとに作り直す、1km ごとの区切り */
data class MSplit(val idx: Int, val distanceM: Double, val movingMs: Long, val gainM: Double, val lossM: Double, val steps: Int, val avgHr: Int)

/**
 * 2つの記録を1つにつなげる計算（「終了したけど、あとから続きを走った」ときのため）。
 * あとの記録の地点は、距離と動いていた時間を、前の記録の続きになるようにずらす。
 * 高さは、記録ごとに合わせ方（altOffset）が違うので、前の記録の合わせ方にそろえる。
 */
object RunMerge {
    /** あとの記録の地点を、前の記録の続きにずらす */
    fun shift(points: List<MPoint>, distM: Double, movingMs: Long, altShift: Double): List<MPoint> =
        points.map { it.copy(distM = it.distM + distM, movingMs = it.movingMs + movingMs, alt = it.alt?.plus(altShift)) }

    /**
     * 地点の並びから、1km ごとの区切りを作り直す。最後の 1km に満たない分も、50m 以上あれば区切りにする。
     * 登り・下りは、3m 以上の上下だけを数える（気圧の小さなゆれを数えないため）。
     */
    fun splits(points: List<MPoint>): List<MSplit> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<MSplit>()
        var idx = 1
        var startDist = points.first().distM
        var startMs = points.first().movingMs
        var gain = 0.0
        var loss = 0.0
        var steps = 0.0
        var hrSum = 0.0
        var hrMs = 0L
        var anchor = points.first().alt
        fun close(endDist: Double, endMs: Long) {
            out += MSplit(idx, endDist - startDist, endMs - startMs, gain, loss, Math.round(steps).toInt(), if (hrMs > 0) Math.round(hrSum / hrMs).toInt() else 0)
            idx++
            startDist = endDist; startMs = endMs
            gain = 0.0; loss = 0.0; steps = 0.0; hrSum = 0.0; hrMs = 0
        }
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dms = (b.movingMs - a.movingMs).coerceAtLeast(0)
            b.cadence?.let { steps += it * dms / 60000.0 }
            b.hr?.let { if (it > 0) { hrSum += it.toDouble() * dms; hrMs += dms } }
            b.alt?.let { alt ->
                val base = anchor
                if (base == null) anchor = alt
                else if (alt - base >= 3) { gain += alt - base; anchor = alt }
                else if (base - alt >= 3) { loss += base - alt; anchor = alt }
            }
            // 1km の切れ目をまたいだら、またいだ所までの時間を割り振って区切る
            while (b.distM - startDist >= 1000.0) {
                val target = startDist + 1000.0
                val span = (b.distM - a.distM).coerceAtLeast(1e-9)
                val f = ((target - a.distM) / span).coerceIn(0.0, 1.0)
                val ms = a.movingMs + Math.round(f * (b.movingMs - a.movingMs))
                close(target, ms)
            }
        }
        val last = points.last()
        if (last.distM - startDist >= 50.0) close(last.distM, last.movingMs)
        return out
    }

    /** 動いていた時間で重みをつけた、平均の心拍数 */
    fun avgHr(avgA: Int, msA: Long, avgB: Int, msB: Long): Int = when {
        avgA <= 0 -> avgB
        avgB <= 0 -> avgA
        else -> Math.round((avgA.toDouble() * msA + avgB.toDouble() * msB) / (msA + msB).coerceAtLeast(1)).toInt()
    }
}
