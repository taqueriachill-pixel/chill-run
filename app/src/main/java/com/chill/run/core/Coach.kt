package com.chill.run.core

/** ふりかえりの1項目。GOOD＝よかった点、TIP＝次へのヒント、CARE＝体をいたわる */
data class Advice(val kind: Kind, val title: String, val text: String) {
    enum class Kind { GOOD, TIP, CARE }
}

/** ふりかえりに使う、過去の記録の要約 */
data class PastRun(val startMs: Long, val distanceM: Double, val movingMs: Long, val courseId: Long)

/** ふりかえりに使う、今日のランの数字 */
data class TodayRun(
    val startMs: Long,
    val trail: Boolean,
    val interval: Boolean,
    val distanceM: Double,
    val movingMs: Long,
    val gainM: Double,
    /** 1km ごとのタイム（秒）。1km に満たない最後の区切りは入れない */
    val splitSec: List<Double>,
    /** ケイデンスの平均（歩/分）。分からなければ null */
    val cadence: Int?,
    /** 心拍ゾーンごとの時間の割合（0〜5 の6つ）。心拍数がなければ null */
    val zoneShare: DoubleArray?,
    /** 今日の体調（0=未入力 1=つらい 2=重い 3=ふつう 4=良い 5=絶好調） */
    val condition: Int,
    val courseId: Long,
    /** 自己ベストを更新した距離（"1km" など） */
    val newBests: List<String>,
    /** このランで今週のもくひょうを達成したか・続いた週の数 */
    val weekGoalDone: Boolean,
    val streakWeeks: Int,
)

/**
 * 走ったあとの「きょうのふりかえり」。数字から、よかった点・次へのヒント・休み方を、短い言葉で返す。
 * 医療の判断はしない。体の痛みや強い不調のときは、無理をしないように伝えるだけ。
 */
object Coach {
    private const val DAY = 86_400_000L

