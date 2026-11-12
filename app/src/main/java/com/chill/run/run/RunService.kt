package com.chill.run.run

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.chill.run.App
import com.chill.run.R
import com.chill.run.RunActivity
import com.chill.run.core.AnnounceText
import com.chill.run.core.Fmt
import com.chill.run.core.Geo
import com.chill.run.core.GoalKind
import com.chill.run.core.PhraseBook
import com.chill.run.core.RunConfig
import com.chill.run.core.RunTracker
import com.chill.run.core.RunType
import com.chill.run.core.Scenes
import com.chill.run.core.TrackerEvent
import com.chill.run.data.AppDb
import com.chill.run.data.PhraseStore
import com.chill.run.data.PointEntity
import com.chill.run.data.RunEntity
import com.chill.run.data.Settings
import com.chill.run.data.SplitEntity
import com.chill.run.data.deleteRun
import com.chill.run.health.Health
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 走っている間ずっと動き続ける常駐サービス。
 * 画面を消してポケットに入れても、GPS・気圧・歩数を受け取り続ける。
 */
class RunService : Service(), SensorEventListener, LocationListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)

    private lateinit var settings: Settings
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager

    private var tracker: RunTracker? = null
    private var config = RunConfig()
    private var speaker: Speaker? = null
    private var phrases = PhraseBook(emptyMap())
    private var wakeLock: PowerManager.WakeLock? = null
    private var ticker: Job? = null
    private var runId = CompletableDeferred<Long>()
    private var startWallMs = 0L

    // 標高
    private var hasBarometer = false
    private var pressureSum = 0.0
    private var pressureCount = 0
    private var baroAlt = Double.NaN
    private var altOffset = Double.NaN
    private var startAltSource = ""
    private var calibrationStarted = false
    private var gpsAltSmooth = Double.NaN

    // 歩数
    private var lastStepCounter = -1f
    private var tickCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        sensorManager = getSystemService(SensorManager::class.java)
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "ランの記録中", NotificationManager.IMPORTANCE_LOW).apply {
                description = "走っている間、距離や時間を表示します"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (tracker == null) startRun(intent) else updateNotification()
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_FINISH -> finishRun()
            else -> {}
        }
        // 記録していないのに呼ばれたときは、何もせず閉じる
        if (tracker == null && intent?.action != ACTION_START && intent?.action != ACTION_FINISH) stopSelf()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun startRun(intent: Intent) {
        val type = runCatching { RunType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: "ROAD") }.getOrDefault(RunType.ROAD)
        val goalKind = runCatching { GoalKind.valueOf(intent.getStringExtra(EXTRA_GOAL_KIND) ?: "FREE") }.getOrDefault(GoalKind.FREE)
        val goalValue = intent.getDoubleExtra(EXTRA_GOAL_VALUE, 0.0)

        val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        hasBarometer = pressureSensor != null
        config = settings.runConfig(type, goalKind, goalValue, hasBarometer)
        val t = RunTracker(config)
        tracker = t
        startWallMs = System.currentTimeMillis()
        runId = CompletableDeferred()

        // まず常駐の通知を出す（画面が開いている間に始めるので「使用中のみ」の位置情報で動く）
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            tracker = null
            RunHub.state.value = LiveState(finishedRunId = -1)
            stopSelf()
            return
        }

        val dao = AppDb.get(this).runDao()
        val elevationSource = if (hasBarometer) "気圧" else "GPS"
        // サービスがすぐ閉じられても、ランの番号は必ず決まるようにアプリ側の作業場で行う
        (application as App).scope.launch(dbDispatcher) {
            val id = dao.insertRun(
                RunEntity(
                    startTime = startWallMs,
                    type = type.name,
                    goalKind = goalKind.name,
                    goalValue = goalValue,
                    elevationSource = elevationSource,
                )
            )
            runId.complete(id)
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chillrun:recording")
            .also { it.acquire() }

        phrases = PhraseBook(PhraseStore.load(this))
        speaker = Speaker(this, settings.ttsRate, settings.ttsVolume)

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepDetector != null) {
            sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_FASTEST)
        } else if (stepCounter != null) {
            sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_FASTEST)
        }

        publish()
        if (settings.announceOn) speaker?.say("スタート")

        t.tick(SystemClock.elapsedRealtime())
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                onTick()
            }
        }
    }

    private fun onTick() {
        val t = tracker ?: return
        val now = SystemClock.elapsedRealtime()
        if (pressureCount > 0) {
            baroAlt = Geo.pressureToAltitude(pressureSum / pressureCount)
            pressureSum = 0.0
            pressureCount = 0
            t.onAltitude(baroAlt)
        }
        val events = t.tick(now)
        for (ev in events) handleEvent(ev)
        publish()
        if (tickCount++ % 3 == 0) updateNotification()
    }

    private fun handleEvent(ev: TrackerEvent) {
        val sp = speaker ?: return
        val on = settings.announceOn
        when (ev) {
            is TrackerEvent.Interval -> if (on) sp.say(AnnounceText.interval(ev, config, settings.announceOptions, phrases))
            is TrackerEvent.Countdown -> if (on) {
                val flush = ev.kind == GoalKind.TIME && ev.remaining < 10
                sp.say(AnnounceText.countdown(ev), flush)
            }
            is TrackerEvent.GoalReached -> {
                sp.chime()
                vibrate(400)
                sp.say("目標達成！" + (phrases.pick(Scenes.GOAL) ?: ""), flush = true)
            }
            is TrackerEvent.AutoPause -> {}
        }
    }

    override fun onLocationChanged(location: Location) {
        val t = tracker ?: return
        val now = location.elapsedRealtimeNanos / 1_000_000
        val accuracy = if (location.hasAccuracy()) location.accuracy else 999f
        val speed = if (location.hasSpeed()) location.speed else null

        if (!hasBarometer && location.hasAltitude() && accuracy <= RunTracker.MAX_ACCURACY_M) {
            // 気圧センサーがない端末では GPS の高さを使う（日本では実際より約37m 高く出るので引く）
            val msl = if (location.hasMslAltitude()) location.mslAltitudeMeters else location.altitude - GEOID_JAPAN_M
            gpsAltSmooth = if (gpsAltSmooth.isNaN()) msl else gpsAltSmooth + 0.1 * (msl - gpsAltSmooth)
            t.onAltitude(gpsAltSmooth)
            if (altOffset.isNaN()) {
                altOffset = 0.0
                startAltSource = "GPS"
            }
        }
        if (hasBarometer && !calibrationStarted && !baroAlt.isNaN() && accuracy <= 20f) {
            calibrationStarted = true
            calibrateAltitude(Location(location), baroAlt)
        }

        val sample = t.onLocation(now, location.latitude, location.longitude, accuracy, speed) ?: return
        val cadence = t.cadence(SystemClock.elapsedRealtime())
        val wall = System.currentTimeMillis()
        val id = runId
        scope.launch(dbDispatcher) {
            AppDb.get(this@RunService).runDao().insertPoint(
                PointEntity(
                    runId = id.await(),
                    time = wall,
                    lat = sample.lat,
                    lon = sample.lon,
                    alt = sample.altM,
                    accuracy = sample.accuracyM,
                    distM = sample.distM,
                    movingMs = sample.movingMs,
                    cadence = cadence,
                )
            )
        }
    }

    /**
     * 走り始めの地点の標高を合わせる。
     * まず GPS の高さで仮に合わせ、国土地理院の標高が取れたらそちらに置き換える。
     */
    private fun calibrateAltitude(fix: Location, baroAtFix: Double) {
        scope.launch {
            val gpsMsl = withContext(Dispatchers.IO) {
                runCatching {
                    if (!fix.hasMslAltitude() && fix.hasAltitude()) {
                        AltitudeConverter().addMslAltitudeToLocation(this@RunService, fix)
                    }
                }
                when {
                    fix.hasMslAltitude() -> fix.mslAltitudeMeters
                    fix.hasAltitude() -> fix.altitude - GEOID_JAPAN_M
                    else -> null
                }
            }
            if (gpsMsl != null && altOffset.isNaN()) {
                altOffset = gpsMsl - baroAtFix
                startAltSource = "GPS"
            }
            val gsi = ElevationApi.fetch(fix.latitude, fix.longitude)
            if (gsi != null) {
                altOffset = gsi - baroAtFix
                startAltSource = "地理院"
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val t = tracker ?: return
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                pressureSum += event.values[0]
                pressureCount++
            }
            Sensor.TYPE_STEP_DETECTOR -> t.onStep(SystemClock.elapsedRealtime())
            Sensor.TYPE_STEP_COUNTER -> {
                val v = event.values[0]
                if (lastStepCounter >= 0) {
                    val n = (v - lastStepCounter).toInt().coerceIn(0, 20)
                    val now = SystemClock.elapsedRealtime()
                    for (i in 0 until n) t.onStep(now - (n - 1 - i) * 330L)
                }
                lastStepCounter = v
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun setPaused(paused: Boolean) {
        val t = tracker ?: return
        if (t.manualPaused == paused) return
        t.setManualPaused(paused)
        vibrate(150)
        if (settings.announceOn) speaker?.say(if (paused) "一時停止" else "再開", flush = true)
        publish()
        updateNotification()
    }

    private fun finishRun() {
        val t = tracker ?: run { stopSelf(); return }
        tracker = null
        ticker?.cancel()
        runCatching { locationManager.removeUpdates(this) }
        sensorManager.unregisterListener(this)
        t.finish()
        vibrate(300)

        val endWall = System.currentTimeMillis().coerceAtLeast(startWallMs + 1000)
        val sp = speaker
        speaker = null
        val keep = t.distanceM >= 20
        if (settings.announceOn) {
            sp?.say(
                if (keep) "おつかれさま。距離${Fmt.distanceSpoken(Math.round(t.distanceM / 10.0) * 10.0)}、タイム${Fmt.durationSpoken(t.movingMs / 1000)}。"
                else "記録を終了しました",
                flush = true,
            )
        }

        val app = application as App
        val context = applicationContext
        val idDeferred = runId
        val weight = settings.weightKg.toDouble()
        val offset = if (altOffset.isNaN()) 0.0 else altOffset
        val altSource = startAltSource
        val dispatcher = dbDispatcher
        app.scope.launch {
            val id = idDeferred.await()
            val dao = AppDb.get(context).runDao()
            // 先に出した地点の保存が終わるのを待ってから仕上げる
            withContext(dispatcher) {
                if (!keep) {
                    dao.deleteRun(id)
                } else {
                    val run = dao.getRun(id) ?: return@withContext
                    dao.insertSplits(t.splits.map {
                        SplitEntity(runId = id, idx = it.index, distanceM = it.distanceM, movingMs = it.movingMs, gainM = it.gainM, lossM = it.lossM, steps = it.steps)
                    })
                    dao.updateRun(
                        run.copy(
                            endTime = endWall,
                            distanceM = t.distanceM,
                            movingMs = t.movingMs,
                            gainM = t.gainM,
                            lossM = t.lossM,
                            steps = t.steps,
                            calories = RunTracker.calories(weight, t.distanceM, t.gainM),
                            startAltSource = altSource,
                            altOffset = offset,
                        )
                    )
                }
            }
            // ヘルスコネクトへの書き込みは数秒だけ待つ（遅いときは待たずに記録の画面へ進む）
            val hcJob = if (keep) launch { runCatching { Health.writeRun(context, id) } } else null
            withTimeoutOrNull(4000) { hcJob?.join() }
            RunHub.state.value = LiveState(finishedRunId = if (keep) id else -1)
            delay(8000)
            sp?.shutdown()
        }

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publish() {
        val t = tracker ?: return
        val now = SystemClock.elapsedRealtime()
        val alt = t.altitudeM
        RunHub.state.value = LiveState(
            active = true,
            type = config.type,
            goalKind = config.goalKind,
            goalValue = config.goalValue,
            distanceM = t.distanceM,
            movingMs = t.movingMs,
            pace = if (t.isPaused) null else t.currentPace(),
            avgPace = t.averagePace(),
            cadence = if (t.isPaused) null else t.cadence(now),
            altitudeM = if (alt != null && !altOffset.isNaN()) alt + altOffset else null,
            gainM = t.gainM,
            lossM = t.lossM,
            manualPaused = t.manualPaused,
            autoPaused = t.autoPaused,
            gpsAccuracyM = t.lastAccuracyM,
            elevationSource = if (hasBarometer) "気圧" else "GPS",
            goalReached = t.goalReached,
        )
    }

    private fun buildNotification(): Notification {
        val t = tracker
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, RunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val status = when {
            t == null -> "記録中"
            t.manualPaused -> "一時停止中"
            t.autoPaused -> "自動で一時停止中"
            else -> "記録中"
        }
        val title = if (t == null) "ラン $status" else "${Fmt.km(t.distanceM)} km ・ ${Fmt.clock(t.movingMs)}"
        val text = if (t == null) "" else "$status ・ 平均ペース ${Fmt.pace(t.averagePace())}"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .build()
    }

    private fun updateNotification() {
        if (tracker == null) return
        runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun vibrate(ms: Long) {
        runCatching {
            getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onDestroy() {
        // 終了の操作なしに止められた場合は、次にアプリを開いたときに App が記録を仕上げる
        if (tracker != null) {
            tracker = null
            runCatching { locationManager.removeUpdates(this) }
            sensorManager.unregisterListener(this)
            speaker?.shutdown()
            wakeLock?.let { if (it.isHeld) it.release() }
            RunHub.state.value = LiveState()
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "run"
        private const val NOTIFICATION_ID = 1
        private const val GEOID_JAPAN_M = 37.0
        const val ACTION_START = "com.chill.run.START"
        const val ACTION_PAUSE = "com.chill.run.PAUSE"
        const val ACTION_RESUME = "com.chill.run.RESUME"
        const val ACTION_FINISH = "com.chill.run.FINISH"
        const val EXTRA_TYPE = "type"
        const val EXTRA_GOAL_KIND = "goalKind"
        const val EXTRA_GOAL_VALUE = "goalValue"

        fun start(context: Context, type: RunType, goalKind: GoalKind, goalValue: Double) {
            RunHub.state.value = LiveState(active = true, type = type, goalKind = goalKind, goalValue = goalValue)
            context.startForegroundService(
                Intent(context, RunService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TYPE, type.name)
                    .putExtra(EXTRA_GOAL_KIND, goalKind.name)
                    .putExtra(EXTRA_GOAL_VALUE, goalValue)
            )
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, RunService::class.java).setAction(action))
        }
    }
}
