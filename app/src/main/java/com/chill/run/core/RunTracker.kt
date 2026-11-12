package com.chill.run.core

enum class RunType { ROAD, TRAIL }
enum class GoalKind { FREE, DISTANCE, TIME, INTERVAL }
enum class PhaseKind { WARMUP, RUN, REST, COOLDOWN }

/** インターバルの1区間。amount は、時間なら秒、距離ならメートル */
data class Phase(val kind: PhaseKind, val byDistance: Boolean, val amount: Double, val rep: Int)

/** インターバルのメニュー */
data class IntervalPlan(
    val warmupSec: Int = 0,
    val runByDistance: Boolean = false,
    /** 走る区間。時間なら秒、距離ならメートル */
    val runAmount: Double = 60.0,
    val restSec: Int = 60,
    val repeats: Int = 5,
    val cooldownSec: Int = 0,
) {
    fun phases(): List<Phase> {
        val out = ArrayList<Phase>()
        if (warmupSec > 0) out += Phase(PhaseKind.WARMUP, false, warmupSec.toDouble(), 0)
        for (r in 1..repeats.coerceAtLeast(1)) {
            out += Phase(PhaseKind.RUN, runByDistance, runAmount, r)
            // 最後の1本のあとは、休みをはさまずクールダウン（または終了）へ
            if (r < repeats && restSec > 0) out += Phase(PhaseKind.REST, false, restSec.toDouble(), r)
        }
        if (cooldownSec > 0) out += Phase(PhaseKind.COOLDOWN, false, cooldownSec.toDouble(), 0)
        return out
    }
}

/** インターバルで走り終えた1区間の記録 */
data class Lap(val kind: PhaseKind, val rep: Int, val distanceM: Double, val movingMs: Long) {
    val paceSecPerKm: Double? get() = if (distanceM > 5) movingMs / 1000.0 / (distanceM / 1000.0) else null
}
enum class AnnounceUnit { DISTANCE, TIME }

data class RunConfig(
    val type: RunType = RunType.ROAD,
    val goalKind: GoalKind = GoalKind.FREE,
    /** 目標。距離ならメートル、時間なら秒 */
    val goalValue: Double = 0.0,
    val autoPause: Boolean = true,
    val announceUnit: AnnounceUnit = AnnounceUnit.DISTANCE,
    /** お知らせの間隔。距離ならメートル、時間なら秒 */
    val announceEvery: Double = 1000.0,
    val gainThresholdM: Double = 2.5,
    val interval: IntervalPlan? = null,
)

/** 1km ごとの区切り（最後の端数も含む） */
data class Split(
    val index: Int,
    val distanceM: Double,
    val movingMs: Long,
    val gainM: Double,
    val lossM: Double,
    val steps: Int,
) {
    val paceSecPerKm: Double? get() = if (distanceM > 1) movingMs / 1000.0 / (distanceM / 1000.0) else null
    val cadence: Int get() = if (movingMs > 0) Math.round(steps / (movingMs / 60000.0)).toInt() else 0
}

/** 保存する1地点 */
data class TrackSample(
    val lat: Double,
    val lon: Double,
    val altM: Double?,
    val accuracyM: Float,
    val distM: Double,
    val movingMs: Long,
)

sealed interface TrackerEvent {
    /** お知らせの間隔に届いた */
    data class Interval(
        val index: Int,
        val totalDistM: Double,
        val totalMovingMs: Long,
        val paceSecPerKm: Double?,
        val prevPaceSecPerKm: Double?,
        /** この区間の登りと下り（メートル） */
        val gainM: Double = 0.0,
        val lossM: Double = 0.0,
    ) : TrackerEvent

    /** インターバル：切り替えの3秒前から「3、2、1」 */
    data class PhaseCountdown(val seconds: Int) : TrackerEvent

    /** インターバル：区間が切り替わった。next が null ならメニュー終了 */
    data class PhaseChange(val next: Phase?, val finished: Lap, val totalReps: Int) : TrackerEvent

    /** 目標の手前。remaining は距離ならメートル、時間なら秒 */
    data class Countdown(val kind: GoalKind, val remaining: Int) : TrackerEvent
    data object GoalReached : TrackerEvent
    data class AutoPause(val paused: Boolean) : TrackerEvent
}

/**
 * ランの記録の中身。Android の部品に頼らないので、パソコン上でテストできる。
 * 時刻はすべて「止まらない時計」のミリ秒で受け取る。
 */
