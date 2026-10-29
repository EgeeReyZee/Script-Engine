package com.scriptengine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scriptengine.databinding.ActivityMainBinding
import com.scriptengine.service.AutoClickService
import com.scriptengine.service.ScriptService
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val RC_MEDIA_PROJECTION = 1001
        private const val RC_OVERLAY_PERMISSION = 1002
        private const val PREFS_NAME = "script_engine_prefs"
        private const val PREF_SCRIPT_PATH = "last_script_path"
        private const val DEFAULT_SCRIPTS_FOLDER = "scripts"
        private const val DEFAULT_IMAGES_FOLDER = "images"
    }

    private lateinit var binding: ActivityMainBinding
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ──────────────────────── Lifecycle ────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        restoreScriptPath()
        setupUI()
        startScriptService()
        requestStoragePermissionsIfNeeded()
        requestOverlayPermissionIfNeeded()
        initializeAppDirectories()

        ScriptService.logCallback = { log ->
            mainScope.launch {
                binding.tvLog.text = log
                binding.scrollLog.post {
                    binding.scrollLog.fullScroll(android.view.View.FOCUS_DOWN)
                }
            }
        }
        ScriptService.statusCallback = { status ->
            mainScope.launch { binding.tvStatus.text = "Status: $status" }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    override fun onPause() {
        super.onPause()
        saveScriptPath()
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }

    // ──────────────────────── App Directories ────────────────────────

    fun getAppPrivateDir(): File = getExternalFilesDir(null) ?: filesDir

    fun getScriptsDir(): File = File(getAppPrivateDir(), DEFAULT_SCRIPTS_FOLDER).also { it.mkdirs() }

    fun getImagesDir(): File = File(getAppPrivateDir(), DEFAULT_IMAGES_FOLDER).also { it.mkdirs() }

    private fun initializeAppDirectories() {
        val scriptsDir = getScriptsDir()
        val imagesDir = getImagesDir()

        Log.i("MainActivity", "App dirs: scripts=$scriptsDir, images=$imagesDir")

        // Создаём пример скрипта, если папка пуста
        if (scriptsDir.listFiles()?.isEmpty() != false) {
            createExampleScript(scriptsDir)
        }

        // Автоматически выбираем первый скрипт в папке
        val firstScript = scriptsDir.listFiles()?.firstOrNull { it.extension == "py" }
        if (firstScript != null && binding.etScriptPath.text.isNullOrEmpty()) {
            binding.etScriptPath.setText(firstScript.absolutePath)
        }
    }

    private fun createExampleScript(scriptsDir: File) {
        val exampleScript = File(scriptsDir, "demo.py")
        exampleScript.writeText("""
from api import *

log("=== ScriptEngine Demo ===")
w, h = get_screen_size()
log(f"Screen size: {w}x{h}")

# Проверяем все доступные API
log("Testing get_color...")
color = get_color(100, 100)
log(f"Color at (100,100): {color:#08x}")

log("Testing shell...")
result = shell("echo hello")
log(f"Shell echo: {result}")

log("Demo completed successfully!")
log(f"Try writing your own scripts in: ${scriptsDir.absolutePath}")
        """.trimIndent())

        Log.i("MainActivity", "Created example script: ${exampleScript.absolutePath}")
    }

    // ──────────────────────── Overlay Permission ────────────────────────

    private fun requestOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, RC_OVERLAY_PERMISSION)
            }
        }
    }

    // ──────────────────────── UI Setup ────────────────────────

    private fun setupUI() {
        binding.btnStart.setOnClickListener {
            val path = binding.etScriptPath.text.toString().trim()
            if (path.isEmpty()) {
                Toast.makeText(this, "Enter script path", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (AutoClickService.instance == null) {
                Toast.makeText(
                    this,
                    "Сначала включите Accessibility Service!",
                    Toast.LENGTH_LONG
                ).show()
                openAccessibilitySettings()
                return@setOnClickListener
            }
            saveScriptPath()
            requestMediaProjection(onGranted = {
                ScriptService.instance?.startScript(path)
                    ?: Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show()
            })
        }

        binding.btnEditor.setOnClickListener {
            startActivity(Intent(this, com.scriptengine.editor.CodeEditorActivity::class.java))
        }

        binding.btnStop.setOnClickListener {
            ScriptService.instance?.stopAll()
        }

        binding.btnAdbConnect.setOnClickListener {
            Toast.makeText(this, "ADB connection initiated", Toast.LENGTH_SHORT).show()
        }

        binding.btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // Новые кнопки
        binding.btnBrowseScript.setOnClickListener {
            val scriptsDir = getScriptsDir()
            val scripts = scriptsDir.listFiles()?.filter { it.extension == "py" } ?: emptyList()
            if (scripts.isEmpty()) {
                Toast.makeText(this, "No scripts found in ${scriptsDir.absolutePath}", Toast.LENGTH_LONG).show()
            } else {
                val names = scripts.map { it.name }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Select Script")
                    .setItems(names) { _, which ->
                        binding.etScriptPath.setText(scripts[which].absolutePath)
                    }
                    .show()
            }
        }

        binding.btnOpenScriptsDir.setOnClickListener {
            val dir = getScriptsDir()
            Toast.makeText(this, "Scripts folder: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
            // Open in file manager if available
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(dir.absolutePath)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Cannot open file manager", e)
            }
        }
    }

    // ──────────────────────── Accessibility ────────────────────────

    private fun updateAccessibilityStatus() {
        val connected = AutoClickService.instance != null
        if (connected) {
            binding.tvAccessibilityStatus.text = "✓ Accessibility: включён"
            binding.tvAccessibilityStatus.setTextColor(
                getColor(android.R.color.holo_green_dark)
            )
        } else {
            binding.tvAccessibilityStatus.text = "⚠ Accessibility: не включён — нажмите «Enable Accessibility»"
            binding.tvAccessibilityStatus.setTextColor(
                getColor(android.R.color.holo_red_dark)
            )
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ──────────────────────── Storage Permissions ────────────────────────

    private fun requestStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                100
            )
        }
    }

    // ──────────────────────── Script Path Persistence ────────────────────────

    private fun saveScriptPath() {
        val path = binding.etScriptPath.text.toString().trim()
        if (path.isNotEmpty()) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_SCRIPT_PATH, path)
                .apply()
        }
    }

    private fun restoreScriptPath() {
        val path = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_SCRIPT_PATH, "") ?: ""
        if (path.isNotEmpty()) {
            binding.etScriptPath.setText(path)
        }
    }

    // ──────────────────────── MediaProjection ────────────────────────

    private var pendingGrantedCallback: (() -> Unit)? = null

    private fun requestMediaProjection(onGranted: () -> Unit) {
        pendingGrantedCallback = onGranted
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), RC_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            RC_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = mpm.getMediaProjection(resultCode, data)

                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    val metrics = DisplayMetrics()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val bounds = wm.currentWindowMetrics.bounds
                        metrics.widthPixels = bounds.width()
                        metrics.heightPixels = bounds.height()
                        metrics.densityDpi = resources.displayMetrics.densityDpi
                    } else {
                        @Suppress("DEPRECATION")
                        wm.defaultDisplay.getRealMetrics(metrics)
                    }

                    ScriptService.instance?.initializeWithProjection(
                        projection,
                        metrics.widthPixels,
                        metrics.heightPixels,
                        metrics.densityDpi
                    )

                    pendingGrantedCallback?.invoke()
                    pendingGrantedCallback = null
                } else {
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show()
                }
            }
            RC_OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, "Overlay permission granted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Overlay permission denied. Widget won't work.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun startScriptService() {
        val intent = Intent(this, ScriptService::class.java)
        startForegroundService(intent)
    }
}