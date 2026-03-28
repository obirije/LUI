package com.lui.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ChatMessageEntity::class], version = 1, exportSchema = false)
abstract class LuiDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile private var INSTANCE: LuiDatabase? = null

        fun getInstance(context: Context): LuiDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, LuiDatabase::class.java, "lui_db")
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
