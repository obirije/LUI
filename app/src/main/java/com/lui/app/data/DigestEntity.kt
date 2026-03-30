package com.lui.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "digest")
data class DigestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val app: String,
    val title: String,
    val text: String,
    val bucket: String,  // URGENT, NOISE, AUTO_ACTION
    val timestamp: Long,
    val code: String? = null  // 2FA code if extracted
)
