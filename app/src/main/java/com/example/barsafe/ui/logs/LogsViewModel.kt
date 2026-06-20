package com.example.barsafe.ui.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.barsafe.data.local.AppDatabase
import com.example.barsafe.data.remote.FirebaseLog
import com.example.barsafe.data.remote.FirebaseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.example.barsafe.navigation.dataStore
import androidx.datastore.preferences.core.edit

enum class LogFilter { ALL, ACCESS, ANOMALY }

class LogsViewModel(application: Application) : AndroidViewModel(application) {

    private val firebaseRepo = FirebaseRepository()
    private val db = AppDatabase.getInstance(application)

    val firebaseLogs: StateFlow<List<FirebaseLog>> = firebaseRepo.recentLogsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localLogs = db.anomalyLogDao().getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _filter = MutableStateFlow(LogFilter.ALL)
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    fun setFilter(f: LogFilter) { _filter.value = f }

    fun filteredLogs(logs: List<FirebaseLog>, filter: LogFilter): List<FirebaseLog> {
        return when (filter) {
            LogFilter.ALL -> logs
            LogFilter.ACCESS -> logs.filter { it.type.contains("ACCESS", true) || it.type.contains("UNLOCK", true) || it.type.contains("Sistem", true) }
            LogFilter.ANOMALY -> logs.filter { it.type.contains("ANOMALY", true) || it.type.contains("INTRUSION", true) || it.type.contains("Anomali", true) }
        }
    }

    private val LAST_READ_TIMESTAMP = androidx.datastore.preferences.core.longPreferencesKey("last_read_timestamp")

    val lastReadTimestamp: StateFlow<Long> = getApplication<Application>().dataStore.data
        .map { prefs -> prefs[LAST_READ_TIMESTAMP] ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    fun markAllAsRead() {
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[LAST_READ_TIMESTAMP] = System.currentTimeMillis()
            }
        }
    }

    fun deleteLog(log: FirebaseLog) {
        viewModelScope.launch(Dispatchers.IO) {
            firebaseRepo.deleteLog(log.key)
        }
    }
}
