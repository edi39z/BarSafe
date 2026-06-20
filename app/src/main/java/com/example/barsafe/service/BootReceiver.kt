package com.example.barsafe.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, BarSafeMonitorService::class.java)
            // Start the foreground service securely
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