class RunTracker(val config: RunConfig) {
    var distanceM = 0.0; private set
    var movingMs = 0L; private set
    var steps = 0; private set
    var manualPaused = false; private set
    var autoPaused = false; private set
    var altitudeM: Double? = null; private set
    var lastAccuracyM: Float? = null; private set
    var goalReached = false; private set
    val isPaused get() = manualPaused || autoPaused
    val gainM get() = elevation.gain
    val lossM get() = elevation.loss

    private val elevation = ElevationGain(config.gainThresholdM)
    private val altSmoother = Smoother(0.2)
    private val _splits = ArrayList<Split>()
    val splits: List<Split> get() = _splits

    private var lastTickMs = -1L
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var lastFixMs = -1L
    private var lastSpeed = 0.0
    private var slowSinceMs = -1L
    private val stepTimes = ArrayDeque<Long>()
    private var stepsSeen = false
    private val paceWindow = ArrayDeque<Pair<Long, Double>>()

    // 1km の区切り用
    private var splitStartDist = 0.0
    private var splitStartMs = 0L
    private var splitStartGain = 0.0
    private var splitStartLoss = 0.0
    private var splitStartSteps = 0

    // お知らせ用
    private var intervalIndex = 0
    private var intervalStartDist = 0.0
    private var intervalStartMs = 0L
    private var prevIntervalPace: Double? = null
    private val firedCountdowns = HashSet<Int>()
    private var intervalStartGain = 0.0
    private var intervalStartLoss = 0.0

    // インターバル用
    private val phases: List<Phase> = config.interval?.phases().orEmpty()
    private var phaseIndex = 0
    private var phaseStartDist = 0.0
    private var phaseStartMs = 0L
    private val firedPhaseCountdowns = HashSet<Int>()
    private val _laps = ArrayList<Lap>()
    val laps: List<Lap> get() = _laps
    val currentPhase: Phase? get() = phases.getOrNull(phaseIndex)

    /** いまの区間の残り。時間なら秒、距離ならメートル */
    fun phaseRemaining(): Double? {
        val p = currentPhase ?: return null
        return if (p.byDistance) p.amount - (distanceM - phaseStartDist) else p.amount - (movingMs - phaseStartMs) / 1000.0
    }

    fun setManualPaused(paused: Boolean) {
        manualPaused = paused
        if (!paused) resetAnchor()
    }

    private fun resetAnchor() {
        anchorLat = Double.NaN
        anchorLon = Double.NaN
    }

    /** GPS の位置を受け取る。保存すべき地点なら返す */
    fun onLocation(tMs: Long, lat: Double, lon: Double, accuracyM: Float, speedMps: Float?): TrackSample? {
        lastAccuracyM = accuracyM
        if (accuracyM > MAX_ACCURACY_M) return null
        val prevFix = lastFixMs
        lastFixMs = tMs
        if (anchorLat.isNaN()) {
            anchorLat = lat; anchorLon = lon
            if (speedMps != null) lastSpeed = speedMps.toDouble()
            return if (isPaused) null else TrackSample(lat, lon, altitudeM, accuracyM, distanceM, movingMs)
        }
        val d = Geo.distanceM(anchorLat, anchorLon, lat, lon)
        val dt = if (prevFix >= 0) (tMs - prevFix) / 1000.0 else 1.0
        lastSpeed = speedMps?.toDouble() ?: if (dt > 0) d / dt else 0.0
        if (isPaused) {
            // 止まっている間は距離を足さない。動き出したらここから数え直す
            anchorLat = lat; anchorLon = lon
            return null
        }
        if (d < MIN_MOVE_M) return null
        // ありえない速さの飛び（GPS の誤り）は捨てる
        if (dt > 0 && d / dt > MAX_SPEED_MPS && d > 30) {
            anchorLat = lat; anchorLon = lon
            return null
        }
        distanceM += d
        anchorLat = lat; anchorLon = lon
        return TrackSample(lat, lon, altitudeM, accuracyM, distanceM, movingMs)
    }

    /** 標高（合わせ済みの値）を受け取る */
    fun onAltitude(altM: Double) {
        val s = altSmoother.add(altM)
        altitudeM = s
        if (!manualPaused) elevation.add(s)
    }

    /** 歩数センサーが1歩を知らせた */
    fun onStep(tMs: Long) {
        stepsSeen = true
        stepTimes.addLast(tMs)
        while (stepTimes.isNotEmpty() && tMs - stepTimes.first() > 20_000) stepTimes.removeFirst()
        if (!isPaused) steps++
    }

