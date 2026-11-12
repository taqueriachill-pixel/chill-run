package com.chill.run.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

/** 曲のテンポ（BPM）を、音そのものから調べる */
object BpmDetector {
    private const val TARGET_RATE = 11025
    private const val FRAME = 512
    private const val HOP = 64

    /**
     * @param mono 1チャンネルにまとめた音（-1〜1）
     * @return BPM（60〜200 のあいだ）。調べられなければ null
     */
    fun detect(mono: FloatArray, sampleRate: Int): Double? {
        // 計算を軽くするため、約 11kHz まで間引く
        val factor = (sampleRate / TARGET_RATE).coerceAtLeast(1)
        val rate = sampleRate.toDouble() / factor
        val n = mono.size / factor
        if (n < rate * 8) return null // 8秒より短いと当てにならない
        val x = FloatArray(n) { i ->
            var s = 0f
            for (k in 0 until factor) s += mono[i * factor + k]
            s / factor
        }

        // 音の「立ち上がり」の強さを、細かい時間ごとに出す（スペクトルの増えた分の合計）
        val frames = (n - FRAME) / HOP
        if (frames < 200) return null
        val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).toFloat() }
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        var prev = FloatArray(FRAME / 2)
        var cur = FloatArray(FRAME / 2)
        val onset = FloatArray(frames)
        for (f in 0 until frames) {
            val off = f * HOP
            for (i in 0 until FRAME) { re[i] = x[off + i] * window[i]; im[i] = 0f }
            fft(re, im)
            var flux = 0f
            for (b in 0 until FRAME / 2) {
                val mag = ln(1.0 + 100.0 * sqrt((re[b] * re[b] + im[b] * im[b]).toDouble())).toFloat()
                cur[b] = mag
                val d = mag - prev[b]
                if (d > 0) flux += d
            }
            onset[f] = flux
            val t = prev; prev = cur; cur = t
        }
        // まわりの平均を引いて、山だけ残す
        val frameRate = rate / HOP
        val half = (frameRate * 0.4).toInt().coerceAtLeast(1)
        val env = FloatArray(frames)
        var sum = 0.0
        var lo = 0
        var hi = -1
        for (i in 0 until frames) {
            val wantHi = (i + half).coerceAtMost(frames - 1)
            val wantLo = (i - half).coerceAtLeast(0)
            while (hi < wantHi) { hi++; sum += onset[hi] }
            while (lo < wantLo) { sum -= onset[lo]; lo++ }
            env[i] = (onset[i] - sum / (hi - lo + 1)).toFloat().coerceAtLeast(0f)
        }
        if (env.none { it > 0f }) return null

        // 自己相関：何フレームずらすと、いちばんよく重なるか
        val maxLag = (frameRate * 60.0 / 60.0 * 4).toInt().coerceAtMost(frames - 1) // 60BPM の4拍ぶんまで
        val acf = DoubleArray(maxLag + 2)
        for (lag in 1..maxLag) {
            var s = 0.0
            for (i in 0 until frames - lag) s += env[i] * env[i + lag]
            acf[lag] = s / (frames - lag)
        }
        fun at(lag: Double): Double {
            if (lag < 1 || lag >= maxLag) return 0.0
            val i = lag.toInt()
            val f = lag - i
            return acf[i] * (1 - f) + acf[i + 1] * f
        }
        // 1拍・2拍・3拍・4拍ぶんずらした所がそろって強いテンポを選ぶ。120BPM 付近を少しだけ優先する
        fun score(bpm: Double): Double {
            val lag = frameRate * 60.0 / bpm
            val comb = at(lag) + at(lag * 2) * 0.5 + at(lag * 3) * 0.33 + at(lag * 4) * 0.25
            val prior = exp(-0.5 * (log2(bpm / 120.0) / 0.9).let { it * it })
            return comb * prior
        }
        var best = 0.0
        var bestScore = 0.0
        var bpm = 60.0
        while (bpm <= 200.0) {
            val s = score(bpm)
            if (s > bestScore) { bestScore = s; best = bpm }
            bpm += 0.5
        }
        if (bestScore <= 0) return null
        var fine = best - 0.5
        val end = best + 0.5
        while (fine <= end) {
            val s = score(fine)
            if (s > bestScore) { bestScore = s; best = fine }
            fine += 0.05
        }
        return Math.round(best * 10) / 10.0
    }

    /** 高速フーリエ変換（長さは2のべき乗） */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** 画面をタップして BPM を測る */
class TapTempo {
    private val taps = ArrayList<Long>()

