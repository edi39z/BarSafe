package com.example.barsafe.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "anomaly_logs")
data class AnomalyLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val type: String,           // "ACCESS", "UNLOCK_SUCCESS", "UNLOCK_FAILED", "ANOMALY", "INTRUSION"
    val level: String = "INFO", // "CRITICAL", "WARNING", "INFO"
    val personName: String = "",
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val isAcked: Boolean = false  // true jika solenoid sudah konfirmasi (ACK dari ESP32)
)
