package com.chill.run.run

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 国土地理院の標高 API。通信できないときやデータがない場所では null */
object ElevationApi {
    suspend fun fetch(lat: Double, lon: Double): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://cyberjapandata2.gsi.go.jp/general/dem/scripts/getelevation.php?lon=$lon&lat=$lat&outtype=JSON")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            try {
                if (conn.responseCode != 200) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val v = JSONObject(body).optDouble("elevation", Double.NaN)
                if (v.isNaN()) null else v
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
