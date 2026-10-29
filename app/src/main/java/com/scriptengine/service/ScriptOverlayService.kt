package com.scriptengine.service

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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.scriptengine.R

class ScriptOverlayService : Service() {

    companion object {
        private var instance: ScriptOverlayService? = null

        fun show(context: Context, scriptName: String, onStop: () -> Unit) {
            instance?.let {
                it.updateScriptName(scriptName)
                it.setOnStopCallback(onStop)
                return
            }
            val intent = Intent(context, ScriptOverlayService::class.java).apply {
                putExtra("script_name", scriptName)
            }
            context.startService(intent)
            pendingStopCallback = onStop
        }

        fun hide() {
            instance?.stopSelf()
        }

        fun isVisible(): Boolean = instance != null

        private var pendingStopCallback: (() -> Unit)? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var stopCallback: (() -> Unit)? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scriptName = intent?.getStringExtra("script_name") ?: "Script"
        updateScriptName(scriptName)
        stopCallback = pendingStopCallback
        pendingStopCallback = null
        return START_NOT_STICKY
    }

    private fun createOverlayView() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 200

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
            background = ContextCompat.getDrawable(this@ScriptOverlayService, R.drawable.overlay_background)
            gravity = Gravity.CENTER_VERTICAL
        }

        val iconView = TextView(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(8, 0, 8, 0)
        }

        val nameView = TextView(this).apply {
            id = View.generateViewId()
            text = "Loading..."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(8, 0, 8, 0)
            maxWidth = 300
            maxLines = 1
            setEllipsize(android.text.TextUtils.TruncateAt.END)
        }

        val stopButton = Button(this).apply {
            text = "⛔"
            textSize = 16f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#FF6B6B"))
            setPadding(8, 4, 8, 4)
            setOnClickListener {
                stopCallback?.invoke()
                stopSelf()
            }
        }

        container.addView(iconView)
        container.addView(nameView)
        container.addView(stopButton)

        // Drag to move
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        overlayView = container
        windowManager.addView(overlayView, params)
    }

    private fun updateScriptName(name: String) {
        val container = overlayView as? LinearLayout ?: return
        val nameView = container.getChildAt(1) as? TextView
        nameView?.text = name.take(30)
    }

    private fun setOnStopCallback(callback: () -> Unit) {
        stopCallback = callback
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            // View already removed
        }
        instance = null
    }
}