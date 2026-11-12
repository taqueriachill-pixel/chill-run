package com.chill.run.core

import kotlin.random.Random

object Scenes {
    const val FIRST = "first"
    const val PACE_UP = "pace_up"
    const val PACE_DOWN = "pace_down"
    const val STEADY = "steady"
    const val SECOND_HALF = "second_half"
    const val LAST = "last"
    const val CLIMB = "climb"
    const val DESCENT = "descent"
    const val HR_HIGH = "hr_high"
    const val HR_EASY = "hr_easy"
    const val BEST_PACE = "best_pace"
    const val GOAL = "goal"

    val labels = linkedMapOf(
        FIRST to "走り始め",
        PACE_UP to "ペースが上がった",
        PACE_DOWN to "ペースが落ちた",
        STEADY to "ペースが安定している",
        SECOND_HALF to "後半に入った",
        LAST to "最後の区間",
        CLIMB to "登りの途中",
        DESCENT to "下りの途中",
        HR_HIGH to "心拍数が高すぎる",
        HR_EASY to "心拍数に余裕がある",
        BEST_PACE to "自己ベストのペース",
        GOAL to "目標達成",
    )
}

/** 場面ごとの言葉の候補から、同じ言葉が続かないように1つ選ぶ */
class PhraseBook(private val scenes: Map<String, List<String>>, private val random: Random = Random.Default) {
    private val recent = HashMap<String, ArrayDeque<String>>()
    private var lastSpoken: String? = null

    fun pick(scene: String): String? {
        val all = scenes[scene].orEmpty().filter { it.isNotBlank() }
        if (all.isEmpty()) return null
        val used = recent.getOrPut(scene) { ArrayDeque() }
        // 直近に使った言葉（候補の半分まで）は避ける
        val keep = (all.size / 2).coerceAtLeast(0)
        while (used.size > keep) used.removeFirst()
        val candidates = all.filter { it !in used && it != lastSpoken }.ifEmpty { all }
        val chosen = candidates[random.nextInt(candidates.size)]
        used.addLast(chosen)
        lastSpoken = chosen
        return chosen
    }
}

data class AnnounceOptions(
    val pace: Boolean = true,
    val compare: Boolean = true,
    val cheer: Boolean = true,
    val heart: Boolean = true,
)

object AnnounceText {
    /** 区切りごとのお知らせ文を作る */
    fun interval(ev: TrackerEvent.Interval, config: RunConfig, opts: AnnounceOptions, phrases: PhraseBook): String {
        val sb = StringBuilder()
        if (config.announceUnit == AnnounceUnit.DISTANCE) {
            sb.append(Fmt.distanceSpoken(ev.totalDistM)).append("。")
        } else {
            sb.append(Fmt.durationSpoken(ev.totalMovingMs / 1000)).append("。")
            sb.append("距離").append(Fmt.distanceSpoken(roundForSpeech(ev.totalDistM))).append("。")
        }
        val pace = ev.paceSecPerKm
        val prev = ev.prevPaceSecPerKm
        if (opts.pace && pace != null) {
            sb.append("ペース").append(Fmt.paceSpoken(pace))
            if (opts.compare && prev != null) {
                val diff = Math.round(prev - pace).toInt()
                sb.append("、")
                sb.append(
                    when {
                        diff >= 3 -> "前より${diff}秒速いです"
                        diff <= -3 -> "前より${-diff}秒遅いです"
                        else -> "前とほぼ同じです"
                    }
                )
            }
            sb.append("。")
        }
        if (opts.heart && ev.hr != null && ev.zone != null) {
            sb.append("心拍${ev.hr}、").append(if (ev.zone >= 1) "ゾーン${ev.zone}" else "ゾーンの下").append("。")
        }
        // トレイルでは、この区間の登りと下りも伝える
        if (config.type == RunType.TRAIL) {
            val up = Math.round(ev.gainM).toInt()
            val down = Math.round(ev.lossM).toInt()
            if (up >= 3 || down >= 3) {
                val parts = ArrayList<String>()
                if (up >= 3) parts += "登り${up}メートル"
                if (down >= 3) parts += "下り${down}メートル"
                sb.append(parts.joinToString("、")).append("。")
            }
        }
        if (opts.cheer) phrases.pick(scene(ev, config))?.let { sb.append(it) }
        return sb.toString()
    }

