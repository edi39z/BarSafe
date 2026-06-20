package com.example.barsafe.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "inventory_items")
data class InventoryItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val description: String = "",
    val category: String = "Umum",
    val photoUrl: String = "",
    val estimatedValue: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
