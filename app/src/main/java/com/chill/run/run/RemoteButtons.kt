package com.chill.run.run

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * イヤホン（や時計）のボタンを受け取る。
 * イヤホンのタップは、どのメーカーでも「再生／一時停止」「次の曲」「前の曲」という共通の合図（Bluetooth の AVRCP）になって届く。
 * それを受け取って、ランの操作（読み上げ・メモ・写真など）に置きかえる。
 */
class RemoteButtons(context: Context, private val onSignal: (Signal) -> Unit) {
    enum class Signal(val label: String) { PLAY_PAUSE("再生／一時停止"), NEXT("次の曲"), PREV("前の曲") }

    companion object {
        /** ボタンの合図（キー）を、3つの合図のどれかに直す */
        fun signalOf(code: Int): Signal? = when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_STOP -> Signal.PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Signal.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> Signal.PREV
            else -> null
        }

        /** メディアボタンの知らせから、合図を取り出す（押した瞬間だけ。押しっぱなしの繰り返しは数えない） */
        fun signalOf(intent: Intent): Signal? {
            @Suppress("DEPRECATION")
            val key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return null
            if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount > 0) return null
            return signalOf(key.keyCode)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var last = 0L

    private fun fire(s: Signal) {
        // 1回のタップで合図が2つ届くイヤホンがあるので、すぐあとの同じような合図は無視する
        val now = SystemClock.elapsedRealtime()
        if (now - last < 450) return
        last = now
        onSignal(s)
    }

    private val session = MediaSession(context.applicationContext, "ChillRunButtons").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                signalOf(mediaButtonIntent)?.let { fire(it) }
                return true
            }
            // 時計やロック画面の「再生」「次へ」ボタンは、キーではなくこちらで届く
            override fun onPlay() = fire(Signal.PLAY_PAUSE)
            override fun onPause() = fire(Signal.PLAY_PAUSE)
            override fun onSkipToNext() = fire(Signal.NEXT)
            override fun onSkipToPrevious() = fire(Signal.PREV)
        }, handler)
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
    }

    /**
     * 無音を流しつづける。Android は「最後に音を出したアプリ」にボタンの合図を送るので、
     * 音楽を流していないときでも、このアプリに合図が届くようにする（音は出ない）。
     */
    private var silence: AudioTrack? = null

    fun start(withSilence: Boolean) {
        session.isActive = true
        if (withSilence && silence == null) silence = runCatching {
            val rate = 8000
            val frames = rate / 2
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build().apply {
                    write(ShortArray(frames), 0, frames)
                    setLoopPoints(0, frames, -1)
                    setVolume(0f)
                    play()
                }
        }.getOrNull()
    }

    /** 音楽を流し始めた・止めたときに、無音を止めたり流したりする */
    fun setSilence(on: Boolean) {
        if (on) start(true) else { runCatching { silence?.stop(); silence?.release() }; silence = null }
    }

    fun stop() {
        setSilence(false)
        runCatching { session.isActive = false; session.release() }
    }
}
