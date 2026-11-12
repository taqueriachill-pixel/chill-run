package com.chill.run.music

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.chill.run.App
import com.chill.run.core.BpmDetector
import com.chill.run.data.AppDb
import com.chill.run.data.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 曲のフォルダを読んで曲の一覧を作り、曲ごとにテンポ（BPM）を調べる */
object MusicLibrary {
    /** いま何をしているか（画面に出す）。何もしていなければ null */
    val status = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private val audioExt = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav")

    /** フォルダの中（下のフォルダも）から曲をさがして、一覧に足す。@return 見つけた曲の数 */
    suspend fun scan(context: Context, treeUri: Uri): Int = withContext(Dispatchers.IO) {
        // 次にアプリを開いたときも、このフォルダを読めるようにしておく
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val found = ArrayList<Pair<Uri, String>>()
        fun walk(docId: String, depth: Int) {
            if (depth > 6 || found.size > 3000) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )?.use { c ->
                val dirs = ArrayList<String>()
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) dirs += id
                    else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in audioExt) {
                        found += DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to name.substringBeforeLast('.')
                    }
                }
                dirs.forEach { walk(it, depth + 1) }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(treeUri), 0)

        val dao = AppDb.get(context).runDao()
        val existing = dao.tracks().associateBy { it.uri }
        val foundUris = found.map { it.first.toString() }.toSet()
        // フォルダからなくなった曲は一覧から外す
        existing.values.filter { it.uri !in foundUris }.forEach { dao.deleteTrack(it.id) }
        for ((uri, name) in found) {
            if (uri.toString() !in existing) runCatching { dao.insertTrack(TrackEntity(uri = uri.toString(), title = name)) }
        }
        found.size
    }

    /** まだテンポを調べていない曲を、1曲ずつ調べる（調べるのは初回だけ。結果は保存する） */
    fun analyzeAll(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext as App
        job = app.scope.launch(Dispatchers.Default) {
            try {
                val dao = AppDb.get(app).runDao()
                val pending = dao.tracks().filter { it.bpmState == 0 }
                pending.forEachIndexed { i, t ->
                    status.value = "テンポを調べています… ${i + 1} / ${pending.size}　${t.title}"
                    val uri = Uri.parse(t.uri)
                    var title = t.title
                    var artist = t.artist
                    var duration = t.durationMs
                    runCatching {
                        val r = MediaMetadataRetriever()
                        try {
                            r.setDataSource(app, uri)
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { duration = it }
                        } finally {
                            r.release()
                        }
                    }
                    val bpm = AudioDecoder.decode(app, uri)?.let { BpmDetector.detect(it.mono, it.sampleRate) }
                    // 調べている間に手で直されていたら、その値を残す
                    val latest = dao.track(t.id) ?: return@forEachIndexed
                    dao.updateTrack(
                        if (latest.bpmManual) latest.copy(title = title, artist = artist, durationMs = duration, bpmState = 1)
                        else latest.copy(title = title, artist = artist, durationMs = duration, bpm = bpm ?: 0.0, bpmState = if (bpm != null) 1 else 2)
                    )
                }
            } finally {
                status.value = null
            }
        }
    }
}
