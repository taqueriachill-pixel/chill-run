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
}
