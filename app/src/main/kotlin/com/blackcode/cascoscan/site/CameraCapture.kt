package com.blackcode.cascoscan.site

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.exifinterface.media.ExifInterface
import com.blackcode.cascoscan.detect.GrayImage
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The camera, and turning what it captured into something the detector can read.
 *
 * Kept free of Compose so the awkward parts - provider binding, EXIF orientation, deciding how much to
 * downsample - are testable reasoning rather than tangled into a composable.
 */
object CameraCapture {

    private const val TAG = "CascoScan/Camera"

    /**
     * Roughly how many pixels to decode a captured photograph into.
     *
     * Generous compared with the detector's own working budget, because the same bitmap is what the
     * auditor pans around to place calibration taps and to check the markers, and a soft image makes
     * that harder than it needs to be.
     */
    const val DECODE_PIXELS = 6_000_000

    class Session(
        val preview: Preview,
        val imageCapture: ImageCapture,
        private val provider: ProcessCameraProvider,
    ) {
        fun unbind() = runCatching { provider.unbindAll() }
    }

    /** Binds preview and capture to [lifecycleOwner]; rebinding replaces any previous binding. */
    suspend fun start(
        context: Context,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        previewView: PreviewView,
    ): Session {
        val provider = awaitProvider(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        // Quality over latency: a hole's edge is the measurement, and edge sharpness is exactly what
        // aggressive noise reduction and low-light frame stacking destroy.
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        return Session(preview, capture, provider)
    }

    /** Takes one photograph into [target]. */
    suspend fun capture(imageCapture: ImageCapture, target: File, executor: Executor): File =
        suspendCoroutine { continuation ->
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(target).build(),
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                        continuation.resume(target)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }

    /**
     * Decodes a captured JPEG, applying the rotation recorded in its EXIF.
     *
     * Orientation is metadata, not pixels, so a photograph taken in portrait decodes on its side. It does
     * not change what is detected - the measurements are rotation-invariant - but the auditor has to
     * recognise their own wall to place a calibration tap on it.
     */
    suspend fun decode(file: File, targetPixels: Int = DECODE_PIXELS): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPixels)
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
            val degrees = rotationOf(file)
            if (degrees == 0) decoded else rotate(decoded, degrees)
        }.onFailure { Log.w(TAG, "could not decode ${file.name}: ${it.message}") }.getOrNull()
    }

    /** Greyscale for the detector, converted a row at a time to keep the peak allocation down. */
    fun toGray(bitmap: Bitmap): GrayImage {
        val w = bitmap.width
        val h = bitmap.height
        val out = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * w
            for (x in 0 until w) {
                val p = row[x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                out[base + x] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    /** Largest power-of-two reduction that still leaves at least [targetPixels]. */
    internal fun sampleSizeFor(width: Int, height: Int, targetPixels: Int): Int {
        var sample = 1
        while ((width / (sample * 2)).toLong() * (height / (sample * 2)) >= targetPixels) sample *= 2
        return sample
    }

    private fun rotationOf(file: File): Int = runCatching {
        when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    private suspend fun awaitProvider(context: Context): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(it) }
                },
                Executor { it.run() },
            )
        }
}
