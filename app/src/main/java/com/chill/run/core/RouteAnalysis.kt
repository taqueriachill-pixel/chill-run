package com.chill.run.core

data class LatLon(val lat: Double, val lon: Double)

/** 走り終わったルートを調べる：坂の割合、トレイルかどうか、同じコースかどうか */
object RouteAnalysis {

    /** 勾配が grade 以上の坂（登りも下りも）を通った距離の割合（0〜1） */
    fun steepFraction(dist: DoubleArray, alt: DoubleArray, windowM: Double = 30.0, grade: Double = 0.10): Double {
        if (dist.size < 2) return 0.0
        var steep = 0.0
        var i0 = 0
        for (j in 1 until dist.size) {
            val d = dist[j] - dist[i0]
            if (d >= windowM) {
                if (Math.abs(alt[j] - alt[i0]) / d >= grade) steep += d
                i0 = j
            }
        }
        val total = dist.last() - dist.first()
        return if (total > 0) steep / total else 0.0
    }

    /** トレイルの候補かどうか。1km あたりの登りと、坂の割合の両方が目安以上なら候補 */
    fun looksLikeTrail(distanceM: Double, gainM: Double, steepFraction: Double, gainPerKm: Double, steepPercent: Double): Boolean {
        if (distanceM < 500) return false
        return gainM / (distanceM / 1000.0) >= gainPerKm && steepFraction * 100.0 >= steepPercent
    }

    /** ルートを stepM ごとの点に間引く（形の比べやすさのため） */
    fun resample(lat: DoubleArray, lon: DoubleArray, dist: DoubleArray, stepM: Double = 50.0): List<LatLon> {
        val out = ArrayList<LatLon>()
        if (lat.isEmpty()) return out
        var next = dist[0]
        for (i in lat.indices) {
            if (dist[i] >= next) {
                out += LatLon(lat[i], lon[i])
                next = dist[i] + stepM
            }
        }
        val last = LatLon(lat.last(), lon.last())
        if (out.last() != last) out += last
        return out
    }

    fun encode(shape: List<LatLon>): String =
        shape.joinToString(";") { "%.5f,%.5f".format(java.util.Locale.US, it.lat, it.lon) }

    fun decode(text: String): List<LatLon> =
        if (text.isBlank()) emptyList() else text.split(';').mapNotNull { p ->
            val parts = p.split(',')
            val la = parts.getOrNull(0)?.toDoubleOrNull()
            val lo = parts.getOrNull(1)?.toDoubleOrNull()
            if (la != null && lo != null) LatLon(la, lo) else null
        }

    /** a の点のうち、b のルートの近く（nearM 以内）を通っている割合 */
    fun overlap(a: List<LatLon>, b: List<LatLon>, nearM: Double = 60.0): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val near = a.count { p -> b.any { q -> Geo.distanceM(p.lat, p.lon, q.lat, q.lon) <= nearM } }
        return near.toDouble() / a.size
    }

    /** 同じコースとみなすか：スタートとゴールが近く、ルートの7割以上が重なる */
    fun sameCourse(a: List<LatLon>, b: List<LatLon>): Boolean {
        if (a.size < 4 || b.size < 4) return false
        val startNear = Geo.distanceM(a.first().lat, a.first().lon, b.first().lat, b.first().lon) <= 200
        val endNear = Geo.distanceM(a.last().lat, a.last().lon, b.last().lat, b.last().lon) <= 200
        if (!startNear || !endNear) return false
        return overlap(a, b) >= 0.7 && overlap(b, a) >= 0.7
    }

    /** 一覧に出す小さな標高グラフ用に、標高を n 個の値にまとめる */
    fun elevationProfile(dist: DoubleArray, alt: DoubleArray, n: Int = 40): List<Float> {
        if (dist.size < 2) return emptyList()
        val total = dist.last() - dist.first()
        if (total <= 0) return emptyList()
        val out = ArrayList<Float>(n)
        var j = 0
        for (k in 0 until n) {
            val target = dist.first() + total * k / (n - 1)
            while (j < dist.size - 1 && dist[j] < target) j++
            out += alt[j].toFloat()
        }
        return out
    }

    fun encodeProfile(values: List<Float>): String = values.joinToString(",") { "%.1f".format(java.util.Locale.US, it) }
    fun decodeProfile(text: String): List<Float> = if (text.isBlank()) emptyList() else text.split(',').mapNotNull { it.toFloatOrNull() }
}
