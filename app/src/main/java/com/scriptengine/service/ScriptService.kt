package com.scriptengine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.scriptengine.R
import com.scriptengine.adb.AdbManager
import com.scriptengine.api.ScriptAPI
import com.scriptengine.engine.ImageFinder
import com.scriptengine.engine.ScriptEngine
import com.scriptengine.engine.ScreenCapture
import kotlinx.coroutines.*
import java.io.File

/**
 * Foreground Service that manages script execution.
 * - SupervisorJob so one failing script doesn't kill others
 * - Separate monitoring scope
 * - Log UI throttle: max 10 updates/sec
 * - stopAll() via cancelChildren()
 */
class ScriptService : Service() {

    companion object {
        private const val TAG = "ScriptService"
        private const val CHANNEL_ID = "script_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val LOG_THROTTLE_MS = 100L

        const val ACTION_START_SCRIPT = "com.scriptengine.START_SCRIPT"
        const val ACTION_STOP_ALL = "com.scriptengine.STOP_ALL"
        const val EXTRA_SCRIPT_PATH = "script_path"

        // Singleton for MainActivity to observe logs
        @Volatile
        var instance: ScriptService? = null
            private set

        var logCallback: ((String) -> Unit)? = null
        var statusCallback: ((String) -> Unit)? = null
    }

    // SupervisorJob: if a child coroutine (script) fails, others keep running
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    // Separate scope for monitoring / health checks
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var screenCapture: ScreenCapture
    private lateinit var imageFinder: ImageFinder
    private lateinit var adbManager: AdbManager
    private lateinit var scriptEngine: ScriptEngine
    private lateinit var scriptAPI: ScriptAPI

    private var lastLogTime = 0L
    private val logBuffer = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing…"))

        screenCapture = ScreenCapture.getInstance(this)
        imageFinder = ImageFinder()
        adbManager = AdbManager(this)
        scriptAPI = ScriptAPI(
            context = this,
            screenCapture = screenCapture,
            imageFinder = imageFinder,
            adbManager = adbManager,
            logCallback = ::onScriptLog
        )
        scriptEngine = ScriptEngine(this)

        Log.i(TAG, "ScriptService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCRIPT -> {
                val path = intent.getStringExtra(EXTRA_SCRIPT_PATH) ?: return START_NOT_STICKY
                startScript(path)
            }
            ACTION_STOP_ALL -> stopAll()
        }
        return START_STICKY
    }

    /**
     * Initialize script engine with MediaProjection (called from MainActivity after permission).
     */
    fun initializeWithProjection(
        projection: android.media.projection.MediaProjection,
        width: Int,
        height: Int,
        dpi: Int
    ) {
        screenCapture.initialize(projection, width, height, dpi)
        scriptEngine.initialize(scriptAPI)
        updateNotification("Ready")
        Log.i(TAG, "Service initialized with projection ${width}x${height}")
    }

    /**
     * Start a script in a supervised coroutine.
     */
    fun startScript(scriptPath: String) {
        // Показать overlay виджет
        val scriptName = File(scriptPath).name
        ScriptOverlayService.show(this, scriptName) {
            stopAll()
            ScriptOverlayService.hide()
        }

        serviceScope.launch {
            updateStatus("Running: $scriptPath")
            updateNotification("Running script")
            onScriptLog("▶ Starting: $scriptPath")

            scriptAPI.scriptDir = java.io.File(scriptPath).parent ?: ""

            try {
                scriptEngine.executeScript(scriptPath)
                onScriptLog("✓ Script completed")
            } catch (e: CancellationException) {
                onScriptLog("■ Script stopped")
                Log.i(TAG, "Script cancelled: $scriptPath")
            } catch (e: Exception) {
                onScriptLog("✗ Error: ${e.message}")
                Log.e(TAG, "Script error: $scriptPath", e)
            } finally {
                updateStatus("Idle")
                updateNotification("Idle")
                ScriptOverlayService.hide()
            }
        }
    }

    /**
     * Stop all running scripts.
     */
    fun stopAll() {
        serviceJob.cancelChildren()
        scriptEngine.stopScript()
        updateStatus("Stopped")
        updateNotification("Stopped")
        onScriptLog("■ All scripts stopped")
        Log.i(TAG, "All scripts stopped")
    }

    // ──────────────────────── Log / UI ────────────────────────

    private fun onScriptLog(message: String) {
        val now = System.currentTimeMillis()
        synchronized(logBuffer) {
            logBuffer.appendLine(message)
            // Throttle UI updates to max 10/sec
            if (now - lastLogTime > LOG_THROTTLE_MS) {
                lastLogTime = now
                val snapshot = logBuffer.toString()
                logCallback?.invoke(snapshot)
            }
        }
    }

    private fun updateStatus(status: String) {
        statusCallback?.invoke(status)
    }

    // ──────────────────────── Notification ────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setSound(null, null)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ──────────────────────── Lifecycle ────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAll()
        monitorScope.cancel()
        serviceScope.cancel()
        screenCapture.release()
        adbManager.disconnect()
        instance = null
        super.onDestroy()
        Log.i(TAG, "ScriptService destroyed")
    }
}
