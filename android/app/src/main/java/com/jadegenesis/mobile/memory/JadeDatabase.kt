package com.jadegenesis.mobile.memory

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase

@Database(
    entities = [MemoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class JadeDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: JadeDatabase? = null

        fun get(context: Context): JadeDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    JadeDatabase::class.java,
                    "jade_memory.db"
                ).build().also { INSTANCE = it }
            }
    }
}
