package com.chill.run.data

import android.content.Context
import android.content.SharedPreferences
import com.chill.run.core.AnnounceOptions
import com.chill.run.core.AnnounceUnit
import com.chill.run.core.GoalKind
import com.chill.run.core.IntervalPlan
import com.chill.run.core.RunConfig
import com.chill.run.core.RunType

/** アプリの設定（端末の中に保存） */
class Settings(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoPause: Boolean
        get() = p.getBoolean("autoPause", true)
        set(v) = p.edit().putBoolean("autoPause", v).apply()

    var announceOn: Boolean
        get() = p.getBoolean("announceOn", true)
        set(v) = p.edit().putBoolean("announceOn", v).apply()

    /** "D500" "D1000" "D2000" "T300" "T600" の形 */
    var announceInterval: String
        get() = p.getString("announceInterval", "D1000") ?: "D1000"
        set(v) = p.edit().putString("announceInterval", v).apply()

    var sayPace: Boolean
        get() = p.getBoolean("sayPace", true)
        set(v) = p.edit().putBoolean("sayPace", v).apply()

    var sayCompare: Boolean
        get() = p.getBoolean("sayCompare", true)
        set(v) = p.edit().putBoolean("sayCompare", v).apply()

    var sayCheer: Boolean
        get() = p.getBoolean("sayCheer", true)
        set(v) = p.edit().putBoolean("sayCheer", v).apply()

    var ttsRate: Float
        get() = p.getFloat("ttsRate", 1.0f)
        set(v) = p.edit().putFloat("ttsRate", v).apply()

    var ttsVolume: Float
        get() = p.getFloat("ttsVolume", 1.0f)
        set(v) = p.edit().putFloat("ttsVolume", v).apply()

    var birthYear: Int
        get() = p.getInt("birthYear", 1978)
        set(v) = p.edit().putInt("birthYear", v).apply()

    var weightKg: Float
        get() = p.getFloat("weightKg", 65f)
        set(v) = p.edit().putFloat("weightKg", v).apply()

    var lastType: String
        get() = p.getString("lastType", "ROAD") ?: "ROAD"
        set(v) = p.edit().putString("lastType", v).apply()

    var lastGoalKind: String
        get() = p.getString("lastGoalKind", "FREE") ?: "FREE"
        set(v) = p.edit().putString("lastGoalKind", v).apply()

    var lastGoalKm: Float
        get() = p.getFloat("lastGoalKm", 5f)
        set(v) = p.edit().putFloat("lastGoalKm", v).apply()

    var lastGoalMinutes: Int
        get() = p.getInt("lastGoalMinutes", 30)
        set(v) = p.edit().putInt("lastGoalMinutes", v).apply()

    /** トレイルの判定の目安：1km あたりの登り（m） */
    var trailGainPerKm: Int
        get() = p.getInt("trailGainPerKm", 15)
        set(v) = p.edit().putInt("trailGainPerKm", v).apply()

    /** トレイルの判定の目安：勾配10%以上の坂を通った割合（%） */
    var trailSteepPercent: Int
        get() = p.getInt("trailSteepPercent", 10)
        set(v) = p.edit().putInt("trailSteepPercent", v).apply()

    var lastMenuId: Long
        get() = p.getLong("lastMenuId", 0)
        set(v) = p.edit().putLong("lastMenuId", v).apply()

    val announceOptions: AnnounceOptions
        get() = AnnounceOptions(pace = sayPace, compare = sayCompare, cheer = sayCheer)

    fun runConfig(type: RunType, goalKind: GoalKind, goalValue: Double, hasBarometer: Boolean, interval: IntervalPlan? = null): RunConfig {
        val iv = announceInterval
        val unit = if (iv.startsWith("T")) AnnounceUnit.TIME else AnnounceUnit.DISTANCE
        val every = iv.drop(1).toDoubleOrNull() ?: 1000.0
        return RunConfig(
            type = type,
            goalKind = goalKind,
            goalValue = goalValue,
            // インターバルでは、休みの間も時計を進め、区切りのお知らせは区間の切り替えに任せる
            autoPause = autoPause && interval == null,
            announceUnit = unit,
            announceEvery = if (announceOn && interval == null) every else 0.0,
            interval = interval,
            // GPS の高さはゆれが大きいので、気圧がないときは目を粗くする
            gainThresholdM = if (hasBarometer) 2.5 else 6.0,
        )
    }

    companion object {
        val intervalChoices = listOf(
            "D500" to "500m ごと",
            "D1000" to "1km ごと",
            "D2000" to "2km ごと",
            "T300" to "5分ごと",
            "T600" to "10分ごと",
        )
    }
}
