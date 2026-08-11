package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TranscriptionEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transcriptionDao(): TranscriptionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Real migration rather than relying on the destructive fallback below: v1 users have a
        // full transcription history in this table and adding three columns must not wipe it.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcriptions ADD COLUMN status TEXT NOT NULL DEFAULT '${TranscriptionEntity.STATUS_COMPLETED}'")
                db.execSQL("ALTER TABLE transcriptions ADD COLUMN statusText TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transcriptions ADD COLUMN progress REAL NOT NULL DEFAULT 0")
            }
        }

        // Index name must match exactly what Room generates for @Index("timestamp"), otherwise the
        // schema validation on open fails.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcriptions_timestamp ON transcriptions (timestamp)")
            }
        }

        // Exposed so the migration test can build the same upgrade path against a real v1 file.
        fun migrations(): Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "transcriber_database"
                )
                // Deliberately no fallbackToDestructiveMigration(): with real migrations in place,
                // the fallback would silently wipe the user's entire history the first time a
                // migration is forgotten - exactly the failure these migrations exist to prevent.
                // A missing migration should crash loudly in development instead.
                .addMigrations(*migrations())
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
