package com.lui.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface TriggerDao {
    @Insert
    suspend fun insert(trigger: TriggerEntity): Long

    @Update
    suspend fun update(trigger: TriggerEntity)

    @Delete
    suspend fun delete(trigger: TriggerEntity)

    @Query("SELECT * FROM triggers WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getActive(): List<TriggerEntity>

    @Query("SELECT * FROM triggers ORDER BY createdAt DESC")
    suspend fun getAll(): List<TriggerEntity>

    @Query("SELECT * FROM triggers WHERE id = :id")
    suspend fun getById(id: Long): TriggerEntity?

    @Query("SELECT * FROM triggers WHERE type = 'geofence' AND enabled = 1")
    suspend fun getActiveGeofences(): List<TriggerEntity>

    @Query("SELECT * FROM triggers WHERE type = 'scheduled' AND enabled = 1")
    suspend fun getActiveScheduled(): List<TriggerEntity>

    @Query("DELETE FROM triggers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM triggers WHERE name LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<TriggerEntity>
}
