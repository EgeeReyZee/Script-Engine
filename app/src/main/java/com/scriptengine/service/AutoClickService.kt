package com.scriptengine.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService that performs taps, swipes, long-presses.
 * Singleton instance accessible via AutoClickService.instance.
 */
class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"

        /**
         * Singleton reference — set when service connects, cleared on disconnect.
         * Access from ScriptAPI to perform gestures.
         */
        @Volatile
        var instance: AutoClickService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "AutoClickService connected")
    }

    override fun onInterrupt() {
        Log.w(TAG, "AutoClickService interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.i(TAG, "AutoClickService destroyed")
    }

    // AccessibilityEvent not used, but required override
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // ──────────────────────────────────────────────────────────

    /**
     * Tap at (x, y).
     */
    fun performClick(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Swipe from (x1,y1) to (x2,y2) over duration ms.
     */
    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Long press at (x, y) for duration ms.
     */
    fun performLongPress(x: Float, y: Float, duration: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Perform a complex swipe.
     */

    fun complexSwipe(points: List<Pair<Int, Int>>, durationPerSegment: Long) {
        if (points.size < 2) return

        val path = Path()
        path.moveTo(points[0].first.toFloat(), points[0].second.toFloat())

        for (i in 1 until points.size) {
            path.lineTo(points[i].first.toFloat(), points[i].second.toFloat())
        }

        val totalDuration = durationPerSegment * (points.size - 1)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, totalDuration))

        dispatchGesture(gestureBuilder.build(), null, null)
    }
    fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
}
