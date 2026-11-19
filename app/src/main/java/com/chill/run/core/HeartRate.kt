package com.chill.run.core

object HeartRate {
    /** Bluetooth の心拍数の通知（Heart Rate Measurement）の中身から、拍/分を取り出す */
    fun parse(value: ByteArray): Int? {
        if (value.size < 2) return null
        val sixteenBit = value[0].toInt() and 0x01 != 0
        return if (sixteenBit) {
            if (value.size < 3) null else (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else value[1].toInt() and 0xFF
    }

    /**
     * 肌に触れているか。機器が「触れているかどうか」を知らせない作りなら null。
     * 触れていない（false）ときの数字は当てにならないので使わない。
     */
    fun contact(value: ByteArray): Boolean? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt()
        val supported = flags and 0x04 != 0
        return if (supported) flags and 0x02 != 0 else null
    }

    /** 年齢からの最大心拍数の目安（208 − 0.7 × 年齢）。人によって大きくずれることがある */
    fun maxFromAge(age: Int): Int = Math.round(208 - 0.7 * age).toInt()
}

/**
 * 心拍ゾーン（カルボーネン法）。
 * 目標の心拍数 ＝（最大心拍数 − 安静時心拍数）× 強さ ＋ 安静時心拍数
 * ゾーン1 は強さ 50〜60%、ゾーン5 は 90% 以上。50% 未満は 0（ゾーンの外）。
 */
class HeartZones(val maxHr: Int, val restHr: Int) {
    private val reserve = (maxHr - restHr).coerceAtLeast(1)

    /** ゾーン z（1〜5）が始まる心拍数 */
    fun lowerBound(zone: Int): Int = Math.round(restHr + reserve * (0.4 + 0.1 * zone)).toInt()

    fun zoneOf(bpm: Int): Int {
        for (z in 5 downTo 1) if (bpm >= lowerBound(z)) return z
        return 0
    }

    /** ゾーンの範囲を「133〜146」のように出す。ゾーン5 は「172〜」 */
    fun rangeText(zone: Int): String =
        if (zone >= 5) "${lowerBound(5)}〜" else "${lowerBound(zone)}〜${lowerBound(zone + 1) - 1}"

    companion object {
        val names = listOf("ゾーン外", "とても楽", "楽（ゆっくりジョグ）", "ややきつい", "きつい", "とてもきつい")
    }
}

enum class HrAlert { HIGH, EASY }

/**
 * 心拍数から「ペースが今日に合っているか」を見張る。
 * ・目標より高いゾーンが 60秒 続いたら HIGH
 * ・目標より低いゾーンが 2分 続き、速く走りたいメニューなら EASY
 * ・同じ注意は 2分以上あける
 */
class HrCoach {
    private var targetKey = ""
    private var highSince = -1L
    private var lowSince = -1L
    private var lastAlertAt = -1L

    /**
     * @param lo 目標ゾーンの下、@param hi 上（0 なら見張らない）
     * @param climbing トレイルの登りの途中（心拍数が上がるのは当たり前なので、1ゾーンぶん大目に見る）
     */
    fun update(tMs: Long, zone: Int?, lo: Int, hi: Int, wantsFaster: Boolean, climbing: Boolean): HrAlert? {
        val key = "$lo-$hi-$wantsFaster"
        if (key != targetKey) {
            // 目標が変わったら（インターバルの切り替えなど）、数え直す
            targetKey = key
            highSince = -1
            lowSince = -1
        }
        if (zone == null || hi <= 0) {
            highSince = -1
            lowSince = -1
            return null
        }
        val high = zone > hi + (if (climbing) 1 else 0)
        val low = wantsFaster && zone < lo
        if (high) { if (highSince < 0) highSince = tMs } else highSince = -1
        if (low) { if (lowSince < 0) lowSince = tMs } else lowSince = -1

        val canAlert = lastAlertAt < 0 || tMs - lastAlertAt >= MIN_GAP_MS
        if (canAlert && highSince >= 0 && tMs - highSince >= HIGH_MS) {
            lastAlertAt = tMs
            highSince = tMs
            return HrAlert.HIGH
        }
        if (canAlert && lowSince >= 0 && tMs - lowSince >= LOW_MS) {
            lastAlertAt = tMs
            lowSince = tMs
            return HrAlert.EASY
        }
        return null
    }

    companion object {
        const val HIGH_MS = 60_000L
        const val LOW_MS = 120_000L
        const val MIN_GAP_MS = 120_000L
    }
}
