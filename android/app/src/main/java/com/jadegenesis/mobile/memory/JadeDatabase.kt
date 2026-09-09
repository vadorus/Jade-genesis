package com.jadegenesis.mobile.memory

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

@Database(
    entities = [MemoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class JadeDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

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

        fun get(context: Context): JadeDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JadeDatabase::class.java,
                    "jade_memory.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
