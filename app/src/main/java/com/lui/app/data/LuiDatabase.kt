package com.lui.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ChatMessageEntity::class, DigestEntity::class, TriggerEntity::class, HealthReadingEntity::class], version = 5, exportSchema = false)
abstract class LuiDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun digestDao(): DigestDao
    abstract fun triggerDao(): TriggerDao
    abstract fun healthReadingDao(): HealthReadingDao

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS health_readings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        metric TEXT NOT NULL,
                        value REAL NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_health_readings_metric_timestamp ON health_readings (metric, timestamp)")
            }
        }

        fun getInstance(context: Context): LuiDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, LuiDatabase::class.java, "lui_db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
