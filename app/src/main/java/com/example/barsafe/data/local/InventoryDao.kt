package com.example.barsafe.data.local

import androidx.room.*
import com.example.barsafe.data.model.InventoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    @Query("SELECT * FROM inventory_items ORDER BY addedAt DESC")
    fun getAllFlow(): Flow<List<InventoryItem>>

    @Query("SELECT * FROM inventory_items WHERE id = :id")
    suspend fun getById(id: Int): InventoryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: InventoryItem): Long

    @Update
    suspend fun update(item: InventoryItem)

    @Delete
    suspend fun delete(item: InventoryItem)

    @Query("DELETE FROM inventory_items WHERE id = :id")
    suspend fun deleteById(id: Int): Int

    @Query("SELECT COUNT(*) FROM inventory_items")
    suspend fun count(): Int
}
