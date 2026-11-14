package com.chill.run.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** 地図タイル1枚の番号 */
data class Tile(val z: Int, val x: Int, val y: Int)

/**
 * 地図の計算（ウェブメルカトル）。世界全体を 0〜1 の正方形として扱い、
 * 画面に描くときに「タイル1枚の大きさ × 2^ズーム」を掛ける。
 */
object MapMath {
    const val MIN_ZOOM = 5
    const val MAX_ZOOM = 18

    fun lonToX(lon: Double): Double = (lon + 180.0) / 360.0

    fun latToY(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }

    fun xToLon(x: Double): Double = x * 360.0 - 180.0

    fun yToLat(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))

    /** 範囲が画面に収まる、いちばん大きいズーム */
    fun fitZoom(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, widthPx: Double, heightPx: Double, tilePx: Double): Double {
        val w = (lonToX(maxLon) - lonToX(minLon)).coerceAtLeast(1e-9)
        val h = (latToY(minLat) - latToY(maxLat)).coerceAtLeast(1e-9)
        val zx = log2(widthPx / (w * tilePx))
        val zy = log2(heightPx / (h * tilePx))
        return minOf(zx, zy).coerceIn(MIN_ZOOM.toDouble(), 17.5)
    }

    fun tileAt(lat: Double, lon: Double, z: Int): Tile {
        val n = 2.0.pow(z)
        val x = floor(lonToX(lon) * n).toInt().coerceIn(0, n.toInt() - 1)
        val y = floor(latToY(lat) * n).toInt().coerceIn(0, n.toInt() - 1)
        return Tile(z, x, y)
    }

    /** ある地点から半径 radiusM の範囲にかかるタイル（一時保存用） */
    fun tilesAround(lat: Double, lon: Double, radiusM: Double, zooms: IntRange): List<Tile> {
        val dLat = radiusM / 111_195.0
        val dLon = radiusM / (111_195.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val out = ArrayList<Tile>()
        for (z in zooms) {
            val a = tileAt(lat + dLat, lon - dLon, z)
            val b = tileAt(lat - dLat, lon + dLon, z)
            for (x in a.x..b.x) for (y in a.y..b.y) out += Tile(z, x, y)
        }
        return out
    }

    /**
     * 地点ごとの速さを 0（遅い）〜1（速い）にならす。地図の色分けに使う。
     * そのランの中での、遅いほう1割〜速いほう1割のあいだで割り振る。
     */
    fun speedFractions(dist: DoubleArray, movingMs: LongArray): FloatArray {
        val n = dist.size
        val speed = DoubleArray(n)
        for (i in 0 until n) {
            val a = (i - 4).coerceAtLeast(0)
            val b = (i + 4).coerceAtMost(n - 1)
            val dt = (movingMs[b] - movingMs[a]) / 1000.0
            speed[i] = if (dt > 0) (dist[b] - dist[a]) / dt else 0.0
        }
        if (n == 0) return FloatArray(0)
        val sorted = speed.sorted()
        val lo = sorted[(n * 0.1).toInt().coerceIn(0, n - 1)]
        val hi = sorted[(n * 0.9).toInt().coerceIn(0, n - 1)]
        val range = hi - lo
        return FloatArray(n) { i -> if (range < 0.05) 0.5f else ((speed[i] - lo) / range).coerceIn(0.0, 1.0).toFloat() }
    }

    /** 1km ごとの地点（何km 目か と、地点の番号） */
    fun kmMarks(dist: DoubleArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var next = 1
        for (i in dist.indices) {
            while (dist[i] >= next * 1000.0) {
                out += next to i
                next++
            }
        }
        return out
    }
}