    /** どの場面の言葉を使うか */
    fun scene(ev: TrackerEvent.Interval, config: RunConfig): String {
        if (config.goalValue > 0 && config.goalKind != GoalKind.FREE) {
            val done = if (config.goalKind == GoalKind.DISTANCE) ev.totalDistM else ev.totalMovingMs / 1000.0
            val step = when {
                config.goalKind == GoalKind.DISTANCE && config.announceUnit == AnnounceUnit.DISTANCE -> config.announceEvery
                config.goalKind == GoalKind.TIME && config.announceUnit == AnnounceUnit.TIME -> config.announceEvery
                else -> 0.0
            }
            val remaining = config.goalValue - done
            if (remaining > 0 && step > 0 && remaining <= step) return Scenes.LAST
            if (step > 0 && done >= config.goalValue / 2 && done - step < config.goalValue / 2) return Scenes.SECOND_HALF
        }
        if (ev.index == 1) return Scenes.FIRST
        // トレイルの坂では、ペースの上がり下がりより坂の言葉を優先する
        if (config.type == RunType.TRAIL) {
            if (ev.gainM - ev.lossM >= 15) return Scenes.CLIMB
            if (ev.lossM - ev.gainM >= 15) return Scenes.DESCENT
        }
        val pace = ev.paceSecPerKm
        val prev = ev.prevPaceSecPerKm
        if (pace == null || prev == null) return Scenes.STEADY
        val diff = prev - pace
        return when {
            diff >= 5 -> Scenes.PACE_UP
            diff <= -5 -> Scenes.PACE_DOWN
            else -> Scenes.STEADY
        }
    }

    fun countdown(ev: TrackerEvent.Countdown): String = when (ev.kind) {
        GoalKind.DISTANCE -> when (ev.remaining) {
            100 -> "あと100メートル、ラストスパート！"
            else -> "あと${Fmt.distanceSpoken(ev.remaining.toDouble())}"
        }
        GoalKind.TIME -> when {
            ev.remaining >= 60 -> "あと${ev.remaining / 60}分"
            ev.remaining == 10 -> "あと10秒"
            else -> ev.remaining.toString()
        }
        GoalKind.FREE, GoalKind.INTERVAL -> ""
    }

    fun phaseName(kind: PhaseKind): String = when (kind) {
        PhaseKind.WARMUP -> "ウォームアップ"
        PhaseKind.RUN -> "走る"
        PhaseKind.REST -> "ゆっくり"
        PhaseKind.COOLDOWN -> "クールダウン"
    }

    /** インターバルの最初のひとこと */
    fun phaseStart(phase: Phase, totalReps: Int): String = when (phase.kind) {
        PhaseKind.RUN -> if (phase.rep == totalReps && totalReps > 1) "走る、ラスト1本" else "走る、${phase.rep}本目"
        else -> phaseName(phase.kind)
    }

    /** インターバルの切り替えのお知らせ */
    fun phaseChange(ev: TrackerEvent.PhaseChange): String {
        val sb = StringBuilder()
        val next = ev.next
        sb.append(if (next == null) "メニュー終了、おつかれさま" else phaseStart(next, ev.totalReps)).append("。")
        val lap = ev.finished
        val pace = lap.paceSecPerKm
        if (lap.kind == PhaseKind.RUN && pace != null) {
            sb.append("${lap.rep}本目はペース${Fmt.paceSpoken(pace)}。")
        }
        return sb.toString()
    }

    private fun roundForSpeech(m: Double): Double =
        if (m >= 1000) Math.round(m / 100.0) * 100.0 else Math.round(m / 10.0) * 10.0
}
