package com.example.barsafe.data.local

import androidx.room.*
import com.example.barsafe.data.model.PendingCommand
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingCommandDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(command: PendingCommand): Long

    @Query("SELECT * FROM pending_commands ORDER BY timestamp ASC")
    suspend fun getAll(): List<PendingCommand>

    @Query("SELECT COUNT(*) FROM pending_commands")
    fun countFlow(): Flow<Int>

    @Query("DELETE FROM pending_commands WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM pending_commands")
    suspend fun deleteAll()

    @Update
    suspend fun update(command: PendingCommand)
}
