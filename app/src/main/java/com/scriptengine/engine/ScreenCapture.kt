package com.scriptengine.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock

class ScreenCapture private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CACHE_TTL_MS = 50L

        var nativeAvailable = false
            private set

        @Volatile
        private var instance: ScreenCapture? = null

        fun getInstance(context: Context): ScreenCapture {
            return instance ?: synchronized(this) {
                instance ?: ScreenCapture(context.applicationContext).also { instance = it }
            }
        }

        init {
            nativeAvailable = tryLoadNative()
        }

        private fun tryLoadNative(): Boolean {
            var opencvLoaded = false
            try {
                System.loadLibrary("opencv_java4")
                opencvLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "opencv_java4 not available")
            }

            return try {
                System.loadLibrary("scriptengine")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }
    }

    private external fun nativeGetColor(bitmap: Bitmap, x: Int, y: Int): Int
    private external fun nativeGetColors(bitmap: Bitmap, xArray: IntArray, yArray: IntArray): IntArray

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDpi = 0

    private var reusableBitmap: Bitmap? = null
    private val lock = ReentrantReadWriteLock()
    private var lastCaptureTime = 0L

    private val captureThread = HandlerThread("ScreenCaptureThread", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
    private val captureHandler = Handler(captureThread.looper)

    fun initialize(projection: MediaProjection, width: Int, height: Int, dpi: Int) {
        this.mediaProjection = projection
        this.screenWidth = width
        this.screenHeight = height
        this.screenDpi = dpi

        // Не создаем Bitmap жестко здесь, дождемся первого кадра, чтобы узнать точный rowStride
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = projection.createVirtualDisplay(
            "ScriptEngineCapture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, captureHandler
        )
        Log.i(TAG, "Initialized ${width}x${height}")
    }

    private fun captureFrame() {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < CACHE_TTL_MS && reusableBitmap != null) return

        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        lock.writeLock().lock()
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride

            // Истинная ширина буфера в пикселях (включая невидимый padding)
            val bufferWidth = rowStride / pixelStride

            // Если размер изменился или bitmap еще не создан
            if (reusableBitmap == null || reusableBitmap!!.width != bufferWidth || reusableBitmap!!.height != screenHeight) {
                reusableBitmap?.recycle()
                // ВАЖНО: Используем ARGB_8888 (32-bit), так как ImageReader выдает RGBA_8888
                reusableBitmap = Bitmap.createBitmap(bufferWidth, screenHeight, Bitmap.Config.ARGB_8888)
            }

            buffer.position(0)
            reusableBitmap!!.copyPixelsFromBuffer(buffer)
            lastCaptureTime = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "captureFrame error", e)
        } finally {
            image.close()
            lock.writeLock().unlock()
        }
    }

    fun getColor(x: Int, y: Int): Int {
        if (!nativeAvailable) return getColorFallback(x, y)
        captureFrame()
        lock.readLock().lock()
        return try {
            nativeGetColor(reusableBitmap ?: return -1, x, y)
        } finally {
            lock.readLock().unlock()
        }
    }

    private fun getColorFallback(x: Int, y: Int): Int {
        captureFrame()
        lock.readLock().lock()
        return try {
            reusableBitmap?.getPixel(x, y) ?: -1
        } catch (e: Exception) { -1 }
        finally { lock.readLock().unlock() }
    }

    fun getColors(points: List<Pair<Int, Int>>): IntArray {
        if (!nativeAvailable) return IntArray(points.size) { getColorFallback(points[it].first, points[it].second) }
        captureFrame()
        lock.readLock().lock()
        return try {
            val bitmap = reusableBitmap ?: return IntArray(points.size) { -1 }
            val xs = IntArray(points.size) { points[it].first }
            val ys = IntArray(points.size) { points[it].second }
            nativeGetColors(bitmap, xs, ys)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getScreenBitmap(): Bitmap? {
        captureFrame()
        lock.readLock().lock()
        return try {
            val bitmap = reusableBitmap ?: return null
            // Если ширина буфера больше ширины экрана, обрезаем невидимый край при запросе скриншота
            if (bitmap.width > screenWidth) {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            } else {
                bitmap.copy(bitmap.config, false)
            }
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getMagnifierCrop(x: Int, y: Int, radius: Int): Bitmap? {
        captureFrame()
        lock.readLock().lock()
        return try {
            val bitmap = reusableBitmap ?: return null
            val size = radius * 2 + 1
            // Создаем маленький квадрат для лупы
            val crop = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(crop)
            canvas.drawColor(android.graphics.Color.BLACK) // Заливка для краев экрана

            val srcLeft = (x - radius).coerceAtLeast(0)
            val srcTop = (y - radius).coerceAtLeast(0)
            val srcRight = (x + radius + 1).coerceAtMost(screenWidth)
            val srcBottom = (y + radius + 1).coerceAtMost(screenHeight)

            val dstLeft = if (x - radius < 0) radius - x else 0
            val dstTop = if (y - radius < 0) radius - y else 0
            val dstRight = dstLeft + (srcRight - srcLeft)
            val dstBottom = dstTop + (srcBottom - srcTop)

            val srcRect = android.graphics.Rect(srcLeft, srcTop, srcRight, srcBottom)
            val dstRect = android.graphics.Rect(dstLeft, dstTop, dstRight, dstBottom)

            // Рисуем кусок экрана в нашу лупу
            canvas.drawBitmap(bitmap, srcRect, dstRect, null)
            crop
        } catch (e: Exception) {
            null
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getScreenSize(): Pair<Int, Int> = Pair(screenWidth, screenHeight)

    fun release() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
        reusableBitmap?.recycle(); reusableBitmap = null
        captureThread.quitSafely()
    }
}