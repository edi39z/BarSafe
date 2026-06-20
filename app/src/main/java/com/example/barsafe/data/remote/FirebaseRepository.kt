package com.example.barsafe.data.remote

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class SafeState(
    val status: String = "UNKNOWN",   // "LOCKED", "UNLOCKED", "ALERT"
    val lastAccess: Long = 0L,
    val lastPerson: String = "",
    val isAcked: Boolean = false       // true jika ESP32 sudah konfirmasi perintah terakhir
)

data class SensorState(
    val pirDetected: Boolean = false,
    val vibrationDetected: Boolean = false,
    val doorClosed: Boolean = true,
    val wifiConnected: Boolean = true,
    val heartbeat: Long = 0L  // epoch millis dari heartbeat ESP32 terakhir
)

data class FirebaseLog(
    val key: String = "",
    val type: String = "",
    val level: String = "INFO",        // "CRITICAL", "WARNING", "INFO"
    val personName: String = "",
    val description: String = "",
    val timestamp: Long = 0L,
    val imageUrl: String = "",
    val imageUrls: List<String> = emptyList()
)

/** Hasil dari operasi unlock dengan ACK */
sealed class UnlockResult {
    /** Perintah terkirim dan solenoid sudah konfirmasi (ACK dari ESP32) */
    data class SuccessAcked(val personName: String) : UnlockResult()

    /** Perintah terkirim ke Firebase, tapi ACK timeout (solenoid mungkin sudah buka) */
    data class SuccessSent(val personName: String) : UnlockResult()

    /** Tidak ada koneksi internet — perintah disimpan offline queue */
    data class QueuedOffline(val personName: String) : UnlockResult()

    /** Firebase tersedia tapi operasi gagal */
    data class Failed(val reason: String) : UnlockResult()
}

/**
 * Data kelas yang menyimpan latency detail dari proses Firebase.
 * Digunakan untuk tabel pengujian latency di laporan skripsi.
 */
data class FirebaseLatencyBreakdown(
    /** Waktu (ms) untuk menulis perintah UNLOCK ke Firebase RTDB */
    val firebaseWriteMs: Long = 0L,
    /** Waktu (ms) menunggu ACK dari ESP32 via Firebase listener */
    val ackWaitMs: Long = 0L,
    /** Total waktu (ms) dari mulai write sampai ACK diterima/timeout */
    val totalFirebaseSyncMs: Long = 0L,
    /** Apakah ACK berhasil diterima (true) atau timeout (false) */
    val ackReceived: Boolean = false
)

class FirebaseRepository {

    private val db = FirebaseDatabase.getInstance()
    private val safeRef = db.getReference("safe")
    private val sensorsRef = db.getReference("sensors")
    private val logsRef = db.getReference("logs")

    companion object {
        private const val TAG = "FirebaseRepository"
        private const val ACK_TIMEOUT_MS = 8_000L  // 8 detik tunggu konfirmasi solenoid
    }

    /** Latency breakdown terakhir dari operasi sendUnlockWithAck */
    var lastLatencyBreakdown: FirebaseLatencyBreakdown = FirebaseLatencyBreakdown()
        private set

