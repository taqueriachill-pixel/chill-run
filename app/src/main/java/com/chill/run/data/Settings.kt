package com.chill.run.data

import android.content.Context
import android.content.SharedPreferences
import com.chill.run.core.AnnounceOptions
import com.chill.run.core.AnnounceUnit
import com.chill.run.core.GoalKind
import com.chill.run.core.HeartRate
import com.chill.run.core.HideZone
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

    /** 未舗装の道を通った割合が、これ以上ならトレイルの目安に当てはまる（%） */
    var trailUnpavedPercent: Int
        get() = p.getInt("trailUnpavedPercent", 30)
        set(v) = p.edit().putInt("trailUnpavedPercent", v).apply()

    /** 読み込んだプレイリストの名前。使っていなければ空 */
    var playlistName: String
        get() = p.getString("playlistName", "") ?: ""
        set(v) = p.edit().putString("playlistName", v).apply()

    /** 走り終わったあと、地図（OpenStreetMap）で道の種類を調べるか */
    var osmSurface: Boolean
        get() = p.getBoolean("osmSurface", true)
        set(v) = p.edit().putBoolean("osmSurface", v).apply()

    /** 動画の長さ（秒） */
    var videoSeconds: Int
        get() = p.getInt("videoSeconds", 4)
        set(v) = p.edit().putInt("videoSeconds", v).apply()

    /** 動画の手ブレ補正を使うか */
    var videoStabilize: Boolean
        get() = p.getBoolean("videoStabilize", true)
        set(v) = p.edit().putBoolean("videoStabilize", v).apply()

    // ---- ランムービーと、隠す場所 ----
    /** 走り終わったら、ランムービーを自動で作るか */
    var autoMovie: Boolean
        get() = p.getBoolean("autoMovie", false)
        set(v) = p.edit().putBoolean("autoMovie", v).apply()

    /** ランムービーに、その場で作った曲を入れるか */
    var movieMusic: Boolean
        get() = p.getBoolean("movieMusic", true)
        set(v) = p.edit().putBoolean("movieMusic", v).apply()

    /** 隠す場所（自宅など）。決めていなければ null */
    var hideZone: HideZone?
        get() {
            val lat = p.getString("hideLat", null)?.toDoubleOrNull() ?: return null
            val lon = p.getString("hideLon", null)?.toDoubleOrNull() ?: return null
            return HideZone(lat, lon, p.getInt("hideRadius", 200).toDouble())
        }
        set(v) {
            if (v == null) p.edit().remove("hideLat").remove("hideLon").apply()
            else p.edit().putString("hideLat", v.lat.toString()).putString("hideLon", v.lon.toString())
                .putInt("hideRadius", Math.round(v.radiusM).toInt()).apply()
        }

    // ---- 音楽 ----
    /** 曲のフォルダ。選んでいなければ空 */
    var musicFolder: String
        get() = p.getString("musicFolder", "") ?: ""
        set(v) = p.edit().putString("musicFolder", v).apply()

    /** ランで音楽を流すか */
    var musicOn: Boolean
        get() = p.getBoolean("musicOn", false)
        set(v) = p.edit().putBoolean("musicOn", v).apply()

    /** 速さを変える幅（%）。5〜15 */
    var tempoRangePercent: Int
        get() = p.getInt("tempoRangePercent", 10)
        set(v) = p.edit().putInt("tempoRangePercent", v).apply()

    /** 目標テンポの決め方："FOLLOW"（いまの足に合わせる）か "PACE"（目標のペースに引っ張る） */
    var tempoMode: String
        get() = p.getString("tempoMode", "FOLLOW") ?: "FOLLOW"
        set(v) = p.edit().putString("tempoMode", v).apply()

    /** 目標のペース（秒/km） */
    var targetPaceSec: Int
        get() = p.getInt("targetPaceSec", 360)
        set(v) = p.edit().putInt("targetPaceSec", v).apply()

    /** インターバルの「ゆっくり」のときの目標テンポ */
    var restTempo: Int
        get() = p.getInt("restTempo", 120)
        set(v) = p.edit().putInt("restTempo", v).apply()

    var musicVolumePercent: Int
        get() = p.getInt("musicVolumePercent", 100)
        set(v) = p.edit().putInt("musicVolumePercent", v).apply()

    // ---- 心拍数 ----
    /** 心拍数を受け取る機器（時計など）の番号と名前。選んでいなければ空 */
    var hrDeviceAddress: String
        get() = p.getString("hrDeviceAddress", "") ?: ""
        set(v) = p.edit().putString("hrDeviceAddress", v).apply()

    var hrDeviceName: String
        get() = p.getString("hrDeviceName", "") ?: ""
        set(v) = p.edit().putString("hrDeviceName", v).apply()

    var restHr: Int
        get() = p.getInt("restHr", 60)
        set(v) = p.edit().putInt("restHr", v).apply()

    /** 自分で測った最大心拍数。0 なら年齢からの目安を使う */
    var maxHrManual: Int
        get() = p.getInt("maxHrManual", 0)
        set(v) = p.edit().putInt("maxHrManual", v).apply()

    /** 「最大心拍数を更新しますか？」に「しない」と答えた値。これ以下では、もう聞かない */
    var maxHrDeclined: Int
        get() = p.getInt("maxHrDeclined", 0)
        set(v) = p.edit().putInt("maxHrDeclined", v).apply()

    val maxHrFromAge: Int get() = HeartRate.maxFromAge(java.time.Year.now().value - birthYear)
    val maxHr: Int get() = if (maxHrManual > 0) maxHrManual else maxHrFromAge

    var sayHeart: Boolean
        get() = p.getBoolean("sayHeart", true)
        set(v) = p.edit().putBoolean("sayHeart", v).apply()

    /** メニューごとの目標ゾーン（下・上） */
    var jogZoneLo: Int
        get() = p.getInt("jogZoneLo", 2)
        set(v) = p.edit().putInt("jogZoneLo", v).apply()
    var jogZoneHi: Int
        get() = p.getInt("jogZoneHi", 2)
        set(v) = p.edit().putInt("jogZoneHi", v).apply()
    var tempoZoneLo: Int
        get() = p.getInt("tempoZoneLo", 3)
        set(v) = p.edit().putInt("tempoZoneLo", v).apply()
    var tempoZoneHi: Int
        get() = p.getInt("tempoZoneHi", 4)
        set(v) = p.edit().putInt("tempoZoneHi", v).apply()
    var intervalZoneLo: Int
        get() = p.getInt("intervalZoneLo", 4)
        set(v) = p.edit().putInt("intervalZoneLo", v).apply()
    var intervalZoneHi: Int
        get() = p.getInt("intervalZoneHi", 4)
        set(v) = p.edit().putInt("intervalZoneHi", v).apply()

    /** 今日のねらい（前回選んだもの） */
    var lastAim: String
        get() = p.getString("lastAim", "") ?: ""
        set(v) = p.edit().putString("lastAim", v).apply()

    var lastMenuId: Long
        get() = p.getLong("lastMenuId", 0)
        set(v) = p.edit().putLong("lastMenuId", v).apply()

    val announceOptions: AnnounceOptions
        get() = AnnounceOptions(pace = sayPace, compare = sayCompare, cheer = sayCheer, heart = sayHeart)

    fun runConfig(type: RunType, goalKind: GoalKind, goalValue: Double, hasBarometer: Boolean, interval: IntervalPlan? = null, aim: String = ""): RunConfig {
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
