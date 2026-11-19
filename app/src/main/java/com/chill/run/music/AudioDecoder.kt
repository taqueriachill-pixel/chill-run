package com.chill.run.music

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** 曲のファイルを、テンポを調べるための生の音（1チャンネル）に直す */
object AudioDecoder {
    class Pcm(val mono: FloatArray, val sampleRate: Int)

    /**
     * 曲の途中（イントロを飛ばした所）から、最長 maxSec 秒ぶんを取り出す。
     * 読めない形式などのときは null。
     */
    fun decode(context: Context, uri: Uri, maxSec: Int = 60): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(index)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            // イントロは静かなことが多いので、曲の 1/5（最大30秒）進んだ所から調べる
            val startUs = minOf(30_000_000L, durationUs / 5)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var out = FloatArray(sampleRate * maxSec)
            var count = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 60_000

            while (!outputDone && count < out.size && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (newRate != sampleRate && count == 0) {
                            sampleRate = newRate
                            out = FloatArray(sampleRate * maxSec)
                        }
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 && channels > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            if (isFloat) {
                                val fb = buf.asFloatBuffer()
                                while (fb.remaining() >= channels && count < out.size) {
                                    var s = 0f
                                    for (c in 0 until channels) s += fb.get()
                                    out[count++] = s / channels
                                }
                            } else {
                                val sb = buf.asShortBuffer()
                                while (sb.remaining() >= channels && count < out.size) {
                                    var s = 0
                                    for (c in 0 until channels) s += sb.get()
                                    out[count++] = s / channels / 32768f
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (count < sampleRate * 8) return null
            return Pcm(if (count == out.size) out else out.copyOf(count), sampleRate)
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