    /** いまのケイデンス（1分あたりの歩数）。出せないときは null */
    fun cadence(tMs: Long): Int? {
        while (stepTimes.isNotEmpty() && tMs - stepTimes.first() > 20_000) stepTimes.removeFirst()
        if (stepTimes.size < 5 || tMs - stepTimes.last() > 3000) return null
        val span = stepTimes.last() - stepTimes.first()
        if (span <= 0) return null
        return Math.round((stepTimes.size - 1) * 60_000.0 / span).toInt()
    }

    /** 直近のペース（秒/km） */
    fun currentPace(): Double? {
        if (paceWindow.size < 2) return null
        val (t0, d0) = paceWindow.first()
        val (t1, d1) = paceWindow.last()
        val dd = d1 - d0
        if (dd < 8 || t1 - t0 < 4000) return null
        return (t1 - t0) / 1000.0 / (dd / 1000.0)
    }

    fun averagePace(): Double? =
        if (distanceM > 10) movingMs / 1000.0 / (distanceM / 1000.0) else null

    /** 1秒ごとに呼ぶ。時間を進め、お知らせすべき出来事を返す */
    fun tick(tMs: Long): List<TrackerEvent> {
        val events = ArrayList<TrackerEvent>()
        val dt = if (lastTickMs < 0) 0L else (tMs - lastTickMs).coerceIn(0, 5000)
        lastTickMs = tMs

        if (config.autoPause && !manualPaused) {
            val nowStationary = isStationary(tMs)
            if (!autoPaused && nowStationary) {
                autoPaused = true
                events += TrackerEvent.AutoPause(true)
            } else if (autoPaused && isMoving(tMs)) {
                autoPaused = false
                resetAnchor()
                events += TrackerEvent.AutoPause(false)
            }
        } else if (autoPaused) {
            autoPaused = false
        }

        if (isPaused) return events
        movingMs += dt

        paceWindow.addLast(movingMs to distanceM)
        while (paceWindow.size > 2 && movingMs - paceWindow.first().first > 20_000) paceWindow.removeFirst()

        // 1km の区切り
        while (distanceM - splitStartDist >= 1000.0) closeSplit(1000.0)

        // お知らせの間隔
        if (config.announceEvery > 0) {
            when (config.announceUnit) {
                AnnounceUnit.DISTANCE -> {
                    val next = (intervalIndex + 1) * config.announceEvery
                    if (distanceM >= next) {
                        intervalIndex++
                        val segMs = movingMs - intervalStartMs
                        val pace = segMs / 1000.0 / (config.announceEvery / 1000.0)
                        events += TrackerEvent.Interval(
                            intervalIndex, next, movingMs, pace, prevIntervalPace,
                            elevation.gain - intervalStartGain, elevation.loss - intervalStartLoss,
                        )
                        prevIntervalPace = pace
                        intervalStartMs = movingMs
                        intervalStartDist = next
                        intervalStartGain = elevation.gain
                        intervalStartLoss = elevation.loss
                    }
                }
                AnnounceUnit.TIME -> {
                    val nextMs = ((intervalIndex + 1) * config.announceEvery * 1000).toLong()
                    if (movingMs >= nextMs) {
                        intervalIndex++
                        val segDist = distanceM - intervalStartDist
                        val pace = if (segDist > 10) config.announceEvery / (segDist / 1000.0) else null
                        events += TrackerEvent.Interval(
                            intervalIndex, distanceM, movingMs, pace, prevIntervalPace,
                            elevation.gain - intervalStartGain, elevation.loss - intervalStartLoss,
                        )
                        prevIntervalPace = pace
                        intervalStartMs = movingMs
                        intervalStartDist = distanceM
                        intervalStartGain = elevation.gain
                        intervalStartLoss = elevation.loss
                    }
                }
            }
        }

        // インターバルの区間の切り替え
        val phase = currentPhase
        if (phase != null) {
            val remaining = phaseRemaining() ?: 0.0
            if (remaining <= 0) {
                val lap = closeLap(phase)
                phaseIndex++
                phaseStartDist = distanceM
                phaseStartMs = movingMs
                firedPhaseCountdowns.clear()
                events += TrackerEvent.PhaseChange(currentPhase, lap, config.interval?.repeats ?: 0)
            } else {
                // 距離で区切る区間は、いまの速さから「あと何秒か」を見積もる
                val remainingSec = if (phase.byDistance) currentPace()?.let { remaining / (1000.0 / it) } else remaining
                if (remainingSec != null) {
                    val n = Math.ceil(remainingSec).toInt()
                    if (n in 1..3 && phase.amount > 5 && firedPhaseCountdowns.add(n)) {
                        (n + 1..3).forEach { firedPhaseCountdowns.add(it) }
                        events += TrackerEvent.PhaseCountdown(n)
                    }
                }
            }
        }

        // 目標の手前のカウントダウン
        if (!goalReached && config.goalValue > 0) {
            when (config.goalKind) {
                GoalKind.DISTANCE -> {
                    val remaining = config.goalValue - distanceM
                    if (remaining <= 0) {
                        goalReached = true
                        events += TrackerEvent.GoalReached
                    } else for (th in DISTANCE_COUNTDOWN) {
                        if (config.goalValue > th && remaining <= th && firedCountdowns.add(th)) {
                            // 一気に複数またいだときは、いちばん近いものだけ伝える
                            DISTANCE_COUNTDOWN.filter { it > th }.forEach { firedCountdowns.add(it) }
                            if (DISTANCE_COUNTDOWN.none { it < th && remaining <= it }) {
                                events += TrackerEvent.Countdown(GoalKind.DISTANCE, th)
                            }
                        }
                    }
                }
                GoalKind.TIME -> {
                    val remaining = Math.ceil(config.goalValue - movingMs / 1000.0).toInt()
                    if (remaining <= 0) {
                        goalReached = true
                        events += TrackerEvent.GoalReached
                    } else if (remaining in TIME_COUNTDOWN && config.goalValue > remaining && firedCountdowns.add(remaining)) {
                        events += TrackerEvent.Countdown(GoalKind.TIME, remaining)
                    }
                }
                GoalKind.FREE, GoalKind.INTERVAL -> {}
            }
        }
        return events
    }