    /** タップするたびに呼ぶ。4回以上たまったら BPM を返す */
    fun tap(tMs: Long): Double? {
        // 2秒以上あいたら、測り直し
        if (taps.isNotEmpty() && tMs - taps.last() > 2000) taps.clear()
        taps += tMs
        if (taps.size > 12) taps.removeAt(0)
        if (taps.size < 4) return null
        val gaps = taps.zipWithNext { a, b -> (b - a).toDouble() }.sorted()
        val median = gaps[gaps.size / 2]
        return if (median > 0) Math.round(600_000.0 / median) / 10.0 else null
    }

    fun reset() = taps.clear()
}

/** 曲を目標テンポに合わせるための選び方 */
object TempoMatch {
    data class Fit(
        /** 曲の BPM を、倍・半分も含めて目標にいちばん近く読みかえたもの */
        val effectiveBpm: Double,
        /** 目標にぴったり合わせるための速さ（1.0 が元の速さ） */
        val speed: Double,
    ) {
        val offset: Double get() = abs(speed - 1.0)
    }

    /** 85BPM の曲は 170 としても扱える（倍・半分は同じテンポ） */
    fun fit(trackBpm: Double, target: Double): Fit? {
        if (trackBpm <= 0 || target <= 0) return null
        return listOf(trackBpm, trackBpm * 2, trackBpm / 2)
            .map { Fit(it, target / it) }
            .minBy { it.offset }
    }

    /**
     * 次の曲を選ぶ。変える幅（range。0.10 なら ±10%）に収まる曲の中から、
     * 最近かけた曲を避けて選ぶ。収まる曲がなければ、いちばん近い曲を返す（inRange = false）。
     * @param tracks 曲の番号と BPM
     * @return 曲の番号、合わせ方、幅に収まっているか
     */
    fun pick(
        tracks: List<Pair<Long, Double>>,
        target: Double,
        range: Double,
        recent: List<Long>,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): Triple<Long, Fit, Boolean>? {
        val fits = tracks.mapNotNull { (id, bpm) -> fit(bpm, target)?.let { id to it } }
        if (fits.isEmpty()) return null
        val ok = fits.filter { it.second.offset <= range + 1e-9 }
        if (ok.isEmpty()) {
            val (id, f) = fits.filter { it.first !in recent.takeLast(1) }.ifEmpty { fits }.minBy { it.second.offset }
            return Triple(id, f, false)
        }
        // 最近かけた曲は、ほかに選べるかぎり避ける（曲が少ないときは、直前の1曲だけ避ける）
        val avoid = recent.takeLast((ok.size - 1).coerceIn(0, 8))
        val fresh = ok.filter { it.first !in avoid }.ifEmpty { ok }
        val (id, f) = fresh[random.nextInt(fresh.size)]
        return Triple(id, f, true)
    }

    /** いまの曲を目標に合わせる速さ。幅からはみ出すぶんは、幅の端で止める */
    fun speedFor(effectiveBpm: Double, target: Double, range: Double): Double =
        (target / effectiveBpm).coerceIn(1.0 - range, 1.0 + range)
}

/**
 * ペース（秒/km）から、合うケイデンス（歩/分）を見積もる。
 * 初めは「6分/km で 168歩/分、30秒速くなるごとに 3歩増える」を目安にし、
 * 自分の 1km ごとの記録が 8個以上たまったら、そこから学ぶ。
 */
class CadenceModel(samples: List<Pair<Double, Int>> = emptyList()) {
    private var slope = -0.1
    private var base = 168.0 // 360秒/km のときのケイデンス
    val learned: Boolean

    init {
        val good = samples.filter { it.first in 180.0..600.0 && it.second in 120..220 }
        var ok = false
        if (good.size >= 8) {
            val mx = good.map { it.first }.average()
            val my = good.map { it.second.toDouble() }.average()
            val sxx = good.sumOf { (it.first - mx) * (it.first - mx) }
            val sxy = good.sumOf { (it.first - mx) * (it.second - my) }
            if (sxx > 2000) {
                // 「速いほどケイデンスが上がる」向きで、おかしくない傾きだけ使う
                val s = (sxy / sxx).coerceIn(-0.25, 0.0)
                slope = s
                base = my + s * (360.0 - mx)
            } else {
                // ペースの幅がせまい（いつも同じペース）ときは、平均だけ学ぶ
                base = my + slope * (360.0 - mx)
            }
            ok = true
        }
        learned = ok
    }

    fun cadenceFor(paceSecPerKm: Double): Double = (base + slope * (paceSecPerKm - 360.0)).coerceIn(140.0, 200.0)
}
