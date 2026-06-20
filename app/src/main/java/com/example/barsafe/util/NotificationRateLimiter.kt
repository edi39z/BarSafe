package com.example.barsafe.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.example.barsafe.navigation.dataStore
import kotlinx.coroutines.flow.first

/**
 * Rate limiter untuk notifikasi — mencegah spam notifikasi jenis yang sama
 * dalam interval tertentu. Tersimpan di DataStore agar persisten antar session.
 *
 * Penggunaan:
 *   val limiter = NotificationRateLimiter(context)
 *   if (limiter.canSend("alert_vibration", minIntervalMs = 30_000)) {
 *       showNotification(...)
 *       limiter.markSent("alert_vibration")
 *   }
 */
class NotificationRateLimiter(private val context: Context) {

    companion object {
        // Default intervals per level (ms)
        const val CRITICAL_INTERVAL_MS = 15_000L  // 15 detik
        const val WARNING_INTERVAL_MS  = 30_000L  // 30 detik
        const val INFO_INTERVAL_MS     = 60_000L  // 60 detik

        fun intervalForLevel(level: String) = when (level.uppercase()) {
            "CRITICAL" -> CRITICAL_INTERVAL_MS
            "WARNING"  -> WARNING_INTERVAL_MS
            else       -> INFO_INTERVAL_MS
        }
    }

    /**
     * Cek apakah notifikasi dengan [channelKey] boleh dikirim.
     * @param channelKey identifier unik per jenis notifikasi (contoh: "alert_pir", "unlock_success")
     * @param minIntervalMs minimum jeda antar pengiriman dalam milidetik
     */
    suspend fun canSend(channelKey: String, minIntervalMs: Long): Boolean {
        val key = longPreferencesKey("notif_last_sent_$channelKey")
        val prefs = context.dataStore.data.first()
        val lastSent = prefs[key] ?: 0L
        return (System.currentTimeMillis() - lastSent) >= minIntervalMs
    }

    /**
     * Catat bahwa notifikasi dengan [channelKey] baru saja dikirim.
     */
    suspend fun markSent(channelKey: String) {
        val key = longPreferencesKey("notif_last_sent_$channelKey")
        context.dataStore.edit { it[key] = System.currentTimeMillis() }
    }

    /**
     * Reset rate limit untuk [channelKey] (misalnya saat user dismiss notif).
     */
    suspend fun reset(channelKey: String) {
        val key = longPreferencesKey("notif_last_sent_$channelKey")
        context.dataStore.edit { it.remove(key) }
    }
}
