package com.chill.run.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.chill.run.run.MemoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

/**
 * 声のメモを文字にする。スマホの中の音声認識だけを使うので、通信がなくても動く（機種が対応していれば）。
 * 録音したファイルの音を、音声認識にそのまま流し込む。
 */
object Transcriber {
    fun available(context: Context): Boolean =
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    private fun baseIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")

    /**
     * 録音ファイル（WAV）を文字にする。
     * まず「長い録音を最後まで聞く」やり方で試し、だめなら「ひと息ぶんだけ聞く」ふつうのやり方でもう一度試す。
     */
    suspend fun transcribe(context: Context, wav: File): Result<String> {
        if (!available(context)) return Result.failure(IllegalStateException("このスマホには、端末内の音声認識がありません"))
        if (!wav.exists() || wav.length() <= MemoRecorder.HEADER_SIZE) return Result.failure(IllegalStateException("録音が見つかりません"))
        val first = attempt(context, wav, segmented = true)
        if (first.isSuccess) return first
        val second = attempt(context, wav, segmented = false)
        return if (second.isSuccess) second else Result.failure(
            IllegalStateException("${second.exceptionOrNull()?.message}（1回目：${first.exceptionOrNull()?.message}）")
        )
    }

    private suspend fun attempt(context: Context, wav: File, segmented: Boolean): Result<String> = withContext(Dispatchers.Main) {
        var recognizer: SpeechRecognizer? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            // 録音の中身（先頭の44バイトを除いた音そのもの）を、音声認識に流し込む
            Thread {
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                        FileInputStream(wav).use { input ->
                            input.skip(MemoRecorder.HEADER_SIZE.toLong())
                            input.copyTo(out)
                        }
                    }
                }
            }.start()
            val intent = baseIntent()
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MemoRecorder.SAMPLE_RATE)
            if (segmented) intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            val r = recognizer
            val result = withTimeoutOrNull(if (segmented) 75_000 else 45_000) {
                suspendCancellableCoroutine<Result<String>> { cont ->
                    val segments = ArrayList<String>()
                    var partial = ""
                    fun best(extra: String = ""): String =
                        (segments + listOf(extra.ifBlank { partial })).filter { it.isNotBlank() }.joinToString("。").trim()
                    fun finish(text: String, error: String) {
                        if (!cont.isActive) return
                        cont.resume(if (text.isNotBlank()) Result.success(text) else Result.failure(IllegalStateException(error)))
                    }
                    fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    r.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                        override fun onPartialResults(partialResults: Bundle?) {
                            first(partialResults).let { if (it.isNotBlank()) partial = it }
                        }

                        override fun onSegmentResults(segmentResults: Bundle) {
                            first(segmentResults).let { if (it.isNotBlank()) segments += it }
                            partial = ""
                        }

                        override fun onEndOfSegmentedSession() = finish(best(), "声を聞き取れませんでした")
                        override fun onResults(results: Bundle?) = finish(best(first(results)), "声を聞き取れませんでした")
                        // 途中まで聞き取れていれば、それを使う
                        override fun onError(error: Int) = finish(best(), errorText(error))
                    })
                    r.startListening(intent)
                }
            }
            result ?: Result.failure(IllegalStateException("時間がかかりすぎたので中止しました"))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            runCatching { pipe?.get(0)?.close() }
        }
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "声を聞き取れませんでした（番号 $code）"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "声が入っていないようです（番号 $code）"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイクの許可がありません（番号 $code）"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "日本語に対応していません（番号 $code）"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "日本語の音声認識のデータが入っていません（番号 $code）"
        SpeechRecognizer.ERROR_AUDIO -> "録音の受け渡しに失敗しました（番号 $code）"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "対応しているかを確かめられませんでした（番号 $code）"
        else -> "音声認識で問題が起きました（番号 $code）"
    }

    /** 実機チェック用：端末内の音声認識が日本語に対応しているかを文章で返す。メインスレッドから呼ぶこと */
    suspend fun supportReport(context: Context): String = withContext(Dispatchers.Main) {
        if (!available(context)) return@withContext "×　このスマホには、端末内の音声認識がありません。"
        var recognizer: SpeechRecognizer? = null
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            val r = recognizer
            val text = withTimeoutOrNull(8000) {
                suspendCancellableCoroutine<String> { cont ->
                    r.checkRecognitionSupport(baseIntent(), context.mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            val installed = support.installedOnDeviceLanguages
                            val pending = support.pendingOnDeviceLanguages
                            val supported = support.supportedOnDeviceLanguages
                            fun ja(list: List<String>) = list.any { it.startsWith("ja") }
                            val line = when {
                                ja(installed) -> "✓　端末内の音声認識が使えます。日本語のデータも入っています。"
                                ja(pending) -> "△　日本語のデータをダウンロードしている途中です。しばらくしてからもう一度見てください。"
                                ja(supported) -> "△　日本語に対応していますが、データがまだ入っていません。「日本語のデータを入れる」を押してください。"
                                else -> "×　端末内の音声認識はありますが、日本語には対応していないようです。"
                            }
                            if (cont.isActive) cont.resume("$line\n  入っている言語：${installed.joinToString("、").ifEmpty { "なし" }}\n  対応している言語の数：${supported.size}")
                        }

                        override fun onError(error: Int) {
                            if (cont.isActive) cont.resume("△　端末内の音声認識はありますが、日本語に対応しているかは確かめられませんでした（番号 $error）。下の「5秒話して試す」で実際に試してください。")
                        }
                    })
                }
            }
            text ?: "△　端末内の音声認識はありますが、返事がありませんでした。下の「5秒話して試す」で実際に試してください。"
        } catch (e: Exception) {
            "△　確かめている途中で問題が起きました：${e.message}"
        } finally {
            runCatching { recognizer?.destroy() }
        }
    }

    /** 日本語のデータのダウンロードを頼む（対応している機種だけ） */
    fun requestJapaneseModel(context: Context) {
        runCatching {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            r.triggerModelDownload(baseIntent())
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { r.destroy() } }, 5000)
        }
    }
}
