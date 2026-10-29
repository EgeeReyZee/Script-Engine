package com.scriptengine.adb

import android.content.Context
import android.util.Log
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * ADB over TCP using tananaev/adblib.
 * API reference: AdbStream.read() returns ByteArray (no arguments).
 * AdbCrypto.loadAdbKeyPair takes File, File (not byte arrays).
 */
class AdbManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbManager"
        private const val PREFS_NAME = "adb_keys"
        private const val KEY_PUBLIC_PATH = "adb_public_path"
        private const val KEY_PRIVATE_PATH = "adb_private_path"
        private const val COMMAND_TIMEOUT_MS = 10_000L
    }

    var host: String = "127.0.0.1"
    var port: Int = 5555

    private var socket: Socket? = null
    private var connection: AdbConnection? = null
    private var crypto: AdbCrypto? = null

    private val adbBase64 = AdbBase64 { bytes -> Base64.getEncoder().encodeToString(bytes) }

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            crypto = loadOrGenerateCrypto()
            Log.i(TAG, "Connecting to $host:$port")
            val s = Socket(host, port)
            val conn = AdbConnection.create(s, crypto)
            conn.connect()
            socket = s
            connection = conn
            Log.i(TAG, "ADB connected")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ADB connect failed: ${e.message}")
            false
        }
    }

    fun disconnect() {
        try { connection?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        connection = null
        socket = null
    }

    fun isConnected(): Boolean =
        socket?.isConnected == true && socket?.isClosed == false

    suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        ensureConnected()
        val conn = connection ?: return@withContext "ERROR: not connected"

        val result = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
            try {
                val stream = conn.open("shell:$command")
                val sb = StringBuilder()
                // AdbStream.read() takes NO arguments — returns ByteArray
                var packet: ByteArray? = stream.read()
                while (packet != null && packet.isNotEmpty()) {
                    sb.append(String(packet, Charsets.UTF_8))
                    packet = try { stream.read() } catch (_: Exception) { null }
                }
                stream.close()
                sb.toString().trim()
            } catch (e: Exception) {
                Log.e(TAG, "shell '$command' error: ${e.message}")
                null
            }
        }
        result ?: "ERROR: timeout"
    }

    suspend fun startApp(packageName: String, activity: String? = null): String {
        val cmd = if (activity != null) "am start -n $packageName/$activity"
                  else "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        return shell(cmd)
    }

    suspend fun stopApp(packageName: String) = shell("am force-stop $packageName")

    suspend fun sendKey(keycode: Int) = shell("input keyevent $keycode")

    suspend fun getInstalledApps(): List<String> =
        shell("pm list packages").lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }

    // ──────────────────────────────────────────────────────────────

    private suspend fun ensureConnected() {
        if (!isConnected()) {
            Log.w(TAG, "ADB disconnected, reconnecting…")
            connect()
        }
    }

    /**
     * AdbCrypto.loadAdbKeyPair(base64, privateKeyFile, publicKeyFile)
     * AdbCrypto.generateAdbKeyPair(base64) → then save via .getPrivateKey() / .getPublicKey()
     * Keys stored as files in app's filesDir.
     */
    private fun loadOrGenerateCrypto(): AdbCrypto {
        val privFile = File(context.filesDir, "adb_key")
        val pubFile  = File(context.filesDir, "adb_key.pub")

        if (privFile.exists() && pubFile.exists()) {
            try {
                return AdbCrypto.loadAdbKeyPair(adbBase64, privFile, pubFile)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load stored keys, regenerating: ${e.message}")
            }
        }

        Log.i(TAG, "Generating new ADB key pair")
        val crypto = AdbCrypto.generateAdbKeyPair(adbBase64)
        try {
            crypto.saveAdbKeyPair(privFile, pubFile)
            Log.i(TAG, "Keys saved to ${context.filesDir}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not save keys: ${e.message}")
        }
        return crypto
    }
}
