package com.chill.run.music

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.chill.run.core.TempoMatch
import com.chill.run.data.TrackEntity

/** 音楽がいつ・なぜ切り替わったかの覚え書き（直近40件）。「実機チェック」で見られる */
object MusicLog {
    private val lines = ArrayDeque<String>()
    private val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    @Synchronized
    fun add(text: String) {
        lines.addLast("${java.time.LocalTime.now().format(fmt)} $text")
        while (lines.size > 40) lines.removeFirst()
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "まだ何もありません" else lines.joinToString("\n")
}

/**
 * ランの最中に音楽を流す。
 * ・目標テンポに近い BPM の曲を選ぶ
 * ・音程はそのままに、速さだけ変えて目標テンポに合わせる（数秒かけてなめらかに）
 * ・曲の変わり目は、2.5秒かけて重ねて切り替える（プレイヤーを2つ使う）
 * ・声のお知らせの間は、自分で音量を下げる
 * ・イヤホンのボタン（再生・停止、次の曲）で操作できる
 * すべてメインスレッドで動かす。
 */
class MusicEngine(
    context: Context,
    tracks: List<TrackEntity>,
    /** 速さを変える幅。0.10 なら ±10% */
    private val range: Double,
    private val baseVolume: Float,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val usable = tracks.filter { it.enabled && it.bpm > 0 }
    private val players = Array(2) { newPlayer() }
    private var active = 0
    private val recent = ArrayList<Long>()

    private var current: TrackEntity? = null
    private var effectiveBpm = 0.0
    private var inRange = true
    private var speed = 1.0
    private var target = 0.0

    private var userPaused = false
    private var focusPaused = false
    private var ducked = false
    private var fading = false
    private var fadeStart = 0L
    private var released = false
    private var startedAt = 0L
    private var errorCount = 0
    private var lastSpeedChangeAt = 0L
    private var holdSpeedUntil = 0L
    private var lastReason = ""

    val hasTracks: Boolean get() = usable.isNotEmpty()
    val title: String get() = current?.let { if (it.artist.isNotEmpty()) "${it.title} / ${it.artist}" else it.title } ?: ""
    val playing: Boolean get() = current != null && !userPaused && !focusPaused

    /** 画面に出す説明：「曲 86→172BPM ・ 速さ 101% ・ 目標 174」 */
    val info: String
        get() {
            val t = current ?: return if (usable.isEmpty()) "BPM の分かる曲がありません" else ""
            val bpmText = if (Math.abs(effectiveBpm - t.bpm) > 0.5) "${Math.round(t.bpm)}→${Math.round(effectiveBpm)}" else "${Math.round(t.bpm)}"
            return "曲 ${bpmText}BPM ・ 速さ ${Math.round(speed * 100)}% ・ 目標 ${Math.round(target)}" + if (inRange) "" else "（合う曲がないので近い曲）"
        }

    // ---- ほかの音との兼ね合い（電話などが鳴ったら止める） ----
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handler.post {
            if (released) return@post
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    MusicLog.add("ほかの音（電話など）が鳴ったので、いったん止めた")
                    focusPaused = true; applyPlayState()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // ほかのアプリが音楽などを流し始めた。勝手には戻らないので、「流す」を押すまで止めておく
                    MusicLog.add("ほかのアプリが音を使い始めたので止めた（「流す」で再開）")
                    userPaused = true; applyPlayState()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (focusPaused) MusicLog.add("ほかの音が終わったので再開した")
                    focusPaused = false; applyPlayState()
                }
            }
        }
    }
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            PlatformAudioAttributes.Builder().setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        .setOnAudioFocusChangeListener(focusListener, handler)
        .build()

    // ---- イヤホンのボタン ----
    private val session = MediaSession(appContext, "ChillRunMusic").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = setUserPaused(false, "イヤホンや時計のボタン")
            override fun onPause() = setUserPaused(true, "イヤホンや時計のボタン")
            override fun onSkipToNext() = next("イヤホンや時計のボタン")
        }, handler)
    }

    private fun newPlayer(): ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        // 2つのプレイヤーが取り合わないよう、音の優先権はこのクラスでまとめて扱う
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        setWakeMode(C.WAKE_MODE_LOCAL)
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // 曲が最後まで行った（重ねて切り替える前に終わった）ときは、次の曲へ
                if (state == Player.STATE_ENDED && this@apply === players[active] && !fading) switchTo(pickNext(), fade = false, reason = "曲が最後まで行った")
            }

            override fun onPlayerError(error: PlaybackException) {
                // 原因を覚え書きに残す（どこで何が起きたかが分かるように、いちばん奥の原因まで書く）
                var cause: Throwable? = error.cause
                val chain = StringBuilder()
                var depth = 0
                while (cause != null && depth < 3) {
                    chain.append(" ← ${cause.javaClass.simpleName}: ${cause.message ?: ""}")
                    cause.stackTrace.firstOrNull()?.let { chain.append(" @${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}") }
                    cause = cause.cause
                    depth++
                }
                MusicLog.add("再生の問題：${current?.title}（${error.errorCodeName}$chain）")
                if (this@apply !== players[active]) return
                handler.post {
                    if (released) return@post
                    if (errorCount < 2) {
                        // 曲を飛ばさず、止まった所からもう一度流す（速さは元に戻して、変えるのをしばらく休む）
                        errorCount++
                        speed = 1.0
                        holdSpeedUntil = SystemClock.elapsedRealtime() + 20_000
                        playbackParameters = PlaybackParameters(1f, 1f)
                        prepare()
                        MusicLog.add("同じ曲を、止まった所から流し直した（${errorCount}回目）")
                    } else switchTo(pickNext(), fade = false, reason = "同じ曲で問題が続いた")
                }
            }
        })
    }

    /** 最初の曲をかけ始める */
    fun start(initialTarget: Double) {
        if (usable.isEmpty()) return
        target = initialTarget
        audioManager.requestAudioFocus(focusRequest)
        session.isActive = true
        MusicLog.add("音楽を始めた（使える曲 ${usable.size} 曲、目標 ${Math.round(initialTarget)}、幅 ±${Math.round(range * 100)}%）")
        switchTo(pickNext(), fade = false, reason = "最初の曲")
    }

    private fun pickNext(): Triple<TrackEntity, TempoMatch.Fit, Boolean>? {
        val picked = TempoMatch.pick(usable.map { it.id to it.bpm }, target, range, recent) ?: return null
        val track = usable.first { it.id == picked.first }
        return Triple(track, picked.second, picked.third)
    }

    private fun switchTo(choice: Triple<TrackEntity, TempoMatch.Fit, Boolean>?, fade: Boolean, reason: String) {
        if (released || choice == null) return
        val (track, fit, ok) = choice
        val before = current
        val playedSec = if (before != null) (SystemClock.elapsedRealtime() - startedAt) / 1000 else 0
        MusicLog.add("切り替え［$reason］" + (if (before != null) "「${before.title}」を ${playedSec}秒 流した所で → " else "") + "「${track.title}」")
        lastReason = reason
        startedAt = SystemClock.elapsedRealtime()
        errorCount = 0
        val old = players[active]
        val incoming = if (fade && old.isPlaying) players[1 - active] else old
        current = track
        effectiveBpm = fit.effectiveBpm
        inRange = ok
        speed = TempoMatch.speedFor(effectiveBpm, target, range)
        recent += track.id
        if (recent.size > 20) recent.removeAt(0)

        incoming.setMediaItem(MediaItem.fromUri(Uri.parse(track.uri)))
        incoming.playbackParameters = PlaybackParameters(speed.toFloat(), 1f) // 音程は 1.0 のまま、速さだけ変える
        incoming.prepare()
        if (incoming !== old) {
            // 重ねて切り替える：新しい曲を音量0で始めて、少しずつ入れ替える
            incoming.volume = 0f
            active = 1 - active
            fading = true
            fadeStart = SystemClock.elapsedRealtime()
            handler.post(fadeStep)
        } else {
            fading = false
            players[1 - active].stop()
            incoming.volume = volume()
        }
        applyPlayState()
        updateSession()
    }

    private val fadeStep = object : Runnable {
        override fun run() {
            if (released || !fading) return
            val p = ((SystemClock.elapsedRealtime() - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
            players[active].volume = volume() * p
            players[1 - active].volume = volume() * (1 - p)
            if (p >= 1f) {
                fading = false
                players[1 - active].stop()
            } else handler.postDelayed(this, 50)
        }
    }

    private fun volume() = baseVolume * if (ducked) DUCK else 1f

    private fun applyPlayState() {
        val play = !userPaused && !focusPaused
        players[active].playWhenReady = play
        if (fading) players[1 - active].playWhenReady = play
        updateSession()
    }

    /**
     * 1秒ごとに呼ぶ。目標テンポに向けて、速さを少しずつ変える。曲の終わりが近づいたら、次の曲を重ねて始める。
     * @param switchNow true なら、いますぐ目標に合う曲へ切り替える（インターバルの切り替えなど）
     */
    fun update(newTarget: Double?, switchNow: Boolean = false) {
        if (released || usable.isEmpty()) return
        // 歩数の細かいゆれで曲がふらつかないよう、目標はならしながら追いかける（切り替えのときだけ、すぐ合わせる）
        if (newTarget != null && newTarget > 0) target = if (switchNow) newTarget else target + 0.2 * (newTarget - target)
        val player = players[active]
        if (current == null) return
        if (switchNow && !fading) {
            val next = pickNext()
            // いまの曲のままで合うなら、切り替えずに速さだけ変える
            val keep = TempoMatch.fit(current!!.bpm, target)?.let { it.offset <= range } == true
            if (!keep && next != null && next.first.id != current!!.id) { switchTo(next, fade = true, reason = "インターバルの切り替え"); return }
            TempoMatch.fit(current!!.bpm, target)?.let { effectiveBpm = it.effectiveBpm }
        }
        // 速さは、3秒に1回、最大 2% ずつ変える（0.5% きざみ）。
        // 毎秒こまかく変え続けると、再生の部品が止まることがあったため、変える回数を減らしている。
        val now = SystemClock.elapsedRealtime()
        val wanted = Math.round(TempoMatch.speedFor(effectiveBpm, target, range) * 200) / 200.0
        if (Math.abs(wanted - speed) >= 0.01 && now - lastSpeedChangeAt >= 3000 && now >= holdSpeedUntil && !fading) {
            speed += (wanted - speed).coerceIn(-0.02, 0.02)
            lastSpeedChangeAt = now
            player.playbackParameters = PlaybackParameters(speed.toFloat(), 1f)
            MusicLog.add("速さを ${Math.round(speed * 1000) / 10.0}% にした（目標 ${Math.round(target)}）")
        }
        // 終わりの 3秒前（聞こえる時間で）になったら、次の曲を重ねて始める。
        // プレイヤーが出す「曲の長さ」は、ファイルによっては実際より短く出ることがある（途中で切れる原因になる）。
        // そこで、曲を取り込んだときに調べた長さと比べて、長いほうを使う。長すぎた場合は、曲が最後まで行った時点で次へ進む。
        val reported = player.duration
        val duration = maxOf(if (reported == C.TIME_UNSET) 0L else reported, current?.durationMs ?: 0L)
        if (!fading && duration > 15_000 && player.isPlaying && SystemClock.elapsedRealtime() - startedAt > 10_000) {
            val remaining = (duration - player.currentPosition) / speed
            if (remaining <= FADE_MS + 500) switchTo(pickNext(), fade = true, reason = "曲の終わり（重ねて切り替え）")
        }
    }

    /** @param from だれが押したか（覚え書き用） */
    fun next(from: String = "画面のボタン") {
        if (released) return
        userPaused = false
        switchTo(pickNext(), fade = true, reason = "次の曲（$from）")
    }

    fun togglePause(from: String = "画面のボタン") = setUserPaused(!userPaused, from)

    private fun setUserPaused(paused: Boolean, from: String) {
        if (released) return
        MusicLog.add(if (paused) "止めた（$from）" else "流した（$from）")
        userPaused = paused
        if (!paused) { focusPaused = false; audioManager.requestAudioFocus(focusRequest) }
        applyPlayState()
    }

    /** 声のお知らせの間は true。アプリ自身で音楽の音量を下げる */
    fun setDucked(value: Boolean) {
        if (released || ducked == value) return
        ducked = value
        if (!fading) players[active].volume = volume()
    }

    private fun updateSession() {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, speed.toFloat())
                .build()
        )
        current?.let {
            session.setMetadata(
                MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, it.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, it.artist).build()
            )
        }
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        runCatching { session.isActive = false; session.release() }
        players.forEach { runCatching { it.release() } }
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    companion object {
        private const val FADE_MS = 2500L
        private const val DUCK = 0.25f
    }
}
