package com.chill.run.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.chill.run.run.RunHub
import java.io.File

/** 声のメモ・写真・動画の置き場所。スマホの写真フォルダには入れず、このアプリの中だけに保存する */
object MediaFiles {
    const val MEMO = "MEMO"
    const val PHOTO = "PHOTO"
    const val VIDEO = "VIDEO"
    /** 今日のランムービー */
    const val MOVIE = "MOVIE"

    private fun root(context: Context) = File(context.filesDir, "media")

    fun file(context: Context, relative: String) = File(root(context), relative)

    /** 新しく保存するファイルの場所（相対パス）を決める */
    fun newRelativePath(runId: Long, kind: String): String {
        val ext = when (kind) { MEMO -> "wav"; PHOTO -> "jpg"; else -> "mp4" }
        return "$runId/${kind.lowercase()}_${System.currentTimeMillis()}.$ext"
    }

    /** いま走っているランに、残したものを記録する。@return 記録できたら true */
    suspend fun addToCurrentRun(context: Context, kind: String, relative: String, durationMs: Long = 0): Boolean {
        val live = RunHub.state.value
        if (!live.active || live.runId <= 0) return false
        AppDb.get(context).runDao().insertMedia(
            MediaEntity(
                runId = live.runId, kind = kind, time = System.currentTimeMillis(),
                lat = live.lat, lon = live.lon, distM = live.distanceM, file = relative, durationMs = durationMs,
            )
        )
        return true
    }

    suspend fun delete(context: Context, media: MediaEntity) {
        AppDb.get(context).runDao().deleteMediaRow(media.id)
        file(context, media.file).delete()
    }

    fun deleteRunFiles(context: Context, runId: Long) {
        File(root(context), runId.toString()).deleteRecursively()
    }

    /** 共有メニューを開く（写真フォルダや SNS に書き出したいとき） */
    fun share(context: Context, media: MediaEntity) {
        val uri = FileProvider.getUriForFile(context, "com.chill.run.files", file(context, media.file))
        val type = when (media.kind) { MEMO -> "audio/wav"; PHOTO -> "image/jpeg"; else -> "video/mp4" }
        val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "共有").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** アプリの中のファイル（画像）を、共有メニューで外に出す */
    fun shareImage(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "com.chill.run.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "地図を共有").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
