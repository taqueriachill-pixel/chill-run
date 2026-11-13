package com.chill.run.core

import kotlin.math.PI
import kotlin.math.abs
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

/** 曲の型（ジャンル） */
enum class TuneStyle(val label: String) {
    LOFI("ローファイ・ヒップホップ"),
    JAZZY("ジャジー（スウィング）"),
    HOUSE("ディープハウス"),
    CLUB("クラブ（メロディック・テクノ）"),
}

/** 和音。root は主音から何半音上か、tones は和音の音（根音からの半音） */
class Chord(val root: Int, val quality: String) {
    val tones: IntArray = when (quality) {
        "M7" -> intArrayOf(0, 4, 7, 11)
        "M9" -> intArrayOf(0, 4, 7, 11, 14)
        "69" -> intArrayOf(0, 4, 7, 9, 14)
        "m7" -> intArrayOf(0, 3, 7, 10)
        "m9" -> intArrayOf(0, 3, 7, 10, 14)
        "7" -> intArrayOf(0, 4, 7, 10)
        "9" -> intArrayOf(0, 4, 7, 10, 14)
        "13" -> intArrayOf(0, 4, 7, 10, 14, 21)
        "m7b5" -> intArrayOf(0, 3, 6, 10)
        else -> intArrayOf(0, 4, 7)
    }

    /** 和音に入っている音（オクターブを無視した 0〜11） */
    fun pitchClasses(tonic: Int): Set<Int> = tones.map { Math.floorMod(tonic + root + it, 12) }.toSet()
}

/** 決まった曲の設計 */
class TunePlan(
    val style: TuneStyle,
    val bpm: Double,
    /** 主音（MIDI の番号。48 = 低いド） */
    val rootMidi: Int,
    /** 0=明るい（長調） 1=しっとり（短調） 2=浮遊感（ドリアの2コード） */
    val tonality: Int,
    val chords: List<Chord>,
    /** 和音ごとの長さ（拍） */
    val chordBeats: Int,
    /** リズムが鳴っている拍の数（最後の和音の手前まで） */
    val beats: Int,
    val mood: TuneMood,
) {
    val tonalityName: String get() = listOf("明るい響き（メジャー7th・9th）", "しっとりした響き（マイナー9th）", "浮遊感のある2コード（ドリア）")[tonality]

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
            sound += style.label
            sound += tonalityName
            sound += "テンポ ${Math.round(bpm)}"
            if (mood.cold) sound += "澄んだビブラフォン"
            if (mood.hot) sound += "こもらせてゆったり"
            if (mood.sky == Sky.RAIN) sound += "雨音"
            return "${because.joinToString("・")} → ${sound.joinToString("、")}"
        }
}

/**
 * ランムービーの曲を、その場で作る。既存の曲は使わない。
 *
 * 4つの型（ローファイ、ジャジー、ディープハウス、クラブ）それぞれの「お決まり」に沿って組み立てる。
 * ・和音は 7th・9th の入ったジャズの和音。前の和音から近い音へなめらかにつなぐ
 * ・ローファイ：70〜86BPM、はねたリズム、キックは1拍目と「2拍目の裏」あたり、スネアは2・4拍、レコードのぱちぱち音、高音を落とす
 * ・ジャジー：スウィングのライドシンバル、ハイハットは2・4拍、歩くベース（次の和音へ半音で近づく）、ピアノは裏拍で合いの手
 * ・ハウス：120〜126BPM、キックは4つ打ち、クラップは2・4拍、裏拍のオープンハイハット、和音の短い「スタブ」、キックに合わせて音がうねる
 * ・クラブ：16分音符の分散和音、裏拍のベース、長いパッド、盛り上げのノイズ
 * ランの内容（ロードかトレイルか、気分、天気、気温、がんばり具合）で型の選ばれやすさと響きが変わり、
 * 細かい所は毎回の乱数で決まる。
 */
object RunTune {
    private fun c(root: Int, q: String) = Chord(root, q)

    private val MAJOR_SETS = listOf(
        listOf(c(5, "M9"), c(4, "m7"), c(2, "m9"), c(0, "M9")),   // IV → iii → ii → I
        listOf(c(0, "M9"), c(9, "m7"), c(2, "m9"), c(7, "13")),   // I → vi → ii → V
        listOf(c(2, "m9"), c(7, "13"), c(0, "M9"), c(9, "7")),    // ii → V → I → VI7
        listOf(c(0, "M9"), c(10, "9"), c(5, "M7"), c(5, "m7")),   // I → ♭VII7 → IV → iv（ほろ苦い）
    )
    private val MINOR_SETS = listOf(
        listOf(c(0, "m9"), c(5, "m9"), c(10, "9"), c(3, "M9")),   // i → iv → ♭VII → ♭III
        listOf(c(0, "m9"), c(8, "M7"), c(3, "M9"), c(10, "9")),   // i → ♭VI → ♭III → ♭VII
        listOf(c(0, "m9"), c(10, "7"), c(8, "M7"), c(7, "7")),    // i → ♭VII → ♭VI → V7
        listOf(c(2, "m7b5"), c(7, "7"), c(0, "m9"), c(0, "m9")),  // iiø → V7 → i
    )
    private val VAMP = listOf(c(0, "m9"), c(5, "9"), c(0, "m9"), c(5, "13")) // i → IV（ドリアの2コード）

