package com.chill.run.run

import com.chill.run.core.LatLon
import com.chill.run.core.OsmWay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OpenStreetMap の道の情報を、Overpass API（だれでも使える無料の窓口）から取ってくる。
 * ルートの点のまわり 25m にある道だけを問い合わせる。通信できないときは null。
 * 地図の情報は © OpenStreetMap contributors（ODbL）。
 */
object OsmApi {
    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    suspend fun waysAlong(route: List<LatLon>): List<OsmWay>? = withContext(Dispatchers.IO) {
        if (route.isEmpty()) return@withContext emptyList()
        runCatching {
            val coords = route.joinToString(",") { "%.5f,%.5f".format(java.util.Locale.US, it.lat, it.lon) }
            val query = "[out:json][timeout:25];way[\"highway\"](around:25,$coords);out tags geom;"
            val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 30000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "ChillRun/1.0 (personal running log app)")
            try {
                conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                if (conn.responseCode != 200) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val elements = JSONObject(body).getJSONArray("elements")
                (0 until elements.length()).mapNotNull { i ->
                    val e = elements.getJSONObject(i)
                    if (e.optString("type") != "way") return@mapNotNull null
                    val tagsJson = e.optJSONObject("tags") ?: return@mapNotNull null
                    val tags = tagsJson.keys().asSequence().associateWith { tagsJson.getString(it) }
                    val geom = e.optJSONArray("geometry") ?: return@mapNotNull null
                    val points = (0 until geom.length()).map { k ->
                        val g = geom.getJSONObject(k)
                        LatLon(g.getDouble("lat"), g.getDouble("lon"))
                    }
                    if (points.size >= 2) OsmWay(tags, points) else null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
