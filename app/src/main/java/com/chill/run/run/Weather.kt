package com.chill.run.run

import android.content.Context
import com.chill.run.data.AppDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * 走ったときの天気と気温を、Open-Meteo（無料の天気サービス。データは CC BY 4.0）で調べる。
 * 送るのは、約1km 単位に丸めた位置と日付だけ。通信できないときは null。
 */
object Weather {
    const val ATTRIBUTION = "天気のデータ：Open-Meteo.com"

    class Result(val tempC: Double, val code: Int)

    suspend fun fetch(lat: Double, lon: Double, timeMs: Long): Result? = withContext(Dispatchers.IO) {
        runCatching {
            val time = Instant.ofEpochMilli(timeMs).atOffset(ZoneOffset.UTC)
            val date = time.toLocalDate().toString()
            val ageDays = (System.currentTimeMillis() - timeMs) / 86_400_000L
            // 90日より前は、過去の天気の窓口で調べる
            val base = if (ageDays > 85) "https://archive-api.open-meteo.com/v1/archive" else "https://api.open-meteo.com/v1/forecast"
            val url = String.format(
                Locale.US,
                "%s?latitude=%.2f&longitude=%.2f&hourly=temperature_2m,weather_code&start_date=%s&end_date=%s&timezone=UTC",
                base, lat, lon, date, date,
            )
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            try {
                if (conn.responseCode != 200) return@runCatching null
                val hourly = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("hourly")
                val temps = hourly.getJSONArray("temperature_2m")
                val codes = hourly.getJSONArray("weather_code")
                val i = time.hour.coerceIn(0, temps.length() - 1)
                if (temps.isNull(i) || codes.isNull(i)) null else Result(temps.getDouble(i), codes.getInt(i))
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** ランの天気をまだ調べていなければ調べて、記録に付ける。手で選んであるときは何もしない */
    suspend fun ensureForRun(context: Context, runId: Long) {
        val dao = AppDb.get(context).runDao()
        val run = dao.getRun(runId) ?: return
        if (run.weatherManual || run.weatherCode >= 0) return
        val p = dao.points(runId).firstOrNull() ?: return
        val result = fetch(p.lat, p.lon, (run.startTime + run.endTime) / 2) ?: return
        dao.setWeather(runId, result.tempC, result.code, false)
    }
}
