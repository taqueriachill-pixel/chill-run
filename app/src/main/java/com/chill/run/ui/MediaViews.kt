package com.chill.run.ui

import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chill.run.core.Fmt
import com.chill.run.data.MediaEntity
import com.chill.run.data.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFmt = DateTimeFormatter.ofPattern("H:mm")
fun formatClock(ms: Long): String = clockFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** 「9:41・2.35km 地点」のような、残した時刻と場所 */
fun whereWhen(m: MediaEntity) = "${formatClock(m.time)}・${Fmt.km(m.distM)}km 地点"

private suspend fun loadPicture(file: File, video: Boolean, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (video) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.path)
                r.getFrameAtTime(0)?.asImageBitmap()
            } finally {
                r.release()
            }
        } else {
            // 写真の向き（縦・横）を正しく直して読み込む
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longest / sample > maxSide) sample *= 2
                decoder.setTargetSampleSize(sample)
            }.asImageBitmap()
        }
    }.getOrNull()
}

/** 写真・動画の小さな見本 */
@Composable
fun MediaThumb(media: MediaEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val isVideo = media.kind == MediaFiles.VIDEO || media.kind == MediaFiles.MOVIE
    val image by produceState<ImageBitmap?>(null, media.id) {
        value = loadPicture(MediaFiles.file(context, media.file), isVideo, 400)
    }
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Palette.line).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (isVideo) Text("▶", color = Color.White, fontSize = 30.sp)
        Text(
            formatClock(media.time), color = Color.White, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp),
        )
    }
}

/** 写真・動画を大きく見る。共有（写真フォルダや SNS へ書き出し）と削除もここから */
@Composable
fun MediaViewer(media: MediaEntity, onClose: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val file = remember(media.id) { MediaFiles.file(context, media.file) }
    var confirm by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (media.kind == MediaFiles.MOVIE) "今日のランムービー" else whereWhen(media), color = Color.White, fontSize = 15.sp)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (media.kind == MediaFiles.VIDEO || media.kind == MediaFiles.MOVIE) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(file.path)
                                setOnPreparedListener { it.isLooping = true; start() }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val image by produceState<ImageBitmap?>(null, media.id) { value = loadPicture(file, false, 2400) }
                    val img = image
                    if (img != null) Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else Text("読み込み中…", color = Palette.dim)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("閉じる", Modifier.weight(1f), onClick = onClose)
                SmallButton("共有", Modifier.weight(1f)) { runCatching { MediaFiles.share(context, media) } }
                SmallButton("消す", Modifier.weight(1f)) { confirm = true }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("消しますか？") },
            text = { Text("消すと元に戻せません。") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("消す", color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("やめる") } },
        )
    }
}

/** 声のメモ1件：時刻と地点、文字にしたもの、再生、直す、消す */
@Composable
fun MemoRow(
    media: MediaEntity,
    busy: Boolean,
    onEdit: (String) -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var playing by remember(media.id) { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val player = remember(media.id) { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(media.id) {
        onDispose { player.value?.release(); player.value = null }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.bg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${whereWhen(media)}（${media.durationMs / 1000}秒）", color = Palette.dim, fontSize = 13.sp)
        Text(
            when {
                media.text.isNotBlank() -> media.text
                busy -> "文字にしています…"
                media.textState == 2 -> "文字にできませんでした。録音は残っているので、再生して聞けます。"
                else -> "まだ文字にしていません。"
            },
            color = if (media.text.isNotBlank()) Palette.text else Palette.dim,
            fontSize = 17.sp, fontWeight = if (media.text.isNotBlank()) FontWeight.Bold else FontWeight.Normal,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton(if (playing) "止める" else "再生", Modifier.weight(1f)) {
                if (playing) {
                    player.value?.release(); player.value = null; playing = false
                } else runCatching {
                    val mp = MediaPlayer()
                    mp.setDataSource(MediaFiles.file(context, media.file).path)
                    mp.setOnCompletionListener { it.release(); player.value = null; playing = false }
                    mp.prepare()
                    mp.start()
                    player.value = mp
                    playing = true
                }
            }
            SmallButton("直す", Modifier.weight(1f)) { editing = true }
            SmallButton("もう一度", Modifier.weight(1f), enabled = !busy, onClick = onRetry)
            SmallButton("消す", Modifier.weight(1f)) { confirm = true }
        }
    }

    if (editing) {
        var text by remember(media.id) { mutableStateOf(media.text) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("メモの文字を直す") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3) },
            confirmButton = { TextButton(onClick = { editing = false; onEdit(text.trim()) }) { Text("決定") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("やめる") } },
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("このメモを消しますか？") },
            text = { Text("録音も一緒に消えます。元に戻せません。") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("消す", color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("やめる") } },
        )
    }
}

/** 印の丸の大きさ（地図の上の印に使う） */
val MarkerSize = 46.dp
