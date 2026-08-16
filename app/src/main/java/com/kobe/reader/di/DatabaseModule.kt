package com.kobe.reader.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.kobe.reader.data.database.DocumentDao
import com.kobe.reader.data.database.KobeDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providesDatabase(@ApplicationContext context: Context): KobeDatabase =
        Room.databaseBuilder(context, KobeDatabase::class.java, KobeDatabase.NAME)
            // The platform SQLite driver keeps ~3 MB of bundled native libraries
            // out of the APK. This database stores a few hundred metadata rows;
            // it has no need for a pinned SQLite version across OEMs.
            .setDriver(AndroidSQLiteDriver())
            .build()

    @Provides
    fun providesDocumentDao(database: KobeDatabase): DocumentDao = database.documentDao()
}
