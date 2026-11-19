package com.chill.run.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.chill.run.core.Tile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 国土地理院の地図（地理院タイル・標準地図）を取ってきて、端末の中に取っておく。
 * 一度見た場所は通信なしでも見られる。
 */
object TileStore {
    const val ATTRIBUTION = "出典：国土地理院（地理院タイル）"
    private val memory = LruCache<Tile, ImageBitmap>(96)
    private val network = Semaphore(4)
    private val failedAt = ConcurrentHashMap<Tile, Long>()

    private fun dir(context: Context) = File(context.filesDir, "tiles/std")
    private fun file(context: Context, t: Tile) = File(dir(context), "${t.z}/${t.x}/${t.y}.png")
    private fun url(t: Tile) = "https://cyberjapandata.gsi.go.jp/xyz/std/${t.z}/${t.x}/${t.y}.png"

    fun cached(t: Tile): ImageBitmap? = memory.get(t)

    /** 地図1枚を返す。メモリ → 端末の中 → 通信、の順にさがす */
    suspend fun load(context: Context, t: Tile): ImageBitmap? {
        memory.get(t)?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = file(context, t)
            if (!f.exists()) {
                val last = failedAt[t]
                if (last != null && System.currentTimeMillis() - last < 30_000) return@withContext null
                if (!fetch(t, f)) {
                    failedAt[t] = System.currentTimeMillis()
                    return@withContext null
                }
            }
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bitmap = BitmapFactory.decodeFile(f.path, options)
            if (bitmap == null) {
                f.delete()
                null
            } else bitmap.asImageBitmap().also { memory.put(t, it) }
        }
    }

    /** 一時保存用：端末の中になければ取ってくる。@return 端末の中にあれば true */
    suspend fun ensureSaved(context: Context, t: Tile): Boolean = withContext(Dispatchers.IO) {
        val f = file(context, t)
        f.exists() || fetch(t, f)
    }

    fun isSaved(context: Context, t: Tile) = file(context, t).exists()

    private suspend fun fetch(t: Tile, target: File): Boolean = network.withPermit {
        runCatching {
            val conn = URL(url(t)).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "ChillRun (personal running log app)")
            try {
                if (conn.responseCode != 200) return@runCatching false
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size < 100) return@runCatching false
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(target)
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    /** 取っておいた地図の合計サイズ（バイト）と枚数 */
    suspend fun usage(context: Context): Pair<Long, Int> = withContext(Dispatchers.IO) {
        var bytes = 0L
        var count = 0
        dir(context).walkTopDown().filter { it.isFile }.forEach { bytes += it.length(); count++ }
        bytes to count
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        dir(context).deleteRecursively()
        memory.evictAll()
        failedAt.clear()
    }
}
