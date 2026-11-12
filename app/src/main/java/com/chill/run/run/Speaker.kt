package com.chill.run.run

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** 声のお知らせ。読み上げている間だけ、ほかのアプリの音楽を下げる */
class Speaker(context: Context, private var rate: Float = 1f, private var volume: Float = 1f) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .build()
    private val outstanding = AtomicInteger(0)
    private val pending = ArrayList<Pair<String, Boolean>>()
    private var counter = 0

    @Volatile
    private var ready = false

    /** 日本語の読み上げが使えるか（準備が終わるまでは null） */
    @Volatile
    var japaneseAvailable: Boolean? = null
        private set

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            japaneseAvailable = false
            return
        }
        val result = t.setLanguage(Locale.JAPAN)
        japaneseAvailable = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        // 通信なしで使える声があれば、それを選ぶ（山の中でも読み上げられるように）
        runCatching {
            val offline = t.voices?.firstOrNull {
                it.locale.language == "ja" && !it.isNetworkConnectionRequired &&
                    it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }
            if (offline != null) t.voice = offline
        }
        t.setAudioAttributes(attrs)
        t.setSpeechRate(rate)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?, errorCode: Int) = finished()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
        synchronized(pending) {
            ready = true
            pending.forEach { speakNow(it.first, it.second) }
            pending.clear()
        }
    }

    private fun finished() {
        if (outstanding.decrementAndGet() <= 0) {
            outstanding.set(0)
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    fun configure(rate: Float, volume: Float) {
        this.rate = rate
        this.volume = volume
        if (ready) tts?.setSpeechRate(rate)
    }

    /** flush = true なら、話している途中のものを止めてすぐ話す */
    /** true のあいだは話さない（声のメモの録音に、お知らせの声が入らないように） */
    @Volatile
    var muted = false

    fun say(text: String, flush: Boolean = false) {
        if (text.isBlank() || muted) return
        synchronized(pending) {
            if (!ready) {
                pending += text to flush
                return
            }
        }
        speakNow(text, flush)
    }

    private fun speakNow(text: String, flush: Boolean) {
        val t = tts ?: return
        outstanding.incrementAndGet()
        audioManager.requestAudioFocus(focusRequest)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val r = t.speak(text, mode, params, "u${counter++}")
        if (r != TextToSpeech.SUCCESS) finished()
    }

    /** 短い効果音 */
    fun chime() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 400)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 800)
        }
    }

    fun shutdown() {
        ready = false
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
