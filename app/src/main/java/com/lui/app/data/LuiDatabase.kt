package com.lui.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ChatMessageEntity::class, DigestEntity::class, TriggerEntity::class], version = 4, exportSchema = false)
abstract class LuiDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun digestDao(): DigestDao
    abstract fun triggerDao(): TriggerDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN cardType TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS triggers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        radius REAL,
                        transition TEXT,
                        placeName TEXT,
                        triggerTimeMs INTEGER,
                        recurring INTEGER NOT NULL DEFAULT 0,
                        toolName TEXT NOT NULL,
                        toolParams TEXT NOT NULL DEFAULT '{}',
                        description TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): LuiDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, LuiDatabase::class.java, "lui_db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
