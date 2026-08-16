package com.kobe.reader.feature.organize

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.reader.core.common.Dispatcher
import com.kobe.reader.core.common.KobeDispatcher
import com.kobe.reader.core.common.Outcome
import com.kobe.reader.core.common.Progress
import com.kobe.reader.core.common.ProgressReporter
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.error.KobeException
import com.kobe.reader.core.file.DocumentStore
import com.kobe.reader.core.file.FileNaming
import com.kobe.reader.data.repository.LibraryRepository
import com.kobe.reader.monetization.FeatureAccess
import com.kobe.reader.monetization.PremiumManager
import com.kobe.reader.monetization.ProFeature
import com.kobe.reader.pdf.PageEdits
import com.kobe.reader.pdf.PdfToolkit
import com.kobe.reader.pdf.render.PdfPageRenderer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Page-level editing: reorder, rotate, delete, extract.
 *
 * All edits are held in memory as a [PageEdits] plan and applied in a single
 * rewrite when the user saves. Rewriting the PDF on every drag would be
 * unusably slow on a long document, and it would make undo impossible.
 */
@HiltViewModel
class OrganizeViewModel @Inject constructor(
    private val toolkit: PdfToolkit,
    private val store: DocumentStore,
    private val library: LibraryRepository,
    private val premium: PremiumManager,
    @param:Dispatcher(KobeDispatcher.Default) private val cpu: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrganizeUiState())
    val uiState: StateFlow<OrganizeUiState> = _uiState.asStateFlow()

    private var renderer: PdfPageRenderer? = null
    private var workingCopy: File? = null
    private var source: Uri? = null
    private var runJob: Job? = null
    private val openMutex = Mutex()

    fun load(uri: Uri) {
        if (source == uri) return
        source = uri

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val info = toolkit.inspect(uri)) {
                is Outcome.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = info.error)
                }

                is Outcome.Success -> {
                    val count = info.value.pageCount
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            sourceName = library.find(uri)?.displayName.orEmpty(),
                            pageCount = count,
                            pages = (1..count).map { page -> PageItem(sourcePage = page) },
                        )
                    }
                }
            }
        }
    }

    /** Thumbnail for a page, rendered small and cached by the shared cache. */
    suspend fun thumbnail(sourcePage: Int, widthPx: Int): Bitmap? = try {
        val active = ensureRenderer() ?: return null
        withContext(cpu) { active.renderPage(sourcePage - 1, widthPx) }
    } catch (e: KobeException) {
        _uiState.update { it.copy(error = e.error) }
        null
    }

    // ---------------------------------------------------------------- editing

    fun toggleSelection(sourcePage: Int) {
        _uiState.update { state ->
            val selected = state.selected.toMutableSet()
            if (!selected.add(sourcePage)) selected.remove(sourcePage)
            state.copy(selected = selected)
        }
    }

    fun selectAll() = _uiState.update { state ->
        state.copy(selected = state.pages.map { it.sourcePage }.toSet())
    }

    fun clearSelection() = _uiState.update { it.copy(selected = emptySet()) }

    fun move(from: Int, to: Int) {
        _uiState.update { state ->
            val pages = state.pages.toMutableList()
            if (from !in pages.indices || to !in pages.indices) return@update state
            pages.add(to, pages.removeAt(from))
            state.copy(pages = pages, isDirty = true)
        }
    }

    fun rotateSelected(delta: Int) {
        _uiState.update { state ->
            if (state.selected.isEmpty()) return@update state
            val pages = state.pages.map { page ->
                if (page.sourcePage in state.selected) {
                    page.copy(rotation = ((page.rotation + delta) % 360 + 360) % 360)
                } else {
                    page
                }
            }
            state.copy(pages = pages, isDirty = true)
        }
    }

    fun deleteSelected() {
        _uiState.update { state ->
            if (state.selected.isEmpty()) return@update state
            val remaining = state.pages.filterNot { it.sourcePage in state.selected }
            // A PDF with no pages is not a PDF. Refuse rather than produce one.
            if (remaining.isEmpty()) {
                return@update state.copy(error = KobeError.Unexpected(), selected = emptySet())
            }
            state.copy(pages = remaining, selected = emptySet(), isDirty = true)
        }
    }

    // ---------------------------------------------------------------- output

    /** Saves the edit plan as a new document. */
    fun save() = runEdit(extractOnly = false)

    /** Saves only the selected pages as a new document. */
    fun extractSelected() = runEdit(extractOnly = true)

    private fun runEdit(extractOnly: Boolean) {
        val uri = source ?: return
        val state = _uiState.value
        val feature = if (extractOnly) ProFeature.ExtractPages else ProFeature.Organize

        viewModelScope.launch {
            when (val access = premium.checkAccess(feature)) {
                is FeatureAccess.Allowed -> Unit
                else -> {
                    _uiState.update { it.copy(blockedFeature = feature.name) }
                    return@launch
                }
            }

            runJob = launch {
                _uiState.update { it.copy(isSaving = true, progress = Progress.Indeterminate) }

                val reporter = ProgressReporter { current, total ->
                    _uiState.update { it.copy(progress = Progress(current, total)) }
                }

                val outcome = if (extractOnly) {
                    toolkit.extractPages(
                        source = uri,
                        pages = state.pages
                            .filter { it.sourcePage in state.selected }
                            .map { it.sourcePage },
                        outputName = FileNaming.derived("Extracted", state.sourceName),
                        progress = reporter,
                    )
                } else {
                    toolkit.applyPageEdits(
                        source = uri,
                        edits = PageEdits(
                            order = state.pages.map { it.sourcePage },
                            rotations = state.pages
                                .filter { it.rotation != 0 }
                                .associate { it.sourcePage to it.rotation },
                        ),
                        outputName = FileNaming.derived("Organized", state.sourceName),
                        progress = reporter,
                    )
                }

                when (outcome) {
                    is Outcome.Success -> {
                        premium.recordUse(feature)
                        outcome.value.forEach { library.registerGenerated(it) }
                        _uiState.update {
                            it.copy(isSaving = false, results = outcome.value)
                        }
                    }

                    is Outcome.Failure -> _uiState.update {
                        it.copy(isSaving = false, error = outcome.error)
                    }
                }
            }
        }
    }

    fun cancel() {
        runJob?.cancel()
        runJob = null
        _uiState.update { it.copy(isSaving = false) }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun dismissBlock() = _uiState.update { it.copy(blockedFeature = null) }

    private suspend fun ensureRenderer(): PdfPageRenderer? = openMutex.withLock {
        renderer?.let { return it }
        val uri = source ?: return null
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
        runJob?.cancel()
        renderer?.close()
        renderer = null
        store.releaseWorkingCopy(workingCopy)
        workingCopy = null
    }
}

/** One page in the editor. [sourcePage] is its 1-based index in the original. */
data class PageItem(
    val sourcePage: Int,
    val rotation: Int = 0,
)

data class OrganizeUiState(
    val isLoading: Boolean = true,
    val sourceName: String = "",
    val pageCount: Int = 0,
    val pages: List<PageItem> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val progress: Progress = Progress.Indeterminate,
    val results: List<File> = emptyList(),
    val error: KobeError? = null,
    val blockedFeature: String? = null,
) {
    val selectionCount: Int get() = selected.size
    val hasSelection: Boolean get() = selected.isNotEmpty()
    val canSave: Boolean get() = isDirty && !isSaving && pages.isNotEmpty()
    val pagesRemoved: Int get() = pageCount - pages.size
}