    fun plan(mood: TuneMood, seed: Long): TunePlan {
        val rng = Random(seed)
        val f = mood.feeling
        val effort = mood.effort.coerceIn(0.0, 1.0)
        // ---- 型を選ぶ：材料ごとに「選ばれやすさ」を足し合わせて、くじを引く ----
        var lofi = 1.0 + (if (mood.sky == Sky.RAIN) 2.0 else 0.0) + (if (f < 0) -1.5 * f else 0.0) +
            (if (mood.hot) 1.0 else 0.0) + (if (mood.trail) 0.5 else 0.0) + (if (effort < 0.35) 0.5 else 0.0)
        var jazzy = 1.0 + (if (mood.trail) 1.5 else 0.0) + (if (f == 0) 0.5 else 0.0) + (if (f > 0 && mood.trail) 1.0 else 0.0) +
            (if (mood.sky == Sky.CLOUDY) 0.5 else 0.0) + (if (mood.cold) 0.5 else 0.0)
        var house = 1.0 + (if (!mood.trail) 1.5 else 0.0) + (if (f > 0) f * 1.0 else 0.0) + (if (mood.sky == Sky.SUNNY) 1.0 else 0.0)
        var club = 0.7 + (if (!mood.trail) 1.0 else 0.0) + effort * 2.0 + (if (f > 0) 0.5 else 0.0) + (if (mood.cold) 0.5 else 0.0)
        if (f <= -1) { house *= 0.4; club *= 0.5 }
        if (mood.sky == Sky.RAIN) { house *= 0.7; club *= 0.7 }
        val weights = doubleArrayOf(lofi, jazzy, house, club)
        var pick = rng.nextDouble() * weights.sum()
        var index = 0
        while (index < 3 && pick >= weights[index]) { pick -= weights[index]; index++ }
        val style = TuneStyle.values()[index]

        // ---- 響き：気分と天気を足し合わせる ----
        val brightness = f + when (mood.sky) { Sky.SUNNY -> 1; Sky.RAIN -> -1; else -> 0 }
        val tonality = when {
            style == TuneStyle.CLUB -> 1 // クラブは、いつも短調（明るい日は、のびやかな進行を選ぶ）
            brightness >= 1 -> 0
            brightness <= -1 -> 1
            else -> listOf(2, 2, 0, 1)[rng.nextInt(4)]
        }
        val root = 48 + rng.nextInt(12)
        val beats: Int
        val chordBeats: Int
        val chords: List<Chord>
        val bpm: Double
        // テンポの中での速さ：がんばった日・寒い日は速め、暑い日・雨の日・つらかった日は遅め
        val push = (effort - 0.3) * 0.6 + f * 0.12 + (if (mood.cold) 0.15 else 0.0) - (if (mood.hot) 0.25 else 0.0) -
            (if (mood.sky == Sky.RAIN) 0.15 else 0.0) + (rng.nextDouble() - 0.5) * 0.3
        fun within(lo: Double, hi: Double) = lo + (hi - lo) * (0.5 + push).coerceIn(0.0, 1.0)
        when (style) {
            TuneStyle.LOFI -> {
                bpm = within(70.0, 86.0); beats = 8; chordBeats = 2
                chords = when (tonality) { 0 -> MAJOR_SETS[rng.nextInt(MAJOR_SETS.size)]; 1 -> MINOR_SETS[rng.nextInt(3)]; else -> VAMP }
            }
            TuneStyle.JAZZY -> {
                bpm = within(96.0, 114.0); beats = 12; chordBeats = 2
                // 2小節の「ii → V → I → VI」と、頭に戻る「ii → V」。最後の V は、ときどき半音上からの代理和音にする
                val lastV = if (rng.nextInt(3) == 0) c(1, "9") else c(7, "13")
                chords = when (tonality) {
                    0 -> listOf(c(2, "m9"), c(7, "13"), c(0, "M9"), c(9, "7"), c(2, "m9"), lastV)
                    1 -> listOf(c(2, "m7b5"), c(7, "7"), c(0, "m9"), c(8, "M7"), c(2, "m7b5"), c(7, "7"))
                    else -> listOf(c(0, "m9"), c(5, "9"), c(0, "m9"), c(5, "13"), c(0, "m9"), c(5, "9"))
                }
            }
            TuneStyle.HOUSE -> {
                bpm = within(120.0, 126.0); beats = 16
                if (tonality == 2) { chordBeats = 2; chords = VAMP } else {
                    chordBeats = 4
                    chords = if (tonality == 0) MAJOR_SETS[rng.nextInt(2)] else MINOR_SETS[rng.nextInt(3)]
                }
            }
            TuneStyle.CLUB -> {
                bpm = within(122.0, 128.0); beats = 16; chordBeats = 4
                chords = if (brightness >= 1) MINOR_SETS[1] else MINOR_SETS[listOf(0, 2)[rng.nextInt(2)]]
            }
        }
        return TunePlan(style, bpm, root, tonality, chords, chordBeats, beats, mood)
    }

    private fun freq(midi: Double) = 440.0 * 2.0.pow((midi - 69.0) / 12.0)

    /** 音を足していく場所（左右2チャンネル）と、楽器 */
    private class Studio(val n: Int, val rate: Int, val rng: Random) {
        val musicL = FloatArray(n); val musicR = FloatArray(n)
        val drumL = FloatArray(n); val drumR = FloatArray(n)
        /** キックを打った時刻と、音をへこませる深さ（サイドチェイン用） */
        val kicks = ArrayList<Pair<Double, Double>>()
        /** レコードの回転むらのような、ゆっくりした音程のゆれ（ローファイ用）。0 ならゆれない */
        var wow = 0.0

        inline fun put(drum: Boolean, start: Double, dur: Double, pan: Double, gen: (i: Int, t: Double) -> Double) {
            val l = if (drum) drumL else musicL
            val r = if (drum) drumR else musicR
            val gl = Math.sqrt(0.5 * (1 - pan)).toFloat()
            val gr = Math.sqrt(0.5 * (1 + pan)).toFloat()
            val s0 = (start * rate).toInt()
            val len = (dur * rate).toInt()
            for (i in 0 until len) {
                val idx = s0 + i
                if (idx < 0) continue
                if (idx >= n) break
                val v = gen(i, i.toDouble() / rate).toFloat()
                l[idx] += v * gl
                r[idx] += v * gr
            }
        }

