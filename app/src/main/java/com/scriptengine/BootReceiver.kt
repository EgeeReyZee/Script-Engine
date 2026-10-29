package com.scriptengine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.scriptengine.service.ScriptService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Optionally auto-start service on boot
            // Uncomment if needed:
            // val serviceIntent = Intent(context, ScriptService::class.java)
            // context.startForegroundService(serviceIntent)
        }
    }
}
