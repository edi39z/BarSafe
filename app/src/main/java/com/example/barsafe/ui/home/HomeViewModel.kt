package com.example.barsafe.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.barsafe.data.local.AppDatabase
import com.example.barsafe.data.remote.FirebaseRepository
import com.example.barsafe.data.remote.SafeState
import com.example.barsafe.data.remote.SensorState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import com.example.barsafe.navigation.dataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val firebaseRepo = FirebaseRepository()
    private val db = AppDatabase.getInstance(application)
    
    var hasShownInventoryDialog = false

    private val LAST_READ_TIMESTAMP = androidx.datastore.preferences.core.longPreferencesKey("last_read_timestamp")
    private val lastReadTimestamp: StateFlow<Long> = getApplication<Application>().dataStore.data
        .map { prefs -> prefs[LAST_READ_TIMESTAMP] ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val safeState: StateFlow<SafeState> = firebaseRepo.safeStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SafeState())

    private val rawSensorState: StateFlow<SensorState> = firebaseRepo.sensorStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SensorState())

    // ── Heartbeat-based ESP32 Online Detection ─────────────────────────
    companion object {
        private const val HEARTBEAT_TIMEOUT_MS = 25_000L // 25 detik = toleransi offline (ESP32 kirim tiap 10 detik)
    }

    /**
     * Track kapan terakhir kali heartbeat VALUE berubah (bukan kapan listener pertama kali baca).
     * Ini penting karena saat app baru buka, Firebase mengirim nilai stale dari session lama.
     * Kita hanya menganggap ESP32 online jika heartbeat VALUE berubah dalam 35 detik terakhir.
     */
    private var lastKnownHeartbeat = -1L
    private val _lastHeartbeatChangeTime = MutableStateFlow(0L)

    /** Ticker yang periodik trigger re-evaluation setiap 10 detik */
    private val _heartbeatTicker = MutableStateFlow(0L)

    /** Apakah ESP32 benar-benar online (heartbeat berubah dalam 35 detik terakhir) */
    val isEspOnline: StateFlow<Boolean> = combine(
        _lastHeartbeatChangeTime, _heartbeatTicker
    ) { lastChange, _ ->
        if (lastChange == 0L) false  // Belum pernah terima heartbeat change → offline
        else (System.currentTimeMillis() - lastChange) < HEARTBEAT_TIMEOUT_MS
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Sensor state yang sudah divalidasi: jika ESP32 offline, sensor di-reset ke "aman".
     * Ini mencegah PIR "Terdeteksi!" dari state basi Firebase.
     */
    val sensorState: StateFlow<SensorState> = combine(
        rawSensorState, isEspOnline
    ) { raw, online ->
        if (online) {
            raw // ESP32 online → tampilkan nilai asli dari Firebase
        } else {
            // ESP32 offline → semua sensor dianggap "aman" / tidak aktif
            SensorState(
                pirDetected = false,
                vibrationDetected = false,
                wifiConnected = false,
                heartbeat = raw.heartbeat
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SensorState())

    init {
        // Monitor perubahan heartbeat value
        viewModelScope.launch {
            rawSensorState.collect { state ->
                val hb = state.heartbeat
                if (hb > 0L) {
                    if (lastKnownHeartbeat == -1L) {
                        // Data pertama kali di-load dari Firebase (mungkin stale dari sesi sebelumnya)
                        lastKnownHeartbeat = hb
                    } else if (hb != lastKnownHeartbeat) {
                        // Heartbeat VALUE benar-benar berubah sejak app dibuka → ESP32 sedang online
                        lastKnownHeartbeat = hb
                        _lastHeartbeatChangeTime.value = System.currentTimeMillis()
                    }
                }
            }
        }

        // Periodic ticker untuk re-evaluate online status setiap 10 detik
        viewModelScope.launch {
            while (true) {
                delay(10_000L)
                _heartbeatTicker.value = System.currentTimeMillis()
            }
        }
    }

    val recentLogs = firebaseRepo.recentLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadLogsCount: StateFlow<Int> = combine(firebaseRepo.recentLogsFlow, lastReadTimestamp) { logs, lastRead ->
        logs.count { it.timestamp > lastRead }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val enrolledFaces = db.faceEmbeddingDao().getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Jumlah perintah offline yang belum terkirim */
    val pendingCommandCount: StateFlow<Int> = db.pendingCommandDao().countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun forceRefresh() {
        firebaseRepo.reconnect()
        // Pancing ticker agar isEspOnline terevaluasi ulang dengan lebih cepat
        _heartbeatTicker.value = System.currentTimeMillis()
    }

    fun logInventoryActivity(itemName: String, type: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val description = "Barang $type: $itemName"
            db.anomalyLogDao().insert(
                com.example.barsafe.data.model.AnomalyLog(
                    type = "ACCESS",
                    level = "INFO",
                    description = description,
                    personName = safeState.value.lastPerson.ifBlank { "Pengguna" },
                    timestamp = System.currentTimeMillis()
                )
            )
            if (type.equals("MASUK", ignoreCase = true)) {
                db.inventoryDao().insert(
                    com.example.barsafe.data.model.InventoryItem(
                        name = itemName,
                        description = "Ditambahkan otomatis",
                        category = "Umum"
                    )
                )
            }
        }
    }
}
