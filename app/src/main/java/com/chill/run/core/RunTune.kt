package com.chill.run.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

enum class Sky { SUNNY, CLOUDY, RAIN, SNOW, UNKNOWN }

/** ランムービーの曲を決める材料 */
data class TuneMood(
    val trail: Boolean,
    /** 気分：-2（つらかった）〜 +2（絶好調）。分からなければ 0 */
    val feeling: Int = 0,
    /** 気温（℃）。分からなければ null */
    val tempC: Double? = null,
    val sky: Sky = Sky.UNKNOWN,
    /** がんばり具合 0〜1（心拍数の高いゾーンにいた割合など）。分からなければ 0.3 */
    val effort: Double = 0.3,
) {
    val cold: Boolean get() = tempC != null && tempC < 10 || sky == Sky.SNOW
    val hot: Boolean get() = tempC != null && tempC >= 26

    companion object {
        /** 天気の番号（世界共通の WMO コード）を、晴れ・くもり・雨・雪に分ける */
        fun skyOf(weatherCode: Int): Sky = when (weatherCode) {
            0, 1 -> Sky.SUNNY
            2, 3, 45, 48 -> Sky.CLOUDY
            in 51..67, in 80..82, in 95..99 -> Sky.RAIN
            in 71..77, 85, 86 -> Sky.SNOW
            else -> Sky.UNKNOWN
        }

        /** メモの言葉から、気分の手がかりを拾う（+1：楽しかった、-1：つらかった、0：分からない） */
        fun feelingFromWords(text: String): Int {
            val good = listOf("楽し", "たのし", "気持ちい", "きもちい", "最高", "よかった", "良かった", "快調", "軽い", "爽快", "すっきり")
            val bad = listOf("きつ", "つら", "辛", "しんど", "疲れ", "つかれ", "重い", "だる", "苦し", "痛")
            val g = good.count { text.contains(it) }
            val b = bad.count { text.contains(it) }
            return when {
                g > b -> 1
                b > g -> -1
                else -> 0
            }
        }
    }
}

/** 決まった曲の設計 */
class TunePlan(
    val bpm: Int,
    val rootMidi: Int,
    val scaleName: String,
    val scale: IntArray,
    val progression: IntArray,
    val chordBeats: Int,
    val mood: TuneMood,
) {
    /** どう決めたかを、ふつうの言葉で説明する */
    val description: String
        get() {
            val because = ArrayList<String>()
            because += if (mood.trail) "トレイル" else "ロード"
            because += when {
                mood.feeling >= 1 -> "楽しかった"
                mood.feeling <= -1 -> "つらかった"
                else -> "ふつう"
            }
            when (mood.sky) {
                Sky.SUNNY -> because += "晴れ"
                Sky.CLOUDY -> because += "くもり"
                Sky.RAIN -> because += "雨"
                Sky.SNOW -> because += "雪"
                Sky.UNKNOWN -> {}
            }
            if (mood.cold) because += "寒い" else if (mood.hot) because += "暑い"
            val sound = ArrayList<String>()
            sound += scaleName
            sound += "テンポ $bpm"
            sound += if (mood.trail) "木琴のような音とシェイカー" else "はじく音と四つ打ち"
            if (mood.cold) sound += "澄んだ鈴の音"
            if (mood.hot) sound += "低めでゆったり"
            if (mood.sky == Sky.RAIN) sound += "雨音"
            return "${because.joinToString("・")} → ${sound.joinToString("、")}"
        }
}

/**
 * ランムービーの曲を、その場で作る。既存の曲は使わず、音階・和音・リズム・音色を
 * ランの内容に合わせて組み立てる。同じ材料でも、種（seed）が変われば別の曲になる。
 */
object RunTune {
    private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val MINOR = intArrayOf(0, 2, 3, 5, 7, 8, 10)
    private val DORIAN = intArrayOf(0, 2, 3, 5, 7, 9, 10)
    private val MIXO = intArrayOf(0, 2, 4, 5, 7, 9, 10)

