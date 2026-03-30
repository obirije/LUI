package com.lui.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ChatMessageEntity::class, DigestEntity::class], version = 2, exportSchema = false)
abstract class LuiDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun digestDao(): DigestDao

    companion object {
        @Volatile private var INSTANCE: LuiDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS digest (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        app TEXT NOT NULL,
                        title TEXT NOT NULL,
                        text TEXT NOT NULL,
                        bucket TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        code TEXT
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): LuiDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, LuiDatabase::class.java, "lui_db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
