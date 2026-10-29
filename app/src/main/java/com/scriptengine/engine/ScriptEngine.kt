package com.scriptengine.engine

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.scriptengine.api.ScriptAPI
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ScriptEngine(private val context: Context) {

    companion object {
        private const val TAG = "ScriptEngine"
    }

    private var py: Python? = null
    private var currentScriptThread: Thread? = null
    @Volatile
    private var isStopped = AtomicBoolean(false)

    fun initialize(api: ScriptAPI) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
            Log.i(TAG, "Python started")
        }
        py = Python.getInstance()

        extractPythonAssets()

        val python = py!!
        val sys = python.getModule("sys")
        val bridgeDir = File(context.filesDir, "pybridge").absolutePath

        sys["path"]?.callAttr("insert", 0, bridgeDir)
        Log.i(TAG, "Added to sys.path: $bridgeDir")

        val bridge = python.getModule("bridge")
        bridge.callAttr("set_api", api)
        Log.i(TAG, "Bridge initialized OK")
    }

    private fun extractPythonAssets() {
        val outDir = File(context.filesDir, "pybridge")
        outDir.mkdirs()

        listOf("bridge.py", "api.py").forEach { name ->
            val outFile = File(outDir, name)
            try {
                context.assets.open(name).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Extracted asset: $name → ${outFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract $name: ${e.message}")
            }
        }
    }

    fun executeScript(scriptPath: String) {
        stopScript()
        isStopped.set(false)

        val python = py ?: throw IllegalStateException("ScriptEngine not initialized")

        val file = File(scriptPath)
        if (!file.exists()) throw IllegalArgumentException("Script not found: $scriptPath")
        if (!file.canRead()) throw SecurityException(
            "Cannot read $scriptPath — нет разрешения. " +
                    "Включите 'Все файлы' в Настройках → Приложения → ScriptEngine → Разрешения."
        )

        val code = file.readText(Charsets.UTF_8)
        Log.i(TAG, "Executing: $scriptPath")

        val sys = python.getModule("sys")
        val scriptDir = file.parent ?: ""
        sys["path"]?.callAttr("insert", 0, scriptDir)

        val mainModule = python.getModule("__main__")
        val globalsDict = mainModule["__dict__"]

        // ИСПРАВЛЕНИЕ 1: Флаг остановки ставим напрямую в модуль, а не в словарь dict
        mainModule.put("__scriptengine_stopped", isStopped.get())

        // ИСПРАВЛЕНИЕ 2: Переменная для перехвата исключений из потока (защита от краша)
        var scriptException: Exception? = null

        currentScriptThread = Thread {
            try {
                python.builtins.callAttr("exec", code, globalsDict)
            } catch (e: Exception) {
                if (!isStopped.get()) {
                    // Сохраняем ошибку, чтобы передать ее в сервис, а не ронять приложение
                    scriptException = e
                }
            } finally {
                currentScriptThread = null
            }
        }

        currentScriptThread?.start()

        // Ждем завершения потока
        currentScriptThread?.join()

        // Если скрипт упал с ошибкой — пробрасываем ее, чтобы она вывелась в лог приложения
        scriptException?.let { throw it }
    }

    fun stopScript() {
        isStopped.set(true)
        currentScriptThread?.interrupt()
        currentScriptThread = null

        try {
            val python = py ?: return
            val mainModule = python.getModule("__main__")

            // ИСПРАВЛЕНИЕ 1: Записываем флаг остановки напрямую в модуль
            mainModule.put("__scriptengine_stopped", true)

        } catch (e: Exception) {
            Log.e(TAG, "Error setting stop flag", e)
        }
    }

    fun executeCode(code: String) {
        val python = py ?: throw IllegalStateException("ScriptEngine not initialized")
        val globalsDict = python.getModule("__main__")["__dict__"]
        globalsDict?.let { dict ->
            python.builtins.callAttr("exec", code, dict)
        }
    }
}