    fun plan(mood: TuneMood, seed: Long): TunePlan {
        val rng = Random(seed)
        // 明るさ：気分と天気を足し合わせる
        val brightness = mood.feeling + when (mood.sky) { Sky.SUNNY -> 1; Sky.RAIN -> -1; else -> 0 }
        val (name, scale, progressions) = when {
            brightness >= 1 -> Triple(
                "明るい長調", MAJOR,
                listOf(intArrayOf(0, 4, 5, 3), intArrayOf(0, 3, 4, 3), intArrayOf(5, 3, 0, 4), intArrayOf(0, 5, 3, 4)),
            )
            brightness <= -1 -> Triple(
                "しっとりした短調", MINOR,
                listOf(intArrayOf(0, 5, 2, 6), intArrayOf(0, 3, 5, 6), intArrayOf(0, 6, 5, 6), intArrayOf(5, 6, 0, 0)),
            )
            mood.trail -> Triple("落ち着いた森の調（ドリア）", DORIAN, listOf(intArrayOf(0, 3, 0, 6), intArrayOf(0, 6, 3, 0)))
            else -> Triple("からっとした調（ミクソリディア）", MIXO, listOf(intArrayOf(0, 6, 3, 0), intArrayOf(0, 3, 6, 3)))
        }
        var bpm = (if (mood.trail) 100 else 116) + mood.feeling * 4 + Math.round(mood.effort.coerceIn(0.0, 1.0) * 10).toInt()
        if (mood.hot) bpm -= 6
        if (mood.cold) bpm += 4
        if (mood.sky == Sky.RAIN) bpm -= 4
        bpm = (bpm + rng.nextInt(-3, 4)).coerceIn(84, 138)
        val root = listOf(48, 50, 51, 53, 55, 57)[rng.nextInt(6)] // ド・レ・ミ♭・ファ・ソ・ラ から選ぶ
        val chordBeats = if (!mood.trail && bpm >= 110) 2 else 4
        return TunePlan(bpm, root, name, scale, progressions[rng.nextInt(progressions.size)], chordBeats, mood)
    }

    private fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** 音階の d 番目の音（d は 0 から。7 を超えたら1オクターブ上） */
    private fun degree(plan: TunePlan, d: Int, octaveShift: Int = 0): Int {
        val oct = Math.floorDiv(d, 7)
        return plan.rootMidi + plan.scale[Math.floorMod(d, 7)] + 12 * (oct + octaveShift)
    }

