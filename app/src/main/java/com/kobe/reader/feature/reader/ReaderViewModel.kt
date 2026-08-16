package com.kobe.reader.feature.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.reader.core.common.Dispatcher
import com.kobe.reader.core.common.KobeDispatcher
import com.kobe.reader.core.common.Outcome
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.error.KobeException
import com.kobe.reader.core.file.DocumentStore
import com.kobe.reader.data.repository.LibraryRepository
import com.kobe.reader.pdf.PdfDocumentInfo
import com.kobe.reader.pdf.PdfToolkit
import com.kobe.reader.pdf.SearchHit
import com.kobe.reader.pdf.render.PageBitmapCache
import com.kobe.reader.pdf.render.PdfPageRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Reader state and page rendering.
 *
 * The rendering model, which is the whole design:
 *
 *  - One [PdfPageRenderer] per open document, held for the ViewModel's life.
 *    Re-opening the file per page would dominate scroll cost.
 *  - Pages render on demand at the width they'll be drawn at, and land in a
 *    shared [PageBitmapCache]. Scrolling back is then free.
 *  - Neighbouring pages prefetch one ahead and one behind. Any more and a fast
 *    scroll spends all its time rendering pages the user has already passed.
 *  - Zoom re-renders at a higher width rather than scaling a bitmap up, which
 *    is what keeps text crisp at 3x.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val toolkit: PdfToolkit,
    private val store: DocumentStore,
    private val library: LibraryRepository,
    private val cache: PageBitmapCache,
    @param:Dispatcher(KobeDispatcher.Default) private val cpu: CoroutineDispatcher,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val searchQuery = MutableStateFlow("")

    private var renderer: PdfPageRenderer? = null
    private var workingCopy: File? = null
    private var documentUri: Uri? = null
    private var searchJob: Job? = null

    /** Guards renderer creation so two page requests can't open it twice. */
    private val openMutex = Mutex()

    init {
        viewModelScope.launch {
            searchQuery
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MILLIS)
                .distinctUntilChanged()
                .collect { runSearch(it) }
        }
    }

    fun open(uri: Uri, password: String? = null) {
        if (documentUri == uri && renderer != null) return
        documentUri = uri

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val info = toolkit.inspect(uri, password)) {
                is Outcome.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = info.error,
                            needsPassword = info.error == KobeError.PasswordRequired ||
                                info.error == KobeError.WrongPassword,
                            passwordWasWrong = info.error == KobeError.WrongPassword,
                        )
                    }
                }

                is Outcome.Success -> {
                    val resumePage = library.find(uri)?.lastReadPage ?: 0
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = null,
                            needsPassword = false,
                            documentInfo = info.value,
                            pageCount = info.value.pageCount,
                            currentPage = resumePage.coerceIn(0, info.value.pageCount - 1),
                        )
                    }
                    library.recordOpened(uri, info.value.pageCount)
                }
            }
        }
    }

    fun submitPassword(password: String) {
        val uri = documentUri ?: return
        // Force a reopen: the previous attempt left no usable renderer.
        renderer = null
        documentUri = null
        open(uri, password)
    }

    /**
     * Returns the bitmap for [pageIndex] at [widthPx], rendering it if needed.
     *
     * Called from the page composable rather than pushed from state: only the
     * layout knows how wide a page will actually be drawn.
     */
    suspend fun pageBitmap(pageIndex: Int, widthPx: Int): Bitmap? {
        val uri = documentUri ?: return null
        val key = PageBitmapCache.Key(uri.toString(), pageIndex, widthPx)
        cache[key]?.let { return it }

        return try {
            val active = ensureRenderer() ?: return null
            withContext(cpu) {
                active.renderPage(pageIndex, widthPx).also { cache.put(key, it) }
            }
        } catch (e: KobeException) {
            _uiState.update { it.copy(error = e.error) }
            null
        }
    }

    /** Renders the pages either side so a slow scroll never shows a blank page. */
    fun prefetchAround(pageIndex: Int, widthPx: Int) {
        val count = _uiState.value.pageCount
        viewModelScope.launch {
            listOf(pageIndex + 1, pageIndex - 1)
                .filter { it in 0 until count }
                .forEach { pageBitmap(it, widthPx) }
        }
    }

    fun onPageChanged(pageIndex: Int) {
        if (pageIndex == _uiState.value.currentPage) return
        _uiState.update { it.copy(currentPage = pageIndex) }
    }

    fun setZoom(scale: Float) {
        _uiState.update { it.copy(zoom = scale.coerceIn(MIN_ZOOM, MAX_ZOOM)) }
    }

    fun toggleChrome() {
        _uiState.update { it.copy(chromeVisible = !it.chromeVisible) }
    }

    fun toggleFavorite() {
        val uri = documentUri ?: return
        viewModelScope.launch {
            val current = library.find(uri)?.isFavorite ?: false
            library.setFavorite(uri, !current)
            _uiState.update { it.copy(isFavorite = !current) }
        }
    }

    // ---------------------------------------------------------------- search

    fun openSearch() = _uiState.update { it.copy(searchVisible = true) }

    fun closeSearch() {
        searchJob?.cancel()
        searchQuery.value = ""
        _uiState.update {
            it.copy(searchVisible = false, searchQuery = "", hits = emptyList(), activeHit = -1)
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchQuery.value = query
    }

    private fun runSearch(query: String) {
        val uri = documentUri ?: return
        searchJob?.cancel()

        if (query.isBlank()) {
            _uiState.update { it.copy(hits = emptyList(), activeHit = -1, isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }
            when (val outcome = toolkit.search(uri, query)) {
                is Outcome.Success -> _uiState.update {
                    it.copy(
                        hits = outcome.value,
                        activeHit = if (outcome.value.isEmpty()) -1 else 0,
                        isSearching = false,
                        currentPage = outcome.value.firstOrNull()
                            ?.let { hit -> hit.page - 1 }
                            ?: it.currentPage,
                    )
                }

                is Outcome.Failure -> _uiState.update {
                    it.copy(isSearching = false, error = outcome.error)
                }
            }
        }
    }

    fun nextHit() = moveHit(1)

    fun previousHit() = moveHit(-1)

    private fun moveHit(delta: Int) {
        val state = _uiState.value
        if (state.hits.isEmpty()) return
        val next = (state.activeHit + delta).mod(state.hits.size)
        _uiState.update { it.copy(activeHit = next, currentPage = state.hits[next].page - 1) }
    }

    // ---------------------------------------------------------------- lifecycle

    /** Persists the reading position. Called as the reader leaves the screen. */
    fun savePosition(offset: Float = 0f) {
        val uri = documentUri ?: return
        val page = _uiState.value.currentPage
        // Application-scoped would be safer for a background write, but the
        // reader is always on screen when this runs and the write is one row.
        viewModelScope.launch { library.saveReadingPosition(uri, page, offset) }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    private suspend fun ensureRenderer(): PdfPageRenderer? = openMutex.withLock {
        renderer?.let { return it }
        val uri = documentUri ?: return null

        val copy = withContext(cpu) { store.materialise(uri) }
        workingCopy = copy
        return try {
            PdfPageRenderer.open(copy).also { renderer = it }
        } catch (e: KobeException) {
            store.releaseWorkingCopy(copy)
            workingCopy = null
            _uiState.update { it.copy(error = e.error) }
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
        renderer?.close()
        renderer = null
        store.releaseWorkingCopy(workingCopy)
        workingCopy = null
        documentUri?.let { cache.evictDocument(it.toString()) }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
    }
}

data class ReaderUiState(
    val isLoading: Boolean = true,
    val error: KobeError? = null,
    val needsPassword: Boolean = false,
    val passwordWasWrong: Boolean = false,
    val documentInfo: PdfDocumentInfo? = null,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val zoom: Float = 1f,
    val chromeVisible: Boolean = true,
    val isFavorite: Boolean = false,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val activeHit: Int = -1,
) {
    val hasDocument: Boolean get() = pageCount > 0
    val hasHits: Boolean get() = hits.isNotEmpty()
    val activeHitLabel: String get() = if (hasHits) "${activeHit + 1}/${hits.size}" else ""
}
