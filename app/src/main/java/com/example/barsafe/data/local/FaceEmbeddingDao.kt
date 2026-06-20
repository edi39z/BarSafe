package com.example.barsafe.data.local

import androidx.room.*
import com.example.barsafe.data.model.FaceEmbedding
import kotlinx.coroutines.flow.Flow

@Dao
interface FaceEmbeddingDao {

    @Query("SELECT * FROM face_embeddings ORDER BY enrolledAt DESC")
    fun getAllFlow(): Flow<List<FaceEmbedding>>

    @Query("SELECT * FROM face_embeddings ORDER BY enrolledAt DESC")
    suspend fun getAll(): List<FaceEmbedding>

    @Query("SELECT * FROM face_embeddings WHERE personId = :personId")
    suspend fun getById(personId: String): FaceEmbedding?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(faceEmbedding: FaceEmbedding)

    @Delete
    suspend fun delete(faceEmbedding: FaceEmbedding)

    @Query("DELETE FROM face_embeddings WHERE personId = :personId")
    suspend fun deleteById(personId: String): Int

    @Query("SELECT COUNT(*) FROM face_embeddings")
    suspend fun count(): Int
}
