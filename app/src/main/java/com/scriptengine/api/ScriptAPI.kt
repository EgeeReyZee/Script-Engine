package com.scriptengine.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.scriptengine.adb.AdbManager
import com.scriptengine.engine.ImageFinder
import com.scriptengine.engine.ScreenCapture
import com.scriptengine.service.AutoClickService
import kotlinx.coroutines.runBlocking
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.lang.ref.SoftReference

/**
 * All functions accessible from Python scripts.
 * This class is passed to bridge.py via set_api().
 * All methods are thread-safe (Python may call from threading.Thread).
 */
class ScriptAPI(
    private val context: Context,
    private val screenCapture: ScreenCapture,
    private val imageFinder: ImageFinder,
    private val adbManager: AdbManager,
    private val logCallback: (String) -> Unit
) {

    companion object {
        private const val TAG = "ScriptAPI"
    }

    // Image cache: filename -> SoftReference<Bitmap>
    private val imageCache = HashMap<String, SoftReference<Bitmap>>()

    // Script working directory for loading images
    var scriptDir: String = ""

    // ────────────────────────────── Screen ──────────────────────────────

    /**
     * get_color(x, y) → int (ARGB)
     */
    fun getColor(x: Int, y: Int): Int {
        return try {
            screenCapture.getColor(x, y)
        } catch (e: Exception) {
            log("ERROR get_color: ${e.message}")
            -1
        }
    }

    /**
     * get_colors(points) → List<Int>
     * points: list of [x, y] pairs
     */
    fun getColors(points: List<List<Int>>): List<Int> {
        return try {
            val pairs = points.map { Pair(it[0], it[1]) }
            screenCapture.getColors(pairs).toList()
        } catch (e: Exception) {
            log("ERROR get_colors: ${e.message}")
            List(points.size) { -1 }
        }
    }

    // ────────────────────────────── Images ──────────────────────────────

    /**
     * load_image(name) → Bitmap (cached via SoftReference)
     * Looks in scriptDir, then assets
     */
    fun loadImage(name: String): Bitmap? {
        // Check cache first
        imageCache[name]?.get()?.let { return it }

        val bitmap = try {
            val file = File(scriptDir, name)
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                // Fallback to assets
                context.assets.open(name).use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            log("ERROR load_image '$name': ${e.message}")
            null
        }

        if (bitmap != null) {
            imageCache[name] = SoftReference(bitmap)
        }
        return bitmap
    }

    /**
     * find_img(img, x1, y1, x2, y2, threshold?) → {x, y, confidence} or None
     */
    fun findImg(
        template: Bitmap,
        x1: Int, y1: Int, x2: Int, y2: Int,
        threshold: Float = 0.85f
    ): ImageFinder.Point? {
        return try {
            val screen = screenCapture.getScreenBitmap() ?: return null
            val result = imageFinder.findImage(screen, template, x1, y1, x2, y2, threshold)
            screen.recycle()
            result
        } catch (e: Exception) {
            log("ERROR find_img: ${e.message}")
            null
        }
    }

    /**
     * has_img(img, x1?, y1?, x2?, y2?) → bool
     */
    fun hasImg(
        template: Bitmap,
        x1: Int = 0, y1: Int = 0,
        x2: Int = Int.MAX_VALUE, y2: Int = Int.MAX_VALUE,
        threshold: Float = 0.85f
    ): Boolean {
        return try {
            val screen = screenCapture.getScreenBitmap() ?: return false
            val (sw, sh) = screenCapture.getScreenSize()
            val safeX2 = if (x2 == Int.MAX_VALUE) sw else x2
            val safeY2 = if (y2 == Int.MAX_VALUE) sh else y2
            val result = imageFinder.hasImage(screen, template, x1, y1, safeX2, safeY2, threshold)
            screen.recycle()
            result
        } catch (e: Exception) {
            log("ERROR has_img: ${e.message}")
            false
        }
    }

    /**
     * find_img_all(img, x1, y1, x2, y2) → List of {x, y, confidence}
     */
    fun findImgAll(
        template: Bitmap,
        x1: Int, y1: Int, x2: Int, y2: Int,
        threshold: Float = 0.85f
    ): List<ImageFinder.Point> {
        return try {
            val screen = screenCapture.getScreenBitmap() ?: return emptyList()
            val result = imageFinder.findImageAll(screen, template, x1, y1, x2, y2, threshold)
            screen.recycle()
            result
        } catch (e: Exception) {
            log("ERROR find_img_all: ${e.message}")
            emptyList()
        }
    }

    // ────────────────────────────── Clicks ──────────────────────────────

    private fun requireAccessibility(): AutoClickService {
        return AutoClickService.instance
            ?: throw IllegalStateException("AccessibilityService not connected. Enable it in Settings.")
    }

    fun setClipboardText(text: String) {
        // Работа с буфером обмена должна происходить в главном потоке
        Handler(Looper.getMainLooper()).post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("script", text)
                clipboard.setPrimaryClip(clip)
            } catch (e: Exception) {
                logCallback("Clipboard error: ${e.message}")
            }
        }
    }

    fun click(x: Int, y: Int) {
        try { requireAccessibility().performClick(x.toFloat(), y.toFloat()) }
        catch (e: Exception) { log("ERROR click: ${e.message}") }
    }

    fun clickRand(x: Int, y: Int, radius: Int) {
        try {
            val angle = Math.random() * 2 * Math.PI
            val r = Math.random() * radius
            val rx = x + (r * Math.cos(angle)).toInt()
            val ry = y + (r * Math.sin(angle)).toInt()
            requireAccessibility().performClick(rx.toFloat(), ry.toFloat())
        } catch (e: Exception) { log("ERROR click_rand: ${e.message}") }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300) {
        try { requireAccessibility().performSwipe(
            x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration.toLong()) }
        catch (e: Exception) { log("ERROR swipe: ${e.message}") }
    }

    fun longPress(x: Int, y: Int, duration: Int = 1000) {
        try { requireAccessibility().performLongPress(x.toFloat(), y.toFloat(), duration.toLong()) }
        catch (e: Exception) { log("ERROR long_press: ${e.message}") }
    }

    fun pressBack() {
        try { requireAccessibility().performBack() }
        catch (e: Exception) { log("ERROR press_back: ${e.message}") }
    }

    fun pressHome() {
        try { requireAccessibility().performHome() }
        catch (e: Exception) { log("ERROR press_home: ${e.message}") }
    }

    fun showRecents() {
        try { requireAccessibility().performRecents() }
        catch (e: Exception) { log("ERROR show_recents: ${e.message}") }
    }

    fun complexSwipe(pointsString: String, durationPerSegment: Int) {
        // Проверяем, включен ли сервис в настройках телефона
        val service = AutoClickService.instance
        if (service == null) {
            logCallback("Ошибка: AccessibilityService выключен!")
            return
        }

        try {
            // Парсим строку "x1,y1;x2,y2" в список пар Pair(X, Y)
            val pointsList = pointsString.split(";").map {
                val coords = it.split(",")
                Pair(coords[0].toInt(), coords[1].toInt())
            }

            // Accessibility API требует, чтобы жесты отправлялись из главного потока
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                service.complexSwipe(pointsList, durationPerSegment.toLong())
            }
        } catch (e: Exception) {
            logCallback("Ошибка параметров complexSwipe: ${e.message}")
        }
    }

    // ────────────────────────────── Control ──────────────────────────────

    fun sleep(ms: Int) {
        try { Thread.sleep(ms.toLong()) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    fun log(message: String) {
        Log.i(TAG, message)
        logCallback(message)
    }

    // ИСПРАВЛЕНО: возвращаем String вместо List
    fun getScreenSize(): String {
        val (w, h) = screenCapture.getScreenSize()
        return "$w,$h"
    }

    // ────────────────────────────── ADB ──────────────────────────────

    fun shell(command: String): String {
        return try {
            runBlocking { adbManager.shell(command) }
        } catch (e: Exception) {
            log("ERROR shell: ${e.message}")
            ""
        }
    }

    fun startApp(packageName: String, activity: String? = null) {
        try {
            runBlocking { adbManager.startApp(packageName, activity) }
        } catch (e: Exception) { log("ERROR start_app: ${e.message}") }
    }

    fun stopApp(packageName: String) {
        try {
            runBlocking { adbManager.stopApp(packageName) }
        } catch (e: Exception) { log("ERROR stop_app: ${e.message}") }
    }

    fun sendKey(keycode: Int) {
        try {
            runBlocking { adbManager.sendKey(keycode) }
        } catch (e: Exception) { log("ERROR send_key: ${e.message}") }
    }

    fun getInstalledApps(): List<String> {
        return try {
            runBlocking { adbManager.getInstalledApps() }
        } catch (e: Exception) {
            log("ERROR get_installed_apps: ${e.message}")
            emptyList()
        }
    }

    fun inputAdbText(text: String) {
        try {
            val intent = android.content.Intent("ADB_INPUT_TEXT")
            intent.putExtra("msg", text)
            // Направляем бродкаст точно в ADBKeyBoard, чтобы система его не заблокировала
            intent.setPackage("com.android.adbkeyboard")
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            logCallback("AdbText error: ${e.message}")
        }
    }

}