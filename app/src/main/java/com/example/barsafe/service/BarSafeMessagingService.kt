package com.example.barsafe.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.barsafe.MainActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM Push Notification Handler.
 *
 * Format payload yang diharapkan dari server/Firebase Functions:
 * {
 *   "data": {
 *     "title": "Judul notifikasi",
 *     "body": "Isi notifikasi",
 *     "level": "CRITICAL" | "WARNING" | "INFO",
 *     "type": "INTRUSION" | "UNLOCK_FAILED" | "UNLOCK_SUCCESS" | ...
 *   }
 * }
 *
 * Level menentukan:
 *   CRITICAL → 🔴 Channel barsafe_critical (max priority, vibration kuat)
 *   WARNING  → 🟡 Channel barsafe_warning  (high priority, vibration sedang)
 *   INFO     → 🟢 Channel barsafe_info     (low priority, tidak berbunyi)
 */
class BarSafeMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Extract fields dari notification atau data payload
        val title = message.notification?.title ?: message.data["title"] ?: "⚠️ BarSafe Alert"
        val body  = message.notification?.body  ?: message.data["body"]  ?: "Ada aktivitas baru pada brankas Anda."
        val level = message.data["level"]?.uppercase() ?: inferLevel(message.data["type"] ?: "")
        val type  = message.data["type"] ?: ""

        scope.launch {
            // Rate limit check sebelum tampilkan notifikasi
            val rateLimiter = com.example.barsafe.util.NotificationRateLimiter(applicationContext)
            val rateKey = "fcm_${level}_${type.take(20)}"
            val interval = com.example.barsafe.util.NotificationRateLimiter.intervalForLevel(level)

            if (!rateLimiter.canSend(rateKey, interval)) {
                android.util.Log.d("FCMService", "Rate limited: $rateKey")
                return@launch
            }
            rateLimiter.markSent(rateKey)

            // Ensure channels exist
            BarSafeMonitorService.ensureChannels(applicationContext)

            val (channelId, group, priority, vibrate, color) = when (level) {
                "CRITICAL" -> NotifParams(
                    channelId = BarSafeMonitorService.CHANNEL_CRITICAL,
                    group     = BarSafeMonitorService.GROUP_CRITICAL,
                    priority  = NotificationCompat.PRIORITY_MAX,
                    vibrate   = longArrayOf(0, 500, 200, 500),
                    color     = android.graphics.Color.parseColor("#FF1744")
                )
                "WARNING" -> NotifParams(
                    channelId = BarSafeMonitorService.CHANNEL_WARNING,
                    group     = BarSafeMonitorService.GROUP_WARNING,
                    priority  = NotificationCompat.PRIORITY_HIGH,
                    vibrate   = longArrayOf(0, 300),
                    color     = android.graphics.Color.parseColor("#FFEA00")
                )
                else -> NotifParams(
                    channelId = BarSafeMonitorService.CHANNEL_INFO,
                    group     = BarSafeMonitorService.GROUP_INFO,
                    priority  = NotificationCompat.PRIORITY_DEFAULT,
                    vibrate   = longArrayOf(0, 100),
                    color     = android.graphics.Color.parseColor("#00E676")
                )
            }

            // Buat icon prefix sesuai level
            val levelPrefix = when (level) {
                "CRITICAL" -> "🔴 "
                "WARNING"  -> "🟡 "
                else       -> "🟢 "
            }

            val deepLink = if (level == "CRITICAL" || type.contains("INTRUSION")) "logs" else "home"
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("deepLink", deepLink)
            }
            val pendingIntent = PendingIntent.getActivity(
                applicationContext, type.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(applicationContext, channelId)
                .setSmallIcon(com.example.barsafe.R.drawable.ic_app_logo_barsafe)
                .setContentTitle("$levelPrefix$title")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(priority)
                .setAutoCancel(true)
                .setColor(color)
                .setVibrate(vibrate)
                .setGroup(group)
                .setContentIntent(pendingIntent)
                .build()

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(System.currentTimeMillis().toInt(), notification)
        }
    }

    /** Inferring level dari type jika tidak disertakan secara eksplisit */
    private fun inferLevel(type: String): String = when {
        type.contains("INTRUSION") || type.contains("TAMPER") -> "CRITICAL"
        type.contains("FAILED")    || type.contains("ANOMALY") -> "WARNING"
        else -> "INFO"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        android.util.Log.d("FCMService", "New FCM token: $token")
        // TODO: Kirim token ke server jika ada backend
    }

    private data class NotifParams(
        val channelId: String,
        val group: String,
        val priority: Int,
        val vibrate: LongArray,
        val color: Int
    )
}
