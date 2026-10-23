package com.jadegenesis.mobile.memory

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

@Database(
    entities = [MemoryEntity::class, UserCorrection::class],
    version = 3,
    exportSchema = false
)
abstract class JadeDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun correctionDao(): CorrectionDao

    companion object {
        @Volatile
        private var INSTANCE: JadeDatabase? = null

        private val MIGRATION_1_2 = Migration(1, 2) { connection ->
            connection.execSQL(
                "ALTER TABLE memory_events ADD COLUMN lastRecalledAt INTEGER NOT NULL DEFAULT 0"
            )
            connection.execSQL(
                "ALTER TABLE memory_events ADD COLUMN recallCount INTEGER NOT NULL DEFAULT 0"
            )
            connection.execSQL(
                "ALTER TABLE memory_events ADD COLUMN verifiedAt INTEGER"
            )
            connection.execSQL(
                "ALTER TABLE memory_events ADD COLUMN supersededBy TEXT"
            )

            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memory_events_createdAt " +
                    "ON memory_events(createdAt)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memory_events_type " +
                    "ON memory_events(type)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memory_events_source " +
                    "ON memory_events(source)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memory_events_lastRecalledAt " +
                    "ON memory_events(lastRecalledAt)"
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_memory_events_supersededBy " +
                    "ON memory_events(supersededBy)"
            )
        }

        internal val MIGRATION_2_3 = Migration(2, 3) { connection ->
            connection.execSQL("CREATE TABLE IF NOT EXISTS user_corrections (id TEXT NOT NULL PRIMARY KEY, question TEXT NOT NULL, answerRef TEXT NOT NULL, wrongAnswer TEXT NOT NULL, fact TEXT NOT NULL, createdAt INTEGER NOT NULL, state TEXT NOT NULL, targets TEXT NOT NULL)")
        }

        fun get(context: Context): JadeDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JadeDatabase::class.java,
                    "jade_memory.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
