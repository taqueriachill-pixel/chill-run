package com.chill.run.core

/** 地図（OpenStreetMap）の道1本。tags は道の種類など、points は道の形 */
class OsmWay(val tags: Map<String, String>, val points: List<LatLon>)

/**
 * 未舗装の道・登山道・遊歩道を、どれくらい通ったか（仕様 9章の3つ目の目安）。
 * OpenStreetMap の道の情報（highway＝道の種類、surface＝路面）から決める。
 */
object Surface {
    /** 路面が、土・砂利・草など（舗装していない） */
    private val UNPAVED = setOf(
        "unpaved", "dirt", "earth", "ground", "gravel", "fine_gravel", "compacted", "grass", "grass_paver", "mud",
        "sand", "woodchips", "rock", "pebblestone", "soil", "forest_floor", "roots", "stone",
    )

    /** 路面が、アスファルト・コンクリート・石畳など（舗装してある） */
    private val PAVED = setOf(
        "asphalt", "concrete", "concrete:plates", "concrete:lanes", "paved", "paving_stones", "sett", "cobblestone",
        "unhewn_cobblestone", "metal", "chipseal", "bricks", "rubber", "tartan", "acrylic", "wood",
    )

    /**
     * その道が未舗装か。true＝未舗装、false＝舗装、null＝数えない（階段など、どちらとも言えない道）。
     * 路面の情報があればそれを使い、なければ道の種類で決める（登山道・山道・林道は未舗装とみなす）。
     */
    fun isUnpaved(tags: Map<String, String>): Boolean? {
        val surface = tags["surface"]?.substringBefore(';')?.trim()
        if (surface != null) {
            if (surface in UNPAVED) return true
            if (surface in PAVED) return false
        }
        if (tags.containsKey("sac_scale") || tags.containsKey("trail_visibility") || tags.containsKey("mtb:scale")) return true
        return when (tags["highway"]) {
            "path", "bridleway" -> true
            "track" -> tags["tracktype"] != "grade1"
            "steps" -> null
            null -> null
            else -> false
        }
    }

    /** 点から、線分 a-b までの距離（m）。狭い範囲なので、平らな面とみなして計算する */
    fun distanceToSegmentM(p: LatLon, a: LatLon, b: LatLon): Double {
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * Math.cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * mPerLon
        val ay = (a.lat - p.lat) * mPerLat
        val bx = (b.lon - p.lon) * mPerLon
        val by = (b.lat - p.lat) * mPerLat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return Math.sqrt(cx * cx + cy * cy)
    }

    /**
     * ルートの点ごとに、いちばん近い道（nearM 以内）を探し、その道が未舗装だった点の割合（0〜1）を返す。
     * 近くに道が見つかった点が半分に満たないとき（地図に道が描かれていない所が多いとき）は、決められないので null。
     */
    fun unpavedFraction(route: List<LatLon>, ways: List<OsmWay>, nearM: Double = 25.0): Double? {
        if (route.isEmpty()) return null
        var matched = 0
        var unpaved = 0
        for (p in route) {
            var best = Double.MAX_VALUE
            var bestWay: OsmWay? = null
            for (w in ways) {
                for (i in 0 until w.points.size - 1) {
                    // 遠い線分は、細かい計算をせずに飛ばす
                    val a = w.points[i]
                    val b = w.points[i + 1]
                    if (Math.abs(a.lat - p.lat) > 0.003 && Math.abs(b.lat - p.lat) > 0.003 && (a.lat - p.lat) * (b.lat - p.lat) > 0) continue
                    val d = distanceToSegmentM(p, a, b)
                    if (d < best) { best = d; bestWay = w }
                }
            }
            if (bestWay == null || best > nearM) continue
            val kind = isUnpaved(bestWay.tags) ?: continue
            matched++
            if (kind) unpaved++
        }
        if (matched * 2 < route.size) return null
        return unpaved.toDouble() / matched
    }
}
