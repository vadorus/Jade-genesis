package com.jadegenesis.mobile.memory

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "memory_events")
data class MemoryEntity(
    @PrimaryKey
    val id: String,
    val type: String,
    val content: String,
    val source: String,
    val confidence: Double,
    val originNode: String,
    val createdAt: Long
)
