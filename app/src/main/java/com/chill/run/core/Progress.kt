package com.chill.run.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 経験値の計算に使う、1回のランの材料 */
data class RunFact(
    val id: Long,
    val startMs: Long,
    val distanceM: Double,
    val gainM: Double,
    /** そのランの中でいちばん速かった 1km・5km・10km（ミリ秒。なければ 0） */
    val best1kMs: Long = 0,
    val best5kMs: Long = 0,
    val best10kMs: Long = 0,
)

/** 1回のランでもらった経験値の内わけ */
data class ExpBreakdown(
    val distance: Int,
    val climb: Int,
    val day: Int,
    val bests: Int,
    val bestNames: List<String>,
    /** 週の目標を、このランで達成したときの分 */
    val weekGoal: Int,
    /** 目標を達成した週が続いているときの分 */
    val streak: Int,
    /** このランで達成したときの、続いた週の数 */
    val streakWeeks: Int,
) {
    val total get() = distance + climb + day + bests + weekGoal + streak
}

data class ProgressResult(
    val totalExp: Int,
    val level: Int,
    /** いまのレベルの中で、たまった経験値と、次のレベルまでに要る経験値 */
    val expInLevel: Int,
    val expForNext: Int,
    /** 今週（月曜はじまり）に走った日数と、週の目標 */
    val weekDays: Int,
    val weekGoal: Int,
    /** 目標を達成した週が続いている数（届かない週があっても 0 に戻さず、1 つ下がるだけ） */
    val streakWeeks: Int,
    val perRun: Map<Long, ExpBreakdown>,
    /** 各ランのあとのレベル */
    val levelAfter: Map<Long, Int>,
)

/**
 * 週の目標・続いた週・レベルの計算（本人と決めた決まり。docs/PROGRESS.md）。
 * 休んでもレベルは下がらず、続いた週の数も 0 には戻らない。
 */
object Progress {
    const val EXP_PER_KM = 10
    const val DAY_BONUS = 10
    const val BEST_BONUS = 20
    const val WEEK_GOAL_BONUS = 30
    const val STREAK_STEP = 5
    const val STREAK_MAX = 50

    /** レベル lv から次のレベルに上がるのに要る経験値 */
    fun needFor(lv: Int) = 100 + 20 * (lv - 1)

    /** 経験値の合計から、レベルと、そのレベルの中での進み具合 */
    fun levelOf(totalExp: Int): Triple<Int, Int, Int> {
        var lv = 1
        var rest = totalExp
        while (rest >= needFor(lv)) { rest -= needFor(lv); lv++ }
        return Triple(lv, rest, needFor(lv))
    }

    private fun weekStart(date: LocalDate) = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun compute(runs: List<RunFact>, weekGoal: Int, zone: ZoneId, nowMs: Long): ProgressResult {
        val goal = weekGoal.coerceIn(1, 7)
        val sorted = runs.sortedBy { it.startMs }
        val perRun = LinkedHashMap<Long, ExpBreakdown>()
        val levelAfter = HashMap<Long, Int>()
        var total = 0
        var streak = 0
        var week: LocalDate? = null
        val daysThisWeek = HashSet<LocalDate>()
        var achievedThisWeek = false
        var best1 = Long.MAX_VALUE
        var best5 = Long.MAX_VALUE
        var best10 = Long.MAX_VALUE

        /** 週が終わったとき：目標に届いていなければ、続いた数を 1 つ下げる（0 より下にはしない） */
        fun closeWeeks(until: LocalDate) {
            var w = week ?: return
            while (w.isBefore(until)) {
                if (!achievedThisWeek) streak = (streak - 1).coerceAtLeast(0)
                achievedThisWeek = false
                daysThisWeek.clear()
                w = w.plusWeeks(1)
            }
            week = w
        }

        for (r in sorted) {
            val date = Instant.ofEpochMilli(r.startMs).atZone(zone).toLocalDate()
            val ws = weekStart(date)
            if (week == null) week = ws else if (ws.isAfter(week)) closeWeeks(ws)
            val distance = (r.distanceM / 1000.0 * EXP_PER_KM).toInt()
            val climb = (r.gainM / 10.0).toInt()
            val firstToday = daysThisWeek.add(date)
            val day = if (firstToday) DAY_BONUS else 0
            val names = ArrayList<String>()
            if (r.best1kMs in 1 until best1) { if (best1 != Long.MAX_VALUE) names += "1km"; best1 = r.best1kMs }
            if (r.best5kMs in 1 until best5) { if (best5 != Long.MAX_VALUE) names += "5km"; best5 = r.best5kMs }
            if (r.best10kMs in 1 until best10) { if (best10 != Long.MAX_VALUE) names += "10km"; best10 = r.best10kMs }
            var weekBonus = 0
            var streakBonus = 0
            var streakNow = 0
            if (!achievedThisWeek && daysThisWeek.size >= goal) {
                achievedThisWeek = true
                streak++
                weekBonus = WEEK_GOAL_BONUS
                streakNow = streak
                // 1週目は週の目標の分だけ。2週以上続いたら、続いた数×5（最大50）を足す
                streakBonus = if (streak >= 2) (STREAK_STEP * streak).coerceAtMost(STREAK_MAX) else 0
            }
            val b = ExpBreakdown(distance, climb, day, BEST_BONUS * names.size, names, weekBonus, streakBonus, streakNow)
            perRun[r.id] = b
            total += b.total
            levelAfter[r.id] = levelOf(total).first
        }
        // 今日までの、走らなかった週も数える（今週はまだ途中なので下げない）
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val thisWeek = weekStart(today)
        if (week != null && thisWeek.isAfter(week)) closeWeeks(thisWeek)
        val (lv, inLv, need) = levelOf(total)
        return ProgressResult(
            totalExp = total, level = lv, expInLevel = inLv, expForNext = need,
            weekDays = if (week == thisWeek) daysThisWeek.size else 0, weekGoal = goal,
            streakWeeks = streak, perRun = perRun, levelAfter = levelAfter,
        )
    }
}
