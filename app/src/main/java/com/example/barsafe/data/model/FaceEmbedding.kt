package com.example.barsafe.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "face_embeddings")
data class FaceEmbedding(
    @PrimaryKey val personId: String,
    val name: String,
    val photoUrl: String = "",
    val embedding: FloatArray = FloatArray(0),
    val enrolledAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceEmbedding) return false
        return personId == other.personId
    }

    override fun hashCode(): Int = personId.hashCode()
}
