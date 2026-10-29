package com.scriptengine.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.scriptengine.engine.ScreenCapture

class PipetteOverlayService : Service() {

    private lateinit var windowManager: WindowManager

    // ТЕПЕРЬ У НАС ДВА НЕЗАВИСИМЫХ ОКНА
    private lateinit var targetView: TargetView
    private lateinit var panelView: PanelView

    private lateinit var screenCapture: ScreenCapture

    private var isTargetDragging = false
    private var isPanelDragging = false

    companion object {
        private const val NOTIFICATION_ID = 1004
        private const val CHANNEL_ID = "AdvancedPipetteChannel"
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, PipetteOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PipetteOverlayService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        screenCapture = ScreenCapture.getInstance(this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        createOverlays()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            if (::targetView.isInitialized) windowManager.removeView(targetView)
            if (::panelView.isInitialized) windowManager.removeView(panelView)
        } catch (e: Exception) {
            // Views уже удалены
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getBaseLayoutParams(): WindowManager.LayoutParams {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun createOverlays() {
        targetView = TargetView(this)
        panelView = PanelView(this)

        val metrics = resources.displayMetrics

        // Настраиваем параметры для ПАНЕЛИ (по умолчанию сверху слева)
        val panelParams = getBaseLayoutParams().apply {
            x = 50
            y = 50
        }

        // Настраиваем параметры для ПРИЦЕЛА (по умолчанию по центру)
        val targetParams = getBaseLayoutParams().apply {
            x = metrics.widthPixels / 2 - (targetView.sizePx / 2).toInt()
            y = metrics.heightPixels / 2 - (targetView.sizePx / 2).toInt()
        }

        setupPanelTouchListener(panelParams)
        setupTargetTouchListener(targetParams)

        windowManager.addView(panelView, panelParams)
        windowManager.addView(targetView, targetParams)

        // Первый замер после отрисовки
        targetView.post { updateTargetColor(targetParams) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPanelTouchListener(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        panelView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isPanelDragging = true
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPanelDragging) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(panelView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPanelDragging = false
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTargetTouchListener(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        targetView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isTargetDragging = true
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    updateTargetColor(params)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTargetDragging) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(targetView, params)
                        updateTargetColor(params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isTargetDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun updateTargetColor(params: WindowManager.LayoutParams) {
        // Абсолютный центр прицела
        val globalX = params.x + (targetView.sizePx / 2).toInt()
        val globalY = params.y + (targetView.sizePx / 2).toInt()

        val metrics = resources.displayMetrics
        if (globalX < 0 || globalY < 0 || globalX >= metrics.widthPixels || globalY >= metrics.heightPixels) return

        try {
            val color = screenCapture.getColor(globalX, globalY)
            if (color != -1) {
                // Захватываем область 31x31 пиксель (радиус 15) для лупы
                val crop = screenCapture.getMagnifierCrop(globalX, globalY, 15)
                panelView.updateData(globalX, globalY, color, crop)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Advanced Pipette", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Пипетка активирована")
        .setContentText("Прицел и панель можно двигать отдельно")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setOngoing(true).build()

    // ──────────────────────── VIEW ПРИЦЕЛА ────────────────────────
    private inner class TargetView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        fun dp(value: Float) = value * density

        val sizePx = dp(60f)
        private val center = sizePx / 2f

        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF3B30")
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(4f)
        }

        init { setWillNotDraw(false) }

        override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(sizePx.toInt(), sizePx.toInt()) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = dp(15f)
            val gap = dp(4f) // Пустота в центре

            canvas.drawCircle(center, center, radius, shadowPaint)
            canvas.drawLine(center - radius, center, center - gap, center, shadowPaint)
            canvas.drawLine(center + gap, center, center + radius, center, shadowPaint)
            canvas.drawLine(center, center - radius, center, center - gap, shadowPaint)
            canvas.drawLine(center, center + gap, center, center + radius, shadowPaint)

            canvas.drawCircle(center, center, radius, crosshairPaint)
            canvas.drawLine(center - radius, center, center - gap, center, crosshairPaint)
            canvas.drawLine(center + gap, center, center + radius, center, crosshairPaint)
            canvas.drawLine(center, center - radius, center, center - gap, crosshairPaint)
            canvas.drawLine(center, center + gap, center, center + radius, crosshairPaint)
        }
    }

    // ──────────────────────── VIEW ПАНЕЛИ С ЛУПОЙ ────────────────────────
    private inner class PanelView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        fun dp(value: Float) = value * density

        private val viewWidth = dp(190f)
        private val viewHeight = dp(80f)

        private var currentX = 0
        private var currentY = 0
        private var hexString = "#000000"
        private var magnifierBmp: Bitmap? = null

        private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6202124")
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(12f)
            typeface = Typeface.MONOSPACE
        }
        // ВАЖНО: isFilterBitmap = false делает пиксели лупы четкими (без размытия)
        private val pixelPaint = Paint().apply { isFilterBitmap = false }

        private val centerHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
        }

        init { setWillNotDraw(false) }

        override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(viewWidth.toInt(), viewHeight.toInt()) }

        fun updateData(x: Int, y: Int, color: Int, bmp: Bitmap?) {
            val oldBmp = magnifierBmp
            this.currentX = x
            this.currentY = y
            this.hexString = String.format("#%06X", (0xFFFFFF and color))
            this.magnifierBmp = bmp
            invalidate()

            // Освобождаем старую картинку лупы, чтобы не было утечек памяти
            oldBmp?.recycle()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Фон панели
            val panelRect = RectF(0f, 0f, viewWidth, viewHeight)
            canvas.drawRoundRect(panelRect, dp(8f), dp(8f), panelPaint)

            // Текст (слева)
            canvas.drawText("X: $currentX", dp(15f), dp(25f), textPaint)
            canvas.drawText("Y: $currentY", dp(15f), dp(45f), textPaint)
            canvas.drawText(hexString, dp(15f), dp(65f), textPaint)

            // Отрисовка лупы (справа)
            magnifierBmp?.let { bmp ->
                val magSize = dp(60f)
                val dstRect = RectF(viewWidth - magSize - dp(10f), dp(10f), viewWidth - dp(10f), dp(10f) + magSize)

                // Рисуем увеличенный кусок экрана
                canvas.drawBitmap(bmp, null, dstRect, pixelPaint)
                canvas.drawRect(dstRect, Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE })

                // Рисуем красный квадратик ровно по центру лупы (размер 1 реального пикселя в масштабе лупы)
                // Лупа у нас 31x31 пиксель, значит размер одного пикселя = magSize / 31
                val pixelSize = magSize / 31f
                val cx = dstRect.centerX()
                val cy = dstRect.centerY()

                val centerPixelRect = RectF(cx - pixelSize/2, cy - pixelSize/2, cx + pixelSize/2, cy + pixelSize/2)
                canvas.drawRect(centerPixelRect, centerHighlightPaint)
            }
        }
    }
}