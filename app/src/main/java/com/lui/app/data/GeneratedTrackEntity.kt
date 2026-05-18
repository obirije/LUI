package com.lui.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single AI-generated music track saved to the library.
 *
 * The file lives under `filesDir/generated_music/` (persistent, unlike the
 * prior cache location). [filename] is the basename only; the absolute path
 * is reconstructed by the caller so the storage layout stays swap-able.
 */
@Entity(tableName = "generated_tracks")
data class GeneratedTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filename: String,
    val displayName: String,           // user-editable; defaults to a short slice of the prompt
    val prompt: String,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
