package com.example.barsafe.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.barsafe.MainActivity
import com.example.barsafe.util.NotificationRateLimiter
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import com.google.firebase.database.ChildEventListener

class BarSafeMonitorService : Service() {
    companion object {
        // ── Notification Channels ─────────────────────────────────────────
        const val FOREGROUND_CHANNEL_ID    = "barsafe_monitor_channel"
        const val CHANNEL_CRITICAL         = "barsafe_critical"    // 🔴 Maling, intrusi
        const val CHANNEL_WARNING          = "barsafe_warning"     // 🟡 Gagal login, getaran
        const val CHANNEL_INFO             = "barsafe_info"        // 🟢 Berhasil buka
        const val UNLOCK_SUCCESS_CHANNEL_ID = "barsafe_unlock_success"

        // ── Notification Groups ───────────────────────────────────────────
        const val GROUP_CRITICAL = "group_critical"
        const val GROUP_WARNING  = "group_warning"
        const val GROUP_INFO     = "group_info"

        // ── Foreground service notif ID ───────────────────────────────────
        const val NOTIFICATION_ID = 1001

        var isServiceRunning = false

        // ── Rate limiter keys ─────────────────────────────────────────────
        private const val KEY_PIR        = "notif_pir"
        private const val KEY_VIBRATION  = "notif_vibration"
        private const val KEY_UNLOCK_OK  = "notif_unlock_success"
        private const val KEY_UNLOCK_FAIL = "notif_unlock_failed"

        /**
         * 🟢 INFO — Notifikasi berhasil buka brankas.
         * Dipanggil dari FaceUnlockScreen setelah face recognition sukses.
         *
         * @param isAcked true jika solenoid sudah mengkonfirmasi via ACK
         */
        fun sendUnlockSuccessNotification(context: Context, personName: String, isAcked: Boolean = false) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                val limiter = NotificationRateLimiter(context)
                if (!limiter.canSend(KEY_UNLOCK_OK, NotificationRateLimiter.INFO_INTERVAL_MS)) {
                    Log.d("BarSafeMonitor", "Rate limited: unlock success")
                    return@launch
                }
                limiter.markSent(KEY_UNLOCK_OK)

                val ackSuffix = if (isAcked) " (Solenoid dikonfirmasi)" else ""
                val bodyText = "Sistem mengautentikasi wajah \"$personName\". Pintu brankas kini terbuka.$ackSuffix"

                showNotification(
                    context = context,
                    channelId = UNLOCK_SUCCESS_CHANNEL_ID,
                    group = GROUP_INFO,
                    title = "Akses Diberikan",
                    body = "Brankas terbuka untuk $personName",
                    bigText = bodyText,
                    color = android.graphics.Color.parseColor("#00E676"),
                    priority = NotificationCompat.PRIORITY_HIGH,
                    vibrate = longArrayOf(0, 300, 100, 300),
                    deepLink = "home"
                )
            }
        }

        /**
         * 🟡 WARNING — Notifikasi perintah disimpan offline.
         */
        fun sendQueuedOfflineNotification(context: Context, personName: String) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                val limiter = NotificationRateLimiter(context)
                if (!limiter.canSend("notif_offline_queue", NotificationRateLimiter.WARNING_INTERVAL_MS)) return@launch
                limiter.markSent("notif_offline_queue")

                showNotification(
                    context = context,
                    channelId = CHANNEL_WARNING,
                    group = GROUP_WARNING,
                    title = "📶 Perintah Disimpan Offline",
                    body = "Wajah $personName dikenali. Perintah buka brankas akan dikirim saat online.",
                    bigText = "Wajah $personName berhasil diverifikasi namun tidak ada koneksi internet.\n" +
                        "Perintah solenoid disimpan dalam antrian dan akan dikirim otomatis saat koneksi pulih.",
                    color = android.graphics.Color.parseColor("#FFEA00"),
                    priority = NotificationCompat.PRIORITY_DEFAULT,
                    vibrate = longArrayOf(0, 200),
                    deepLink = "home"
                )
            }
        }

        /**
         * 🔴 CRITICAL — Dipanggil dari sensor listener (intrusi / getaran keras).
         * Internal — juga dipakai oleh instance sensor listener.
         */
        internal fun sendCriticalAlert(context: Context, title: String, body: String, rateKey: String, imageUrl: String = "") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                val limiter = NotificationRateLimiter(context)
                if (!limiter.canSend(rateKey, NotificationRateLimiter.CRITICAL_INTERVAL_MS)) {
                    Log.d("BarSafeMonitor", "Rate limited: $rateKey")
                    return@launch
                }
                limiter.markSent(rateKey)
                
                val bitmap = if (imageUrl.isNotBlank()) loadBitmapFromUrl(context, imageUrl) else null

                showNotification(
                    context = context,
                    channelId = CHANNEL_CRITICAL,
                    group = GROUP_CRITICAL,
                    title = title,
                    body = body,
                    bigText = body,
                    bitmap = bitmap,
                    color = android.graphics.Color.parseColor("#FF1744"),
                    priority = NotificationCompat.PRIORITY_MAX,
                    vibrate = longArrayOf(0, 500, 200, 500),
                    deepLink = "logs"
                )
            }
        }
        
        private suspend fun loadBitmapFromUrl(context: Context, url: String): Bitmap? {
            return try {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                (result as? SuccessResult)?.drawable?.let {
                    (it as BitmapDrawable).bitmap
                }
            } catch (e: Exception) { null }
        }

        /**
         * 🟡 WARNING — Gagal unlock / mencurigakan.
         */
        internal fun sendWarningAlert(context: Context, title: String, body: String, rateKey: String) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch {
                val limiter = NotificationRateLimiter(context)
                if (!limiter.canSend(rateKey, NotificationRateLimiter.WARNING_INTERVAL_MS)) return@launch
                limiter.markSent(rateKey)

                showNotification(
                    context = context,
                    channelId = CHANNEL_WARNING,
                    group = GROUP_WARNING,
                    title = title,
                    body = body,
                    bigText = body,
                    color = android.graphics.Color.parseColor("#FFEA00"),
                    priority = NotificationCompat.PRIORITY_HIGH,
                    vibrate = longArrayOf(0, 300),
                    deepLink = "logs"
                )
            }
        }

        // ── Unread Badge Counter ─────────────────────────────────────────
        private var notificationBadgeCount = 0

        /**
         * Internal builder — titik tunggal pembuatan notifikasi.
         */
        private fun showNotification(
            context: Context,
            channelId: String,
            group: String,
            title: String,
            body: String,
            bigText: String,
            bitmap: Bitmap? = null,
            color: Int,
            priority: Int,
            vibrate: LongArray,
            deepLink: String
        ) {
            ensureChannels(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("deepLink", deepLink)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, deepLink.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            notificationBadgeCount++

            val notificationBuilder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(com.example.barsafe.R.drawable.ic_app_logo_barsafe)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(priority)
                .setAutoCancel(true)
                .setColor(color)
                .setVibrate(vibrate)
                .setGroup(group)
                .setContentIntent(pendingIntent)
                .setNumber(notificationBadgeCount)
                
            if (bitmap != null) {
                notificationBuilder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(bigText))
            } else {
                notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            }
            
            val notification = notificationBuilder.build()

            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notifId = System.currentTimeMillis().toInt()
            manager.notify(notifId, notification)

            // Update group summary
            updateGroupSummary(context, group, manager)
        }

        /**
         * Update notifikasi group summary agar tergrouping di shade.
         */
        private fun updateGroupSummary(context: Context, group: String, manager: NotificationManager) {
            val (summaryTitle, summaryBody, channelId, color) = when (group) {
                GROUP_CRITICAL -> Triple4(
                    "🔴 Peringatan Keamanan", "Ada beberapa alert keamanan dari BarSafe",
                    CHANNEL_CRITICAL, android.graphics.Color.parseColor("#FF1744")
                )
                GROUP_WARNING -> Triple4(
                    "🟡 Peringatan BarSafe", "Ada beberapa notifikasi BarSafe",
                    CHANNEL_WARNING, android.graphics.Color.parseColor("#FFEA00")
                )
                else -> Triple4(
                    "🟢 BarSafe Info", "Ada beberapa info dari BarSafe",
                    CHANNEL_INFO, android.graphics.Color.parseColor("#00E676")
                )
            }
            val summary = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(com.example.barsafe.R.drawable.ic_app_logo_barsafe)
                .setContentTitle(summaryTitle)
                .setContentText(summaryBody)
                .setStyle(NotificationCompat.InboxStyle().setSummaryText("BarSafe"))
                .setGroup(group)
                .setGroupSummary(true)
                .setColor(color)
                .setAutoCancel(true)
                .build()
            manager.notify(group.hashCode(), summary)
        }

        /**
         * Pastikan semua channel sudah dibuat (idempotent).
         */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            val channels = listOf(
                NotificationChannel(FOREGROUND_CHANNEL_ID, "Sistem Monitor BarSafe", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Notifikasi tetap agar monitoring bekerja di latar belakang"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_CRITICAL, "🔴 Peringatan Kritis", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Intrusi, maling, gerakan mencurigakan — darurat!"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500)
                    lightColor = android.graphics.Color.RED
                    enableLights(true)
                },
                NotificationChannel(CHANNEL_WARNING, "🟡 Peringatan", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Gagal login, getaran ringan, mode offline"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300)
                    lightColor = android.graphics.Color.YELLOW
                    enableLights(true)
                },
                NotificationChannel(CHANNEL_INFO, "🟢 Informasi", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Info sistem BarSafe"
                    setShowBadge(false)
                },
                NotificationChannel(UNLOCK_SUCCESS_CHANNEL_ID, "Brankas Terbuka", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notifikasi saat brankas berhasil dibuka via Face Recognition"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 100, 300)
                    lightColor = android.graphics.Color.GREEN
                    enableLights(true)
                }
            )
            channels.forEach { manager.createNotificationChannel(it) }
        }

        private data class Triple4(val a: String, val b: String, val c: String, val d: Int)
    }

    // ── Service fields ───────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fireDb = FirebaseDatabase.getInstance()
    private val sensorsRef = fireDb.getReference("sensors")
    private val safeRef = fireDb.getReference("safe")
    private val logsRef = fireDb.getReference("logs")

    private var currentSafeStatus = "LOCKED"
    private var isEspOnline = false
    private var serviceStartTime = 0L
    private var lastHeartbeatTimeMs = 0L
    private var lastKnownHeartbeatValue = -1L  // Track heartbeat VALUE untuk deteksi perubahan

    private val safeListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            currentSafeStatus = snapshot.child("status").getValue(String::class.java) ?: "LOCKED"
        }
        override fun onCancelled(error: DatabaseError) {}
    }

    private val sensorListener = object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val pir = snapshot.child("pir").getValue(Boolean::class.java) ?: false
            val vibration = snapshot.child("vibration").getValue(Boolean::class.java) ?: false
            val heartbeatFb = snapshot.child("heartbeat").getValue(Long::class.java) ?: 0L

            // ── Heartbeat-based online detection ──────────────────────
            // Hanya anggap ESP32 online jika heartbeat VALUE berubah
            // (bukan hanya dibaca ulang dari Firebase yang stale)
            if (heartbeatFb > 0) {
                if (lastKnownHeartbeatValue == -1L) {
                    // Data awal saat service jalan, belum bisa dipastikan ini fresh atau stale
                    lastKnownHeartbeatValue = heartbeatFb
                } else if (heartbeatFb != lastKnownHeartbeatValue) {
                    // Ada perubahan nilai sejak service jalan -> ESP32 benar-benar online
                    lastKnownHeartbeatValue = heartbeatFb
                    lastHeartbeatTimeMs = System.currentTimeMillis()
                    if (!isEspOnline) {
                        isEspOnline = true
                        updatePersistentNotification()
                    }
                }
            }
            // Jangan fallback ke wifi flag — wifi flag juga bisa stale

            // ── Hanya kirim notifikasi sensor jika ESP32 benar-benar online ──
            if (isEspOnline && currentSafeStatus in listOf("LOCKED", "ALERT")) {
                when {
                    vibration -> sendCriticalAlert(
                        context = this@BarSafeMonitorService,
                        title = "🚨 Peringatan Getaran!",
                        body = "Brankas mendeteksi getaran keras atau guncangan fisik. Kemungkinan percobaan pembobolan!",
                        rateKey = KEY_VIBRATION
                    )
                    pir -> sendWarningAlert(
                        context = this@BarSafeMonitorService,
                        title = "⚠️ Aktivitas Mencurigakan",
                        body = "PIR mendeteksi ada makhluk hidup bergerak di depan brankas.",
                        rateKey = KEY_PIR
                    )
                }
            }
        }
        override fun onCancelled(error: DatabaseError) {
            Log.e("BarSafeMonitor", "Firebase error: ${error.message}")
        }
    }

    private val logsListener = object : ChildEventListener {
        override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
            val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
            if (timestamp > serviceStartTime) {
                val type = snapshot.child("type").getValue(String::class.java) ?: ""
                val level = snapshot.child("level").getValue(String::class.java) ?: "INFO"
                val imageUrl = snapshot.child("imageUrl").getValue(String::class.java) ?: ""
                val description = snapshot.child("description").getValue(String::class.java) ?: ""
                
                val isPintuTerbuka = description.contains("terbuka", ignoreCase = true)
                val isPintuTertutup = description.contains("ditutup", ignoreCase = true)
                
                if (type.equals("Anomali", ignoreCase = true) || level == "CRITICAL") {
                    sendCriticalAlert(
                        context = this@BarSafeMonitorService,
                        title = "🚨 Anomali Terdeteksi",
                        body = description.ifBlank { "Terdapat gerakan mencurigakan." },
                        imageUrl = imageUrl,
                        rateKey = "notif_anomaly_photo"
                    )
                } else if (level == "WARNING" || isPintuTerbuka) {
                    sendWarningAlert(
                        context = this@BarSafeMonitorService,
                        title = "⚠️ Peringatan Brankas",
                        body = description,
                        rateKey = "notif_warning_${System.currentTimeMillis()}" // Unik agar lolos rate limiter (notif sekali)
                    )
                } else if (level == "INFO" && isPintuTertutup) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    scope.launch {
                        showNotification(
                            context = this@BarSafeMonitorService,
                            channelId = CHANNEL_INFO,
                            group = GROUP_INFO,
                            title = "Pindah Status",
                            body = description,
                            bigText = description,
                            color = android.graphics.Color.parseColor("#00E676"),
                            priority = NotificationCompat.PRIORITY_DEFAULT,
                            vibrate = longArrayOf(0, 200),
                            deepLink = "home"
                        )
                    }
                }
            }
        }
        override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
        override fun onChildRemoved(snapshot: DataSnapshot) {}
        override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
        override fun onCancelled(error: DatabaseError) {}
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isServiceRunning) {
            startForeground(NOTIFICATION_ID, buildPersistentNotification(false))
            isServiceRunning = true
            serviceStartTime = System.currentTimeMillis() - 5000L
            safeRef.addValueEventListener(safeListener)
            sensorsRef.addValueEventListener(sensorListener)
            logsRef.limitToLast(2).addChildEventListener(logsListener)
            
            serviceScope.launch {
                while (isServiceRunning) {
                    kotlinx.coroutines.delay(10000)
                    if (lastHeartbeatTimeMs > 0L) {
                        val isNowOnline = (System.currentTimeMillis() - lastHeartbeatTimeMs) < 12000
                        if (isNowOnline != isEspOnline) {
                            isEspOnline = isNowOnline
                            updatePersistentNotification()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        safeRef.removeEventListener(safeListener)
        sensorsRef.removeEventListener(sensorListener)
        logsRef.removeEventListener(logsListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updatePersistentNotification() {
            if (!isServiceRunning) return
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildPersistentNotification(isEspOnline))
    }

    private fun buildPersistentNotification(isOnline: Boolean): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (isOnline) "Koneksi ESP32 Terhubung" else "Koneksi ESP32 Terputus"
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(com.example.barsafe.R.drawable.ic_app_logo_barsafe)
            .setContentTitle("BarSafe Aktif")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
