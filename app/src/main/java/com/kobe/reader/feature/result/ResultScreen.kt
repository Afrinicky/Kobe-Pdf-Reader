package com.kobe.reader.feature.result

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.reader.R
import com.kobe.reader.core.common.asFileSize
import com.kobe.reader.core.common.compressionSavingPercent
import com.kobe.reader.core.file.FileNaming
import com.kobe.reader.ui.components.ErrorDialog
import com.kobe.reader.ui.components.PdfGlyph
import com.kobe.reader.ui.components.TextInputDialog
import java.io.File

/**
 * "Operation complete".
 *
 * Shows exactly what the spec asks for - name, size, location - and the four
 * things a user wants next: open it, share it, rename it, or be done. The
 * interstitial ad opportunity is taken here and nowhere else: the work is
 * finished, so an ad interrupts nothing.
 */
@Composable
fun ResultScreen(
    files: List<File>,
    title: String,
    originalBytes: Long,
    onOpenDocument: (String) -> Unit,
    onDone: () -> Unit,
    activityProvider: () -> Activity?,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(files) { viewModel.bind(files) }

    LaunchedEffect(Unit) {
        activityProvider()?.let(viewModel::onOperationFinished)
    }

    var renaming by remember { mutableStateOf<File?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PDF_MIME),
    ) { destination -> destination?.let(viewModel::export) }

    state.error?.let { error ->
        ErrorDialog(error = error, onDismiss = viewModel::dismissError)
    }

    renaming?.let { file ->
        TextInputDialog(
            titleRes = R.string.action_rename,
            initialValue = FileNaming.stripExtension(file.name),
            onConfirm = { newName ->
                viewModel.rename(file, newName)
                renaming = null
            },
            onDismiss = { renaming = null },
            validate = { value ->
                if (FileNaming.sanitiseBase(value).isEmpty()) R.string.error_empty_name else null
            },
        )
    }

    Scaffold(
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_done))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 24.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item { SuccessHeader(title = title, fileCount = state.files.size) }

            item {
                SavingsLine(
                    originalBytes = originalBytes,
                    resultBytes = state.totalBytes,
                )
            }

            item { Spacer(Modifier.height(12.dp)) }

            items(state.files, key = { it.absolutePath }) { file ->
                ResultFileCard(
                    file = file,
                    onOpen = { viewModel.open(file) },
                    onShare = { viewModel.share(listOf(file)) },
                    onRename = { renaming = file },
                    onSaveTo = { exportLauncher.launch(file.name) },
                )
            }

            if (state.files.size > 1) {
                item {
                    OutlinedButton(
                        onClick = { viewModel.share(state.files) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) { Text(stringResource(R.string.action_share)) }
                }
            }
        }
    }

    // Any intent the ViewModel produced (share sheet, external viewer).
    LaunchedEffect(state.pendingIntent) {
        state.pendingIntent?.let { intent ->
            context.startActivity(intent)
            viewModel.consumeIntent()
        }
    }
}

@Composable
private fun SuccessHeader(title: String, fileCount: Int) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.operation_complete),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = if (fileCount > 1) {
                stringResource(R.string.result_files_created, fileCount)
            } else {
                title
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavingsLine(originalBytes: Long, resultBytes: Long) {
    if (originalBytes <= 0L || resultBytes <= 0L) return
    val context = LocalContext.current
    val percent = compressionSavingPercent(originalBytes, resultBytes)

    Text(
        text = if (percent > 0) {
            stringResource(
                R.string.result_size_change,
                (originalBytes - resultBytes).asFileSize(context),
                percent,
            )
        } else {
            stringResource(R.string.result_size_grew)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

@Composable
private fun ResultFileCard(
    file: File,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onSaveTo: () -> Unit,
) {
    val context = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PdfGlyph(size = 36)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = file.length().asFileSize(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.result_saved_to, LOCATION_LABEL),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
                TextButton(onClick = onShare) { Text(stringResource(R.string.action_share)) }
                TextButton(onClick = onRename) { Text(stringResource(R.string.action_rename)) }
                TextButton(onClick = onSaveTo) { Text(stringResource(R.string.action_save_to)) }
            }
        }
    }
}

/**
 * Output lives in app-private storage until the user exports it. Saying so
 * plainly is better than printing an internal path nobody can navigate to.
 */
private const val LOCATION_LABEL = "Kobe PDF Reader"
private const val PDF_MIME = "application/pdf"
