package com.chill.run.movie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.asAndroidBitmap
import com.chill.run.core.Fmt
import com.chill.run.core.HeartZones
import com.chill.run.core.MapMath
import com.chill.run.core.MovieScript
import com.chill.run.core.RunTune
import com.chill.run.core.Tile
import com.chill.run.core.TuneMood
import com.chill.run.core.Yuv
import com.chill.run.data.AppDb
import com.chill.run.data.MediaEntity
import com.chill.run.data.MediaFiles
import com.chill.run.data.PointEntity
import com.chill.run.data.RunEntity
import com.chill.run.data.Settings
import com.chill.run.data.TileStore
import com.chill.run.run.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

/**
 * 10秒の「今日のランムービー」を、スマホの中だけで作る。縦長（9:16、720×1280）。曲も、その場で作って入れる。
 * 1コマずつ絵を描いて、動画に固めていく。
 */
object MovieMaker {
    private const val W = 720
    private const val H = 1280
    private const val TILE_PX = 384.0 // 地図の文字が読めるよう、タイルを 1.5倍で描く
    private const val AUDIO_RATE = 44100
    private val lock = Mutex()
    private val ROAD = Color.parseColor("#2E9BD6")
    private val TRAIL = Color.parseColor("#3DAA6B")
    private val BG = Color.parseColor("#0B1418")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy年M月d日(E)", Locale.JAPAN)

    private class Scene(
        val run: RunEntity,
        val place: String,
        val accent: Int,
        val map: Bitmap,
        /** 隠す場所を除いた、ひと続きの線ごとの点（地図の絵の上の位置）と、その点までの距離の割合 */
        val segments: List<List<FloatArray>>,
        val elevation: List<Pair<Float, Float>>, // 距離の割合 と 標高
        val media: List<List<Bitmap>>,           // 写真は1枚、動画は何コマか
        val caption: String,
        val monthKm: Double,
        /** その場で作った曲（入れないときは null）と、その説明 */
        val tune: FloatArray?,
        val tuneNote: String,
    )

    /** @return できた動画の記録。onProgress には 0〜100 が渡る */
    suspend fun make(context: Context, runId: Long, onProgress: (Int) -> Unit = {}): Result<MediaEntity> = lock.withLock {
        // 曲づくりの材料にする天気を、まだなら調べておく（通信できなければ、天気なしで作る）
        runCatching { Weather.ensureForRun(context.applicationContext, runId) }
        withContext(Dispatchers.Default) {
            runCatching {
                val app = context.applicationContext
                val dao = AppDb.get(app).runDao()
                val run = dao.getRun(runId) ?: error("記録が見つかりません")
                val points = dao.points(runId)
                if (points.size < 2) error("ルートの記録がありません")
                onProgress(2)
                val scene = prepare(app, run, points)
                onProgress(15)

                val relative = MediaFiles.newRelativePath(runId, MediaFiles.MOVIE)
                val file = MediaFiles.file(app, relative).also { it.parentFile?.mkdirs() }
                try {
                    encode(file, scene) { f -> onProgress(15 + f * 83 / totalFrames()) }
                } catch (e: Exception) {
                    file.delete()
                    throw e
                } finally {
                    scene.map.recycle()
                    scene.media.flatten().forEach { it.recycle() }
                }
                // 前に作ったムービーがあれば、置きかえる
                dao.media(runId).filter { it.kind == MediaFiles.MOVIE }.forEach { MediaFiles.delete(app, it) }
                val entity = MediaEntity(
                    runId = runId, kind = MediaFiles.MOVIE, time = System.currentTimeMillis(),
                    lat = null, lon = null, distM = run.distanceM, file = relative, durationMs = (MovieScript.TOTAL_SEC * 1000).toLong(),
                    // 曲をどう決めたかを、残しておく（記録の画面に出す）
                    text = scene.tuneNote,
                )
                val id = dao.insertMedia(entity)
                onProgress(100)
                entity.copy(id = id)
            }
        }
    }

    private fun totalFrames() = (MovieScript.TOTAL_SEC * MovieScript.FPS).toInt()