        /** 音程のある音。ph（位相）は、ゆれも込みで進める */
        inline fun tone(start: Double, dur: Double, midi: Double, pan: Double, crossinline wave: (t: Double, ph: Double) -> Double) {
            val w = 2 * PI * freq(midi) / rate
            var ph = 0.0
            val startT = start
            put(false, start, dur, pan) { _, t ->
                ph += if (wow > 0) w * (1 + wow * sin(2 * PI * 0.6 * (startT + t)) + wow * 0.4 * sin(2 * PI * 4.3 * (startT + t))) else w
                wave(t, ph)
            }
        }

        // ---- 鍵盤・弦のたぐい ----
        /** エレクトリックピアノ（ローズのような、丸くて少し鈴っぽい音） */
        fun ep(start: Double, dur: Double, midi: Int, amp: Double, pan: Double = 0.0) = tone(start, dur + 0.25, midi.toDouble(), pan) { t, ph ->
            val env = (1 - exp(-t * 500)) * exp(-t * 1.7) * (if (t > dur) exp(-(t - dur) * 16) else 1.0)
            val index = (1.5 * exp(-t * 6) + 0.25) * (0.5 + amp * 3)
            amp * env * (sin(ph + index * sin(ph)) + 0.18 * exp(-t * 20) * sin(ph * 11)) * (1 + 0.07 * sin(2 * PI * 4.6 * t))
        }
        /** ビブラフォン（澄んだ金属の音。ゆっくり震える） */
        fun vibes(start: Double, midi: Int, amp: Double, pan: Double = 0.0) = tone(start, 1.6, midi.toDouble(), pan) { t, ph ->
            amp * (1 - exp(-t * 600)) * (sin(ph) * exp(-t * 2.4) + 0.3 * sin(ph * 4) * exp(-t * 10)) * (1 + 0.22 * sin(2 * PI * 5.5 * t))
        }
        /** 低いベース（丸い音。少しだけ歪ませて、小さなスピーカーでも聞こえるように） */
        fun sub(start: Double, dur: Double, midi: Int, amp: Double) = tone(start, dur + 0.08, midi.toDouble(), 0.0) { t, ph ->
            val env = (1 - exp(-t * 250)) * (if (t > dur) exp(-(t - dur) * 40) else 1.0) * (0.75 + 0.25 * exp(-t * 4))
            amp * env * (tanh(1.8 * sin(ph)) * 0.8 + 0.25 * sin(ph * 2))
        }
        /** ウッドベース（はじいて、すっと消える） */
        fun upright(start: Double, dur: Double, midi: Int, amp: Double) {
            val w = 2 * PI * freq(midi.toDouble()) / rate
            var ph = 0.0
            put(false, start, dur + 0.1, 0.0) { _, t ->
                ph += w * (1 - 0.03 * exp(-t * 60)) // 弾いた瞬間に、音程がわずかに下からずり上がる
                val env = (1 - exp(-t * 400)) * exp(-t * 2.8) * (if (t > dur) exp(-(t - dur) * 30) else 1.0)
                amp * env * (sin(ph) + 0.45 * sin(ph * 2) * exp(-t * 7) + 0.25 * sin(ph * 3) * exp(-t * 12))
            }
        }
        /** 短い和音のひと突き（ハウスの「スタブ」）。bright が大きいほど明るい */
        fun stab(start: Double, dur: Double, midi: Int, amp: Double, pan: Double, bright: Double) = tone(start, dur + 0.12, midi.toDouble(), pan) { t, ph ->
            var s = 0.0
            for (k in 1..7) s += sin(ph * k) / k * exp(-t * (1.0 + k * (6.0 - 4.5 * bright)))
            amp * (1 - exp(-t * 700)) * (if (t > dur) exp(-(t - dur) * 30) else 1.0) * s
        }
        /** 広がりのある長い和音（パッド）。左右で少し音程をずらす */
        fun pad(start: Double, dur: Double, midi: Int, amp: Double) {
            for ((detune, pan) in listOf(-0.06 to -0.6, 0.06 to 0.6)) tone(start, dur + 0.6, midi + detune, pan) { t, ph ->
                val env = (t / 0.5).coerceAtMost(1.0) * (if (t > dur) exp(-(t - dur) * 4) else 1.0)
                amp * env * (sin(ph) + 0.3 * sin(ph * 2) + 0.12 * sin(ph * 3))
            }
        }
        /** のびやかな主旋律の音（少し鋭い） */
        fun lead(start: Double, dur: Double, midi: Int, amp: Double, pan: Double = 0.0) = tone(start, dur + 0.2, midi.toDouble(), pan) { t, ph ->
            val env = (1 - exp(-t * 60)) * (if (t > dur) exp(-(t - dur) * 14) else 1.0)
            val vib = 0.25 * (t / 0.3).coerceAtMost(1.0) * sin(2 * PI * 5.2 * t)
            amp * env * (sin(ph + vib) + 0.35 * sin(2 * ph + vib) + 0.15 * sin(3 * ph))
        }

