package com.kobe.reader.data.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [DocumentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class KobeDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        const val NAME = "kobe-reader.db"
    }
}
