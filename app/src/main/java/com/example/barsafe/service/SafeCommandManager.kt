package com.example.barsafe.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.barsafe.data.local.AnomalyLogDao
import com.example.barsafe.data.local.PendingCommandDao
import com.example.barsafe.data.model.AnomalyLog
import com.example.barsafe.data.model.PendingCommand
import com.example.barsafe.data.remote.FirebaseRepository
import com.example.barsafe.data.remote.UnlockResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SafeCommandManager — koordinator tunggal untuk semua perintah ke brankas.
 *
 * Bertanggung jawab atas:
 * 1. Kirim perintah UNLOCK ke Firebase + tunggu ACK dari ESP32
 * 2. Jika offline → simpan ke Room pending_commands
 * 3. Saat koneksi pulih → retry semua pending commands (FIFO)
 * 4. Update state untuk UI via StateFlow
 */
class SafeCommandManager(
    private val context: Context,
    private val firebaseRepo: FirebaseRepository,
    private val anomalyLogDao: AnomalyLogDao,
    private val pendingCommandDao: PendingCommandDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "SafeCommandManager"
    }

    // ── Pending count observable untuk UI badge ──────────────────────────
    val pendingCommandCount = pendingCommandDao.countFlow()

    // ── Network connectivity state ────────────────────────────────────────
    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isOnline.value = true
            Log.d(TAG, "Network available — retrying pending commands")
            scope.launch { retryPendingCommands() }
        }
        override fun onLost(network: Network) {
            _isOnline.value = isCurrentlyOnline()
        }
    }

    init {
        registerNetworkCallback()
        _isOnline.value = isCurrentlyOnline()
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed: ${e.message}")
        }
    }

    fun unregister() {
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    private fun isCurrentlyOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ── Unlock Command ────────────────────────────────────────────────────

    /**
     * Eksekusi perintah UNLOCK. Urutan:
     * 1. Cek koneksi
     * 2. Jika online → Firebase dengan ACK
     * 3. Jika offline → simpan ke Room, return QueuedOffline
     */
    suspend fun executeUnlock(personName: String): UnlockResult = withContext(Dispatchers.IO) {
        if (!isCurrentlyOnline()) {
            Log.w(TAG, "Offline — queueing unlock for $personName")
            pendingCommandDao.insert(PendingCommand(command = "UNLOCK", personName = personName))
            anomalyLogDao.insert(
                AnomalyLog(
                    type = "UNLOCK_QUEUED",
                    level = "WARNING",
                    personName = personName,
                    description = "Perintah buka brankas antri — tidak ada koneksi internet",
                    isSynced = false
                )
            )
            return@withContext UnlockResult.QueuedOffline(personName)
        }

        val result = firebaseRepo.sendUnlockWithAck(personName)
        handleUnlockResult(result, personName)
        result
    }

    private suspend fun handleUnlockResult(result: UnlockResult, personName: String) {
        when (result) {
            is UnlockResult.SuccessAcked -> {
                anomalyLogDao.insert(
                    AnomalyLog(
                        type = "UNLOCK_SUCCESS",
                        level = "INFO",
                        personName = personName,
                        description = "Brankas dibuka oleh $personName — solenoid dikonfirmasi (ACK ✓)",
                        isSynced = true,
                        isAcked = true
                    )
                )
                firebaseRepo.pushLog(
                    type = "UNLOCK_SUCCESS",
                    level = "INFO",
                    personName = personName,
                    description = "Brankas dibuka via Face Unlock (ACK)"
                )
                firebaseRepo.clearAck()
            }
            is UnlockResult.SuccessSent -> {
                anomalyLogDao.insert(
                    AnomalyLog(
                        type = "UNLOCK_SUCCESS",
                        level = "INFO",
                        personName = personName,
                        description = "Brankas dibuka oleh $personName — perintah terkirim (ACK timeout)",
                        isSynced = true,
                        isAcked = false
                    )
                )
                firebaseRepo.pushLog(
                    type = "UNLOCK_SUCCESS",
                    level = "INFO",
                    personName = personName,
                    description = "Brankas dibuka via Face Unlock (no ACK)"
                )
            }
            is UnlockResult.QueuedOffline -> { /* sudah dihandle di executeUnlock */ }
            is UnlockResult.Failed -> {
                anomalyLogDao.insert(
                    AnomalyLog(
                        type = "UNLOCK_FAILED",
                        level = "WARNING",
                        personName = personName,
                        description = "Gagal kirim perintah unlock: ${result.reason}"
                    )
                )
                firebaseRepo.pushLog(
                    type = "UNLOCK_FAILED",
                    level = "WARNING",
                    personName = personName,
                    description = "Gagal membuka brankas: ${result.reason}"
                )
            }
        }
    }

    // ── Offline Retry ─────────────────────────────────────────────────────

    /**
     * Coba kirim ulang semua pending commands (FIFO).
     * Dipanggil otomatis saat koneksi pulih.
     */
    private suspend fun retryPendingCommands() = withContext(Dispatchers.IO) {
        val commands = pendingCommandDao.getAll()
        if (commands.isEmpty()) return@withContext

        Log.d(TAG, "Retrying ${commands.size} pending command(s)")
        for (cmd in commands) {
            if (!isCurrentlyOnline()) {
                Log.w(TAG, "Network lost during retry. Stopping.")
                break
            }
            try {
                val result = firebaseRepo.sendUnlockWithAck(cmd.personName)
                when (result) {
                    is UnlockResult.SuccessAcked, is UnlockResult.SuccessSent -> {
                        pendingCommandDao.deleteById(cmd.id)
                        handleUnlockResult(result, cmd.personName)
                        Log.d(TAG, "Retry success for ${cmd.personName}")
                    }
                    else -> {
                        // Update retry count
                        if (cmd.retryCount >= 3) {
                            // Give up setelah 3 kali retry
                            pendingCommandDao.deleteById(cmd.id)
                            Log.w(TAG, "Gave up retrying command for ${cmd.personName} after ${cmd.retryCount} retries")
                        } else {
                            pendingCommandDao.update(cmd.copy(retryCount = cmd.retryCount + 1))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Retry failed for cmd ${cmd.id}: ${e.message}")
            }
        }
    }
}
