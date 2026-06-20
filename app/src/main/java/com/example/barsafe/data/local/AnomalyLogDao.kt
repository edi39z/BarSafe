package com.example.barsafe.data.local

import androidx.room.*
import com.example.barsafe.data.model.AnomalyLog
import kotlinx.coroutines.flow.Flow

@Dao
interface AnomalyLogDao {

    @Query("SELECT * FROM anomaly_logs ORDER BY timestamp DESC LIMIT 100")
    fun getAllFlow(): Flow<List<AnomalyLog>>

    @Query("SELECT * FROM anomaly_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<AnomalyLog>

    @Insert
    suspend fun insert(log: AnomalyLog): Long

    @Query("DELETE FROM anomaly_logs WHERE id = :id")
    suspend fun deleteById(id: Int): Int

    @Query("DELETE FROM anomaly_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM anomaly_logs")
    suspend fun deleteAll(): Int
}