    private fun isStationary(tMs: Long): Boolean {
        val gpsFresh = lastFixMs >= 0 && tMs - lastFixMs < 5000
        val sinceStep = if (stepTimes.isEmpty()) Long.MAX_VALUE else tMs - stepTimes.last()
        return if (stepsSeen) {
            if (gpsFresh) sinceStep > 4000 && lastSpeed < 0.8 else sinceStep > 6000
        } else {
            if (!gpsFresh) return false
            if (lastSpeed < 0.5) {
                if (slowSinceMs < 0) slowSinceMs = tMs
                tMs - slowSinceMs >= 5000
            } else {
                slowSinceMs = -1
                false
            }
        }
    }

    private fun isMoving(tMs: Long): Boolean {
        val gpsFresh = lastFixMs >= 0 && tMs - lastFixMs < 5000
        if (stepsSeen) {
            val recent = stepTimes.count { tMs - it <= 3000 }
            if (recent >= 3) return true
            return gpsFresh && lastSpeed > 1.5
        }
        val moving = gpsFresh && lastSpeed > 1.0
        if (moving) slowSinceMs = -1
        return moving
    }

    private fun closeSplit(dist: Double) {
        _splits += Split(
            index = _splits.size + 1,
            distanceM = dist,
            movingMs = movingMs - splitStartMs,
            gainM = elevation.gain - splitStartGain,
            lossM = elevation.loss - splitStartLoss,
            steps = steps - splitStartSteps,
        )
        splitStartDist += dist
        splitStartMs = movingMs
        splitStartGain = elevation.gain
        splitStartLoss = elevation.loss
        splitStartSteps = steps
    }

    private fun closeLap(phase: Phase): Lap {
        val lap = Lap(phase.kind, phase.rep, distanceM - phaseStartDist, movingMs - phaseStartMs)
        _laps += lap
        return lap
    }

    /** 終了時に呼ぶ。最後の端数（50m 以上）を区切りに足す */
    fun finish() {
        currentPhase?.let { if (movingMs - phaseStartMs > 3000) closeLap(it) }
        phaseIndex = phases.size
        val rest = distanceM - splitStartDist
        if (rest >= 50.0) closeSplit(rest)
    }

    fun averageCadence(): Int =
        if (movingMs > 10_000) Math.round(steps / (movingMs / 60000.0)).toInt() else 0

    companion object {
        const val MAX_ACCURACY_M = 30f
        const val MIN_MOVE_M = 2.5
        const val MAX_SPEED_MPS = 12.0
        val DISTANCE_COUNTDOWN = listOf(1000, 500, 100)
        val TIME_COUNTDOWN = setOf(300, 60, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)

        /** 消費カロリーの目安（kcal） */
        fun calories(weightKg: Double, distanceM: Double, gainM: Double): Double =
            weightKg * (distanceM / 1000.0) * 1.036 + weightKg * gainM * 0.0094
    }
}
