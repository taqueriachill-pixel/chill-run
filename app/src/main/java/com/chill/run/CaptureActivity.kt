package com.chill.run

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.chill.run.data.MediaFiles
import com.chill.run.data.Settings
import com.chill.run.run.RunHub
import com.chill.run.ui.ChillTheme
import com.chill.run.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 写真・動画を撮る画面。ロックを解除しなくても開ける。
 * 3秒のカウントダウンのあと自動で撮る。画面のシャッターを押せばすぐ撮れる。
 * 撮ったものはこのアプリの中だけに保存し、撮った地点と時刻を記録する。
 */
class CaptureActivity : ComponentActivity() {
    private var isVideo = false
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    /** 画面に出す状態 */
    private var countdown by mutableIntStateOf(3)
    private var phase by mutableStateOf("ready") // ready / shooting / saved / error
    private var message by mutableStateOf("")
    private var cameraReady by mutableStateOf(false)
    private var started = false
    private var stabilized = false
    private var stabilizeNote by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        isVideo = intent.getBooleanExtra(EXTRA_VIDEO, false)

        val hasCamera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val running = RunHub.state.value.let { it.active && it.runId > 0 }
        if (!hasCamera) {
            phase = "error"
            message = "カメラの許可がありません。\nロックを解除して、アプリの「許可の状態」で許可してください。"
        } else if (!running) {
            phase = "error"
            message = "ランを記録しているときだけ撮れます。"
        }

        setContent {
            ChillTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (phase != "error") {
                        AndroidView(
                            factory = { ctx -> PreviewView(ctx).also { bindCamera(it) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (isVideo) "動画（${Settings(this@CaptureActivity).videoSeconds}秒）" + (if (stabilizeNote.isNotEmpty()) "\n$stabilizeNote" else "") else "写真",
                            color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        )
                        Text(
                            when (phase) {
                                "ready" -> if (cameraReady) countdown.toString() else "カメラの準備中…"
                                "shooting" -> if (isVideo) "● 録画中 $countdown" else "撮っています…"
                                "saved" -> "保存しました"
                                else -> message
                            },
                            color = if (phase == "shooting") Palette.danger else Color.White,
                            fontSize = if (phase == "ready" && cameraReady) 140.sp else 30.sp,
                            fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BottomButton("やめる", Color(0xFF3A4A54), Modifier.weight(1f)) { cancelAndClose() }
                            if (phase == "ready") {
                                BottomButton(if (isVideo) "すぐ撮り始める" else "すぐ撮る", Palette.danger, Modifier.weight(1.4f)) { shoot() }
                            }
                        }
                    }
                }
                // 3秒のカウントダウンのあと、自動で撮る
                LaunchedEffect(cameraReady) {
                    if (cameraReady && phase == "ready") {
                        for (n in 3 downTo 1) {
                            countdown = n
                            delay(1000)
                            if (phase != "ready") return@LaunchedEffect
                        }
                        shoot()
                    }
                }
                LaunchedEffect(phase) {
                    if (phase == "saved") { delay(900); finish() }
                    if (phase == "error") { delay(4000); finish() }
                }
            }
        }
    }

    private fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()
                if (isVideo) {
                    val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
                    // 手ブレ補正：このスマホのカメラが対応していれば使う（映る範囲が少しせまくなる）
                    val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).firstOrNull()
                    val wanted = Settings(this).videoStabilize
                    val videoOk = wanted && info != null &&
                        runCatching { Recorder.getVideoCapabilities(info).isStabilizationSupported }.getOrDefault(false)
                    val previewOk = videoOk && info != null &&
                        runCatching { Preview.getPreviewCapabilities(info).isStabilizationSupported }.getOrDefault(false)
                    fun bind(stabilize: Boolean) {
                        val preview = Preview.Builder()
                            .apply { if (stabilize && previewOk) setPreviewStabilizationEnabled(true) }
                            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val vc = VideoCapture.Builder(recorder)
                            .apply { if (stabilize) setVideoStabilizationEnabled(true) }
                            .build()
                        provider.unbindAll()
                        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, vc)
                        videoCapture = vc
                        stabilized = stabilize
                    }
                    try {
                        bind(videoOk)
                    } catch (e: Exception) {
                        // 手ブレ補正つきで開けなかったら、補正なしで開き直す
                        if (videoOk) bind(false) else throw e
                    }
                    stabilizeNote = when {
                        stabilized -> "手ブレ補正あり"
                        !wanted -> "手ブレ補正なし（設定でオフ）"
                        else -> "手ブレ補正なし（このカメラは非対応）"
                    }
                } else {
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                    imageCapture = ic
                }
                cameraReady = true
            } catch (e: Exception) {
                phase = "error"
                message = "カメラを開けませんでした。\n${e.message ?: ""}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun shoot() {
        if (started || phase != "ready") return
        val runId = RunHub.state.value.runId
        if (runId <= 0) {
            phase = "error"; message = "ランを記録しているときだけ撮れます。"
            return
        }
        started = true
        phase = "shooting"
        buzz(80)
        val executor = ContextCompat.getMainExecutor(this)
        if (!isVideo) {
            val ic = imageCapture ?: return fail("カメラの準備ができていません。")
            val relative = MediaFiles.newRelativePath(runId, MediaFiles.PHOTO)
            val file = MediaFiles.file(this, relative).also { it.parentFile?.mkdirs() }
            ic.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(), executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) = saved(MediaFiles.PHOTO, relative, 0)
                    override fun onError(e: ImageCaptureException) = fail("撮れませんでした。\n${e.message ?: ""}")
                },
            )
        } else {
            val vc = videoCapture ?: return fail("カメラの準備ができていません。")
            val seconds = Settings(this).videoSeconds.coerceIn(3, 5)
            val relative = MediaFiles.newRelativePath(runId, MediaFiles.VIDEO)
            val file = MediaFiles.file(this, relative).also { it.parentFile?.mkdirs() }
            var pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            // マイクの許可があるときだけ、音も入れる
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled()
            }
            recording = pending.start(executor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    if (!event.hasError() || file.length() > 10_000) saved(MediaFiles.VIDEO, relative, seconds * 1000L)
                    else fail("録画できませんでした（番号 ${event.error}）。")
                }
            }
            lifecycleScope.launch {
                for (n in seconds downTo 1) {
                    countdown = n
                    delay(1000)
                }
                recording?.stop()
            }
        }
    }

    private fun saved(kind: String, relative: String, durationMs: Long) {
        buzz(160)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { MediaFiles.addToCurrentRun(applicationContext, kind, relative, durationMs) }
            if (ok) phase = "saved" else fail("ランの記録が終わっていたので、保存できませんでした。")
        }
    }

    private fun fail(text: String) {
        phase = "error"
        message = text
    }

    private fun cancelAndClose() {
        // 録画の途中でやめたときは、そこまでを保存する
        if (recording != null) recording?.stop() else finish()
    }

    private fun buzz(ms: Long) {
        runCatching { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    companion object {
        private const val EXTRA_VIDEO = "video"

        fun intent(context: Context, video: Boolean): Intent =
            Intent(context, CaptureActivity::class.java)
                .putExtra(EXTRA_VIDEO, video)
                // 写真と動画で別の指示として扱われるようにする
                .setAction(if (video) "com.chill.run.CAPTURE_VIDEO" else "com.chill.run.CAPTURE_PHOTO")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@androidx.compose.runtime.Composable
private fun BottomButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(96.dp).clip(RoundedCornerShape(22.dp)).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
}
