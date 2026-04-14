package com.lui.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DigestDao {

    @Insert
    suspend fun insert(entry: DigestEntity)

    @Query("SELECT * FROM digest WHERE bucket = 'NOISE' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getDigest(limit: Int = 100): List<DigestEntity>

    @Query("SELECT * FROM digest WHERE bucket = 'AUTO_ACTION' AND code IS NOT NULL ORDER BY timestamp DESC LIMIT :limit")
    suspend fun get2faCodes(limit: Int = 10): List<DigestEntity>

    @Query("SELECT * FROM digest WHERE timestamp > :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getSince(since: Long, limit: Int = 100): List<DigestEntity>

    @Query("SELECT * FROM digest WHERE timestamp > :since AND app LIKE :appPattern ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getSinceForApp(since: Long, appPattern: String, limit: Int = 100): List<DigestEntity>

    @Query("DELETE FROM digest WHERE bucket = 'NOISE'")
    suspend fun clearDigest()

    @Query("DELETE FROM digest WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM digest WHERE bucket = 'NOISE'")
    suspend fun digestCount(): Int
}
