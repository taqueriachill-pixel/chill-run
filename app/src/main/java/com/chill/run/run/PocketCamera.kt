package com.chill.run.run

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 画面を出さずに写真を1枚撮る（イヤホンのボタンから「ノールック」で撮るため）。
 * 走っている間の常駐サービス（カメラを使う種類つき）から使う。画面が消えていても撮れる。
 */
class PocketCamera(private val context: Context) {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** @return 撮れたら true */
    suspend fun shoot(file: File): Boolean = withContext(Dispatchers.Main) {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val provider = runCatching { suspendCancellableCoroutine<ProcessCameraProvider> { c ->
            val f = ProcessCameraProvider.getInstance(context)
            f.addListener({ c.resume(f.get()) }, ContextCompat.getMainExecutor(context))
        } }.getOrNull() ?: return@withContext false
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build()
            )
            .setJpegQuality(90)
            .build()
        try {
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            // 明るさとピントが合うまで、少し待つ
            delay(900)
            file.parentFile?.mkdirs()
            suspendCancellableCoroutine { c ->
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) { c.resume(true) }
                        override fun onError(exception: ImageCaptureException) { c.resume(false) }
                    },
                )
            }
        } catch (e: Exception) {
            false
        } finally {
            runCatching { provider.unbind(capture) }
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
