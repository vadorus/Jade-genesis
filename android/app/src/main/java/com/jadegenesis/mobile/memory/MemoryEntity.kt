package com.jadegenesis.mobile.memory

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "memory_events",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["type"]),
        Index(value = ["source"]),
        Index(value = ["lastRecalledAt"]),
        Index(value = ["supersededBy"])
    ]
)
data class MemoryEntity(
    @PrimaryKey
    val id: String,
    val type: String,
    val content: String,
    val source: String,
    val confidence: Double,
    val originNode: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0")
    val lastRecalledAt: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val recallCount: Int = 0,
    val verifiedAt: Long? = null,
    val supersededBy: String? = null
)
