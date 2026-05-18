package com.lui.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GeneratedTrackDao {

    @Insert
    suspend fun insert(track: GeneratedTrackEntity): Long

    /** Reactive list — favorites first, then newest. Hub UI observes this. */
    @Query("SELECT * FROM generated_tracks ORDER BY favorite DESC, createdAt DESC")
    fun observeAll(): Flow<List<GeneratedTrackEntity>>

    @Query("SELECT * FROM generated_tracks WHERE id = :id")
    suspend fun getById(id: Long): GeneratedTrackEntity?

    @Query("UPDATE generated_tracks SET displayName = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE generated_tracks SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("DELETE FROM generated_tracks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
