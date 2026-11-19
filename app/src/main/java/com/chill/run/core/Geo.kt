package com.chill.run.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 2地点の間の距離（メートル） */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** 気圧（hPa）から、標準大気での高さ（メートル）を出す。差を取って上がり下がりに使う */
    fun pressureToAltitude(hPa: Double): Double =
        44330.0 * (1.0 - (hPa / 1013.25).pow(1.0 / 5.255))
}

object Fmt {
    /** 12:34 や 1:02:03 の形 */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** 6'12" の形。出せないときは --'--" */
    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm <= 0 || secPerKm > 3600) return "--'--\""
        val t = Math.round(secPerKm).toInt()
        return "%d'%02d\"".format(t / 60, t % 60)
    }

    /** 読み上げ用：6分12秒 */
    fun paceSpoken(secPerKm: Double): String {
        val t = Math.round(secPerKm).toInt()
        val m = t / 60
        val s = t % 60
        return if (s == 0) "${m}分" else "${m}分${s}秒"
    }

    fun km(m: Double): String = "%.2f".format(m / 1000.0)

    /** 読み上げ用：3キロ、2.5キロ、500メートル */
    fun distanceSpoken(m: Double): String {
        val r = Math.round(m).toInt()
        return when {
            r < 1000 -> "${r}メートル"
            r % 1000 == 0 -> "${r / 1000}キロ"
            r % 100 == 0 -> "${r / 1000}.${(r % 1000) / 100}キロ"
            else -> "%.2fキロ".format(r / 1000.0)
        }
    }

    /** 読み上げ用：5分、1時間10分 */
    fun durationSpoken(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        val sb = StringBuilder()
        if (h > 0) sb.append("${h}時間")
        if (m > 0) sb.append("${m}分")
        if (s > 0 || sb.isEmpty()) sb.append("${s}秒")
        return sb.toString()
    }
}
