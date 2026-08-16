package com.kobe.reader.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.kobe.reader.core.common.Dispatcher
import com.kobe.reader.core.common.KobeDispatcher
import com.kobe.reader.pdf.PdfToolkit
import com.kobe.reader.pdf.internal.PdfBoxToolkit
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Dispatcher(KobeDispatcher.IO)
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Dispatcher(KobeDispatcher.Default)
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Application-lifetime scope for work that must outlive a screen - writing a
     * reading position as the user leaves the reader, for instance.
     * [SupervisorJob] keeps one failure from cancelling everything else.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun providesApplicationScope(
        @Dispatcher(KobeDispatcher.Default) dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun providesPreferencesDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile(PREFERENCES_NAME) },
    )

    private const val PREFERENCES_NAME = "kobe_settings"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PdfModule {

    /**
     * The whole app depends on [PdfToolkit], never on PdfBox. Swapping the
     * engine later is a one-line change here.
     */
    @Binds
    @Singleton
    abstract fun bindsPdfToolkit(implementation: PdfBoxToolkit): PdfToolkit
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
