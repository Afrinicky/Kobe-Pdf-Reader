package com.kobe.reader.feature.tools

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.reader.core.common.Outcome
import com.kobe.reader.core.common.Progress
import com.kobe.reader.core.common.ProgressReporter
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.file.DocumentRef
import com.kobe.reader.core.file.DocumentStore
import com.kobe.reader.core.file.FileNaming
import com.kobe.reader.core.pdf.PageSelection
import com.kobe.reader.data.repository.LibraryRepository
import com.kobe.reader.monetization.FeatureAccess
import com.kobe.reader.monetization.PremiumManager
import com.kobe.reader.monetization.ProFeature
import com.kobe.reader.pdf.CompressionLevel
import com.kobe.reader.pdf.ImageFormat
import com.kobe.reader.pdf.ImagesToPdfOptions
import com.kobe.reader.pdf.PdfToImagesOptions
import com.kobe.reader.pdf.PdfToolkit
import com.kobe.reader.pdf.ProtectOptions
import com.kobe.reader.pdf.SplitMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Drives every tool.
 *
 * The flow is identical for all ten: pick files, adjust options, run, hand the
 * results to the result screen. Only [execute] branches on which tool it is, so
 * gating, progress, cancellation, naming and error handling are written once.
 */
@HiltViewModel
class ToolFlowViewModel @Inject constructor(
    private val toolkit: PdfToolkit,
    private val store: DocumentStore,
    private val library: LibraryRepository,
    private val premium: PremiumManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolFlowUiState())
    val uiState: StateFlow<ToolFlowUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    fun bind(tool: Tool) {
        if (_uiState.value.tool == tool) return
        _uiState.update { ToolFlowUiState(tool = tool) }
        refreshAccess(tool)
    }

    private fun refreshAccess(tool: Tool) {
        viewModelScope.launch {
            val access = premium.checkAccess(tool.feature)
            _uiState.update {
                it.copy(
                    access = access,
                    availableCompression = premium.availableCompressionLevels(),
                )
            }
        }
    }

    // ---------------------------------------------------------------- selection

    fun addSelection(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val tool = _uiState.value.tool ?: return@launch
            val described = uris.mapNotNull { uri ->
                store.persistPermission(uri, writable = false)
                store.describe(uri, DocumentRef.Origin.Picked)
            }

            _uiState.update { state ->
                val merged = if (tool.input.allowsMultiple) {
                    // De-duplicate: picking the same file twice in a merge is
                    // almost always a mis-tap, and silently duplicating a
                    // 50-page document is an expensive mistake.
                    (state.selection + described).distinctBy { it.uri }
                } else {
                    described.take(1)
                }
                state.copy(selection = merged, error = null)
            }
            loadPageCountIfNeeded()
        }
    }

    fun removeSelection(index: Int) {
        _uiState.update { state ->
            state.copy(selection = state.selection.filterIndexed { i, _ -> i != index })
        }
    }

    /** Drag-to-reorder in the merge list. */
    fun moveSelection(from: Int, to: Int) {
        _uiState.update { state ->
            val items = state.selection.toMutableList()
            if (from !in items.indices || to !in items.indices) return@update state
            items.add(to, items.removeAt(from))
            state.copy(selection = items)
        }
    }

    private fun loadPageCountIfNeeded() {
        val tool = _uiState.value.tool ?: return
        if (tool.input == ToolInput.MultipleImages) return
        val source = _uiState.value.selection.firstOrNull() ?: return

        viewModelScope.launch {
            when (val info = toolkit.inspect(source.uri)) {
                is Outcome.Success -> _uiState.update {
                    it.copy(sourcePageCount = info.value.pageCount)
                }

                is Outcome.Failure -> _uiState.update { it.copy(error = info.error) }
            }
        }
    }

    // ---------------------------------------------------------------- options

    fun setSplitMode(mode: SplitModeChoice) = _uiState.update { it.copy(splitChoice = mode) }

    fun setPageRangeText(text: String) = _uiState.update { it.copy(pageRangeText = text) }

    fun setSplitAtPage(page: Int) = _uiState.update { it.copy(splitAtPage = page) }

    fun setCompressionLevel(level: CompressionLevel) {
        viewModelScope.launch {
            if (!premium.isCompressionLevelAllowed(level)) {
                _uiState.update { it.copy(blockedFeature = ProFeature.AdvancedCompression.name) }
                return@launch
            }
            _uiState.update { it.copy(compressionLevel = level) }
        }
    }

    fun setImageFormat(format: ImageFormat) = _uiState.update { it.copy(imageFormat = format) }

    fun setImageDpi(dpi: Int) = _uiState.update { it.copy(imageDpi = dpi) }

    fun setPassword(value: String) = _uiState.update { it.copy(password = value) }

    fun setPasswordConfirm(value: String) = _uiState.update { it.copy(passwordConfirm = value) }

    fun setAllowPrinting(value: Boolean) = _uiState.update { it.copy(allowPrinting = value) }

    fun setAllowCopying(value: Boolean) = _uiState.update { it.copy(allowCopying = value) }

    fun setRotation(degrees: Int) = _uiState.update { it.copy(rotationDegrees = degrees) }

    fun setOutputName(name: String) = _uiState.update { it.copy(outputName = name) }

    // ---------------------------------------------------------------- run

    fun run() {
        val state = _uiState.value
        val tool = state.tool ?: return

        viewModelScope.launch {
            when (val access = premium.checkAccess(tool.feature)) {
                is FeatureAccess.Allowed -> Unit
                is FeatureAccess.RequiresPro,
                is FeatureAccess.LimitReached,
                -> {
                    _uiState.update { it.copy(access = access, blockedFeature = tool.feature.name) }
                    return@launch
                }
            }

            val validation = state.validate()
            if (validation != null) {
                _uiState.update { it.copy(error = validation) }
                return@launch
            }

            runJob = launch {
                _uiState.update {
                    it.copy(isRunning = true, progress = Progress.Indeterminate, error = null)
                }

                val reporter = ProgressReporter { current, total ->
                    _uiState.update { it.copy(progress = Progress(current, total)) }
                }

                when (val outcome = execute(tool, state, reporter)) {
                    is Outcome.Success -> {
                        premium.recordUse(tool.feature)
                        outcome.value.forEach { library.registerGenerated(it) }
                        _uiState.update {
                            it.copy(
                                isRunning = false,
                                results = outcome.value,
                                originalBytes = state.selection.sumOf { ref -> ref.sizeBytes },
                            )
                        }
                    }

                    is Outcome.Failure -> _uiState.update {
                        it.copy(isRunning = false, error = outcome.error)
                    }
                }
            }
        }
    }

    fun cancel() {
        runJob?.cancel()
        runJob = null
        _uiState.update { it.copy(isRunning = false, error = KobeError.Cancelled) }
    }

    private suspend fun execute(
        tool: Tool,
        state: ToolFlowUiState,
        progress: ProgressReporter,
    ): Outcome<List<File>> {
        val sources = state.selection.map { it.uri }
        val first = sources.firstOrNull() ?: return Outcome.Failure(KobeError.Unexpected())
        val name = state.resolvedOutputName()

        return when (tool) {
            Tool.Merge -> toolkit.merge(sources, name, progress)

            Tool.Split -> toolkit.split(first, state.resolvedSplitMode(), name, progress)

            Tool.Compress -> toolkit.compress(first, state.compressionLevel, name, progress)

            Tool.ImagesToPdf -> toolkit.imagesToPdf(
                images = sources,
                options = ImagesToPdfOptions(),
                outputName = name,
                progress = progress,
            )

            Tool.PdfToImages -> toolkit.pdfToImages(
                source = first,
                pages = state.resolvedPages(),
                options = PdfToImagesOptions(
                    format = state.imageFormat,
                    dpi = state.imageDpi,
                ),
                outputName = name,
                progress = progress,
            )

            Tool.ExtractPages -> toolkit.extractPages(
                source = first,
                pages = state.resolvedPages(),
                outputName = name,
                progress = progress,
            )

            Tool.DeletePages -> toolkit.deletePages(
                source = first,
                pages = state.resolvedPages(),
                outputName = name,
                progress = progress,
            )

            Tool.Rotate -> toolkit.rotatePages(
                source = first,
                rotations = (1..state.sourcePageCount).associateWith { state.rotationDegrees },
                outputName = name,
                progress = progress,
            )

            Tool.Protect -> toolkit.protect(
                source = first,
                options = ProtectOptions(
                    userPassword = state.password,
                    allowPrinting = state.allowPrinting,
                    allowExtraction = state.allowCopying,
                ),
                outputName = name,
                progress = progress,
            )

            // Organize has its own screen; reaching here means a bad route.
            Tool.Organize -> Outcome.Failure(KobeError.Unexpected())
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun dismissBlock() = _uiState.update { it.copy(blockedFeature = null) }

    override fun onCleared() {
        super.onCleared()
        runJob?.cancel()
    }
}

/** Which split option the user picked, before it becomes a [SplitMode]. */
enum class SplitModeChoice { EveryPage, AtPage, CustomRanges }

data class ToolFlowUiState(
    val tool: Tool? = null,
    val selection: List<DocumentRef> = emptyList(),
    val sourcePageCount: Int = 0,
    val access: FeatureAccess? = null,
    val availableCompression: List<CompressionLevel> = CompressionLevel.entries,

    // options
    val splitChoice: SplitModeChoice = SplitModeChoice.EveryPage,
    val splitAtPage: Int = 1,
    val pageRangeText: String = "",
    val compressionLevel: CompressionLevel = CompressionLevel.Balanced,
    val imageFormat: ImageFormat = ImageFormat.Jpeg,
    val imageDpi: Int = 150,
    val password: String = "",
    val passwordConfirm: String = "",
    val allowPrinting: Boolean = true,
    val allowCopying: Boolean = false,
    val rotationDegrees: Int = 90,
    val outputName: String = "",

    // run state
    val isRunning: Boolean = false,
    val progress: Progress = Progress.Indeterminate,
    val results: List<File> = emptyList(),
    val originalBytes: Long = 0L,
    val error: KobeError? = null,
    /** Name of a ProFeature the user just bumped into, or null. */
    val blockedFeature: String? = null,
) {
    val hasSelection: Boolean get() = selection.isNotEmpty()

    val canRun: Boolean
        get() = tool != null && !isRunning && hasSelection && validate() == null

    val remainingToday: Int? get() = (access as? FeatureAccess.Allowed)?.remainingToday

    /** Returns the first reason the user can't proceed, or null if they can. */
    fun validate(): KobeError? {
        val current = tool ?: return KobeError.Unexpected()
        if (selection.isEmpty()) return KobeError.NothingSelected

        return when (current) {
            Tool.Merge -> if (selection.size < 2) KobeError.NeedTwoFiles else null

            Tool.Split -> when (splitChoice) {
                SplitModeChoice.CustomRanges ->
                    if (PageSelection.parse(pageRangeText, sourcePageCount) == null) {
                        KobeError.InvalidPageRange
                    } else {
                        null
                    }

                SplitModeChoice.AtPage ->
                    if (splitAtPage !in 1 until sourcePageCount) {
                        KobeError.InvalidPageRange
                    } else {
                        null
                    }

                SplitModeChoice.EveryPage -> null
            }

            // An empty range means "every page" for these three, so only a
            // non-empty but unparseable range is an error.
            Tool.ExtractPages, Tool.DeletePages, Tool.PdfToImages ->
                if (pageRangeText.isNotBlank() &&
                    PageSelection.parse(pageRangeText, sourcePageCount) == null
                ) {
                    KobeError.InvalidPageRange
                } else {
                    null
                }

            Tool.Protect -> when {
                password.length < MIN_PASSWORD_LENGTH -> KobeError.PasswordTooShort
                password != passwordConfirm -> KobeError.PasswordMismatch
                else -> null
            }

            else -> null
        }
    }

    /** Empty range text means "all pages" for the tools that allow it. */
    fun resolvedPages(): List<Int> =
        PageSelection.parse(pageRangeText, sourcePageCount) ?: emptyList()

    fun resolvedSplitMode(): SplitMode = when (splitChoice) {
        SplitModeChoice.EveryPage -> SplitMode.EveryPage
        SplitModeChoice.AtPage -> SplitMode.AtPage(splitAtPage)
        SplitModeChoice.CustomRanges -> SplitMode.Ranges(
            PageSelection.parse(pageRangeText, sourcePageCount)
                ?.toRanges()
                ?: emptyList(),
        )
    }

    fun resolvedOutputName(): String {
        if (outputName.isNotBlank()) return FileNaming.sanitiseBase(outputName)
        val source = selection.firstOrNull()?.displayName.orEmpty()
        return when (tool) {
            Tool.Merge -> FileNaming.dated("Merged")
            Tool.Split -> FileNaming.derived("Split", source)
            Tool.Compress -> FileNaming.derived("Compressed", source)
            Tool.ImagesToPdf -> FileNaming.dated("Images_to_PDF")
            Tool.PdfToImages -> FileNaming.stripExtension(source).ifBlank { "Page" }
            Tool.ExtractPages -> FileNaming.derived("Extracted", source)
            Tool.DeletePages -> FileNaming.derived("Edited", source)
            Tool.Rotate -> FileNaming.derived("Rotated", source)
            Tool.Protect -> FileNaming.derived("Protected", source)
            Tool.Organize, null -> FileNaming.derived("Organized", source)
        }
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 4
    }
}

/** Collapses sorted page numbers into contiguous ranges for the splitter. */
private fun List<Int>.toRanges(): List<IntRange> {
    if (isEmpty()) return emptyList()
    val sorted = sorted()
    val ranges = mutableListOf<IntRange>()
    var start = sorted.first()
    var previous = start
    for (page in sorted.drop(1)) {
        if (page == previous + 1) {
            previous = page
            continue
        }
        ranges += start..previous
        start = page
        previous = page
    }
    ranges += start..previous
    return ranges
}
