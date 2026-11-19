package com.chill.run.core

/** 自己ベストの計算 */
object Bests {
    /**
     * ルートの中で、windowM（メートル）をいちばん速く走った区間の時間（ミリ秒）。
     * 走った距離が足りなければ null。地点と地点のあいだは、同じ速さで進んだものとして補う。
     */
    fun fastest(dist: DoubleArray, movingMs: LongArray, windowM: Double): Long? {
        val n = dist.size
        if (n < 2 || dist[n - 1] - dist[0] < windowM) return null
        var best = Long.MAX_VALUE
        var j = 0
        for (i in 0 until n) {
            val target = dist[i] + windowM
            if (target > dist[n - 1]) break
            while (j < n - 1 && dist[j] < target) j++
            // dist[j-1] < target <= dist[j]
            val t = if (j == 0 || dist[j] == dist[j - 1]) movingMs[j].toDouble()
            else movingMs[j - 1] + (movingMs[j] - movingMs[j - 1]) * (target - dist[j - 1]) / (dist[j] - dist[j - 1])
            val elapsed = Math.round(t - movingMs[i])
            if (elapsed in 1 until best) best = elapsed
        }
        return if (best == Long.MAX_VALUE) null else best
    }
}

/** ふりかえりの計算 */
object Review {
    /**
     * 7日間の平均の線。毎日の上下で一喜一憂しないように、その日を含む直近7日の平均を出す。
     * @param daily 日付（1970年からの日数）と値。同じ日に何回か測っていれば、先に平均しておく
     */
    fun sevenDayAverage(daily: List<Pair<Long, Double>>): List<Pair<Long, Double>> {
        val sorted = daily.groupBy { it.first }.map { (day, list) -> day to list.map { it.second }.average() }.sortedBy { it.first }
        return sorted.map { (day, _) ->
            val window = sorted.filter { it.first in (day - 6)..day }
            day to window.map { it.second }.average()
        }
    }

    data class PaceHr(val paceSecPerKm: Int, val thisAvg: Int, val lastAvg: Int, val thisCount: Int, val lastCount: Int) {
        val diff: Int get() = thisAvg - lastAvg
    }

    /**
     * 同じペースで走ったときの平均の心拍数を、今月と先月で比べる。
     * 1km ごとの記録を 30秒きざみのペースの組に分け、両方の月に 2個以上ある組のうち、いちばん数の多い組を選ぶ。
     * @param thisMonth ペース（秒/km）と平均の心拍数
     */
    fun samePaceHr(thisMonth: List<Pair<Double, Int>>, lastMonth: List<Pair<Double, Int>>): PaceHr? {
        // 6分30秒/km なら、6分15秒〜6分45秒 を同じ組にする
        fun bucket(pace: Double) = Math.round(pace / 30).toInt() * 30
        val a = thisMonth.filter { it.second > 0 }.groupBy { bucket(it.first) }
        val b = lastMonth.filter { it.second > 0 }.groupBy { bucket(it.first) }
        val key = a.keys.intersect(b.keys)
            .filter { a.getValue(it).size >= 2 && b.getValue(it).size >= 2 }
            .maxByOrNull { minOf(a.getValue(it).size, b.getValue(it).size) } ?: return null
        val x = a.getValue(key)
        val y = b.getValue(key)
        return PaceHr(
            key,
            Math.round(x.map { it.second }.average()).toInt(),
            Math.round(y.map { it.second }.average()).toInt(),
            x.size, y.size,
        )
    }

    /** 「続けたこと」をほめるひとこと。数字の良し悪しは言わない */
    fun cheer(runsThisMonth: Int, runsThisWeek: Int): String = when {
        runsThisMonth == 0 -> "今月はこれから。まずは1回、外に出てみよう。"
        runsThisMonth == 1 -> "今月は1回走った。走り出せたのがえらい。"
        runsThisWeek >= 3 -> "今週は${runsThisWeek}回、今月は${runsThisMonth}回走った。いいリズムで続いている。"
        else -> "今月は${runsThisMonth}回走った。続けているのがすごい。"
    }
}

/** 隠す場所（自宅など）。ここから半径 radiusM の中のルートは、ランムービーに描かない */
data class HideZone(val lat: Double, val lon: Double, val radiusM: Double) {
    fun hides(lat: Double, lon: Double): Boolean = Geo.distanceM(this.lat, this.lon, lat, lon) <= radiusM
}

/** 動画を作るときの、色の変換 */
object Yuv {
    /**
     * 画面の色（ARGB）を、動画の色（Y・U・V）に直す。U と V は、縦横とも2分の1に間引く（2×2 の左上の色を使う）。
     * 動画でふつうに使われる範囲（BT.601、Y は 16〜235）で出す。
     */
    fun fromArgb(pixels: IntArray, width: Int, height: Int, y: ByteArray, u: ByteArray, v: ByteArray) {
        var yi = 0
        var ci = 0
        for (row in 0 until height) {
            val base = row * width
            val chromaRow = row and 1 == 0
            for (col in 0 until width) {
                val p = pixels[base + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                y[yi++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if (chromaRow && col and 1 == 0) {
                    u[ci] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    v[ci] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                    ci++
                }
            }
        }
    }
}

/** ランムービー（約10秒）の流れ */
object MovieScript {
    const val FPS = 30
    const val TOTAL_SEC = 10.0
    const val TITLE_END = 1.5
    const val STATS_START = 8.5

    enum class Scene { TITLE, MAP, MEDIA, STATS }

    /** 写真・動画がない日は、地図を 8.5秒まで続ける */
    fun mapEnd(mediaCount: Int): Double = if (mediaCount > 0) 5.0 else STATS_START

    fun sceneAt(t: Double, mediaCount: Int): Scene = when {
        t < TITLE_END -> Scene.TITLE
        t < mapEnd(mediaCount) -> Scene.MAP
        t < STATS_START -> Scene.MEDIA
        else -> Scene.STATS
    }

    /** 地図の場面で、ルートを何割まで描くか（最後の 0.4秒は描き終わった形を見せる） */
    fun routeProgress(t: Double, mediaCount: Int): Double {
        val end = mapEnd(mediaCount) - 0.4
        return ((t - TITLE_END) / (end - TITLE_END)).coerceIn(0.0, 1.0)
    }

    /** 写真・動画の場面で、何番目を見せるか と、その中での進み具合（0〜1） */
    fun mediaSlot(t: Double, mediaCount: Int): Pair<Int, Double> {
        val n = mediaCount.coerceIn(1, 4)
        val each = (STATS_START - 5.0) / n
        val index = ((t - 5.0) / each).toInt().coerceIn(0, n - 1)
        return index to ((t - 5.0 - index * each) / each).coerceIn(0.0, 1.0)
    }
}
