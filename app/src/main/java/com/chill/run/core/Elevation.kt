package com.chill.run.core

/**
 * 獲得標高（登った高さの合計）と下りの合計を数える。
 * 細かいゆれを数えすぎないよう、向きが変わるのは threshold（m）以上動いたときだけ。
 */
class ElevationGain(private val threshold: Double = 2.5) {
    var gain = 0.0
        private set
    var loss = 0.0
        private set
    private var dir = 0
    private var ref = Double.NaN

    fun add(alt: Double) {
        if (ref.isNaN()) {
            ref = alt
            return
        }
        when (dir) {
            0 -> if (alt - ref >= threshold) {
                gain += alt - ref; ref = alt; dir = 1
            } else if (ref - alt >= threshold) {
                loss += ref - alt; ref = alt; dir = -1
            }
            1 -> if (alt > ref) {
                gain += alt - ref; ref = alt
            } else if (ref - alt >= threshold) {
                loss += ref - alt; ref = alt; dir = -1
            }
            else -> if (alt < ref) {
                loss += ref - alt; ref = alt
            } else if (alt - ref >= threshold) {
                gain += alt - ref; ref = alt; dir = 1
            }
        }
    }
}

/** 値をなめらかにする（急な飛びをならす） */
class Smoother(private val alpha: Double) {
    var value = Double.NaN
        private set

    fun add(x: Double): Double {
        value = if (value.isNaN()) x else value + alpha * (x - value)
        return value
    }
}
