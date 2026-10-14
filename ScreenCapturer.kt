package com.berkay.oyunceviri

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.max
import kotlin.math.min

/**
 * Ekran yakalama (MediaProjection -> VirtualDisplay -> ImageReader) ve altyazı bölgesini
 * kesip [frames] akışına vermek. Üretici-tüketici deseni: akış CONFLATED, yani OCR yavaşlarsa
 * eski kareler atılır, hep en yeni kare okunur (gecikme birikmez).
 *
 * Sahip olduğu her şey [release] içinde kapanır: VirtualDisplay, ImageReader, MediaProjection, thread.
 */
class ScreenCapturer(
    context: Context,
    private val projection: MediaProjection,
    private val onStopped: () -> Unit
) {
    private companion object {
        const val FRAME_INTERVAL_MS = 250L
        const val PREVIEW_INTERVAL_MS = 1_000L
        const val PREVIEW_WIDTH = 480
        const val MAX_CAPTURE_SIDE = 1280f
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val thread = HandlerThread("capture").also { it.start() }
    private val handler = Handler(thread.looper)

    private val channel = Channel<Bitmap>(Channel.CONFLATED, onUndeliveredElement = { it.recycle() })

    /** Kesilmiş altyazı bölgesi. Alan, her bitmap'i okuduktan sonra recycle etmelidir. */
    val frames: Flow<Bitmap> = channel.receiveAsFlow()

    @Volatile var screenHeight = 0
        private set

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var lastFrameAt = 0L
    private var lastPreviewAt = 0L
    @Volatile private var released = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!released) onStopped()
        }
    }

    init {
        // Android 14+: sanal ekran oluşturmadan önce callback kaydı şart
        projection.registerCallback(projectionCallback, handler)
        configure()
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }

    /** İlk kurulum ve ekran döndürülünce (yatay/dikey) yeniden boyutlandırma. */
    fun configure() {
        if (released) return
        val dm = realMetrics()
        screenHeight = dm.heightPixels
        val scale = min(1f, MAX_CAPTURE_SIDE / max(dm.widthPixels, dm.heightPixels))
        val w = (dm.widthPixels * scale).toInt()
        val h = (dm.heightPixels * scale).toInt()

        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener({ onImage(it) }, handler)
        val old = reader
        reader = newReader

        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection.createVirtualDisplay(
                "oyunceviri", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, handler
            )
        } else {
            vd.surface = newReader.surface
            vd.resize(w, h, dm.densityDpi)
        }
        old?.setOnImageAvailableListener(null, null)
        old?.close()
    }

    private fun onImage(r: ImageReader) {
        val image: Image = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return
            // Ana ekran açıkken yakalanan şey kendi arayüzümüz olur: okuma ve önizlemeyi durdur
            if (SessionBus.uiVisible) return
            lastFrameAt = now

            val full = toBitmap(image) ?: return
            try {
                if (now - lastPreviewAt >= PREVIEW_INTERVAL_MS) {
                    lastPreviewAt = now
                    SessionBus.setPreview(thumbnail(full))
                }
                val top = (full.height * SessionBus.regionTop.value / 100f)
                    .toInt().coerceIn(0, full.height - 16)
                channel.trySend(Bitmap.createBitmap(full, 0, top, full.width, full.height - top))
            } finally {
                full.recycle()
            }
        } catch (e: Exception) {
            // tek kare bozuksa atla
        } finally {
            image.close()
        }
    }

    private fun toBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return padded
        val exact = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return exact
    }

    private fun thumbnail(src: Bitmap): Bitmap {
        val w = min(PREVIEW_WIDTH, src.width)
        val h = max(1, src.height * w / src.width)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    fun release() {
        if (released) return
        released = true
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        try {
            // Eski oturumun onStop'u yeni oturumu kapatmasın
            projection.unregisterCallback(projectionCallback)
            projection.stop()
        } catch (_: Exception) {
        }
        channel.close()
        thread.quitSafely()
    }
}
