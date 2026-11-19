package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RegisteredPhoneEntity::class, CheckLogEntity::class],
    version = 2,
    exportSchema = false
)
abstract class SiagaDatabase : RoomDatabase() {
    abstract fun registeredPhoneDao(): RegisteredPhoneDao
    abstract fun siagaDao(): SiagaDao

    companion object {
        @Volatile
        private var INSTANCE: SiagaDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_registered_phones_isMonitored` ON `registered_phones` (`isMonitored`)"
                )
            }
        }

        fun getInstance(context: Context): SiagaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SiagaDatabase::class.java,
                    "siagahp_database.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
