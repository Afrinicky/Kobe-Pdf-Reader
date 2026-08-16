package com.kobe.reader.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.reader.core.common.Dispatcher
import com.kobe.reader.core.common.KobeDispatcher
import com.kobe.reader.core.common.asFileSize
import com.kobe.reader.core.file.DocumentStore
import com.kobe.reader.data.prefs.SettingsRepository
import com.kobe.reader.data.prefs.ThemePreference
import com.kobe.reader.data.repository.LibraryRepository
import com.kobe.reader.monetization.PremiumManager
import com.kobe.reader.pdf.render.PageBitmapCache
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    private val store: DocumentStore,
    private val cache: PageBitmapCache,
    @param:Dispatcher(KobeDispatcher.IO) private val io: CoroutineDispatcher,
    premium: PremiumManager,
) : ViewModel() {

    private val cacheSize = MutableStateFlow("")

    val uiState: StateFlow<SettingsUiState> =
        combine(premium.isPro, cacheSize) { isPro, size ->
            SettingsUiState(isPro = isPro, cacheSizeLabel = size)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    init {
        refreshCacheSize()
    }

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { settings.setTheme(theme) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settings.setKeepScreenOn(enabled) }
    }

    fun setRememberPosition(enabled: Boolean) {
        viewModelScope.launch { settings.setRememberPosition(enabled) }
    }

    fun setHorizontalPaging(enabled: Boolean) {
        viewModelScope.launch { settings.setHorizontalPaging(enabled) }
    }

    fun clearHistory() {
        viewModelScope.launch { library.clearHistory() }
    }

    /**
     * Clears rendered pages and any leftover working copies.
     *
     * Generated output is deliberately untouched - the user's files are not
     * cache, and deleting a merge they haven't exported yet would be data loss.
     */
    fun clearCache() {
        viewModelScope.launch {
            cache.clear()
            withContext(io) {
                store.cacheDir.listFiles()?.forEach(File::delete)
            }
            refreshCacheSize()
        }
    }

    private fun refreshCacheSize() {
        viewModelScope.launch {
            val bytes = withContext(io) {
                store.cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
            }
            cacheSize.update { bytes.asFileSize(context) }
        }
    }
}

data class SettingsUiState(
    val isPro: Boolean = false,
    val cacheSizeLabel: String = "",
)