        // ---- 打楽器 ----
        private inline fun noiseHit(drum: Boolean, start: Double, dur: Double, pan: Double, low: Double, high: Double, crossinline env: (t: Double) -> Double) {
            var lp = 0.0
            var hp = 0.0
            put(drum, start, dur, pan) { _, t ->
                val white = rng.nextDouble() * 2 - 1
                lp += low * (white - lp)          // low が小さいほど、こもる
                hp += high * (lp - hp)            // high が大きいほど、低い所を削る
                env(t) * (lp - hp)
            }
        }
        fun kick(start: Double, amp: Double, soft: Boolean = false, duck: Double = 0.0) {
            var ph = 0.0
            val top = if (soft) 60.0 else 110.0
            put(true, start, 0.32, 0.0) { _, t ->
                ph += 2 * PI * (48 + top * exp(-t * (if (soft) 22 else 32))) / rate
                amp * (sin(ph) * exp(-t * (if (soft) 8.0 else 10.0)) + (if (soft) 0.0 else 0.25 * exp(-t * 600) * (rng.nextDouble() * 2 - 1)))
            }
            if (duck > 0) kicks += start to duck
        }
        fun snare(start: Double, amp: Double, pan: Double = 0.05) {
            noiseHit(true, start, 0.22, pan, 0.5, 0.06) { t -> amp * exp(-t * 20) }
            put(true, start, 0.12, pan) { _, t -> amp * 0.5 * sin(2 * PI * 190 * t) * exp(-t * 30) }
        }
        fun rim(start: Double, amp: Double, pan: Double = -0.15) = put(true, start, 0.06, pan) { _, t ->
            amp * (sin(2 * PI * 1750 * t) + 0.5 * sin(2 * PI * 820 * t)) * exp(-t * 95)
        }
        fun clap(start: Double, amp: Double) {
            for (d in doubleArrayOf(0.0, 0.011, 0.023)) noiseHit(true, start + d, 0.02, 0.0, 0.6, 0.12) { t -> amp * 0.8 * exp(-t * 220) }
            noiseHit(true, start + 0.03, 0.22, 0.0, 0.6, 0.12) { t -> amp * exp(-t * 17) }
        }
        fun hat(start: Double, amp: Double, open: Boolean = false, pan: Double = 0.25) =
            noiseHit(true, start, if (open) 0.28 else 0.06, pan, 1.0, 0.75) { t -> amp * exp(-t * (if (open) 13.0 else 75.0)) }
        fun shaker(start: Double, amp: Double, pan: Double = -0.3) =
            noiseHit(true, start, 0.07, pan, 0.9, 0.5) { t -> amp * (1 - exp(-t * 300)) * exp(-t * 45) }
        /** ライドシンバル（ジャズの「チーン、チッチ」） */
        fun ride(start: Double, amp: Double, long: Boolean = false) {
            val partials = doubleArrayOf(3170.0, 4390.0, 5230.0, 6780.0, 8410.0)
            val decay = if (long) 1.8 else 5.5
            put(true, start, if (long) 2.0 else 0.6, 0.3) { _, t ->
                var s = 0.0
                for (p in partials) s += sin(2 * PI * p * t)
                amp * exp(-t * decay) * (s / partials.size * 0.7 + (rng.nextDouble() * 2 - 1) * 0.5 * exp(-t * 30))
            }
        }
        fun brush(start: Double, amp: Double) = noiseHit(true, start, 0.16, -0.1, 0.35, 0.2) { t -> amp * sin(PI * (t / 0.16).coerceAtMost(1.0)) }
        fun crash(start: Double, amp: Double) = noiseHit(true, start, 1.6, 0.0, 1.0, 0.55) { t -> amp * exp(-t * 2.6) }

        // ---- 雰囲気の音 ----
        /** レコードのぱちぱち音と、うっすらしたさーっという音 */
        fun crackle(seconds: Double, amp: Double) {
            var hiss = 0.0
            var pop = 0.0
            put(true, 0.0, seconds, 0.0) { _, _ ->
                val white = rng.nextDouble() * 2 - 1
                hiss += 0.3 * (white - hiss)
                if (rng.nextDouble() < 0.0007) pop = (rng.nextDouble() * 2 - 1) * (0.4 + rng.nextDouble())
                pop *= 0.55
                amp * (hiss * 0.25 + pop)
            }
        }
        /** 盛り上げのノイズ（だんだん大きく、明るく） */
        fun riser(start: Double, dur: Double, amp: Double) {
            var lp = 0.0
            put(true, start, dur, 0.0) { _, t ->
                val p = t / dur
                val white = rng.nextDouble() * 2 - 1
                lp += (0.05 + 0.9 * p * p) * (white - lp)
                amp * p * p * lp
            }
        }
        fun rain(seconds: Double, amp: Double) {
            var lp = 0.0
            put(true, 0.0, seconds, 0.0) { _, _ ->
                lp += 0.35 * ((rng.nextDouble() * 2 - 1) - lp)
                amp * lp
            }
            repeat((seconds * 10).toInt()) {
                val f = 1600 + rng.nextDouble() * 2800
                put(true, rng.nextDouble() * seconds, 0.03, rng.nextDouble() * 1.6 - 0.8) { _, t -> amp * 0.9 * sin(2 * PI * f * t) * exp(-t * 170) }
            }
        }
    }

    /** 和音を、前の和音の近くに置く（音がなめらかにつながる）。ベースが根音を弾くので、ここでは根音を抜く */
    private fun voicing(plan: TunePlan, chord: Chord, center: Int): IntArray {
        val intervals = if (chord.tones.size >= 5) chord.tones.filter { it != 0 && it != 7 } else chord.tones.filter { it != 0 }
        return intervals.map { iv ->
            val pc = Math.floorMod(plan.rootMidi + chord.root + iv, 12)
            var note = center - 6 + Math.floorMod(pc - (center - 6), 12)
            if (note > center + 5) note -= 12
            note
        }.sorted().toIntArray()
    }

    /** 旋律に使う音（5音音階。にごりにくい） */
    private fun melodyNotes(plan: TunePlan, lowest: Int): List<Int> {
        val pcs = if (plan.tonality == 0) intArrayOf(0, 2, 4, 7, 9) else intArrayOf(0, 3, 5, 7, 10)
        return (lowest..lowest + 26).filter { Math.floorMod(it - plan.rootMidi, 12) in pcs }
    }

