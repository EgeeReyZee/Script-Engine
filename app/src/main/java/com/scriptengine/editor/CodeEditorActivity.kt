package com.scriptengine.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scriptengine.R
import com.scriptengine.engine.ScreenCapture
import com.scriptengine.service.PipetteOverlayService
import com.scriptengine.service.ScriptService
import java.io.File

class CodeEditorActivity : AppCompatActivity() {

    private lateinit var codeEditor: EditText
    private var currentFile: File? = null
    private lateinit var screenCapture: ScreenCapture

    companion object {
        private const val TAG = "CodeEditorActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002
        private const val EXTRA_FILE_PATH = "file_path"

        fun start(context: Context, file: File?) {
            val intent = Intent(context, CodeEditorActivity::class.java)
            file?.let { intent.putExtra(EXTRA_FILE_PATH, it.absolutePath) }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_code_editor)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // Используем твои оригинальные ID из макета
        codeEditor = findViewById(R.id.etCode)

        // Используем Singleton, так как мы изменили ScreenCapture.kt
        screenCapture = ScreenCapture.getInstance(this)

        intent.getStringExtra(EXTRA_FILE_PATH)?.let { path ->
            currentFile = File(path)
            title = currentFile?.name
            loadCode()
        } ?: run {
            title = "new_script.py"
            codeEditor.setText("from api import *\n\n")
        }

        setupToolbar()
    }

    private fun setupToolbar() {
        // Используем твои оригинальные ID кнопок
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveCode() }
        findViewById<Button>(R.id.btnRun).setOnClickListener { runScript() }

        // Кнопка пипетки
        findViewById<Button>(R.id.btnPickColor).setOnClickListener {
            checkPermissionsAndTogglePipette()
        }
    }

    private fun checkPermissionsAndTogglePipette() {
        // 1. Проверяем разрешение на оверлей
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Нужно разрешение поверх других окон", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            return
        }

        // 2. Запрашиваем захват экрана
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    checkPermissionsAndTogglePipette()
                } else {
                    Toast.makeText(this, "Оверлей отклонен. Пипетка не сработает.", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_MEDIA_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = mpm.getMediaProjection(resultCode, data)

                    val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    val metrics = android.util.DisplayMetrics()
                    wm.defaultDisplay.getRealMetrics(metrics) // Берем сырой размер экрана, включая челки и бары!

                    screenCapture.initialize(
                        projection,
                        metrics.widthPixels,
                        metrics.heightPixels,
                        metrics.densityDpi
                    )

                    togglePipetteService()
                } else {
                    Toast.makeText(this, "Захват экрана отклонен", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun togglePipetteService() {
        if (PipetteOverlayService.isRunning) {
            PipetteOverlayService.stop(this)
            Toast.makeText(this, "Пипетка остановлена", Toast.LENGTH_SHORT).show()
        } else {
            // Сворачиваем редактор
            moveTaskToBack(true)

            // Даем задержку перед запуском оверлея
            Handler(Looper.getMainLooper()).postDelayed({
                PipetteOverlayService.start(this)
            }, 300)
        }
    }

    private fun loadCode() {
        try {
            currentFile?.let {
                if (it.exists()) {
                    codeEditor.setText(it.readText())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения кода", e)
            Toast.makeText(this, "Не удалось загрузить скрипт", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCode() {
        try {
            val code = codeEditor.text.toString()
            if (currentFile == null) {
                val scriptsDir = File(getExternalFilesDir(null) ?: filesDir, "scripts")
                scriptsDir.mkdirs()
                currentFile = File(scriptsDir, "new_script.py")
                title = currentFile?.name
            }
            currentFile?.writeText(code)
            Toast.makeText(this, "Скрипт сохранен", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка сохранения", e)
            Toast.makeText(this, "Ошибка сохранения скрипта", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runScript() {
        saveCode()
        val path = currentFile?.absolutePath
        if (path != null) {
            val intent = Intent(this, ScriptService::class.java).apply {
                action = ScriptService.ACTION_START_SCRIPT
                putExtra(ScriptService.EXTRA_SCRIPT_PATH, path)
            }
            startService(intent)
            Toast.makeText(this, "Запуск...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // В оригинальном классе метод называется release(), а не stop()
            screenCapture.release()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка закрытия ScreenCapture", e)
        }
    }
}