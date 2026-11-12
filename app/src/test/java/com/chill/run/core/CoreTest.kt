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
        assertTrue(RouteAnalysis.looksLikeTrail(2000.0, 60.0, steep, 15.0, 10.0))
        assertFalse(RouteAnalysis.looksLikeTrail(2000.0, 10.0, 0.0, 15.0, 10.0))
        // 登りは多いが坂が短い（ゆるい長い登り）はトレイルにしない
        assertFalse(RouteAnalysis.looksLikeTrail(2000.0, 60.0, 0.02, 15.0, 10.0))
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
}