    /**
     * 地図だけを、1枚の画像（PNG・縦長）に書き出す（仕様 2-5 の「地図の書き出し」）。
     * ランムービーと同じく、隠す場所（自宅など）のまわりのルートは描かない。
     * @return できた画像のファイル（アプリの中。共有メニューから外に出す）
     */
    suspend fun exportMap(context: Context, runId: Long): Result<File> = lock.withLock {
        withContext(Dispatchers.Default) {
            runCatching {
                val app = context.applicationContext
                val dao = AppDb.get(app).runDao()
                val run = dao.getRun(runId) ?: error("記録が見つかりません")
                val points = dao.points(runId)
                if (points.size < 2) error("ルートの記録がありません")
                val scene = prepare(app, run, points, mapOnly = true)
                val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                try {
                    val c = Canvas(bitmap)
                    drawMap(c, scene, 1f)
                    // 上の帯の下に、日付・場所と、数字
                    fill.color = Color.argb(150, 0, 0, 0); fill.alpha = 150
                    c.drawRect(0f, 150f, W.toFloat(), 262f, fill)
                    val date = dateFmt.format(Instant.ofEpochMilli(run.startTime).atZone(ZoneId.systemDefault()))
                    drawText(c, date + if (scene.place.isNotEmpty()) "　${scene.place}" else "", 36f, 198f, 30f, align = Paint.Align.LEFT)
                    val pace = if (run.distanceM > 10) run.movingMs / 1000.0 / (run.distanceM / 1000.0) else null
                    drawText(
                        c, "タイム ${Fmt.clock(run.movingMs)}　平均 ${Fmt.pace(pace)}/km　登り +${Math.round(run.gainM)}m",
                        36f, 244f, 30f, Color.LTGRAY, Paint.Align.LEFT,
                    )
                    drawText(c, "Chill Run", W - 20f, H - 14f, 22f, Color.WHITE, Paint.Align.RIGHT, alpha = 200)
                    // 共有するまでの一時置き場（アプリの「キャッシュ」。バックアップには入らない）
                    val file = File(app.cacheDir, "exports/run_${runId}_map.png").also { it.parentFile?.mkdirs() }
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    file
                } finally {
                    bitmap.recycle()
                    scene.map.recycle()
                }
            }
        }
    }

    // ---------- 下ごしらえ ----------

