package com.chill.run.run

import com.chill.run.core.GoalKind
import com.chill.run.core.RunType
import kotlinx.coroutines.flow.MutableStateFlow

/** 走っている最中の、画面に出す値 */
data class LiveState(
    val active: Boolean = false,
    val type: RunType = RunType.ROAD,
    val goalKind: GoalKind = GoalKind.FREE,
    val goalValue: Double = 0.0,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val pace: Double? = null,
    val avgPace: Double? = null,
    val cadence: Int? = null,
    val altitudeM: Double? = null,
    val gainM: Double = 0.0,
    val lossM: Double = 0.0,
    val manualPaused: Boolean = false,
    val autoPaused: Boolean = false,
    val gpsAccuracyM: Float? = null,
    val elevationSource: String = "",
    val goalReached: Boolean = false,
    /** 終了して保存したランの番号。保存しなかったときは -1 */
    val finishedRunId: Long? = null,
)

/** 常駐サービスと画面のあいだで、いまの状態を受け渡す場所 */
object RunHub {
    val state = MutableStateFlow(LiveState())
}
