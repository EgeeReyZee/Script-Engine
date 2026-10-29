package com.scriptengine.engine

import android.graphics.Bitmap
import android.util.Log
import java.lang.ref.SoftReference
import kotlin.math.abs

class ImageFinder {
    companion object {
        private const val TAG = "ImageFinder"
        private const val DEFAULT_THRESHOLD = 0.85f

        // Берем статус загрузки из ScreenCapture
        val nativeAvailable: Boolean get() = ScreenCapture.nativeAvailable
    }

    private external fun nativeFindImage(
        screenBitmap: Bitmap, templateBitmap: Bitmap,
        x1: Int, y1: Int, x2: Int, y2: Int, threshold: Float
    ): FloatArray?

    private external fun nativeFindImageAll(
        screenBitmap: Bitmap, templateBitmap: Bitmap,
        x1: Int, y1: Int, x2: Int, y2: Int, threshold: Float
    ): FloatArray?

    private val templateCache = HashMap<String, SoftReference<Bitmap>>()

    data class Point(val x: Int, val y: Int, val confidence: Float)

    fun findImage(
        screenBitmap: Bitmap, template: Bitmap,
        x1: Int, y1: Int, x2: Int, y2: Int,
        threshold: Float = DEFAULT_THRESHOLD
    ): Point? {
        // Если C++ не загрузился (как на твоем MuMu), используем Kotlin Fallback
        if (!nativeAvailable) {
            return findImageFallback(screenBitmap, template, x1, y1, x2, y2, threshold)
        }

        // Защита от краша OpenCV при выходе за пределы экрана
        val startX = x1.coerceIn(0, screenBitmap.width)
        val startY = y1.coerceIn(0, screenBitmap.height)
        val endX = x2.coerceIn(startX, screenBitmap.width)
        val endY = y2.coerceIn(startY, screenBitmap.height)

        if (startX >= endX || startY >= endY) return null

        val raw = nativeFindImage(screenBitmap, template, startX, startY, endX, endY, threshold) ?: return null

        // ВАЖНО: Возвращаем ЦЕНТР картинки, чтобы клик попадал куда нужно
        val centerX = raw[0].toInt() + template.width / 2
        val centerY = raw[1].toInt() + template.height / 2
        return Point(centerX, centerY, raw[2])
    }

    // ──────────────────────── PURE KOTLIN FALLBACK ────────────────────────

    private fun findImageFallback(
        screen: Bitmap, template: Bitmap,
        x1: Int, y1: Int, x2: Int, y2: Int,
        threshold: Float
    ): Point? {
        val sWidth = screen.width
        val sHeight = screen.height
        val tWidth = template.width
        val tHeight = template.height

        // Защита 1: шаблон не может быть больше самого экрана
        if (tWidth > sWidth || tHeight > sHeight) return null

        // Защита 2: ограничиваем запрошенные координаты реальными размерами экрана
        val safeX2 = minOf(x2, sWidth)
        val safeY2 = minOf(y2, sHeight)

        // ИСПРАВЛЕНИЕ ОШИБКИ 1440000:
        // Высчитываем максимальные X и Y, при которых шаблон ещё ПОМЕЩАЕТСЯ в рамки
        val maxStartX = safeX2 - tWidth
        val maxStartY = safeY2 - tHeight

        // Если искомая зона меньше самого шаблона
        if (maxStartX < 0 || maxStartY < 0) return null

        val startX = x1.coerceIn(0, maxStartX)
        val startY = y1.coerceIn(0, maxStartY)

        // Конечные границы цикла (+1, так как 'until' исключает последнее значение)
        val endX = maxStartX + 1
        val endY = maxStartY + 1

        if (startX >= endX || startY >= endY) return null

        // Выгружаем пиксели в одномерные массивы для дикой скорости
        val sPixels = IntArray(sWidth * sHeight)
        screen.getPixels(sPixels, 0, sWidth, 0, 0, sWidth, sHeight)

        val tPixels = IntArray(tWidth * tHeight)
        template.getPixels(tPixels, 0, tWidth, 0, 0, tWidth, tHeight)

        var bestMatch = 0f
        var bestX = -1
        var bestY = -1

        // Внешний шаг сканирования экрана (2 пикселя ускоряет поиск в 4 раза)
        val screenStep = 2

        for (y in startY until endY step screenStep) {
            for (x in startX until endX step screenStep) {
                val match = compareRegionFast(sPixels, sWidth, tPixels, tWidth, tHeight, x, y)
                if (match > bestMatch) {
                    bestMatch = match
                    bestX = x
                    bestY = y
                }
                // Если нашли практически идеальное совпадение, не тратим время дальше
                if (bestMatch >= 0.98f) break
            }
            if (bestMatch >= 0.98f) break
        }

        if (bestMatch >= threshold) {
            // Возвращаем координаты ЦЕНТРА найденной кнопки
            return Point(bestX + tWidth / 2, bestY + tHeight / 2, bestMatch)
        }
        return null
    }