    fun advise(today: TodayRun, past: List<PastRun>, zoneStartDay: (Long) -> Long = { it / DAY }): List<Advice> {
        val good = ArrayList<Advice>()
        val tips = ArrayList<Advice>()
        val care = ArrayList<Advice>()
        val km = today.distanceM / 1000.0
        val before = past.filter { it.startMs < today.startMs }

        // 自己ベスト・週のもくひょう
        if (today.newBests.isNotEmpty()) good += Advice(Advice.Kind.GOOD, "自己ベスト更新", "${today.newBests.joinToString("・")}のベストを更新しました。積み重ねが数字に出ています。")
        if (today.weekGoalDone) good += Advice(
            Advice.Kind.GOOD, "今週のもくひょう達成",
            if (today.streakWeeks >= 2) "${today.streakWeeks}週つづけて達成です。この「続ける力」が、いちばん効きます。" else "今週のもくひょうを達成しました。来週もこのペースでいきましょう。",
        )

        // 同じコースの前回と比べる
        if (today.courseId > 0 && km > 0.5) {
            before.filter { it.courseId == today.courseId && it.distanceM > 0 }.maxByOrNull { it.startMs }?.let { prev ->
                val nowPace = today.movingMs / 1000.0 / km
                val prevPace = prev.movingMs / 1000.0 / (prev.distanceM / 1000.0)
                val diff = Math.round(prevPace - nowPace).toInt()
                if (diff >= 5) good += Advice(Advice.Kind.GOOD, "前回より速い", "同じコースの前回より、1km あたり $diff 秒速く走れました。")
            }
        }

        // いつもより長い
        val month = before.filter { it.startMs >= today.startMs - 28 * DAY }
        if (month.size >= 3) {
            val avg = month.map { it.distanceM }.average()
            if (today.distanceM >= avg * 1.5 && km >= 5) good += Advice(Advice.Kind.GOOD, "いつもより長く走れた", "この4週間の平均（${"%.1f".format(avg / 1000)}km）の ${"%.1f".format(today.distanceM / avg)} 倍の距離です。今夜はしっかり食べて寝ましょう。")
        }

        // ペース配分（インターバル以外、3km 以上）
        val s = today.splitSec
        if (!today.interval && s.size >= 3) {
            val half = s.size / 2
            val first = s.take(half).average()
            val second = s.takeLast(half).average()
            val mean = s.average()
            val sd = Math.sqrt(s.map { (it - mean) * (it - mean) }.average())
            when {
                second < first * 0.98 -> good += Advice(Advice.Kind.GOOD, "後半に上げられた", "後半のほうが 1km あたり ${Math.round(first - second)} 秒速く走れています（ネガティブスプリット）。余力を残して入れた証拠です。")
                !today.trail && second > first * 1.06 -> tips += Advice(Advice.Kind.TIP, "前半を少しだけ抑える", "後半が 1km あたり ${Math.round(second - first)} 秒落ちました。最初の 1〜2km を 10 秒ほどゆっくり入ると、最後まで楽に走れます。")
                !today.trail && sd / mean < 0.025 -> good += Advice(Advice.Kind.GOOD, "ペースが安定", "1km ごとのタイムのぶれが小さく、とても安定したペースでした。")
            }
        }

        // 心拍（インターバル以外）
        val z = today.zoneShare
        if (z != null && !today.interval && today.movingMs > 10 * 60_000) {
            val hard = z[4] + z[5]
            val easy = z[1] + z[2]
            when {
                hard > 0.4 -> care += Advice(Advice.Kind.CARE, "きょうは強めの日", "心拍がゾーン4以上の時間が ${Math.round(hard * 100)}% でした。次の1〜2日は、休むかゆっくりジョグにすると、疲れが抜けて力になります。")
                easy > 0.7 -> good += Advice(Advice.Kind.GOOD, "ゆっくり走れた", "ほとんどをゾーン1〜2で走れています。持久力の土台づくりには、この「話せるくらいのペース」がいちばんです。")
            }
        }

        // ケイデンス（ロード）
        val cad = today.cadence
        if (cad != null && !today.trail && today.movingMs > 10 * 60_000) {
            if (cad in 120 until 160) tips += Advice(Advice.Kind.TIP, "歩幅を少し小さく", "ケイデンスは ${cad} 歩/分でした。歩幅を少し小さくして足の回転を上げる（170 前後を目安に）と、ひざや足首への負担が減ります。")
            else if (cad >= 170) good += Advice(Advice.Kind.GOOD, "リズムがいい", "ケイデンス ${cad} 歩/分。軽いリズムで、脚にやさしい走りです。")
        }

        // トレイルの登り
        if (today.trail && km > 1 && today.gainM / km >= 40) {
            good += Advice(Advice.Kind.GOOD, "しっかり登った", "1km あたり ${Math.round(today.gainM / km)}m の登りでした。脚力がつきます。下りは太ももに負担が残るので、今夜はストレッチを。")
        }

        // 量の増やしすぎ：この7日と、その前の3週の平均
        val last7 = past.filter { it.startMs in (today.startMs - 7 * DAY + 1)..today.startMs }.sumOf { it.distanceM } +
            if (past.none { it.startMs == today.startMs }) today.distanceM else 0.0
        val prev3 = past.filter { it.startMs in (today.startMs - 28 * DAY + 1)..(today.startMs - 7 * DAY) }.sumOf { it.distanceM } / 3.0
        if (prev3 > 3000 && last7 > prev3 * 1.4 && last7 > 15_000) {
            care += Advice(Advice.Kind.CARE, "距離が急に増えています", "この7日間で ${"%.0f".format(last7 / 1000)}km（その前の3週の平均は 1週 ${"%.0f".format(prev3 / 1000)}km）。急に増やすとけがをしやすいので、来週は少し控えめに。")
        }

        // 休みなしの連続
        val todayDay = zoneStartDay(today.startMs)
        val days = (before.map { zoneStartDay(it.startMs) } + todayDay).toSet()
        var streak = 0
        while ((todayDay - streak) in days) streak++
        if (streak >= 4) care += Advice(Advice.Kind.CARE, "${streak}日つづけて走っています", "休む日も練習のうちです。明日は休むか、ゆっくり短めにすると、次がもっと走れます。")

        // 体調の入力
        if (today.condition in 1..2) care += Advice(Advice.Kind.CARE, "体が重い日", "体調が「${if (today.condition == 1) "つらい" else "重い"}」の日は、よく寝て、次は短めかゆっくりで。痛みが続くときは無理せず休んでください。")
        else if (today.condition == 5 && good.isEmpty()) good += Advice(Advice.Kind.GOOD, "絶好調", "体が軽い日の感覚を覚えておくと、次の目安になります。")

        // よかった点が何もなければ、走ったこと自体を数字で
        if (good.isEmpty()) {
            val weekRuns = past.count { it.startMs in (today.startMs - 7 * DAY + 1) until today.startMs } + 1
            good += Advice(Advice.Kind.GOOD, "きょうも走った", "この7日間で ${weekRuns} 回目のランです。走りに出たぶんだけ、体は確実に変わっていきます。")
        }

        // 多すぎると読まないので、いたわり1つ・よかった点2つ・ヒント1つまで
        return care.take(1) + good.take(2) + tips.take(1)
    }
}
