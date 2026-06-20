package com.example.barsafe.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Perintah yang belum berhasil dikirim ke Firebase (mode offline).
 * Akan di-retry saat koneksi pulih oleh SafeCommandManager.
 */
@Entity(tableName = "pending_commands")
data class PendingCommand(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val command: String,        // "UNLOCK"
    val personName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)
