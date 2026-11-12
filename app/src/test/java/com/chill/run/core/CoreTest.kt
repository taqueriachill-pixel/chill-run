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
}