    /** Real-time safe status stream */
    val safeStateFlow: Flow<SafeState> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val state = SafeState(
                    status = snapshot.child("status").getValue(String::class.java) ?: "LOCKED",
                    lastAccess = snapshot.child("lastAccess").getValue(Long::class.java) ?: 0L,
                    lastPerson = snapshot.child("lastPerson").getValue(String::class.java) ?: "",
                    isAcked = snapshot.child("ack").getValue(Boolean::class.java) ?: false
                )
                trySend(state)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "safeStateFlow cancelled: ${error.message}")
            }
        }
        safeRef.addValueEventListener(listener)
        awaitClose { safeRef.removeEventListener(listener) }
    }

    /** Real-time sensor status stream */
    val sensorStateFlow: Flow<SensorState> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val heartbeatVal = snapshot.child("heartbeat").getValue(Long::class.java) ?: 0L

                val state = SensorState(
                    pirDetected = snapshot.child("pir").getValue(Boolean::class.java) ?: false,
                    vibrationDetected = snapshot.child("vibration").getValue(Boolean::class.java) ?: false,
                    doorClosed = snapshot.child("doorClosed").getValue(Boolean::class.java) ?: true,
                    wifiConnected = snapshot.child("wifi").getValue(Boolean::class.java) ?: true,
                    heartbeat = heartbeatVal
                )
                trySend(state)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        sensorsRef.addValueEventListener(listener)
        awaitClose { sensorsRef.removeEventListener(listener) }
    }

    /** Real-time logs stream (last 50), sorted newest first */
    val recentLogsFlow: Flow<List<FirebaseLog>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.map { child ->
                    val singleImageUrl = child.child("imageUrl").getValue(String::class.java) ?: ""
                    val urls = mutableListOf<String>()
                    if (singleImageUrl.isNotBlank()) urls.add(singleImageUrl)
                    child.child("imageUrl2").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                    child.child("imageUrl3").getValue(String::class.java)?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                    
                    FirebaseLog(
                        key = child.key ?: "",
                        type = child.child("type").getValue(String::class.java) ?: "",
                        level = child.child("level").getValue(String::class.java) ?: "INFO",
                        personName = child.child("personName").getValue(String::class.java) ?: "",
                        description = child.child("description").getValue(String::class.java) ?: "",
                        timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L,
                        imageUrl = singleImageUrl,
                        imageUrls = urls
                    )
                }.sortedByDescending { it.timestamp }.take(50)
                trySend(list)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        logsRef.limitToLast(50).addValueEventListener(listener)
        awaitClose { logsRef.removeEventListener(listener) }
    }

    /**
     * Kirim perintah UNLOCK ke ESP32 via Firebase RTDB dan tunggu ACK.
     * Timeout: 8 detik. Jika ACK tidak datang, return [UnlockResult.SuccessSent].
     */
    suspend fun sendUnlockWithAck(personName: String): UnlockResult {
        return try {
            val totalStart = System.currentTimeMillis()

            // 1. Reset ack node dulu supaya tidak terpakai nilai lama
            safeRef.child("ack").setValue(false).await()

            // 2. Kirim perintah unlock
            val writeStart = System.currentTimeMillis()
            val commandData = mapOf(
                "command" to "UNLOCK",
                "requestedBy" to personName,
                "timestamp" to System.currentTimeMillis(),
                "ack" to false
            )
            safeRef.updateChildren(commandData).await()
            val writeEnd = System.currentTimeMillis()
            val firebaseWriteLatency = writeEnd - writeStart

            Log.i(TAG, "[LATENCY] Firebase Write (UNLOCK command): ${firebaseWriteLatency}ms")

            // 3. Tunggu ACK dari ESP32 (max 8 detik)
            val ackWaitStart = System.currentTimeMillis()
            val acked = withTimeoutOrNull(ACK_TIMEOUT_MS) {
                observeAck()
            } ?: false
            val ackWaitEnd = System.currentTimeMillis()
            val ackWaitLatency = ackWaitEnd - ackWaitStart
            val totalLatency = ackWaitEnd - totalStart

            // Simpan breakdown latency
            lastLatencyBreakdown = FirebaseLatencyBreakdown(
                firebaseWriteMs = firebaseWriteLatency,
                ackWaitMs = ackWaitLatency,
                totalFirebaseSyncMs = totalLatency,
                ackReceived = acked
            )

            Log.i(TAG, "[LATENCY] ACK Wait: ${ackWaitLatency}ms (received=$acked) | " +
                "Total Firebase Sync: ${totalLatency}ms")
            Log.i(TAG, "════════════════════════════════════════════")
            Log.i(TAG, "  FIREBASE LATENCY BREAKDOWN")
            Log.i(TAG, "  Firebase Write    : ${firebaseWriteLatency} ms")
            Log.i(TAG, "  ACK Wait (ESP32)  : ${ackWaitLatency} ms (${if (acked) "✓ Received" else "✗ Timeout"})")
            Log.i(TAG, "  Total Sync        : ${totalLatency} ms")
            Log.i(TAG, "════════════════════════════════════════════")

            if (acked) UnlockResult.SuccessAcked(personName)
            else UnlockResult.SuccessSent(personName)

        } catch (e: Exception) {
            Log.e(TAG, "sendUnlockWithAck failed: ${e.message}")
            lastLatencyBreakdown = FirebaseLatencyBreakdown()
            UnlockResult.Failed(e.message ?: "Unknown error")
        }
    }

    /**
     * Poll node `safe/ack` sekali — tunggu sampai nilainya true.
     * Dipanggil di dalam withTimeoutOrNull.
     */
    private suspend fun observeAck(): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val ackRef = safeRef.child("ack")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val acked = snapshot.getValue(Boolean::class.java) ?: false
                if (acked && cont.isActive) {
                    cont.resume(true) {}
                }
            }
            override fun onCancelled(error: DatabaseError) {
                if (cont.isActive) cont.resume(false) {}
            }
        }
        ackRef.addValueEventListener(listener)
        cont.invokeOnCancellation { ackRef.removeEventListener(listener) }
    }

    /** Reset node ACK di Firebase setelah diproses */
    suspend fun clearAck() {
        try {
            safeRef.child("ack").setValue(false).await()
        } catch (_: Exception) {}
    }

    /** Legacy: kirim unlock tanpa ACK (untuk backward compat jika perlu) */
    suspend fun sendUnlockCommand(personName: String): Boolean {
        return try {
            val commandData = mapOf(
                "command" to "UNLOCK",
                "requestedBy" to personName,
                "timestamp" to System.currentTimeMillis()
            )
            safeRef.updateChildren(commandData).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Push a log entry to Firebase RTDB dengan level */
    suspend fun pushLog(
        type: String,
        level: String = "INFO",
        personName: String = "",
        description: String
    ) {
        try {
            val logData = mapOf(
                "type" to type,
                "level" to level,
                "personName" to personName,
                "description" to description,
                "timestamp" to System.currentTimeMillis()
            )
            logsRef.push().setValue(logData).await()
        } catch (_: Exception) {}
    }

    /** Clear all logs manually */
    suspend fun clearLogs() {
        try { logsRef.removeValue().await() } catch (_: Exception) {}
    }

    suspend fun deleteLog(key: String) {
        if (key.isBlank()) return
        try { logsRef.child(key).removeValue().await() } catch (_: Exception) {}
    }

    /** Memaksa sinkronisasi ulang Firebase dengan memutus dan menyambung kembali koneksi */
    fun reconnect() {
        FirebaseDatabase.getInstance().goOffline()
        FirebaseDatabase.getInstance().goOnline()
    }
}
