package com.chill.run.run

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 声のメモを録音する。あとで文字にしやすいよう、加工していない音（WAV、16kHz、モノラル）で残す。
 * 60秒で 2MB 弱。
 */
class MemoRecorder {
    @Volatile
    var recording = false
        private set
    private var startedAt = 0L
    private var thread: Thread? = null

    val elapsedSec: Int get() = if (recording) ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt() else 0

    /** @return 録音を始められたら true。終わったら onFinished（メインスレッド）が呼ばれる */
    @SuppressLint("MissingPermission")
    fun start(file: File, maxMs: Long, onFinished: (durationMs: Long, ok: Boolean) -> Unit): Boolean {
        if (recording) return false
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        file.parentFile?.mkdirs()
        recording = true
        startedAt = SystemClock.elapsedRealtime()
        thread = Thread {
            var bytes = 0L
            var ok = false
            try {
                RandomAccessFile(file, "rw").use { out ->
                    out.setLength(0)
                    out.write(ByteArray(HEADER_SIZE))
                    val buf = ByteArray(minBuf)
                    record.startRecording()
                    while (recording && SystemClock.elapsedRealtime() - startedAt < maxMs) {
                        val n = record.read(buf, 0, buf.size)
                        if (n > 0) {
                            out.write(buf, 0, n)
                            bytes += n
                        } else if (n < 0) break
                    }
                    out.seek(0)
                    out.write(wavHeader(bytes))
                }
                ok = bytes > SAMPLE_RATE // 0.5秒より短いものは残さない
            } catch (e: Exception) {
                ok = false
            } finally {
                runCatching { record.stop() }
                record.release()
                recording = false
                val duration = bytes * 1000 / (SAMPLE_RATE * 2)
                if (!ok) file.delete()
                Handler(Looper.getMainLooper()).post { onFinished(duration, ok) }
            }
        }.also { it.start() }
        return true
    }

    fun stop() {
        recording = false
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val HEADER_SIZE = 44

        fun wavHeader(dataBytes: Long): ByteArray {
            val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            b.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(dataBytes.toInt())
            return b.array()
        }
    }
}
