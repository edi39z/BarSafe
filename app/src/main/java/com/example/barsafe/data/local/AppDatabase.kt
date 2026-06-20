package com.example.barsafe.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.barsafe.data.model.AnomalyLog
import com.example.barsafe.data.model.FaceEmbedding
import com.example.barsafe.data.model.InventoryItem
import com.example.barsafe.data.model.PendingCommand

@Database(
    entities = [FaceEmbedding::class, InventoryItem::class, AnomalyLog::class, PendingCommand::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun faceEmbeddingDao(): FaceEmbeddingDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun anomalyLogDao(): AnomalyLogDao
    abstract fun pendingCommandDao(): PendingCommandDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Migrasi v1 → v2: tambah kolom level & isAcked di anomaly_logs, dan tabel pending_commands */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Tambah kolom baru ke anomaly_logs
                db.execSQL("ALTER TABLE anomaly_logs ADD COLUMN level TEXT NOT NULL DEFAULT 'INFO'")
                db.execSQL("ALTER TABLE anomaly_logs ADD COLUMN isAcked INTEGER NOT NULL DEFAULT 0")
                // Buat tabel pending_commands
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_commands (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        command TEXT NOT NULL,
                        personName TEXT NOT NULL DEFAULT '',
                        timestamp INTEGER NOT NULL,
                        retryCount INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "barsafe_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