    /**
     * 曲を作る。
     * @return 1チャンネルの音（-1〜1）。長さは seconds × rate
     */
    fun render(plan: TunePlan, seed: Long, seconds: Double = 10.0, rate: Int = 44100): FloatArray {
        val rng = Random(seed xor 0x5EED)
        val n = (seconds * rate).toInt()
        val out = FloatArray(n)
        val mood = plan.mood
        val beat = 60.0 / plan.bpm
        val grooveStart = 1.5.coerceAtMost(seconds * 0.15)
        val grooveEnd = seconds - 1.5
        val fullFrom = grooveStart + (grooveEnd - grooveStart) * 0.25 // ここからドラムが全部入る
        val melodyFrom = seconds * 0.5

        // ---- 音色 ----
        fun add(start: Double, dur: Double, f: Double, amp: Double, wave: (t: Double, ph: Double) -> Double) {
            val s0 = (start * rate).toInt()
            val len = (dur * rate).toInt()
            val w = 2 * PI * f / rate
            for (i in 0 until len) {
                val idx = s0 + i
                if (idx < 0) continue
                if (idx >= n) break
                out[idx] += (amp * wave(i.toDouble() / rate, w * i)).toFloat()
            }
        }
        fun pad(start: Double, dur: Double, midi: Int, amp: Double) = add(start, dur + 0.5, freq(midi), amp) { t, ph ->
            val env = (t / 0.35).coerceAtMost(1.0) * (if (t > dur) exp(-(t - dur) * 6) else 1.0)
            env * (sin(ph) + 0.25 * sin(ph * 2.003) + 0.12 * sin(ph * 3.0))
        }
        fun bass(start: Double, midi: Int, amp: Double) = add(start, 0.45, freq(midi), amp) { t, ph ->
            (1 - exp(-t * 400)) * exp(-t * 5.0) * (sin(ph) + 0.3 * sin(ph * 2))
        }
        fun pluck(start: Double, midi: Int, amp: Double) = add(start, 0.5, freq(midi), amp) { t, ph ->
            var s = 0.0
            for (k in 1..5) s += sin(ph * k) / k * exp(-t * (4.0 + 3.0 * k))
            s
        }
        fun marimba(start: Double, midi: Int, amp: Double) = add(start, 0.6, freq(midi), amp) { t, ph ->
            sin(ph) * exp(-t * 7.0) + 0.45 * sin(ph * 4) * exp(-t * 22.0)
        }
        fun bell(start: Double, midi: Int, amp: Double) = add(start, 1.4, freq(midi), amp) { t, ph ->
            exp(-t * 3.2) * (sin(ph) + 0.5 * sin(ph * 2.76) + 0.25 * sin(ph * 5.4))
        }
        fun lead(start: Double, dur: Double, midi: Int, amp: Double) = add(start, dur + 0.15, freq(midi), amp) { t, ph ->
            val env = (t / 0.03).coerceAtMost(1.0) * (if (t > dur) exp(-(t - dur) * 25) else 1.0)
            env * (sin(ph + 0.4 * sin(2 * PI * 5.5 * t)) + 0.2 * sin(ph * 2))
        }
        fun noise(start: Double, dur: Double, amp: Double, decay: Double, bright: Boolean) {
            val s0 = (start * rate).toInt()
            val len = (dur * rate).toInt()
            var prev = 0.0
            var low = 0.0
            for (i in 0 until len) {
                val idx = s0 + i
                if (idx < 0) continue
                if (idx >= n) break
                val white = rng.nextDouble() * 2 - 1
                // bright：高い音だけ（ハイハットなど）。そうでなければ、こもった音（拍手・雨）
                val v = if (bright) white - prev else { low += 0.25 * (white - low); low }
                prev = white
                out[idx] += (amp * v * exp(-i.toDouble() / rate * decay)).toFloat()
            }
        }
        fun kick(start: Double, amp: Double) {
            val s0 = (start * rate).toInt()
            var ph = 0.0
            for (i in 0 until (0.28 * rate).toInt()) {
                val idx = s0 + i
                if (idx < 0) continue
                if (idx >= n) break
                val t = i.toDouble() / rate
                ph += 2 * PI * (45 + 90 * exp(-t * 28)) / rate
                out[idx] += (amp * sin(ph) * exp(-t * 11)).toFloat()
            }
        }
        fun instrument(start: Double, midi: Int, amp: Double) = if (mood.trail) marimba(start, midi, amp * 1.2) else pluck(start, midi, amp)

        fun chordAt(t: Double): Int {
            val k = ((t - grooveStart) / beat).toInt().coerceAtLeast(0)
            return plan.progression[(k / plan.chordBeats) % plan.progression.size]
        }
        val hotShift = if (mood.hot) -1 else 0
        val coldShift = if (mood.cold) 1 else 0

        // ---- 出だし：和音がふわっと入り、3つの音が上っていく ----
        for (tone in intArrayOf(0, 2, 4)) pad(0.0, grooveStart, degree(plan, plan.progression[0] + tone, 1), 0.07)
        for (i in 0 until 3) {
            val t = grooveStart * (0.35 + 0.2 * i)
            val midi = degree(plan, plan.progression[0] + i * 2, 2 + coldShift)
            if (mood.cold) bell(t, midi, 0.13) else instrument(t, midi, 0.16)
        }

        // ---- 本編：拍ごとに、和音・ベース・分散和音・ドラム・メロディ ----
        val arpPattern = listOf(intArrayOf(0, 2, 4, 2), intArrayOf(0, 4, 2, 4), intArrayOf(4, 2, 0, 2), intArrayOf(0, 2, 4, 7))[rng.nextInt(4)]
        val busy = !mood.trail && mood.effort > 0.6 && mood.feeling >= 0 // がんばったロードは、細かく刻む
        val sparse = mood.feeling <= -1                                 // つらかった日は、音数を減らす
        var melodyDegree = plan.progression[0] + 4
        var k = 0
        while (true) {
            val t = grooveStart + k * beat
            if (t >= grooveEnd - 0.02) break
            val chord = chordAt(t)
            val full = t >= fullFrom
            val inBar = k % 4

            if (k % plan.chordBeats == 0) {
                val span = (plan.chordBeats * beat).coerceAtMost(grooveEnd - t)
                for (tone in intArrayOf(0, 2, 4)) pad(t, span, degree(plan, chord + tone, 1), 0.05)
            }
            // ベース
            if (mood.trail) {
                if (inBar == 0 || inBar == 2) bass(t, degree(plan, chord, -1), 0.30)
            } else {
                bass(t, degree(plan, chord, -1), 0.30)
                if (full && !sparse) bass(t + beat / 2, degree(plan, chord, -1), 0.16)
            }
            // 分散和音（和音の音を、順にはじく）
            val steps = if (busy && full) 4 else 2
            for (s in 0 until steps) {
                if (sparse && s % 2 == 1) continue
                val tone = arpPattern[(k * steps + s) % arpPattern.size]
                instrument(t + s * beat / steps, degree(plan, chord + tone, 1 + hotShift), if (s == 0) 0.15 else 0.11)
            }
            // ドラム
            if (mood.trail) {
                if (inBar == 0 || (inBar == 2 && full)) kick(t, 0.5)
                if (full) {
                    noise(t, 0.06, 0.05, 45.0, true)
                    noise(t + beat / 2, 0.06, 0.07, 45.0, true)
                    if (inBar == 1 || inBar == 3) add(t + beat / 2, 0.08, 880.0, 0.10) { tt, ph -> sin(ph) * exp(-tt * 55) } // 木のたたく音
                }
            } else {
                if (!sparse || inBar % 2 == 0) kick(t, 0.6)
                if (full) {
                    noise(t + beat / 2, 0.05, 0.07, 70.0, true)
                    if (inBar == 1 || inBar == 3) noise(t, 0.12, 0.22, 28.0, false) // 拍手
                }
            }
            // メロディ（後半から）
            if (t >= melodyFrom) {
                val choice = rng.nextInt(if (sparse) 4 else 6)
                val notes = when {
                    choice == 0 -> 0          // 休み
                    choice <= 3 -> 1          // 1拍に1音
                    else -> 2                 // 1拍に2音
                }
                for (i in 0 until notes) {
                    melodyDegree = (melodyDegree + intArrayOf(-2, -1, 1, 2, 0)[rng.nextInt(5)]).coerceIn(chord, chord + 9)
                    // 拍の頭は、和音の音に寄せる（にごらないように）
                    if (i == 0) melodyDegree = chord + listOf(0, 2, 4, 7).minBy { Math.abs(chord + it - melodyDegree) }
                    val midi = degree(plan, melodyDegree, 2 + hotShift + coldShift)
                    val dur = beat / notes * 0.85
                    if (mood.cold) bell(t + i * beat / notes, midi, 0.10) else lead(t + i * beat / notes, dur, midi, 0.09)
                }
            }
            k++
        }

        // ---- 天気の音 ----
        if (mood.sky == Sky.RAIN) {
            noise(0.0, seconds, 0.035, 0.0, false) // さーっという雨
            repeat((seconds * 9).toInt()) {
                add(rng.nextDouble() * seconds, 0.03, 1800 + rng.nextDouble() * 2600, 0.03) { t, ph -> sin(ph) * exp(-t * 160) } // しずく
            }
        }
        if (mood.sky == Sky.SNOW || (mood.cold && mood.sky == Sky.SUNNY)) {
            repeat(6) { bell(grooveStart + rng.nextDouble() * (grooveEnd - grooveStart), degree(plan, plan.progression[0] + listOf(0, 2, 4)[rng.nextInt(3)], 3), 0.05) }
        }

        // ---- 終わり：最初の和音を鳴らして、余韻を残す ----
        val home = plan.progression[0]
        for (tone in intArrayOf(0, 2, 4)) pad(grooveEnd, seconds - grooveEnd, degree(plan, home + tone, 1), 0.10)
        bass(grooveEnd, degree(plan, home, -1), 0.40)
        for (i in 0 until 4) {
            val midi = degree(plan, home + i * 2, 2)
            if (mood.cold) bell(grooveEnd + i * 0.06, midi, 0.16) else instrument(grooveEnd + i * 0.06, midi, 0.18)
        }
        if (!mood.trail) noise(grooveEnd, 1.2, 0.10, 3.5, true) // シンバルのような余韻
        kick(grooveEnd, 0.6)

        // ---- 仕上げ：響きを足し、音量をそろえ、始めと終わりをなめらかに ----
        val d1 = (0.19 * rate).toInt()
        val d2 = (0.31 * rate).toInt()
        for (i in 0 until n) {
            var v = out[i]
            if (i >= d1) v += out[i - d1] * 0.22f
            if (i >= d2) v += out[i - d2] * 0.14f
            out[i] = v
        }
        // 暑い日は、少しこもらせてやわらかく
        if (mood.hot) {
            var low = 0f
            for (i in 0 until n) { low += 0.35f * (out[i] - low); out[i] = low }
        }
        var peak = 1e-6f
        for (v in out) if (Math.abs(v) > peak) peak = Math.abs(v)
        val gain = 1.4f / peak
        val fadeIn = (0.03 * rate).toInt()
        val fadeOut = (0.6 * rate).toInt()
        for (i in 0 until n) {
            var v = tanh((out[i] * gain).toDouble()).toFloat() * 0.9f
            if (i < fadeIn) v *= i.toFloat() / fadeIn
            if (i >= n - fadeOut) v *= (n - i).toFloat() / fadeOut
            out[i] = v
        }
        return out
    }
}