    private fun compareRegionFast(
        sPixels: IntArray, sW: Int,
        tPixels: IntArray, tW: Int, tH: Int,
        sX: Int, sY: Int
    ): Float {
        var diffSum = 0f
        var checked = 0

        // Внутренний шаг проверки шаблона. Проверяем каждый 3-й пиксель.
        // Этого более чем достаточно для точного поиска, но в 9 раз быстрее!
        for (ty in 0 until tH step 3) {
            for (tx in 0 until tW step 3) {
                val tc = tPixels[ty * tW + tx]
                val sc = sPixels[(sY + ty) * sW + (sX + tx)]

                // Извлекаем RGB, игнорируя Alpha
                val tr = (tc shr 16) and 0xFF
                val tg = (tc shr 8) and 0xFF
                val tb = tc and 0xFF

                val sr = (sc shr 16) and 0xFF
                val sg = (sc shr 8) and 0xFF
                val sb = sc and 0xFF

                diffSum += abs(tr - sr) + abs(tg - sg) + abs(tb - sb)
                checked++
            }
        }
        val maxDiff = checked * 3 * 255f
        return 1.0f - (diffSum / maxDiff)
    }

    // ────────────────────────────────────────────────────────────────────────

    fun hasImage(
        screenBitmap: Bitmap, template: Bitmap,
        x1: Int = 0, y1: Int = 0, x2: Int = Int.MAX_VALUE, y2: Int = Int.MAX_VALUE,
        threshold: Float = DEFAULT_THRESHOLD
    ): Boolean {
        return findImage(screenBitmap, template, x1, y1, x2, y2, threshold) != null
    }

    fun findImageAll(
        screenBitmap: Bitmap, template: Bitmap,
        x1: Int, y1: Int, x2: Int, y2: Int,
        threshold: Float = DEFAULT_THRESHOLD
    ): List<Point> {
        if (!nativeAvailable) return emptyList() // Fallback для списка пока пропускаем

        val startX = x1.coerceIn(0, screenBitmap.width)
        val startY = y1.coerceIn(0, screenBitmap.height)
        val endX = x2.coerceIn(startX, screenBitmap.width)
        val endY = y2.coerceIn(startY, screenBitmap.height)

        if (startX >= endX || startY >= endY) return emptyList()

        val raw = nativeFindImageAll(screenBitmap, template, startX, startY, endX, endY, threshold) ?: return emptyList()
        val result = mutableListOf<Point>()
        var i = 0
        while (i + 2 < raw.size) {
            // Возвращаем центр
            result.add(Point(raw[i].toInt() + template.width/2, raw[i+1].toInt() + template.height/2, raw[2]))
            i += 3
        }
        return result
    }

    fun cacheTemplate(key: String, bitmap: Bitmap) { templateCache[key] = SoftReference(bitmap) }
    fun getCachedTemplate(key: String): Bitmap? = templateCache[key]?.get()
    fun clearCache() { templateCache.clear() }
}