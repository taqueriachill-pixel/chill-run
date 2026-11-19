package com.chill.run.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 写真・動画・声のメモの置き場所と大きさ（2026-11-17 に本人と決めた：ギャラリーにも保存、使用量を見せる、小さく保存）。
 */
object Storage {
    // ---------- ギャラリー（スマホの写真フォルダ）にも保存 ----------

    /** ギャラリーに出した写真・動画（アプリの中の場所で覚える。同じものを2回出さないため） */
    private fun exported(context: Context) = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    fun isExported(context: Context, relative: String) = exported(context).getBoolean(relative, false)

    /**
     * 写真・動画を、スマホの写真フォルダ（Pictures/ChillRun・Movies/ChillRun）にもコピーする。
     * Google フォトなどのバックアップに入り、アプリを消しても残る。許可は要らない（自分で作ったファイルのため）。
     */
    suspend fun copyToGallery(context: Context, relative: String, kind: String, takenAt: Long): Boolean = withContext(Dispatchers.IO) {
        if (isExported(context, relative)) return@withContext true
        val src = MediaFiles.file(context, relative)
        if (!src.exists()) return@withContext false
        val photo = kind == MediaFiles.PHOTO
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(takenAt))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "ChillRun_${stamp}_${src.nameWithoutExtension.takeLast(4)}.${src.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, if (photo) "image/jpeg" else "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (photo) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES) + "/ChillRun")
            put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (photo) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@withContext false
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: error("開けません")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }.isSuccess
        if (ok) exported(context).edit().putBoolean(relative, true).apply()
        else runCatching { resolver.delete(uri, null, null) }
        ok
    }

    /** これまでの写真・動画のうち、まだギャラリーに出していないものを全部出す。@return 出した数 */
    suspend fun copyAllToGallery(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val all = AppDb.get(context).runDao().allMedia().filter { (it.kind == MediaFiles.PHOTO || it.kind == MediaFiles.VIDEO) && !isExported(context, it.file) }
        var done = 0
        all.forEachIndexed { i, m ->
            if (copyToGallery(context, m.file, m.kind, m.time)) done++
            onProgress(i + 1, all.size)
        }
        return done
    }

    // ---------- 使っている量 ----------

    class Usage(val photo: Long, val video: Long, val memo: Long, val movie: Long, val tiles: Long, val data: Long) {
        val total get() = photo + video + memo + movie + tiles + data
    }

    private fun sizeOf(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles()?.sumOf { sizeOf(it) } ?: 0)

    suspend fun usage(context: Context): Usage = withContext(Dispatchers.IO) {
        val media = AppDb.get(context).runDao().allMedia()
        fun kindSize(kind: String) = media.filter { it.kind == kind }.sumOf { MediaFiles.file(context, it.file).length() }
        Usage(
            photo = kindSize(MediaFiles.PHOTO),
            video = kindSize(MediaFiles.VIDEO),
            memo = kindSize(MediaFiles.MEMO),
            movie = kindSize(MediaFiles.MOVIE),
            tiles = sizeOf(File(context.filesDir, "tiles")),
            data = sizeOf(context.getDatabasePath("chillrun.db").parentFile ?: File("")),
        )
    }

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    // ---------- 声のメモを小さくする ----------

    /**
     * 文字にし終わった声のメモ（WAV：圧縮していない音）を、AAC（m4a）に変えて約10分の1にする。
     * 文字起こしは WAV でしかできないので、文字にできたものだけを小さくする。
     * @return 小さくしたら新しいメモ、できなければ null
     */
    suspend fun compressMemo(context: Context, m: MediaEntity): MediaEntity? = withContext(Dispatchers.IO) {
        if (m.kind != MediaFiles.MEMO || !m.file.endsWith(".wav") || m.textState != 1) return@withContext null
        val wav = MediaFiles.file(context, m.file)
        if (!wav.exists()) return@withContext null
        val relative = m.file.removeSuffix(".wav") + ".m4a"
        val out = MediaFiles.file(context, relative)
        val ok = runCatching { wavToM4a(wav, out) }.isSuccess && out.length() > 0
        if (!ok) { out.delete(); return@withContext null }
        val updated = m.copy(file = relative)
        AppDb.get(context).runDao().updateMedia(updated)
        wav.delete()
        updated
    }

    /** 文字にし終わった声のメモを、まとめて小さくする。@return 小さくした数 */
    suspend fun compressAllMemos(context: Context): Int =
        AppDb.get(context).runDao().allMedia().count { compressMemo(context, it) != null }

    /** WAV（16bit・1チャンネル）を、AAC の m4a に変える */
    private fun wavToM4a(wav: File, out: File) {
        val raf = RandomAccessFile(wav, "r")
        val header = ByteArray(44).also { raf.readFully(it) }
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channels = hb.getShort(22).toInt().coerceAtLeast(1)
        val rate = hb.getInt(24)
        val pcm = ByteArray((raf.length() - 44).toInt()).also { raf.readFully(it) }
        raf.close()

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var pos = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * channels
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(buf.capacity() / bytesPerFrame * bytesPerFrame, pcm.size - pos)
                        val pts = (pos / bytesPerFrame).toLong() * 1_000_000L / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, pos, n)
                            codec.queueInputBuffer(i, 0, n, pts, 0)
                            pos += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    o >= 0 -> {
                        val data = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            data.position(info.offset); data.limit(info.offset + info.size)
                            muxer.writeSampleData(track, data, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (track >= 0) muxer.stop() }; runCatching { muxer.release() }
        }
    }
}
