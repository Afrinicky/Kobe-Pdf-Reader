package com.kobe.reader.feature.result

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.reader.core.common.Outcome
import com.kobe.reader.core.common.runCatchingKobe
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.file.DocumentStore
import com.kobe.reader.core.file.FileNaming
import com.kobe.reader.data.repository.LibraryRepository
import com.kobe.reader.feature.common.DocumentActionHandler
import com.kobe.reader.monetization.ads.AdsController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ResultViewModel @Inject constructor(
    private val actions: DocumentActionHandler,
    private val store: DocumentStore,
    private val library: LibraryRepository,
    private val ads: AdsController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultUiState())
    val uiState: StateFlow<ResultUiState> = _uiState.asStateFlow()

    fun bind(files: List<File>) {
        val existing = files.filter { it.exists() }
        if (existing.map(File::getAbsolutePath) == _uiState.value.files.map(File::getAbsolutePath)) {
            return
        }
        _uiState.update { it.copy(files = existing) }
        viewModelScope.launch {
            actions.registerGenerated(existing.map(File::getAbsolutePath))
        }
    }

    /**
     * The one place an interstitial may appear: the work is done and the user
     * has not yet started anything else.
     */
    fun onOperationFinished(activity: Activity) = ads.onInterstitialOpportunity(activity)

    fun open(file: File) = _uiState.update { it.copy(pendingIntent = actions.viewIntent(file)) }

    fun share(files: List<File>) =
        _uiState.update { it.copy(pendingIntent = actions.shareIntent(files)) }

    fun rename(file: File, newBaseName: String) {
        viewModelScope.launch {
            val base = FileNaming.sanitiseBase(newBaseName)
            if (base.isEmpty()) {
                _uiState.update { it.copy(error = KobeError.EmptyName) }
                return@launch
            }
            val target = File(
                file.parentFile,
                FileNaming.withExtension(base, FileNaming.extensionOf(file.name)),
            )
            if (target.exists()) {
                _uiState.update { it.copy(error = KobeError.DuplicateName) }
                return@launch
            }
            if (!file.renameTo(target)) {
                _uiState.update { it.copy(error = KobeError.Unexpected()) }
                return@launch
            }
            library.registerGenerated(target)
            _uiState.update { state ->
                state.copy(files = state.files.map { if (it == file) target else it })
            }
        }
    }

    /** Copies the first result to a location the user chose. */
    fun export(destination: Uri) {
        val source = _uiState.value.files.firstOrNull() ?: return
        viewModelScope.launch {
            val outcome = runCatchingKobe { store.exportTo(source, destination) }
            if (outcome is Outcome.Failure) {
                _uiState.update { it.copy(error = outcome.error) }
            } else {
                actions.registerExported(destination)
            }
        }
    }

    fun consumeIntent() = _uiState.update { it.copy(pendingIntent = null) }

    fun dismissError() = _uiState.update { it.copy(error = null) }
}

data class ResultUiState(
    val files: List<File> = emptyList(),
    val pendingIntent: Intent? = null,
    val error: KobeError? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.length() }
}
