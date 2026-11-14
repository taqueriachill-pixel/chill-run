package com.chill.run.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoreTest {
    // 緯度1度 ≒ 111.19km
    private val mPerDegLat = 111_194.9

    /** まっすぐ北へ speed m/s で seconds 秒走らせる。歩数は毎秒3歩 */
    private fun run(t: RunTracker, startMs: Long, seconds: Int, speed: Double, startLat: Double = 35.6, stepping: Boolean = true): Pair<Long, Double> {
        var lat = startLat
        var now = startMs
        val events = ArrayList<TrackerEvent>()
        repeat(seconds) {
            now += 1000
            lat += speed / mPerDegLat
            if (stepping) repeat(3) { i -> t.onStep(now - 900 + i * 300) }
            t.onLocation(now, lat, 139.5, 5f, speed.toFloat())
            events += t.tick(now)
        }
        lastEvents = events
        return now to lat
    }

    private var lastEvents: List<TrackerEvent> = emptyList()

    @Test
    fun distance_haversine() {
        assertEquals(111_194.9, Geo.distanceM(35.0, 139.0, 36.0, 139.0), 50.0)
        assertEquals(0.0, Geo.distanceM(35.0, 139.0, 35.0, 139.0), 0.001)
    }

    @Test
    fun formats() {
        assertEquals("12:34", Fmt.clock(754_000))
        assertEquals("1:02:03", Fmt.clock(3_723_000))
        assertEquals("6'12\"", Fmt.pace(372.0))
        assertEquals("--'--\"", Fmt.pace(null))
        assertEquals("6分12秒", Fmt.paceSpoken(372.0))
        assertEquals("6分", Fmt.paceSpoken(360.0))
        assertEquals("3キロ", Fmt.distanceSpoken(3000.0))
        assertEquals("2.5キロ", Fmt.distanceSpoken(2500.0))
        assertEquals("500メートル", Fmt.distanceSpoken(500.0))
        assertEquals("1時間10分", Fmt.durationSpoken(4200))
    }

    @Test
    fun elevation_ignores_small_wobble() {
        val e = ElevationGain(2.5)
        // ±1m のゆれは数えない
        repeat(100) { e.add(100.0 + if (it % 2 == 0) 1.0 else -1.0) }
        assertEquals(0.0, e.gain, 0.001)
        assertEquals(0.0, e.loss, 0.001)
    }

    @Test
    fun elevation_counts_climb_and_descent() {
        val e = ElevationGain(2.5)
        for (i in 0..50) e.add(100.0 + i)          // 50m 登る
        for (i in 0..30) e.add(150.0 - i)          // 30m 下る
        for (i in 0..20) e.add(120.0 + i)          // 20m 登る
        assertEquals(70.0, e.gain, 0.001)
        assertEquals(30.0, e.loss, 0.001)
    }

    @Test
    fun tracker_distance_time_splits() {
        val t = RunTracker(RunConfig(autoPause = false))
        t.tick(0)
        run(t, 0, 800, 3.0) // 3m/s で 800 秒 = 2400m
        t.finish()
        assertEquals(2400.0, t.distanceM, 15.0)
        assertEquals(800_000L, t.movingMs)
        assertEquals(3, t.splits.size)
        assertEquals(333.3, t.splits[0].paceSecPerKm!!, 4.0)
        assertEquals(400.0, t.splits[2].distanceM, 15.0)
        assertEquals(333.3, t.averagePace()!!, 3.0)
        assertEquals(333.3, t.currentPace()!!, 5.0)
        assertEquals(180, t.averageCadence())
    }

    @Test
    fun tracker_rejects_bad_fixes() {
        val t = RunTracker(RunConfig(autoPause = false))
        t.tick(0)
        val (now, lat) = run(t, 0, 10, 3.0)
        val before = t.distanceM
        // 精度の悪い位置は使わない
        assertEquals(null, t.onLocation(now + 1000, lat + 0.01, 139.5, 80f, 3f))
        // 1秒で 1km 飛ぶような位置も使わない
        assertEquals(null, t.onLocation(now + 2000, lat + 0.01, 139.5, 5f, null))
        assertEquals(before, t.distanceM, 0.001)
    }

    @Test
    fun tracker_gps_jitter_while_standing_adds_nothing() {
        val t = RunTracker(RunConfig(autoPause = false))
        t.tick(0)
        var now = 0L
        repeat(120) {
            now += 1000
            val wobble = (if (it % 2 == 0) 1.0 else -1.0) / mPerDegLat // ±1m
            t.onLocation(now, 35.6 + wobble, 139.5, 8f, 0.1f)
            t.tick(now)
        }
        assertTrue(t.distanceM < 5.0)
    }

    @Test
    fun auto_pause_stops_clock_and_resumes() {
        val t = RunTracker(RunConfig(autoPause = true))
        t.tick(0)
        var (now, lat) = run(t, 0, 60, 3.0)
        assertFalse(t.autoPaused)
        val movingBefore = t.movingMs
        val distBefore = t.distanceM
        // 信号待ち 30 秒（歩数なし、速さ 0）
        repeat(30) {
            now += 1000
            t.onLocation(now, lat, 139.5, 5f, 0f)
            t.tick(now)
        }
        assertTrue(t.autoPaused)
        assertTrue(t.movingMs - movingBefore <= 6000)
        // 走り出す
        val r = run(t, now, 20, 3.0, lat)
        assertFalse(t.autoPaused)
        assertTrue(t.movingMs - movingBefore >= 17_000)
        assertEquals(distBefore + 60.0, t.distanceM, 12.0)
        assertNotNull(r)
    }

    @Test
    fun slow_uphill_walk_does_not_pause() {
        val t = RunTracker(RunConfig(autoPause = true))
        t.tick(0)
        run(t, 0, 60, 0.6) // 急坂をゆっくり歩く（歩数あり）
        assertFalse(t.autoPaused)
        assertEquals(60_000L, t.movingMs)
    }

    @Test
    fun manual_pause() {
        val t = RunTracker(RunConfig(autoPause = false))
        t.tick(0)
        val (now, lat) = run(t, 0, 30, 3.0)
        t.setManualPaused(true)
        val d = t.distanceM
        val m = t.movingMs
        val (now2, lat2) = run(t, now, 30, 3.0, lat)
        assertEquals(d, t.distanceM, 0.001)
        assertEquals(m, t.movingMs)
        t.setManualPaused(false)
        run(t, now2, 30, 3.0, lat2)
        assertEquals(d + 90.0, t.distanceM, 6.0)
    }

    @Test
    fun interval_events_every_km_with_comparison() {
        val t = RunTracker(RunConfig(autoPause = false))
        t.tick(0)
        val all = ArrayList<TrackerEvent>()
        var (now, lat) = run(t, 0, 340, 3.0); all += lastEvents       // 1km 目: 約 333 秒
        val r = run(t, now, 300, 3.5, lat); all += lastEvents          // 2km 目は速い
        val intervals = all.filterIsInstance<TrackerEvent.Interval>()
        assertEquals(2, intervals.size)
        assertEquals(1000.0, intervals[0].totalDistM, 0.001)
        assertEquals(null, intervals[0].prevPaceSecPerKm)
        assertTrue(intervals[1].paceSecPerKm!! < intervals[1].prevPaceSecPerKm!!)
        val text = AnnounceText.interval(intervals[1], t.config, AnnounceOptions(), PhraseBook(mapOf(Scenes.PACE_UP to listOf("いいね"))))
        assertTrue(text, text.startsWith("2キロ。ペース"))
        assertTrue(text, text.contains("速いです"))
        assertTrue(text, text.endsWith("いいね"))
        assertNotNull(r)
    }

    @Test
    fun distance_goal_countdown() {
        val t = RunTracker(RunConfig(autoPause = false, goalKind = GoalKind.DISTANCE, goalValue = 2000.0))
        t.tick(0)
        val all = ArrayList<TrackerEvent>()
        var now = 0L
        var lat = 35.6
        repeat(4) { val r = run(t, now, 200, 3.0, lat); now = r.first; lat = r.second; all += lastEvents }
        val cd = all.filterIsInstance<TrackerEvent.Countdown>().map { it.remaining }
        assertEquals(listOf(1000, 500, 100), cd)
        assertEquals(1, all.count { it is TrackerEvent.GoalReached })
        assertTrue(t.goalReached)
        assertEquals("あと1キロ", AnnounceText.countdown(TrackerEvent.Countdown(GoalKind.DISTANCE, 1000)))
        assertEquals("あと500メートル", AnnounceText.countdown(TrackerEvent.Countdown(GoalKind.DISTANCE, 500)))
    }

    @Test
    fun short_distance_goal_skips_far_countdowns() {
        val t = RunTracker(RunConfig(autoPause = false, goalKind = GoalKind.DISTANCE, goalValue = 400.0))
        t.tick(0)
        run(t, 0, 150, 3.0)
        val cd = lastEvents.filterIsInstance<TrackerEvent.Countdown>().map { it.remaining }
        assertEquals(listOf(100), cd)
    }

    @Test
    fun time_goal_countdown() {
        val t = RunTracker(RunConfig(autoPause = false, goalKind = GoalKind.TIME, goalValue = 600.0))
        t.tick(0)
        run(t, 0, 605, 3.0)
        val cd = lastEvents.filterIsInstance<TrackerEvent.Countdown>().map { it.remaining }
        assertEquals(listOf(300, 60, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1), cd)
        assertEquals(1, lastEvents.count { it is TrackerEvent.GoalReached })
        assertEquals("あと5分", AnnounceText.countdown(TrackerEvent.Countdown(GoalKind.TIME, 300)))
        assertEquals("あと10秒", AnnounceText.countdown(TrackerEvent.Countdown(GoalKind.TIME, 10)))
        assertEquals("9", AnnounceText.countdown(TrackerEvent.Countdown(GoalKind.TIME, 9)))
    }

    @Test
    fun scenes_for_goal_run() {
        val cfg = RunConfig(goalKind = GoalKind.DISTANCE, goalValue = 5000.0)
        fun ev(i: Int, pace: Double = 360.0, prev: Double? = 360.0) = TrackerEvent.Interval(i, i * 1000.0, i * 360_000L, pace, prev)
        assertEquals(Scenes.FIRST, AnnounceText.scene(ev(1, prev = null), cfg))
        assertEquals(Scenes.STEADY, AnnounceText.scene(ev(2), cfg))
        assertEquals(Scenes.SECOND_HALF, AnnounceText.scene(ev(3), cfg))
        assertEquals(Scenes.LAST, AnnounceText.scene(ev(4), cfg))
        assertEquals(Scenes.PACE_UP, AnnounceText.scene(ev(2, pace = 350.0), cfg))
        assertEquals(Scenes.PACE_DOWN, AnnounceText.scene(ev(2, pace = 372.0), cfg))
    }

    @Test
    fun time_interval_announcement() {
        val cfg = RunConfig(autoPause = false, announceUnit = AnnounceUnit.TIME, announceEvery = 300.0)
        val t = RunTracker(cfg)
        t.tick(0)
        run(t, 0, 301, 3.0)
        val iv = lastEvents.filterIsInstance<TrackerEvent.Interval>().single()
        val text = AnnounceText.interval(iv, cfg, AnnounceOptions(cheer = false), PhraseBook(emptyMap()))
        assertTrue(text, text.startsWith("5分。距離900メートル。ペース5分3"))
    }

    @Test
    fun phrases_do_not_repeat_back_to_back() {
        val list = (1..10).map { "言葉$it" }
        val book = PhraseBook(mapOf("a" to list), Random(1))
        var prev: String? = null
        repeat(200) {
            val p = book.pick("a")
            assertNotEquals(prev, p)
            prev = p
        }
        assertEquals(null, PhraseBook(emptyMap()).pick("a"))
    }

    @Test
    fun cadence_from_steps() {
        val t = RunTracker(RunConfig(autoPause = false))
        var now = 0L
        repeat(60) { now += 353; t.onStep(now) } // 約 170 歩/分
        assertEquals(170.0, t.cadence(now)!!.toDouble(), 2.0)
        assertEquals(null, t.cadence(now + 10_000))
    }

    @Test
    fun heart_rate_packet_parsing() {
        // 1バイト形式：flags=0x00, 152 拍/分
        assertEquals(152, HeartRate.parse(byteArrayOf(0x00, 152.toByte())))
        // 2バイト形式：flags=0x01, 300 = 0x012C
        assertEquals(300, HeartRate.parse(byteArrayOf(0x01, 0x2C, 0x01)))
        assertEquals(null, HeartRate.parse(byteArrayOf(0x00)))
    }

    // ---- 段階2 ----

    @Test
    fun mercator_round_trip_and_tiles() {
        val lat = 35.6069; val lon = 139.5497 // 生田緑地のあたり
        assertEquals(lat, MapMath.yToLat(MapMath.latToY(lat)), 1e-9)
        assertEquals(lon, MapMath.xToLon(MapMath.lonToX(lon)), 1e-9)
        // 東京駅付近のタイル番号（ズーム15）は x=29105, y=12903 あたり
        val t = MapMath.tileAt(35.681, 139.767, 15)
        assertEquals(29105, t.x)
        assertEquals(12903, t.y)
        val tiles = MapMath.tilesAround(lat, lon, 3000.0, 12..16)
        assertTrue(tiles.size in 100..1500)
        assertTrue(tiles.any { it == MapMath.tileAt(lat, lon, 16) })
    }

    @Test
    fun fit_zoom_contains_route() {
        val z = MapMath.fitZoom(35.60, 35.61, 139.54, 139.56, 1000.0, 800.0, 256.0)
        val w = (MapMath.lonToX(139.56) - MapMath.lonToX(139.54)) * 256 * Math.pow(2.0, z)
        val h = (MapMath.latToY(35.60) - MapMath.latToY(35.61)) * 256 * Math.pow(2.0, z)
        assertTrue(w <= 1000.0 + 1e-6 && h <= 800.0 + 1e-6)
        assertTrue(w > 900.0 || h > 700.0)
    }

    @Test
    fun speed_fractions_slow_is_low_fast_is_high() {
        val n = 200
        val dist = DoubleArray(n)
        val ms = LongArray(n)
        for (i in 1 until n) {
            ms[i] = i * 1000L
            dist[i] = dist[i - 1] + if (i < 100) 2.0 else 4.0 // 前半は遅く、後半は速い
        }
        val f = MapMath.speedFractions(dist, ms)
        assertTrue(f[30] < 0.1f)
        assertTrue(f[170] > 0.9f)
        assertEquals(listOf(1 to 0), MapMath.kmMarks(doubleArrayOf(1000.0)).map { it.first to it.second })
        val marks = MapMath.kmMarks(DoubleArray(301) { it * 10.0 })
        assertEquals(listOf(1, 2, 3), marks.map { it.first })
        assertEquals(100, marks[0].second)
    }

    @Test
    fun steep_fraction_and_trail_judgement() {
        // 1km 平ら → 500m で 60m 登る（勾配12%）→ 500m 平ら
        val dist = DoubleArray(201) { it * 10.0 }
        val alt = DoubleArray(201) { i -> when { i <= 100 -> 20.0; i <= 150 -> 20.0 + (i - 100) * 1.2; else -> 80.0 } }
        val steep = RouteAnalysis.steepFraction(dist, alt)
        assertEquals(0.25, steep, 0.03)
        fun judge(gain: Double, steepF: Double?, unpaved: Double?) = RouteAnalysis.looksLikeTrail(2000.0, gain, steepF, unpaved, 15.0, 10.0, 30.0)
        // 道の種類が分からないとき：登りと坂の両方
        assertEquals(true, judge(60.0, steep, null))
        assertEquals(false, judge(10.0, 0.0, null))
        // 登りは多いが坂が短い（ゆるい長い登り）はトレイルにしない
        assertEquals(false, judge(60.0, 0.02, null))
        // 3つのうち2つ：ゆるい長い登りでも、土の道が多ければトレイル
        assertEquals(true, judge(60.0, 0.02, 0.7))
        // 平らな土の道（河川敷など）は、未舗装だけなのでトレイルにしない
        assertEquals(false, judge(5.0, 0.0, 0.9))
        // 登りと坂があれば、舗装路でもトレイルの候補（2つ当てはまる）
        assertEquals(true, judge(60.0, steep, 0.0))
        // 高さが分からないときは、道の種類だけで決める。どちらも分からなければ決めない
        assertEquals(true, judge(0.0, null, 0.6))
        assertEquals(null, judge(0.0, null, null))
    }

    @Test
    fun playlist_m3u_is_matched_to_library() {
        val text = "\uFEFF#EXTM3U\n#EXTINF:215,Artist - Song\n/storage/emulated/0/Music/Album A/01 Song.mp3\n\n" +
            "..\\Album B\\02 other.flac\nfile:///storage/emulated/0/Music/%E6%9B%B2.m4a\nmissing.mp3\n"
        val entries = Playlist.parseM3u(text)
        assertEquals(listOf("/storage/emulated/0/Music/Album A/01 Song.mp3", "../Album B/02 other.flac", "/storage/emulated/0/Music/曲.m4a", "missing.mp3"), entries)
        val library = listOf(
            "primary:Music/Album B/01 Song.mp3", // 同じファイル名だが、別のアルバム
            "primary:Music/Album A/01 Song.mp3",
            "primary:Music/Album B/02 Other.flac", // 大文字・小文字の違いは気にしない
            "primary:Music/曲.m4a",
        )
        assertEquals(listOf(1, 2, 3, null), Playlist.match(entries, library))
    }

    @Test
    fun unpaved_fraction_from_osm_ways() {
        assertEquals(true, Surface.isUnpaved(mapOf("highway" to "path")))
        assertEquals(false, Surface.isUnpaved(mapOf("highway" to "path", "surface" to "asphalt")))
        assertEquals(true, Surface.isUnpaved(mapOf("highway" to "footway", "surface" to "dirt")))
        assertEquals(false, Surface.isUnpaved(mapOf("highway" to "footway")))
        assertEquals(true, Surface.isUnpaved(mapOf("highway" to "track")))
        assertEquals(false, Surface.isUnpaved(mapOf("highway" to "track", "tracktype" to "grade1")))
        assertEquals(true, Surface.isUnpaved(mapOf("highway" to "footway", "sac_scale" to "hiking")))
        assertEquals(null, Surface.isUnpaved(mapOf("highway" to "steps")))
        assertEquals(false, Surface.isUnpaved(mapOf("highway" to "residential")))

        // 東西にのびる舗装路と、その北 100m を並行する山道。ルートは前半を舗装路、後半を山道の上を通る
        val road = OsmWay(mapOf("highway" to "residential"), line(35.6000, 139.5000, 35.6000, 139.5200, 5))
        val path = OsmWay(mapOf("highway" to "path"), line(35.6009, 139.5000, 35.6009, 139.5200, 5))
        val route = line(35.60002, 139.5010, 35.60002, 139.5100, 10) + line(35.60088, 139.5100, 35.60088, 139.5190, 10)
        assertEquals(0.5, Surface.unpavedFraction(route, listOf(road, path))!!, 0.06)
        // 地図に道がない所ばかりなら、決めない
        assertEquals(null, Surface.unpavedFraction(line(35.70, 139.60, 35.71, 139.61, 10), listOf(road, path)))
        // 線分までの距離
        assertEquals(100.0, Surface.distanceToSegmentM(LatLon(35.6009, 139.51), LatLon(35.6, 139.50), LatLon(35.6, 139.52)), 2.0)
    }

    private fun line(lat0: Double, lon0: Double, lat1: Double, lon1: Double, n: Int = 100): List<LatLon> =
        (0..n).map { LatLon(lat0 + (lat1 - lat0) * it / n, lon0 + (lon1 - lon0) * it / n) }

    @Test
    fun course_matching() {
        val a = line(35.600, 139.540, 35.620, 139.540)                 // 北へ約2.2km
        val same = line(35.6001, 139.5402, 35.6199, 139.5401)          // ほぼ同じ道
        val reverse = a.reversed()                                     // 逆向き（スタートとゴールが逆）
        val other = line(35.600, 139.540, 35.600, 139.565)             // 東へ
        val half = line(35.600, 139.540, 35.610, 139.540)              // 途中まで
        assertTrue(RouteAnalysis.sameCourse(a, same))
        assertFalse(RouteAnalysis.sameCourse(a, reverse))
        assertFalse(RouteAnalysis.sameCourse(a, other))
        assertFalse(RouteAnalysis.sameCourse(a, half))
        assertEquals(a.size, RouteAnalysis.decode(RouteAnalysis.encode(a)).size)
        assertEquals(35.6, RouteAnalysis.decode(RouteAnalysis.encode(a)).first().lat, 1e-5)
    }

    @Test
    fun resample_and_profile() {
        val n = 1001
        val lat = DoubleArray(n) { 35.6 + it * 0.00001 }
        val lon = DoubleArray(n) { 139.5 }
        val dist = DoubleArray(n) { it * 1.112 }
        val shape = RouteAnalysis.resample(lat, lon, dist, 50.0)
        assertTrue(shape.size in 20..25)
        assertEquals(lat.last(), shape.last().lat, 1e-12)
        val prof = RouteAnalysis.elevationProfile(dist, DoubleArray(n) { it * 0.1 }, 40)
        assertEquals(40, prof.size)
        assertEquals(0f, prof.first(), 0.01f)
        assertEquals(100f, prof.last(), 0.2f)
        assertEquals(prof.size, RouteAnalysis.decodeProfile(RouteAnalysis.encodeProfile(prof)).size)
    }

    @Test
    fun interval_phases_and_events() {
        val plan = IntervalPlan(warmupSec = 60, runByDistance = false, runAmount = 30.0, restSec = 20, repeats = 3, cooldownSec = 40)
        assertEquals(
            listOf(PhaseKind.WARMUP, PhaseKind.RUN, PhaseKind.REST, PhaseKind.RUN, PhaseKind.REST, PhaseKind.RUN, PhaseKind.COOLDOWN),
            plan.phases().map { it.kind },
        )
        val t = RunTracker(RunConfig(autoPause = false, goalKind = GoalKind.INTERVAL, announceEvery = 0.0, interval = plan))
        t.tick(0)
        assertEquals(PhaseKind.WARMUP, t.currentPhase!!.kind)
        run(t, 0, 60 + 30 + 20 + 30 + 20 + 30 + 40 + 5, 3.0)
        val changes = lastEvents.filterIsInstance<TrackerEvent.PhaseChange>()
        assertEquals(7, changes.size)
        assertEquals(listOf(PhaseKind.RUN, PhaseKind.REST, PhaseKind.RUN, PhaseKind.REST, PhaseKind.RUN, PhaseKind.COOLDOWN, null), changes.map { it.next?.kind })
        // 切り替えごとに 3、2、1
        val cds = lastEvents.filterIsInstance<TrackerEvent.PhaseCountdown>().map { it.seconds }
        assertEquals(List(7) { listOf(3, 2, 1) }.flatten(), cds)
        assertEquals(7, t.laps.size)
        val runLaps = t.laps.filter { it.kind == PhaseKind.RUN }
        assertEquals(3, runLaps.size)
        assertEquals(30_000L, runLaps[0].movingMs)
        assertEquals(90.0, runLaps[0].distanceM, 8.0)
        assertEquals(null, t.currentPhase)
        t.finish()
        assertEquals(7, t.laps.size)
        assertTrue(AnnounceText.phaseChange(changes[1]).startsWith("ゆっくり。1本目はペース5分3"))
        assertTrue(AnnounceText.phaseChange(changes[4]).startsWith("走る、ラスト1本。"))
        assertTrue(AnnounceText.phaseChange(changes[5]).startsWith("クールダウン。3本目はペース"))
        assertEquals("メニュー終了、おつかれさま。", AnnounceText.phaseChange(changes[6]))
    }

    @Test
    fun interval_by_distance() {
        val plan = IntervalPlan(runByDistance = true, runAmount = 200.0, restSec = 30, repeats = 2)
        val t = RunTracker(RunConfig(autoPause = false, goalKind = GoalKind.INTERVAL, announceEvery = 0.0, interval = plan))
        t.tick(0)
        run(t, 0, 200, 4.0)
        val changes = lastEvents.filterIsInstance<TrackerEvent.PhaseChange>()
        assertEquals(listOf(PhaseKind.REST, PhaseKind.RUN, null), changes.map { it.next?.kind })
        assertEquals(200.0, t.laps[0].distanceM, 6.0)
        assertEquals(50_000.0, t.laps[0].movingMs.toDouble(), 2000.0)
        assertTrue(lastEvents.filterIsInstance<TrackerEvent.PhaseCountdown>().isNotEmpty())
    }

    @Test
    fun trail_announcement_includes_climb() {
        val cfg = RunConfig(type = RunType.TRAIL)
        val ev = TrackerEvent.Interval(2, 2000.0, 800_000, 420.0, 380.0, gainM = 42.0, lossM = 6.0)
        assertEquals(Scenes.CLIMB, AnnounceText.scene(ev, cfg))
        val text = AnnounceText.interval(ev, cfg, AnnounceOptions(cheer = false), PhraseBook(emptyMap()))
        assertTrue(text, text.endsWith("登り42メートル、下り6メートル。"))
        // ロードでは登りを言わない
        val road = AnnounceText.interval(ev, RunConfig(), AnnounceOptions(cheer = false), PhraseBook(emptyMap()))
        assertFalse(road, road.contains("登り"))
    }

    // ---- 段階4 ----

    @Test
    fun karvonen_zones() {
        // 1978年生まれ → 2026年に48歳。208 − 0.7×48 = 174.4 → 174
        assertEquals(174, HeartRate.maxFromAge(48))
        val z = HeartZones(maxHr = 180, restHr = 60) // 予備 120
        assertEquals(120, z.lowerBound(1)) // 50%
        assertEquals(132, z.lowerBound(2)) // 60%
        assertEquals(144, z.lowerBound(3))
        assertEquals(156, z.lowerBound(4))
        assertEquals(168, z.lowerBound(5)) // 90%
        assertEquals(0, z.zoneOf(110))
        assertEquals(1, z.zoneOf(120))
        assertEquals(2, z.zoneOf(143))
        assertEquals(3, z.zoneOf(152))
        assertEquals(5, z.zoneOf(190))
        assertEquals("132〜143", z.rangeText(2))
        assertEquals("168〜", z.rangeText(5))
    }

    @Test
    fun heart_rate_contact_flag() {
        assertEquals(null, HeartRate.contact(byteArrayOf(0x00, 80)))        // 触れているかを知らせない機器
        assertEquals(true, HeartRate.contact(byteArrayOf(0x06, 89)))        // 触れている
        assertEquals(false, HeartRate.contact(byteArrayOf(0x04, 0)))        // 外れている
        assertEquals(89, HeartRate.parse(byteArrayOf(0x06, 89)))
    }

    @Test
    fun coach_warns_after_a_minute_and_not_too_often() {
        val c = HrCoach()
        val alerts = ArrayList<Pair<Long, HrAlert>>()
        // 目標ゾーン2 で、ゾーン4 が5分続く
        for (t in 0..300) c.update(t * 1000L, 4, 2, 2, false, false)?.let { alerts += t.toLong() to it }
        assertEquals(listOf(60L to HrAlert.HIGH, 180L to HrAlert.HIGH, 300L to HrAlert.HIGH), alerts)
        // 目標の中に戻れば何も言わない
        val c2 = HrCoach()
        assertEquals(null, (0..300).firstNotNullOfOrNull { c2.update(it * 1000L, 2, 2, 2, false, false) })
        // 一度下がれば数え直す
        val c3 = HrCoach()
        var got: HrAlert? = null
        for (t in 0..110) {
            val zone = if (t == 50) 2 else 4
            c3.update(t * 1000L, zone, 2, 2, false, false)?.let { got = it }
        }
        assertEquals(null, got)
    }

    @Test
    fun coach_easy_only_when_wanting_speed_and_relaxed_on_climbs() {
        val jog = HrCoach()
        assertEquals(null, (0..400).firstNotNullOfOrNull { jog.update(it * 1000L, 1, 2, 2, false, false) })
        val tempo = HrCoach()
        val first = (0..400).firstNotNullOfOrNull { t -> tempo.update(t * 1000L, 2, 3, 4, true, false)?.let { t to it } }
        assertEquals(120 to HrAlert.EASY, first)
        // 登りの途中は、1ゾーン上までは注意しない
        val climb = HrCoach()
        assertEquals(null, (0..300).firstNotNullOfOrNull { climb.update(it * 1000L, 3, 2, 2, false, true) })
        assertNotNull((0..300).firstNotNullOfOrNull { climb.update(it * 1000L, 4, 2, 2, false, true) })
        // 心拍数が取れていないときは何も言わない
        val none = HrCoach()
        assertEquals(null, (0..300).firstNotNullOfOrNull { none.update(it * 1000L, null, 2, 2, false, false) })
    }

    @Test
    fun tracker_heart_rate_stats_and_announcement() {
        val cfg = RunConfig(autoPause = false, hrMax = 180, hrRest = 60, targetZoneLo = 2, targetZoneHi = 2)
        val t = RunTracker(cfg)
        t.tick(0)
        var lat = 35.6
        var now = 0L
        val events = ArrayList<TrackerEvent>()
        repeat(400) { i ->
            now += 1000
            lat += 3.0 / mPerDegLat
            t.onHeartRate(now, if (i < 200) 140 else 160) // 前半ゾーン2、後半ゾーン4
            t.onLocation(now, lat, 139.5, 5f, 3f)
            events += t.tick(now)
        }
        t.finish()
        assertEquals(150, t.avgHr)
        assertEquals(160, t.maxHr)
        assertEquals(200_000L, t.zoneMs[2])
        assertEquals(200_000L, t.zoneMs[4])
        val iv = events.filterIsInstance<TrackerEvent.Interval>().first()
        assertEquals(160, iv.hr)
        assertEquals(4, iv.zone)
        val text = AnnounceText.interval(iv, cfg, AnnounceOptions(cheer = false), PhraseBook(emptyMap()))
        assertTrue(text, text.contains("心拍160、ゾーン4。"))
        // ゾーン4 が 60秒 続いた所で「上がりすぎ」
        val heart = events.filterIsInstance<TrackerEvent.Heart>()
        assertEquals(HrAlert.HIGH, heart.first().alert)
        assertTrue(t.splits[0].avgHr in 148..152)
        // 心拍数が10秒届かなければ、取れていない扱い
        assertEquals(null, t.currentHr(now + 11_000))
    }

    @Test
    fun interval_watches_heart_only_while_running() {
        val plan = IntervalPlan(runByDistance = false, runAmount = 150.0, restSec = 150, repeats = 2)
        val t = RunTracker(RunConfig(autoPause = false, goalKind = GoalKind.INTERVAL, announceEvery = 0.0, interval = plan, hrMax = 180, hrRest = 60))
        t.tick(0)
        var now = 0L
        val alerts = ArrayList<Pair<Long, HrAlert>>()
        repeat(300) {
            now += 1000
            t.onHeartRate(now, 175) // ずっとゾーン5（「走る」の目標はゾーン4）
            t.tick(now).filterIsInstance<TrackerEvent.Heart>().forEach { alerts += now / 1000 to it.alert }
        }
        // 「走る」の間（0〜150秒）だけ注意が出て、「ゆっくり」の間（150〜300秒）は出ない
        assertEquals(listOf(61L to HrAlert.HIGH), alerts)
    }

    // ---- 段階5 ----

    /** bpm のテンポで「カッ」という短い音が鳴る、にせものの曲を作る */
    private fun clickTrack(bpm: Double, seconds: Int, rate: Int = 22050): FloatArray {
        val x = FloatArray(seconds * rate)
        val rnd = Random(7)
        val beat = 60.0 / bpm * rate
        var k = 0
        while (true) {
            val start = (k * beat).toInt()
            if (start + 400 >= x.size) break
            // 1拍目は強く、ほかは少し弱く
            val amp = if (k % 4 == 0) 0.9f else 0.6f
            for (i in 0 until 400) x[start + i] += amp * (rnd.nextFloat() * 2 - 1) * (1f - i / 400f)
            k++
        }
        for (i in x.indices) x[i] += 0.01f * (rnd.nextFloat() * 2 - 1) // うっすら雑音
        return x
    }

    private fun sameTempo(found: Double, truth: Double): Boolean =
        listOf(truth, truth * 2, truth / 2).any { Math.abs(found - it) / it < 0.015 }

    @Test
    fun bpm_detection_on_click_tracks() {
        for (bpm in listOf(90.0, 120.0, 128.0, 150.0, 170.0, 85.0, 174.0)) {
            val found = BpmDetector.detect(clickTrack(bpm, 30), 22050)
            assertNotNull("BPM $bpm を調べられませんでした", found)
            assertTrue("BPM $bpm のはずが $found", sameTempo(found!!, bpm))
        }
        assertEquals(null, BpmDetector.detect(FloatArray(22050 * 30), 22050)) // 無音
        assertEquals(null, BpmDetector.detect(clickTrack(120.0, 3), 22050))    // 短すぎる
    }

    @Test
    fun tap_tempo() {
        val t = TapTempo()
        var bpm: Double? = null
        for (i in 0 until 8) bpm = t.tap(i * 353L) // 約170BPM
        assertEquals(170.0, bpm!!, 1.0)
        assertEquals(null, t.tap(8 * 353L + 5000)) // 間があいたら測り直し
    }

    @Test
    fun tempo_fit_treats_double_and_half_as_same() {
        val f = TempoMatch.fit(85.0, 170.0)!!
        assertEquals(170.0, f.effectiveBpm, 1e-9)
        assertEquals(1.0, f.speed, 1e-9)
        val g = TempoMatch.fit(180.0, 172.0)!!
        assertEquals(180.0, g.effectiveBpm, 1e-9)
        assertEquals(0.9556, g.speed, 1e-3)
        assertEquals(120.0, TempoMatch.fit(240.0, 126.0)!!.effectiveBpm, 1e-9)
        assertEquals(1.1, TempoMatch.speedFor(150.0, 172.0, 0.10), 1e-9) // 幅の端で止まる
        assertEquals(172.0 / 170.0, TempoMatch.speedFor(170.0, 172.0, 0.10), 1e-9)
    }

    @Test
    fun pick_prefers_tracks_within_range_and_avoids_recent() {
        val tracks = listOf(1L to 170.0, 2L to 86.0, 3L to 128.0, 4L to 100.0, 5L to 176.0)
        // 目標 172、±10%：1（170）、2（86→172）、5（176）が入る。3（128）と 4（100）は入らない
        val seen = HashSet<Long>()
        repeat(200) {
            val (id, fit, ok) = TempoMatch.pick(tracks, 172.0, 0.10, emptyList(), Random(it))!!
            assertTrue(ok)
            assertTrue(fit.offset <= 0.10)
            seen += id
        }
        assertEquals(setOf(1L, 2L, 5L), seen)
        // 直前にかけた曲は避ける
        repeat(50) {
            val (id, _, _) = TempoMatch.pick(tracks, 172.0, 0.10, listOf(1L, 2L), Random(it))!!
            assertEquals(5L, id)
        }
        // 入る曲がなければ、いちばん近い曲を「幅の外」として返す
        val (id, _, ok) = TempoMatch.pick(listOf(3L to 128.0, 4L to 100.0), 172.0, 0.10, emptyList())!!
        assertEquals(4L, id) // 100×2=200 → 0.86、128 → 1.34
        assertFalse(ok)
        assertEquals(null, TempoMatch.pick(emptyList(), 172.0, 0.10, emptyList()))
    }

    @Test
    fun cadence_model_default_and_learned() {
        val d = CadenceModel()
        assertFalse(d.learned)
        assertEquals(168.0, d.cadenceFor(360.0), 1e-9)
        assertEquals(171.0, d.cadenceFor(330.0), 1e-9)
        // 自分の記録：6分/km で 176、5分/km で 182 くらいの人
        val samples = (0 until 12).map { i -> (300.0 + i * 6) to (182 - Math.round(i * 0.6).toInt()) }
        val m = CadenceModel(samples)
        assertTrue(m.learned)
        assertEquals(176.0, m.cadenceFor(360.0), 1.5)
        assertEquals(182.0, m.cadenceFor(300.0), 1.5)
        // いつも同じペースの人は、平均だけ学ぶ
        val flat = CadenceModel(List(10) { 400.0 to 160 })
        assertTrue(flat.learned)
        assertEquals(160.0, flat.cadenceFor(400.0), 0.5)
    }

    // ---- 段階6 ----

    @Test
    fun fastest_segment() {
        // 3km：1km 目は 6分、2km 目は 5分、3km 目は 7分（10m ごとの地点）
        val n = 301
        val dist = DoubleArray(n) { it * 10.0 }
        val ms = LongArray(n)
        for (i in 1 until n) ms[i] = ms[i - 1] + when { i <= 100 -> 3600L; i <= 200 -> 3000L; else -> 4200L }
        assertEquals(300_000L, Bests.fastest(dist, ms, 1000.0))
        assertEquals(1_080_000L, Bests.fastest(dist, ms, 3000.0))
        assertEquals(null, Bests.fastest(dist, ms, 5000.0))
        // 地点がまばらでも、あいだを補って出せる（100m を 30秒、の等速）
        val d2 = doubleArrayOf(0.0, 700.0, 1400.0, 2100.0)
        val m2 = longArrayOf(0, 210_000, 420_000, 630_000)
        assertEquals(300_000L, Bests.fastest(d2, m2, 1000.0))
    }

    @Test
    fun seven_day_average_smooths_daily_noise() {
        // 毎日 ±1kg ゆれるが、平均は 70kg
        val daily = (0L until 14).map { it to (70.0 + if (it % 2 == 0L) 1.0 else -1.0) }
        val avg = Review.sevenDayAverage(daily)
        assertEquals(14, avg.size)
        assertEquals(71.0, avg[0].second, 1e-9)          // 初日はその日だけ
        assertTrue(Math.abs(avg[13].second - 70.0) < 0.2) // 7日ぶんそろえば、ほぼ 70
        // 同じ日に2回測っていたら、先に平均する
        val twice = Review.sevenDayAverage(listOf(5L to 60.0, 5L to 62.0))
        assertEquals(61.0, twice.single().second, 1e-9)
        // 測っていない日があっても、ある日だけで平均する
        val gaps = Review.sevenDayAverage(listOf(0L to 70.0, 10L to 68.0))
        assertEquals(68.0, gaps[1].second, 1e-9)
    }

    @Test
    fun same_pace_heart_rate_comparison() {
        // 6分30秒/km 前後（390秒）の区間が、今月は平均 148、先月は平均 152
        val now = listOf(385.0 to 147, 392.0 to 149, 398.0 to 148, 330.0 to 165)
        val last = listOf(388.0 to 152, 395.0 to 153, 381.0 to 151, 450.0 to 135)
        val r = Review.samePaceHr(now, last)!!
        assertEquals(390, r.paceSecPerKm)
        assertEquals(3, r.thisCount)
        assertEquals(3, r.lastCount)
        assertEquals(148, r.thisAvg)
        assertEquals(152, r.lastAvg)
        assertEquals(-4, r.diff)
        assertTrue(r.diff < 0)
        // 片方の月にしかないペースでは比べない
        assertEquals(null, Review.samePaceHr(listOf(300.0 to 160, 305.0 to 161), listOf(450.0 to 130, 455.0 to 131)))
        // 心拍数が取れていない区間（0）は使わない
        assertEquals(null, Review.samePaceHr(listOf(390.0 to 0, 391.0 to 0), listOf(390.0 to 150, 391.0 to 150)))
    }

    @Test
    fun cheer_praises_continuing_not_numbers() {
        assertTrue(Review.cheer(8, 1).contains("今月は8回走った"))
        assertTrue(Review.cheer(0, 0).isNotEmpty())
        assertTrue(Review.cheer(5, 3).contains("今週は3回"))
    }

    @Test
    fun hide_zone() {
        val home = HideZone(35.6000, 139.5400, 200.0)
        assertTrue(home.hides(35.6010, 139.5400))   // 約111m
        assertFalse(home.hides(35.6030, 139.5400))  // 約334m
    }

    @Test
    fun yuv_conversion_of_known_colors() {
        val w = 4; val h = 2
        val px = IntArray(w * h)
        px[0] = 0xFFFFFFFF.toInt(); px[1] = 0xFF000000.toInt(); px[2] = 0xFFFF0000.toInt(); px[3] = 0xFF0000FF.toInt()
        val y = ByteArray(w * h); val u = ByteArray(2); val v = ByteArray(2)
        Yuv.fromArgb(px, w, h, y, u, v)
        fun b(x: Byte) = x.toInt() and 0xFF
        assertEquals(235, b(y[0]))  // 白
        assertEquals(16, b(y[1]))   // 黒
        assertEquals(82.0, b(y[2]).toDouble(), 2.0) // 赤
        assertEquals(128, b(u[0])); assertEquals(128, b(v[0])) // 白は色みなし
        assertEquals(240.0, b(v[1]).toDouble(), 2.0)             // 赤は V が高い
    }

    @Test
    fun movie_timeline() {
        assertEquals(MovieScript.Scene.TITLE, MovieScript.sceneAt(0.5, 2))
        assertEquals(MovieScript.Scene.MAP, MovieScript.sceneAt(3.0, 2))
        assertEquals(MovieScript.Scene.MEDIA, MovieScript.sceneAt(6.0, 2))
        assertEquals(MovieScript.Scene.STATS, MovieScript.sceneAt(9.0, 2))
        // 写真・動画がない日は、地図を 8.5秒まで
        assertEquals(MovieScript.Scene.MAP, MovieScript.sceneAt(6.0, 0))
        assertEquals(0.0, MovieScript.routeProgress(1.5, 2), 1e-9)
        assertEquals(1.0, MovieScript.routeProgress(4.7, 2), 1e-9)
        assertEquals(0, MovieScript.mediaSlot(5.1, 4).first)
        assertEquals(3, MovieScript.mediaSlot(8.4, 4).first)
        assertEquals(0, MovieScript.mediaSlot(8.4, 1).first)
    }

    // ---- ランムービーの曲 ----

    /** 左右2チャンネルの音の、ある区間の大きさ */
    private fun rms(x: FloatArray, fromSec: Double, toSec: Double, rate: Int): Double {
        var s = 0.0
        val a = (fromSec * rate).toInt() * 2
        val b = (toSec * rate).toInt() * 2
        for (i in a until b) s += x[i] * x[i]
        return Math.sqrt(s / (b - a))
    }

    @Test
    fun every_style_is_audible_bounded_stereo_and_ten_seconds() {
        val rate = 22050
        val seen = HashSet<TuneStyle>()
        // たくさんの材料と乱数で、4つの型がすべて出てくることと、どれもちゃんと鳴ることを確かめる
        val moods = listOf(
            TuneMood(trail = false, feeling = 2, tempC = 18.0, sky = Sky.SUNNY, effort = 0.8),
            TuneMood(trail = true, feeling = -2, tempC = 3.0, sky = Sky.RAIN, effort = 0.2),
            TuneMood(trail = true, feeling = 0, tempC = 31.0, sky = Sky.CLOUDY),
            TuneMood(trail = false, feeling = 0, sky = Sky.SNOW),
            TuneMood(trail = false),
        )
        for (seed in 1L..8L) for (mood in moods) {
            val plan = RunTune.plan(mood, seed)
            if (!seen.add(plan.style) && seed > 2) continue
            val x = RunTune.render(plan, seed, 10.0, rate)
            val label = "${plan.style} ${plan.description}"
            assertEquals(rate * 10 * 2, x.size)
            assertTrue("音が大きすぎます $label", x.all { Math.abs(it) <= 1.0f })
            assertTrue("全体の音が小さすぎます $label", rms(x, 0.0, 10.0, rate) > 0.05)
            assertTrue("出だしが無音です $label", rms(x, 0.0, 1.5, rate) > 0.003)
            assertTrue("本編が小さすぎます $label", rms(x, 4.0, 8.0, rate) > 0.06)
            assertTrue("終わりの和音が鳴っていません $label", rms(x, 8.5, 9.3, rate) > 0.02)
            assertTrue("最後がぷつっと切れています $label", Math.abs(x[x.size - 1]) < 0.01f && Math.abs(x[x.size - 2]) < 0.01f)
            // 左右で少し違う音が出ている（広がりがある）
            var diff = 0.0
            for (i in 0 until x.size / 2) diff += Math.abs(x[i * 2] - x[i * 2 + 1])
            assertTrue("左右が同じ音です $label", diff / (x.size / 2) > 0.002)
        }
        assertEquals(TuneStyle.values().toSet(), seen)
    }

    @Test
    fun style_follows_the_run_in_combination() {
        fun share(mood: TuneMood, vararg styles: TuneStyle): Double =
            (1L..400L).count { RunTune.plan(mood, it).style in styles } / 400.0
        // 雨のつらかったトレイル → ほとんどローファイかジャジー（落ち着いた型）
        assertTrue(share(TuneMood(trail = true, feeling = -2, sky = Sky.RAIN), TuneStyle.LOFI, TuneStyle.JAZZY) > 0.85)
        assertTrue(share(TuneMood(trail = true, feeling = -2, sky = Sky.RAIN), TuneStyle.LOFI) > 0.5)
        // 晴れの楽しかったロードを、がんばって走った → ほとんどハウスかクラブ（踊れる型）
        assertTrue(share(TuneMood(trail = false, feeling = 2, sky = Sky.SUNNY, effort = 0.9), TuneStyle.HOUSE, TuneStyle.CLUB) > 0.75)
        // ふつうのトレイル → ジャジーがいちばん多い
        val trail = TuneMood(trail = true)
        assertTrue(share(trail, TuneStyle.JAZZY) > share(trail, TuneStyle.HOUSE))
        assertTrue(share(trail, TuneStyle.JAZZY) > share(trail, TuneStyle.CLUB))
        // どの材料でも、ほかの型が出る余地は残っている（毎回ちがう曲になる）
        assertTrue(share(TuneMood(trail = false), TuneStyle.LOFI, TuneStyle.JAZZY) > 0.1)
    }

    @Test
    fun tempo_and_harmony_follow_each_style_conventions() {
        for (seed in 1L..200L) {
            val mood = TuneMood(trail = seed % 2 == 0L, feeling = (seed % 5).toInt() - 2, sky = Sky.values()[(seed % 5).toInt()], effort = (seed % 10) / 10.0)
            val p = RunTune.plan(mood, seed)
            when (p.style) {
                TuneStyle.LOFI -> assertTrue(p.bpm in 70.0..86.0)
                TuneStyle.JAZZY -> assertTrue(p.bpm in 96.0..114.0)
                TuneStyle.HOUSE -> assertTrue(p.bpm in 120.0..126.0)
                TuneStyle.CLUB -> { assertTrue(p.bpm in 122.0..128.0); assertEquals(1, p.tonality) }
            }
            // リズムは、最後の和音（8.5秒）の手前に、ちょうど収まる
            val start = 8.5 - p.beats * 60.0 / p.bpm
            assertTrue("${p.style} ${p.bpm}", start in 0.4..3.2)
            // 和音は全部、7th 以上の音が入ったジャズの和音
            assertTrue(p.chords.all { it.tones.size >= 4 })
            assertEquals(0, p.beats % p.chordBeats)
        }
        // 気分と天気で、響きが変わる
        assertEquals(0, RunTune.plan(TuneMood(trail = true, feeling = 2, sky = Sky.SUNNY), 3).let { if (it.style == TuneStyle.CLUB) 0 else it.tonality })
        assertEquals(1, RunTune.plan(TuneMood(trail = true, feeling = -2, sky = Sky.RAIN), 3).tonality)
    }

    @Test
    fun tune_changes_with_seed_but_repeats_for_same_seed() {
        val mood = TuneMood(trail = false, feeling = 1, sky = Sky.SUNNY)
        val a = RunTune.render(RunTune.plan(mood, 1), 1, 10.0, 11025)
        val a2 = RunTune.render(RunTune.plan(mood, 1), 1, 10.0, 11025)
        val b = RunTune.render(RunTune.plan(mood, 2), 2, 10.0, 11025)
        assertTrue(a.contentEquals(a2))
        assertFalse(a.contentEquals(b))
        val text = RunTune.plan(TuneMood(trail = true, feeling = 1, tempC = 4.0, sky = Sky.SUNNY), 3).description
        assertTrue(text, text.contains("トレイル") && text.contains("楽しかった") && text.contains("晴れ") && text.contains("寒い") && text.contains("ビブラフォン"))
    }

    @Test
    fun weather_code_and_words() {
        assertEquals(Sky.SUNNY, TuneMood.skyOf(0))
        assertEquals(Sky.CLOUDY, TuneMood.skyOf(3))
        assertEquals(Sky.RAIN, TuneMood.skyOf(61))
        assertEquals(Sky.RAIN, TuneMood.skyOf(80))
        assertEquals(Sky.SNOW, TuneMood.skyOf(73))
        assertEquals(Sky.UNKNOWN, TuneMood.skyOf(-1))
        assertEquals(1, TuneMood.feelingFromWords("今日は気持ちいい風で楽しかった"))
        assertEquals(-1, TuneMood.feelingFromWords("後半きつい。足が重い"))
        assertEquals(0, TuneMood.feelingFromWords("生田緑地を走った"))
        assertTrue(TuneMood(trail = false, tempC = 5.0).cold)
        assertTrue(TuneMood(trail = false, tempC = 30.0).hot)
        assertFalse(TuneMood(trail = false).cold)
    }
}