    /**
     * 曲を作る。
     * @return 左右2チャンネルを交互に並べた音（-1〜1）。長さは seconds × rate × 2
     */
    fun render(plan: TunePlan, seed: Long, seconds: Double = 10.0, rate: Int = 44100): FloatArray {
        val rng = Random(seed xor 0x5EED)
        val n = (seconds * rate).toInt()
        val st = Studio(n, rate, rng)
        val mood = plan.mood
        val beat = 60.0 / plan.bpm
        val end = seconds - 1.5                       // ここで最後の和音を鳴らす
        val start = end - plan.beats * beat           // ここからリズムが始まる
        val cold = mood.cold
        val sparse = mood.feeling <= -1

        // はね具合：8分のはね（ジャズ）と、16分のはね（ローファイ・ハウス）
        val swing8 = if (plan.style == TuneStyle.JAZZY) 0.64 else 0.5
        val swing16 = when (plan.style) { TuneStyle.LOFI -> 0.58 + rng.nextDouble() * 0.04; TuneStyle.HOUSE -> 0.54; else -> 0.5 }
        /** 拍の位置（小数つき）を、はねを入れた秒に直す */
        fun at(pos: Double, human: Double = 0.0): Double {
            val whole = Math.floor(pos)
            var frac = pos - whole
            if (swing8 != 0.5) {
                frac = if (frac < 0.5) frac * swing8 / 0.5 else swing8 + (frac - 0.5) * (1 - swing8) / 0.5
            } else if (swing16 != 0.5) {
                val half = Math.floor(frac / 0.5)
                var inner = (frac - half * 0.5) / 0.5
                inner = if (inner < 0.5) inner * swing16 / 0.5 else swing16 + (inner - 0.5) * (1 - swing16) / 0.5
                frac = (half + inner) * 0.5
            }
            return start + (whole + frac) * beat + (if (human > 0) (rng.nextDouble() - 0.5) * 2 * human else 0.0)
        }
        fun chordAt(pos: Double): Chord = plan.chords[((pos / plan.chordBeats).toInt()).coerceAtLeast(0) % plan.chords.size]
        fun chordIndexAt(pos: Double): Int = (pos / plan.chordBeats).toInt()

        // 和音の置き方を、先に全部決めておく（前の和音から近い所へ）
        val slots = plan.beats / plan.chordBeats
        val voicings = ArrayList<IntArray>()
        var center = if (plan.style == TuneStyle.LOFI || plan.style == TuneStyle.JAZZY) 62 else 65
        for (i in 0 until slots) {
            val v = voicing(plan, plan.chords[i % plan.chords.size], center)
            voicings += v
            center = (v.sum().toDouble() / v.size).toInt().coerceIn(58, 68)
        }
        val finalChord = if (plan.tonality == 0) Chord(0, "69") else Chord(0, "m9")
        val finalVoicing = voicing(plan, finalChord, center)
        fun bassNote(chord: Chord, low: Int = 36): Int {
            var note = low + Math.floorMod(plan.rootMidi + chord.root - low, 12)
            if (note > low + 9) note -= 12
            return note.coerceAtLeast(28)
        }
        /** 主旋律の楽器。寒い日はビブラフォン、そうでなければ型ごとの音 */
        fun top(time: Double, dur: Double, midi: Int, amp: Double, pan: Double) {
            when {
                cold || plan.style == TuneStyle.JAZZY -> st.vibes(time, midi, amp * 1.1, pan)
                plan.style == TuneStyle.CLUB -> st.lead(time, dur, midi, amp * 0.8, pan)
                else -> st.ep(time, dur, midi, amp, pan)
            }
        }
        /**
         * 旋律：1小節ぶんの短い「ひとふし」を作り、和音に合わせてくり返す（少しずつ形を変える）。
         * 拍の頭の音は、その和音の音に寄せる。
         */
        fun melody(fromBeat: Int, lowest: Int, density: Int, amp: Double, lazy: Double, withEcho: Boolean) {
            val notes = melodyNotes(plan, lowest)
            // ひとふしのリズム（拍の位置と長さ）。8分音符のます目から、いくつか選ぶ
            val grid = (0 until 8).map { it * 0.5 }.filter { rng.nextInt(8) < density || it == 0.0 }.take(6)
            var idx = notes.size / 2 + rng.nextInt(3) - 1
            val steps = grid.map { intArrayOf(-2, -1, -1, 1, 1, 2, 0)[rng.nextInt(7)] }
            var bar = fromBeat / 4
            while (bar * 4 < plan.beats) {
                var cursor = idx
                for ((k, posInBar) in grid.withIndex()) {
                    val pos = bar * 4 + posInBar
                    if (pos < fromBeat || pos >= plan.beats - 0.25) continue
                    cursor = (cursor + steps[k]).coerceIn(1, notes.size - 2)
                    val chordPcs = chordAt(pos).pitchClasses(plan.rootMidi)
                    if (posInBar % 1.0 == 0.0) {
                        // 拍の頭：近くにある和音の音へ
                        cursor = (cursor - 2..cursor + 2).filter { it in notes.indices && Math.floorMod(notes[it], 12) in chordPcs }
                            .minByOrNull { abs(it - cursor) } ?: cursor
                    }
                    val next = grid.getOrNull(k + 1) ?: 4.0
                    val dur = ((next - posInBar) * beat * 0.9).coerceAtMost(beat * 1.5)
                    val time = at(pos) + lazy
                    val velocity = amp * (0.8 + rng.nextDouble() * 0.3) * (if (posInBar % 1.0 == 0.0) 1.0 else 0.85)
                    top(time, dur, notes[cursor], velocity, 0.15)
                    if (withEcho) {
                        // やまびこ（付点8分おくれ）を、左右に振る
                        top(time + beat * 0.75, dur * 0.7, notes[cursor], velocity * 0.35, -0.6)
                        top(time + beat * 1.5, dur * 0.6, notes[cursor], velocity * 0.16, 0.6)
                    }
                }
                // 次の小節は、同じ形で少し動かす（問いかけと答えのように）
                idx = (idx + intArrayOf(-1, 0, 1, 2)[rng.nextInt(4)]).coerceIn(2, notes.size - 3)
                bar++
            }
        }

        when (plan.style) {
            // ================= ローファイ・ヒップホップ =================
            TuneStyle.LOFI -> {
                st.wow = 0.0022
                st.crackle(seconds, 0.05)
                // 出だし：最後の和音を、ぽろんと弾いてから入る
                val pre = voicings.last()
                val introAt = (start - 2 * beat).coerceAtLeast(0.15)
                pre.forEachIndexed { i, note -> st.ep(introAt + i * 0.03, start - introAt, note, 0.085, -0.2 + 0.13 * i) }
                st.snare(start - beat * 0.5, 0.10); st.snare(start - beat * 0.25, 0.16)
                val extraKick = rng.nextInt(10) < 4
                for (b in 0 until plan.beats) {
                    val inBar = b % 4
                    // ドラム：キックは1拍目と「3拍目の少し前」、スネア（またはリム）は2・4拍。わざと少し後ろにもたれる
                    if (inBar == 0) st.kick(at(b.toDouble()), 0.62, soft = true, duck = 0.3)
                    if (inBar == 2) st.kick(at(b + 0.5), 0.5, soft = true, duck = 0.25)
                    if (inBar == 1 && extraKick && !sparse) st.kick(at(b + 0.75), 0.36, soft = true, duck = 0.15)
                    if (inBar == 1 || inBar == 3) {
                        if (sparse) st.rim(at(b.toDouble()) + 0.012, 0.3) else st.snare(at(b.toDouble()) + 0.012, 0.34)
                    }
                    st.hat(at(b.toDouble(), 0.006), 0.10 + rng.nextDouble() * 0.04)
                    if (rng.nextInt(10) != 0) st.hat(at(b + 0.5, 0.008), 0.06 + rng.nextDouble() * 0.04)
                    if (!sparse && rng.nextInt(10) < 3) st.hat(at(b + 0.75, 0.008), 0.05)
                    // 和音：2拍ごと。指を少しずつずらして弾く。ときどき、次の和音の直前にもう一度軽く弾く
                    if (b % plan.chordBeats == 0) {
                        val slot = chordIndexAt(b.toDouble())
                        val v = voicings[slot % voicings.size]
                        val t0 = at(b.toDouble()) + 0.01
                        v.forEachIndexed { i, note -> st.ep(t0 + i * 0.018, beat * 1.7, note, 0.105 * (0.85 + rng.nextDouble() * 0.3), -0.25 + 0.16 * i) }
                        if (!sparse && rng.nextBoolean()) v.forEachIndexed { i, note -> st.ep(at(b + 1.5) + i * 0.012, beat * 0.4, note, 0.06, -0.25 + 0.16 * i) }
                        // ベース：根音を長く。次の和音の前に、ひとつ経過音
                        val chord = chordAt(b.toDouble())
                        st.sub(at(b.toDouble()), beat * 1.3, bassNote(chord), 0.36)
                        if (rng.nextInt(10) < 6) {
                            val next = plan.chords[(slot + 1) % plan.chords.size]
                            st.sub(at(b + 1.5), beat * 0.4, bassNote(next) + (if (rng.nextBoolean()) -1 else 7).let { if (bassNote(next) + it > 50) it - 12 else it }, 0.22)
                        }
                    }
                }
                melody(fromBeat = if (sparse) 4 else 0, lowest = plan.rootMidi + 19, density = if (sparse) 2 else 3, amp = 0.085, lazy = 0.02, withEcho = false)
            }

            // ================= ジャジー（スウィング） =================
            TuneStyle.JAZZY -> {
                // 出だし：和音をふわっと、ドラムの「タタッ」で入る
                voicings.last().forEachIndexed { i, note -> st.ep((start - 1.5 * beat).coerceAtLeast(0.1) + i * 0.03, beat * 1.2, note, 0.075, -0.2 + 0.14 * i) }
                st.snare(start - beat * 0.66, 0.10); st.snare(start - beat * 0.33, 0.16)
                val charleston = rng.nextBoolean()
                for (b in 0 until plan.beats) {
                    val inBar = b % 4
                    val pos = b.toDouble()
                    // ライド：4分音符＋「2拍目と4拍目の裏」。ハイハットは2・4拍。キックはごく小さく
                    st.ride(at(pos, 0.004), if (inBar == 1 || inBar == 3) 0.13 else 0.10)
                    if (inBar == 1 || inBar == 3) { st.ride(at(pos + 0.5, 0.004), 0.075); st.hat(at(pos), 0.11, pan = -0.3) }
                    st.kick(at(pos), if (inBar == 0) 0.22 else 0.10, soft = true)
                    if (!sparse && rng.nextInt(10) < 3) st.snare(at(pos + 0.5, 0.006), 0.07)
                    if (inBar == 1 || inBar == 3) st.brush(at(pos), 0.05)
                    // 歩くベース：和音の頭は根音、次の拍は、次の根音へ半音で近づく音
                    val slot = chordIndexAt(pos)
                    val chord = plan.chords[slot % plan.chords.size]
                    val nextChord = plan.chords[(slot + 1) % plan.chords.size]
                    val root = bassNote(chord, 40)
                    if (b % plan.chordBeats == 0) st.upright(at(pos, 0.004), beat * 0.9, root, 0.42)
                    else {
                        val target = if (b == plan.beats - 1) bassNote(finalChord, 40) else bassNote(nextChord, 40)
                        val approach = target + (if (rng.nextBoolean()) 1 else -1)
                        st.upright(at(pos, 0.004), beat * 0.9, approach, 0.36)
                    }
                    // ピアノの合いの手：拍の頭と「2拍目の裏」（チャールストン）。裏の一打は、次の和音を先取りする
                    val v = voicings[slot % voicings.size]
                    val bar = b / 4
                    val pattern = if ((bar % 2 == 0) == charleston) doubleArrayOf(0.0, 1.5) else doubleArrayOf(0.5, 2.0)
                    for (p in pattern) {
                        if (inBar.toDouble() == Math.floor(p)) {
                            val hitPos = bar * 4 + p
                            val use = if (p % 1.0 != 0.0) voicings[chordIndexAt(hitPos + 0.5).coerceAtMost(slots - 1) % voicings.size] else v
                            val long = p % 1.0 == 0.0
                            use.forEachIndexed { i, note -> st.ep(at(hitPos, 0.006) + i * 0.006, if (long) beat * 0.8 else beat * 0.3, note, 0.085 * (0.85 + rng.nextDouble() * 0.3), -0.3 + 0.18 * i) }
                        }
                    }
                }
                melody(fromBeat = 0, lowest = plan.rootMidi + 19, density = if (sparse) 3 else 5, amp = 0.10, lazy = 0.0, withEcho = false)
            }

            // ================= ディープハウス ／ クラブ =================
            TuneStyle.HOUSE, TuneStyle.CLUB -> {
                val club = plan.style == TuneStyle.CLUB
                val duck = if (club) 0.6 else 0.55
                // 出だし：ノイズがふくらんで、1拍目のキックへ
                st.riser(0.0, start, 0.16)
                // 2小節目の頭（ここで全部の音がそろう）へ向けて、もう一度ふくらませる
                st.riser(start + 2 * beat, 2 * beat, 0.14)
                st.crash(start + 4 * beat, 0.16)
                val stabPattern = listOf(intArrayOf(0, 3, 6, 10, 12), intArrayOf(0, 3, 6, 8, 11, 14), intArrayOf(2, 5, 8, 11, 14), intArrayOf(0, 4, 7, 10, 14))[rng.nextInt(4)]
                val arpShape = listOf(intArrayOf(0, 1, 2, 1, 3, 2, 1, 2), intArrayOf(0, 2, 1, 3, 2, 1, 3, 2), intArrayOf(0, 1, 2, 3, 2, 1, 2, 3))[rng.nextInt(3)]
                val deepStab = rng.nextBoolean() // 丸いエレピの音か、明るい鍵盤の音か
                for (b in 0 until plan.beats) {
                    val bar = b / 4
                    val inBar = b % 4
                    val pos = b.toDouble()
                    val full = bar >= 1                       // 2小節目から全部入る
                    val lastBeats = b >= plan.beats - 2       // 最後の2拍は、ドラムを抜いて「ため」を作る
                    // キックは4つ打ち。クラップは2・4拍。裏拍にオープンハイハット。16分のハイハットは小さく
                    if (!lastBeats) st.kick(at(pos), 0.72, duck = duck)
                    if (full && (inBar == 1 || inBar == 3) && !lastBeats) st.clap(at(pos), 0.30)
                    if (full && !lastBeats) st.hat(at(pos + 0.5), 0.16, open = true)
                    for (q in 0 until 4) {
                        if (q == 2 && full) continue
                        st.hat(at(pos + q * 0.25, 0.003), (if (q == 0) 0.05 else 0.075) * (if (full) 1.0 else 0.6), pan = if (q % 2 == 0) 0.3 else -0.3)
                    }
                    if (full && !sparse) { st.shaker(at(pos + 0.25), 0.05); st.shaker(at(pos + 0.75), 0.07) }
                    if (lastBeats) for (q in 0 until 4) st.snare(at(pos + q * 0.25), 0.10 + 0.05 * ((b - (plan.beats - 2)) * 4 + q)) // だんだん大きくなる連打
                    val slot = chordIndexAt(pos)
                    val chord = plan.chords[slot % plan.chords.size]
                    val v = voicings[slot % voicings.size]
                    // パッド：和音を長くのばす
                    if (b % plan.chordBeats == 0) v.forEach { st.pad(at(pos), plan.chordBeats * beat, it, if (club) 0.05 else 0.035) }
                    // ベース：裏拍で弾む（キックとぶつからない）
                    if (full) {
                        val root = bassNote(chord)
                        st.sub(at(pos + 0.5), beat * 0.42, root, 0.40)
                        if (inBar == 3 && !sparse) st.sub(at(pos + 0.75), beat * 0.2, root + 12, 0.22)
                    } else if (inBar == 0) st.sub(at(pos), beat * 3.5, bassNote(chord), 0.26)
                    if (club) {
                        // クラブ：16分音符の分散和音。3つごとに強く（3-3-2 のうねり）。1小節目は暗く、だんだん明るく
                        val bright = if (full) 0.75 else 0.15 + 0.5 * (b / 4.0)
                        for (q in 0 until 4) {
                            val step = b * 4 + q
                            val tone = arpShape[step % arpShape.size]
                            val note = v[tone % v.size] + 12 * (tone / v.size) + 12
                            val accent = if (step % 8 == 0 || step % 8 == 3 || step % 8 == 6) 1.0 else 0.6
                            st.stab(at(pos + q * 0.25), beat * 0.2, note, 0.075 * accent, if (step % 2 == 0) -0.35 else 0.35, bright)
                        }
                    } else {
                        // ハウス：和音の短いひと突きを、食い気味のリズムで。1小節目はこもらせておく
                        for (sixteenth in stabPattern) {
                            if (sixteenth / 4 != inBar) continue
                            val hit = bar * 4 + sixteenth / 4.0
                            val bright = if (full) 0.7 else 0.25
                            v.forEachIndexed { i, note ->
                                if (deepStab) st.ep(at(hit), beat * 0.32, note, 0.085, -0.3 + 0.2 * i)
                                else st.stab(at(hit), beat * 0.3, note, 0.07, -0.3 + 0.2 * i, bright)
                            }
                        }
                    }
                }
                // 後半から、短い旋律（クラブは、やまびこつきの長めの音）
                melody(fromBeat = 8, lowest = plan.rootMidi + 24, density = if (club) 2 else 3, amp = if (club) 0.10 else 0.085, lazy = 0.0, withEcho = true)
            }
        }

        // ---- 天気の音 ----
        if (mood.sky == Sky.RAIN) st.rain(seconds, if (plan.style == TuneStyle.LOFI) 0.05 else 0.035)
        if (mood.sky == Sky.SNOW) repeat(5) {
            val notes = melodyNotes(plan, plan.rootMidi + 31)
            st.vibes(start + rng.nextDouble() * (end - start), notes[rng.nextInt(notes.size.coerceAtMost(6))], 0.05, rng.nextDouble() * 1.4 - 0.7)
        }

        // ---- 終わり：主和音を鳴らして、余韻を残す ----
        val bassEnd = bassNote(finalChord)
        when (plan.style) {
            TuneStyle.LOFI -> {
                finalVoicing.forEachIndexed { i, note -> st.ep(end + i * 0.045, 1.3, note, 0.12, -0.3 + 0.2 * i) }
                st.sub(end, 1.2, bassEnd, 0.36)
                st.kick(end, 0.5, soft = true, duck = 0.3)
            }
            TuneStyle.JAZZY -> {
                finalVoicing.forEachIndexed { i, note -> st.ep(end + i * 0.03, 1.3, note, 0.11, -0.3 + 0.2 * i) }
                st.vibes(end + 0.12, finalVoicing.last() + 12, 0.10, 0.2)
                st.upright(end, 1.3, bassNote(finalChord, 40), 0.45)
                st.ride(end, 0.16, long = true)
                st.kick(end, 0.3, soft = true)
            }
            else -> {
                finalVoicing.forEach { st.pad(end, 1.0, it, 0.07) }
                finalVoicing.forEachIndexed { i, note -> st.stab(end, 0.9, note + 12, 0.08, -0.4 + 0.27 * i, 0.5) }
                st.sub(end, 1.1, bassEnd, 0.42)
                st.kick(end, 0.75, duck = 0.5)
                st.crash(end, 0.2)
            }
        }

        // ---- 仕上げ ----
        val l = FloatArray(n)
        val r = FloatArray(n)
        // 1) キックに合わせて、鍵盤やベースを一瞬へこませる（サイドチェイン。うねりが出る）
        val kicks = st.kicks.sortedBy { it.first }
        var ki = -1
        // 2) ハウス・クラブの1小節目は、こもった所から、だんだん明るく開いていく
        val sweep = plan.style == TuneStyle.HOUSE || plan.style == TuneStyle.CLUB
        val sweepEnd = start + 4 * beat
        var fl = 0f
        var fr = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            while (ki + 1 < kicks.size && kicks[ki + 1].first <= t) ki++
            val g = if (ki >= 0) (1 - kicks[ki].second * exp(-(t - kicks[ki].first) * 9)).toFloat() else 1f
            var ml = st.musicL[i] * g
            var mr = st.musicR[i] * g
            if (sweep && t < sweepEnd) {
                val p = (t / sweepEnd).coerceIn(0.0, 1.0)
                val a = (0.03 + 0.97 * p * p * p).toFloat()
                fl += a * (ml - fl); fr += a * (mr - fr)
                ml = fl; mr = fr
            }
            l[i] = ml + st.drumL[i]
            r[i] = mr + st.drumR[i]
        }
        // 3) 部屋の響き（リバーブ）
        val wet = when (plan.style) { TuneStyle.LOFI -> 0.13; TuneStyle.JAZZY -> 0.15; TuneStyle.HOUSE -> 0.17; TuneStyle.CLUB -> 0.21 } + (if (cold) 0.05 else 0.0)
        reverb(l, rate, wet, 0.0)
        reverb(r, rate, wet, 0.0009)
        // 4) ローファイは、高い音を落として、古いテープのように。暑い日も、少しこもらせる
        val cutoff = when {
            plan.style == TuneStyle.LOFI -> if (mood.hot) 2800.0 else 3800.0
            mood.hot -> 7000.0
            else -> 0.0
        }
        if (cutoff > 0) {
            val a = (1 - exp(-2 * PI * cutoff / rate)).toFloat()
            for (ch in arrayOf(l, r)) {
                var y1 = 0f
                var y2 = 0f
                for (i in 0 until n) { y1 += a * (ch[i] - y1); y2 += a * (y1 - y2); ch[i] = y2 }
            }
        }
        // 5) 音量をそろえ、やわらかく押さえ、始めと終わりをなめらかに
        var peak = 1e-6f
        for (i in 0 until n) { peak = maxOf(peak, abs(l[i]), abs(r[i])) }
        val gain = 1.5f / peak
        val fadeIn = (0.03 * rate).toInt()
        val fadeOut = (0.7 * rate).toInt()
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            var env = 0.9f
            if (i < fadeIn) env *= i.toFloat() / fadeIn
            if (i >= n - fadeOut) env *= (n - i).toFloat() / fadeOut
            out[i * 2] = tanh((l[i] * gain).toDouble()).toFloat() * env
            out[i * 2 + 1] = tanh((r[i] * gain).toDouble()).toFloat() * env
        }
        return out
    }

    /** 部屋の響き（くし形の遅れ4本＋通り抜け2本の、古典的なリバーブ）。offsetSec で左右を少しずらして広がりを出す */
    private fun reverb(x: FloatArray, rate: Int, wet: Double, offsetSec: Double) {
        val n = x.size
        val acc = FloatArray(n)
        for (d in doubleArrayOf(0.0297, 0.0371, 0.0411, 0.0437)) {
            val delay = ((d + offsetSec) * rate).toInt()
            val buf = FloatArray(n)
            for (i in 0 until n) {
                val fb = if (i >= delay) buf[i - delay] else 0f
                buf[i] = x[i] + fb * 0.78f
                acc[i] += fb
            }
        }
        for (d in doubleArrayOf(0.005, 0.0017)) {
            val delay = (d * rate).toInt()
            val y = FloatArray(n)
            for (i in 0 until n) {
                val xd = if (i >= delay) acc[i - delay] else 0f
                val yd = if (i >= delay) y[i - delay] else 0f
                y[i] = -0.7f * acc[i] + xd + 0.7f * yd
            }
            System.arraycopy(y, 0, acc, 0, n)
        }
        val w = (wet * 0.25).toFloat()
        for (i in 0 until n) x[i] += acc[i] * w
    }
}