    private suspend fun prepare(context: Context, run: RunEntity, points: List<PointEntity>, mapOnly: Boolean = false): Scene {
        val dao = AppDb.get(context).runDao()
        val hide = Settings(context).hideZone
        val total = points.last().distM.coerceAtLeast(1.0)
        // 隠す場所（自宅など）の近くは描かない
        val shown = points.map { p -> hide?.hides(p.lat, p.lon) != true }
        val visible = points.filterIndexed { i, _ -> shown[i] }.ifEmpty { points }

        // 地図：見えるルートが画面に収まる大きさで、1枚の絵にしておく
        val minLat = visible.minOf { it.lat }; val maxLat = visible.maxOf { it.lat }
        val minLon = visible.minOf { it.lon }; val maxLon = visible.maxOf { it.lon }
        val zoom = MapMath.fitZoom(minLat, maxLat, minLon, maxLon, W * 0.78, H * 0.55, TILE_PX).coerceAtMost(17.0)
        val tz = floor(zoom + 0.5).toInt().coerceIn(MapMath.MIN_ZOOM, MapMath.MAX_ZOOM)
        val tileDisp = TILE_PX * 2.0.pow(zoom - tz)
        val world = tileDisp * 2.0.pow(tz)
        val cx = (MapMath.lonToX(minLon) + MapMath.lonToX(maxLon)) / 2
        val cy = (MapMath.latToY(minLat) + MapMath.latToY(maxLat)) / 2
        val left = cx * world - W / 2.0
        val top = cy * world - H * 0.52 // ルートを、画面の少し上寄りに置く（下に標高のグラフが来るため）
        val map = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(map)
        canvas.drawColor(Color.parseColor("#DAD7CF"))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val n = 1 shl tz
        for (x in floor(left / tileDisp).toInt()..floor((left + W) / tileDisp).toInt()) {
            for (y in floor(top / tileDisp).toInt()..floor((top + H) / tileDisp).toInt()) {
                if (x !in 0 until n || y !in 0 until n) continue
                val tile = TileStore.load(context, Tile(tz, x, y)) ?: continue
                val dx = (x * tileDisp - left).toFloat()
                val dy = (y * tileDisp - top).toFloat()
                canvas.drawBitmap(tile.asAndroidBitmap(), null, RectF(dx, dy, dx + tileDisp.toFloat() + 1, dy + tileDisp.toFloat() + 1), paint)
            }
        }
        // ルートが目立つよう、地図を少し暗くする
        canvas.drawColor(Color.argb(70, 0, 0, 0))

        fun project(p: PointEntity) = floatArrayOf(
            (MapMath.lonToX(p.lon) * world - left).toFloat(),
            (MapMath.latToY(p.lat) * world - top).toFloat(),
            (p.distM / total).toFloat(),
        )
        val segments = ArrayList<List<FloatArray>>()
        var current = ArrayList<FloatArray>()
        val stride = points.size / 1200 + 1
        for (i in points.indices) {
            if (!shown[i]) {
                if (current.size >= 2) segments += current
                current = ArrayList()
            } else if (i % stride == 0 || i == points.lastIndex) current += project(points[i])
        }
        if (current.size >= 2) segments += current

        val elevation = points.filter { it.alt != null }.let { list ->
            val s = list.size / 300 + 1
            list.filterIndexed { i, _ -> i % s == 0 }.map { (it.distM / total).toFloat() to (it.alt!! + run.altOffset).toFloat() }
        }

        // 写真・動画（最大4つ。多いときは、全体からまんべんなく選ぶ）
        val all = dao.media(run.id)
        val pictures = all.filter { it.kind == MediaFiles.PHOTO || it.kind == MediaFiles.VIDEO }
        val chosen = if (mapOnly) emptyList() else if (pictures.size <= 4) pictures else (0 until 4).map { pictures[it * (pictures.size - 1) / 3] }
        val slotSec = if (chosen.isEmpty()) 0.0 else (MovieScript.STATS_START - 5.0) / chosen.size
        val media = chosen.mapNotNull { m ->
            val file = MediaFiles.file(context, m.file)
            runCatching {
                if (m.kind == MediaFiles.PHOTO) listOf(loadPhoto(file)) else loadVideoFrames(file, slotSec)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        val caption = all.firstOrNull { it.kind == MediaFiles.MEMO && it.text.isNotBlank() }?.text.orEmpty()

        val zone = ZoneId.systemDefault()
        val month = Instant.ofEpochMilli(run.startTime).atZone(zone).toLocalDate().withDayOfMonth(1)
        val monthKm = dao.finishedRuns().filter {
            Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate().withDayOfMonth(1) == month
        }.sumOf { it.distanceM } / 1000.0
        val place = if (run.courseId > 0) dao.course(run.courseId)?.name.orEmpty() else ""

        // 曲：ランの内容（ロードかトレイルか、気分、天気、気温、がんばり具合）から、その場で作る。毎回ちがう曲になる
        var tune: FloatArray? = null
        var tuneNote = ""
        val settings = Settings(context)
        if (settings.movieMusic && !mapOnly) {
            val words = (listOf(run.memo) + all.filter { it.kind == MediaFiles.MEMO }.map { it.text }).joinToString(" ")
            val zones = HeartZones(settings.maxHr, settings.restHr)
            val hr = points.mapNotNull { it.hr }
            val mood = TuneMood(
                trail = run.type == "TRAIL",
                // 「今日の体調」を付けてあればそれを、なければメモの言葉から
                feeling = if (run.condition > 0) run.condition - 3 else TuneMood.feelingFromWords(words),
                tempC = run.tempC,
                sky = TuneMood.skyOf(run.weatherCode),
                effort = if (hr.size >= 30) hr.count { zones.zoneOf(it) >= 4 }.toDouble() / hr.size + 0.2 else 0.3,
            )
            val seed = System.nanoTime()
            val plan = RunTune.plan(mood, seed)
            tune = RunTune.render(plan, seed, MovieScript.TOTAL_SEC, AUDIO_RATE)
            tuneNote = plan.description
        }

        return Scene(run, place, if (run.type == "TRAIL") TRAIL else ROAD, map, segments, elevation, media, caption, monthKm, tune, tuneNote)
    }

    private fun loadPhoto(file: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            while (maxOf(info.size.width, info.size.height) / sample > 1600) sample *= 2
            decoder.setTargetSampleSize(sample)
        }

    /** 動画から、見せる時間ぶんのコマ（0.15秒おき）を取り出す */
    private fun loadVideoFrames(file: File, slotSec: Double): List<Bitmap> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // メモリを使いすぎないよう、1つの動画から最大6コマ、少し小さめの大きさで取り出す
            val count = (slotSec * 7).toInt().coerceIn(1, 6)
            val out = ArrayList<Bitmap>()
            for (i in 0 until count) {
                val tMs = (i * 150L).coerceAtMost((durationMs - 50).coerceAtLeast(0))
                r.getScaledFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, W * 2 / 3, H * 2 / 3)?.let { out += it }
            }
            return out
        } finally {
            r.release()
        }
    }

    // ---------- 絵を描く ----------

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawText(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE, align: Paint.Align = Paint.Align.CENTER, alpha: Int = 255) {
        text.textSize = size; text.color = color; text.textAlign = align; text.alpha = alpha
        c.drawText(s, x, y, text)
    }

    private fun drawFrame(c: Canvas, s: Scene, t: Double) {
        val n = s.media.size
        when (MovieScript.sceneAt(t, n)) {
            MovieScript.Scene.TITLE -> drawTitle(c, s, t)
            MovieScript.Scene.MAP -> drawMap(c, s, MovieScript.routeProgress(t, n).toFloat())
            MovieScript.Scene.MEDIA -> {
                val (index, p) = MovieScript.mediaSlot(t, n)
                drawMedia(c, s, index, p.toFloat())
            }
            MovieScript.Scene.STATS -> drawStats(c, s, t)
        }
    }

    private fun typeWord(s: Scene) = if (s.run.type == "TRAIL") "TRAIL" else "ROAD"

    private fun drawTitle(c: Canvas, s: Scene, t: Double) {
        c.drawColor(BG)
        val a = (t / 0.5).coerceIn(0.0, 1.0)
        val alpha = (a * 255).toInt()
        // 海の青／森の緑の帯
        fill.color = s.accent; fill.alpha = alpha
        c.drawRect(0f, H * 0.36f, W.toFloat(), H * 0.37f, fill)
        drawText(c, typeWord(s), W / 2f, H * 0.34f - (1 - a).toFloat() * 30, 170f, s.accent, alpha = alpha)
        val date = dateFmt.format(Instant.ofEpochMilli(s.run.startTime).atZone(ZoneId.systemDefault()))
        drawText(c, date, W / 2f, H * 0.44f, 46f, alpha = alpha)
        if (s.place.isNotEmpty()) drawText(c, s.place, W / 2f, H * 0.52f, 62f, alpha = alpha)
    }

    private fun drawMap(c: Canvas, s: Scene, progress: Float) {
        c.drawBitmap(s.map, 0f, 0f, null)
        // ルートを、進み具合のところまで描く
        var head: FloatArray? = null
        for (seg in s.segments) {
            val path = Path()
            var started = false
            for (p in seg) {
                if (p[2] > progress) break
                if (!started) { path.moveTo(p[0], p[1]); started = true } else path.lineTo(p[0], p[1])
                head = p
            }
            if (!started) continue
            line.color = Color.WHITE; line.strokeWidth = 16f
            c.drawPath(path, line)
            line.color = s.accent; line.strokeWidth = 10f
            c.drawPath(path, line)
        }
        head?.let {
            fill.color = Color.WHITE; fill.alpha = 255
            c.drawCircle(it[0], it[1], 16f, fill)
            fill.color = s.accent
            c.drawCircle(it[0], it[1], 10f, fill)
        }
        // 上の帯：ロードかトレイルか
        fill.color = Color.argb(170, 0, 0, 0)
        c.drawRect(0f, 0f, W.toFloat(), 150f, fill)
        drawText(c, typeWord(s), 36f, 105f, 76f, s.accent, Paint.Align.LEFT)
        drawText(c, "%.2f km".format(s.run.distanceM / 1000 * progress), W - 36f, 105f, 64f, align = Paint.Align.RIGHT)

        // トレイルのときは、標高のグラフも一緒に伸ばす
        if (s.run.type == "TRAIL" && s.elevation.size >= 2) {
            val top = H - 330f
            val bottom = H - 80f
            fill.color = Color.argb(170, 0, 0, 0)
            c.drawRect(0f, top - 40, W.toFloat(), H.toFloat(), fill)
            val lo = s.elevation.minOf { it.second }
            val range = (s.elevation.maxOf { it.second } - lo).coerceAtLeast(30f)
            val path = Path()
            var lastX = 40f
            var count = 0
            for ((d, alt) in s.elevation) {
                if (d > progress) break
                val x = 40f + d * (W - 80f)
                val y = bottom - (alt - lo) / range * (bottom - top)
                if (count == 0) path.moveTo(x, y) else path.lineTo(x, y)
                lastX = x
                count++
            }
            if (count >= 2) {
                val area = Path(path).apply { lineTo(lastX, bottom); lineTo(40f, bottom); close() }
                fill.color = s.accent; fill.alpha = 90
                c.drawPath(area, fill)
                line.color = s.accent; line.strokeWidth = 6f
                c.drawPath(path, line)
            }
            drawText(c, "獲得標高 +${Math.round(s.run.gainM * progress)}m", 40f, top - 4, 34f, align = Paint.Align.LEFT)
        }
        // 地図の出典（国土地理院の決まり）
        fill.color = Color.argb(200, 255, 255, 255)
        c.drawRect(0f, H - 44f, 330f, H.toFloat(), fill)
        drawText(c, TileStore.ATTRIBUTION, 10f, H - 14f, 20f, Color.DKGRAY, Paint.Align.LEFT)
    }

    private fun drawMedia(c: Canvas, s: Scene, index: Int, p: Float) {
        c.drawColor(Color.BLACK)
        val frames = s.media.getOrNull(index) ?: return
        val bmp = frames[(p * frames.size).toInt().coerceIn(0, frames.size - 1)]
        // 画面いっぱいに切り抜いて、ゆっくり少しだけ寄る
        val scale = maxOf(W.toFloat() / bmp.width, H.toFloat() / bmp.height) * (1f + 0.05f * p)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        fill.alpha = 255
        c.drawBitmap(bmp, null, RectF((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        // 声のメモを、字幕のように出す
        if (s.caption.isNotEmpty()) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
            val layout = StaticLayout.Builder.obtain(s.caption, 0, s.caption.length, tp, W - 96)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(3).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            val top = H - 160f - layout.height
            fill.color = Color.argb(150, 0, 0, 0)
            c.drawRect(0f, top - 30, W.toFloat(), H.toFloat(), fill)
            c.save()
            c.translate(48f, top)
            layout.draw(c)
            c.restore()
        }
    }

    private fun drawStats(c: Canvas, s: Scene, t: Double) {
        c.drawColor(BG)
        val a = ((t - MovieScript.STATS_START) / 0.4).coerceIn(0.0, 1.0)
        val alpha = (a * 255).toInt()
        val run = s.run
        drawText(c, typeWord(s), W / 2f, 250f, 70f, s.accent, alpha = alpha)
        drawText(c, "%.2f".format(run.distanceM / 1000), W / 2f, 520f, 230f, alpha = alpha)
        drawText(c, "km", W / 2f, 590f, 50f, Color.LTGRAY, alpha = alpha)
        val pace = if (run.distanceM > 10) run.movingMs / 1000.0 / (run.distanceM / 1000.0) else null
        val cols = listOf("タイム" to Fmt.clock(run.movingMs), "平均ペース" to Fmt.pace(pace), "獲得標高" to "+${Math.round(run.gainM)}m")
        cols.forEachIndexed { i, (label, value) ->
            val x = W * (i + 0.5f) / cols.size
            drawText(c, value, x, 780f, 62f, alpha = alpha)
            drawText(c, label, x, 830f, 30f, Color.LTGRAY, alpha = alpha)
        }
        drawText(c, "今月 ${Math.round(s.monthKm)}km", W / 2f, 1010f, 44f, s.accent, alpha = alpha)
        drawText(c, "Chill Run", W / 2f, H - 90f, 34f, Color.GRAY, alpha = alpha)
    }

    // ---------- 動画に固める ----------

    private class EncodedAudio(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    /** 曲（左右2チャンネルを交互に並べたもの）を、動画に入れられる形（AAC）に固める */
    private fun encodeAac(pcm: FloatArray, rate: Int): EncodedAudio {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 30_000
            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        buf.clear()
                        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        // 左右ひと組（4バイト）単位で入れる。時刻は「何組目か」から計算する
                        val count = minOf(buf.capacity() / 4 * 2, pcm.size - pos)
                        val pts = (pos / 2) * 1_000_000L / rate
                        if (count <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            for (i in 0 until count) buf.putShort((pcm[pos + i].coerceIn(-1f, 1f) * 32767).toInt().toShort())
                            codec.queueInputBuffer(inIndex, 0, count * 2, pts, 0)
                            pos += count
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                } else if (outIndex >= 0) {
                    val buf = codec.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(bytes)
                        samples += bytes to MediaCodec.BufferInfo().apply { set(0, bytes.size, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()) }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return EncodedAudio(outFormat ?: error("曲を固められませんでした"), samples)
    }

    private suspend fun encode(file: File, scene: Scene, onFrame: (Int) -> Unit) = withContext(Dispatchers.Default) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, MovieScript.FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(W * H)
        val yPlane = ByteArray(W * H)
        val uPlane = ByteArray(W * H / 4)
        val vPlane = ByteArray(W * H / 4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var muxing = false
        // 曲がうまく固められなかったときは、音なしで作る
        val audio = scene.tune?.let { pcm -> runCatching { encodeAac(pcm, AUDIO_RATE) }.getOrNull() }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val frames = totalFrames()
            var next = 0
            var inputDone = false
            var outputDone = false
            val deadline = System.currentTimeMillis() + 180_000
            while (!outputDone) {
                ensureActive()
                if (System.currentTimeMillis() > deadline) error("時間がかかりすぎたので中止しました")
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        if (next >= frames) {
                            codec.queueInputBuffer(inIndex, 0, 0, next * 1_000_000L / MovieScript.FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            drawFrame(canvas, scene, next.toDouble() / MovieScript.FPS)
                            bitmap.getPixels(pixels, 0, W, 0, 0, W, H)
                            Yuv.fromArgb(pixels, W, H, yPlane, uPlane, vPlane)
                            val image = codec.getInputImage(inIndex) ?: error("この機種では動画を作れませんでした（入力の形が合いません）")
                            fillPlane(image.planes[0], yPlane, W, H)
                            fillPlane(image.planes[1], uPlane, W / 2, H / 2)
                            fillPlane(image.planes[2], vPlane, W / 2, H / 2)
                            codec.queueInputBuffer(inIndex, 0, W * H * 3 / 2, next * 1_000_000L / MovieScript.FPS, 0)
                            next++
                            onFrame(next)
                        }
                    }
                }
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        val audioTrack = if (audio != null && audio.samples.isNotEmpty()) muxer.addTrack(audio.format) else -1
                        muxer.start()
                        muxing = true
                        // 曲は短いので、先にまとめて書き込む
                        if (audio != null && audioTrack >= 0) {
                            for ((bytes, sampleInfo) in audio.samples) muxer.writeSampleData(audioTrack, java.nio.ByteBuffer.wrap(bytes), sampleInfo)
                        }
                    } else if (outIndex >= 0) {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 && muxing && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputDone = true; break }
                    } else break
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxing) muxer.stop() }
            runCatching { muxer.release() }
            bitmap.recycle()
        }
        if (!muxing || file.length() < 10_000) error("動画を書き出せませんでした")
    }

    /** 機種ごとに並び方がちがう、動画の入力の1面ぶんを埋める */
    private fun fillPlane(plane: android.media.Image.Plane, data: ByteArray, width: Int, height: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1) {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.put(data, row * width, width)
            }
        } else {
            for (row in 0 until height) {
                val base = row * rowStride
                val src = row * width
                for (col in 0 until width) buffer.put(base + col * pixelStride, data[src + col])
            }
        }
    }
}
