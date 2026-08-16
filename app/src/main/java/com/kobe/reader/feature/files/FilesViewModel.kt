package com.kobe.reader.feature.files

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.reader.core.common.Outcome
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.file.DocumentRef
import com.kobe.reader.data.prefs.SettingsRepository
import com.kobe.reader.data.prefs.SortOrder
import com.kobe.reader.data.repository.LibraryRepository
import com.kobe.reader.feature.common.DocumentActionHandler
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
class FilesViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val actions: DocumentActionHandler,
) : ViewModel() {

    private val selectedTab = MutableStateFlow(FilesTab.All)
    private val query = MutableStateFlow("")
    private val isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<FilesUiState> = combine(
        combine(library.allDocuments, library.recentDocuments, library.favoriteDocuments) {
                all, recent, favorites ->
            Triple(all, recent, favorites)
        },
        selectedTab,
        query,
        settings.settings,
        isRefreshing,
    ) { lists, tab, search, prefs, refreshing ->
        val (all, recent, favorites) = lists
        val source = when (tab) {
            FilesTab.All -> all
            FilesTab.Recent -> recent
            FilesTab.Favorites -> favorites
        }
        FilesUiState(
            tab = tab,
            documents = source.filterByQuery(search),
            query = search,
            sortOrder = prefs.sortOrder,
            grantedFolderCount = prefs.grantedFolders.size,
            isRefreshing = refreshing,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = FilesUiState(isLoading = true),
    )

    private val _events = MutableStateFlow<FilesEvent?>(null)
    val events: StateFlow<FilesEvent?> = _events.asStateFlow()

    fun selectTab(tab: FilesTab) = selectedTab.update { tab }

    fun search(text: String) = query.update { text }

    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            library.pruneMissing()
            when (val outcome = library.rescan()) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> emit(FilesEvent.ShowError(outcome.error))
            }
            isRefreshing.value = false
        }
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            when (val outcome = library.addFolder(treeUri)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> emit(FilesEvent.ShowError(outcome.error))
            }
        }
    }

    fun openPicked(uri: Uri) {
        viewModelScope.launch {
            when (val outcome = library.registerPicked(uri)) {
                is Outcome.Success -> emit(FilesEvent.OpenDocument(outcome.value.uri.toString()))
                is Outcome.Failure -> emit(FilesEvent.ShowError(outcome.error))
            }
        }
    }

    fun toggleFavorite(document: DocumentRef) {
        viewModelScope.launch { actions.toggleFavorite(document) }
    }

    fun share(document: DocumentRef) {
        emit(FilesEvent.Share(actions.shareIntent(document)))
    }

    fun rename(document: DocumentRef, newName: String) {
        viewModelScope.launch {
            actions.rename(document, newName)?.let { emit(FilesEvent.ShowError(it)) }
        }
    }

    fun duplicate(document: DocumentRef) {
        viewModelScope.launch {
            actions.duplicate(document)?.let { emit(FilesEvent.ShowError(it)) }
        }
    }

    fun delete(document: DocumentRef) {
        viewModelScope.launch {
            actions.delete(document)?.let { emit(FilesEvent.ShowError(it)) }
        }
    }

    fun consumeEvent() = _events.update { null }

    private fun emit(event: FilesEvent) = _events.update { event }

    private fun List<DocumentRef>.filterByQuery(search: String): List<DocumentRef> {
        val needle = search.trim()
        if (needle.isEmpty()) return this
        return filter { it.displayName.contains(needle, ignoreCase = true) }
    }
}

enum class FilesTab { All, Recent, Favorites }

data class FilesUiState(
    val tab: FilesTab = FilesTab.All,
    val documents: List<DocumentRef> = emptyList(),
    val query: String = "",
    val sortOrder: SortOrder = SortOrder.DateDescending,
    val grantedFolderCount: Int = 0,
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = false,
) {
    val isSearching: Boolean get() = query.isNotBlank()
}

sealed interface FilesEvent {
    data class OpenDocument(val uri: String) : FilesEvent
    data class Share(val intent: Intent) : FilesEvent
    data class ShowError(val error: KobeError) : FilesEvent
}
