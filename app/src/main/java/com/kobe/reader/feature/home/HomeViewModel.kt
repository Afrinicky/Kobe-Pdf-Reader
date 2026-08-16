package com.kobe.reader.feature.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.reader.core.common.Outcome
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.file.DocumentRef
import com.kobe.reader.data.prefs.SettingsRepository
import com.kobe.reader.data.repository.LibraryRepository
import com.kobe.reader.monetization.PremiumManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    premium: PremiumManager,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        library.recentDocuments,
        library.favoriteDocuments,
        premium.isPro,
        settings.settings,
    ) { recent, favorites, isPro, prefs ->
        HomeUiState(
            recent = recent.take(RECENT_ON_HOME),
            favorites = favorites.take(FAVORITES_ON_HOME),
            isPro = isPro,
            needsFolderAccess = prefs.grantedFolders.isEmpty() && recent.isEmpty(),
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        // Keeps the library query alive across a rotation without leaking it
        // when the app goes to background.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(isLoading = true),
    )

    private val _events = MutableStateFlow<HomeEvent?>(null)
    val events: StateFlow<HomeEvent?> = _events.asStateFlow()

    init {
        // A rescan on launch is cheap when nothing changed and keeps the library
        // honest when files were moved or deleted by another app.
        viewModelScope.launch {
            library.pruneMissing()
            library.rescan()
        }
    }

    /** Called after the system picker returns a document. */
    fun openPicked(uri: Uri) {
        viewModelScope.launch {
            when (val outcome = library.registerPicked(uri)) {
                is Outcome.Success -> _events.update { HomeEvent.OpenDocument(outcome.value) }
                is Outcome.Failure -> _events.update { HomeEvent.ShowError(outcome.error) }
            }
        }
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            when (val outcome = library.addFolder(treeUri)) {
                is Outcome.Success -> settings.setSeenStorageIntro()
                is Outcome.Failure -> _events.update { HomeEvent.ShowError(outcome.error) }
            }
        }
    }

    fun toggleFavorite(document: DocumentRef) {
        viewModelScope.launch { library.setFavorite(document.uri, !document.isFavorite) }
    }

    fun consumeEvent() = _events.update { null }

    private companion object {
        const val RECENT_ON_HOME = 6
        const val FAVORITES_ON_HOME = 4
    }
}

data class HomeUiState(
    val recent: List<DocumentRef> = emptyList(),
    val favorites: List<DocumentRef> = emptyList(),
    val isPro: Boolean = false,
    val needsFolderAccess: Boolean = false,
    val isLoading: Boolean = false,
)

sealed interface HomeEvent {
    data class OpenDocument(val document: DocumentRef) : HomeEvent
    data class ShowError(val error: KobeError) : HomeEvent
}
