package com.kobe.reader.feature.tools

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.reader.R
import com.kobe.reader.core.common.asFileSize
import com.kobe.reader.pdf.CompressionLevel
import com.kobe.reader.pdf.ImageFormat
import com.kobe.reader.ui.components.ErrorDialog
import com.kobe.reader.ui.components.OperationProgressDialog
import com.kobe.reader.ui.components.PdfGlyph
import com.kobe.reader.ui.components.ProBadge

/**
 * One screen for every tool: pick, configure, run.
 *
 * The flow is deliberately the same shape each time - select files, see them
 * listed, adjust a small options panel, press one primary button. The spec's
 * "Open → Choose tool → Select file → Process → Save/share" only works if the
 * middle three steps never surprise anyone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolFlowScreen(
    toolKey: String,
    onBack: () -> Unit,
    onDone: (List<String>, String, Long) -> Unit,
    onUpgrade: (String?) -> Unit,
    activityProvider: () -> Activity?,
    viewModel: ToolFlowViewModel = hiltViewModel(),
) {
    val tool = Tool.fromKey(toolKey)
    if (tool == null) {
        LaunchedEffect(toolKey) { onBack() }
        return
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(tool) { viewModel.bind(tool) }

    val pickMultiple = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.addSelection(uris) }

    val pickSingle = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel.addSelection(listOfNotNull(uri)) }

    fun launchPicker() {
        if (tool.input.allowsMultiple) {
            pickMultiple.launch(tool.input.mimeTypes)
        } else {
            pickSingle.launch(tool.input.mimeTypes)
        }
    }

    // Open the picker immediately - the user chose a tool, they already know
    // they need to pick a file, and an empty screen with one button is a
    // pointless extra tap.
    LaunchedEffect(tool) {
        if (state.selection.isEmpty()) launchPicker()
    }

    LaunchedEffect(state.results) {
        if (state.results.isNotEmpty()) {
            onDone(
                state.results.map { it.absolutePath },
                context.getString(tool.titleRes),
                state.originalBytes,
            )
        }
    }

    state.blockedFeature?.let { featureName ->
        LaunchedEffect(featureName) {
            viewModel.dismissBlock()
            onUpgrade(featureName)
        }
    }

    state.error?.let { error ->
        ErrorDialog(error = error, onDismiss = viewModel::dismissError)
    }

    if (state.isRunning) {
        OperationProgressDialog(
            progress = state.progress,
            titleRes = tool.titleRes,
            onCancel = viewModel::cancel,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(tool.titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            RunBar(
                enabled = state.canRun,
                remainingToday = state.remainingToday,
                onRun = viewModel::run,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(tool.descriptionRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            itemsIndexed(state.selection, key = { _, ref -> ref.uri.toString() }) { index, ref ->
                SelectedFileRow(
                    name = ref.displayName,
                    detail = ref.sizeBytes.asFileSize(context),
                    position = if (tool == Tool.Merge) index + 1 else null,
                    canMoveUp = tool == Tool.Merge && index > 0,
                    canMoveDown = tool == Tool.Merge && index < state.selection.lastIndex,
                    onMoveUp = { viewModel.moveSelection(index, index - 1) },
                    onMoveDown = { viewModel.moveSelection(index, index + 1) },
                    onRemove = { viewModel.removeSelection(index) },
                )
            }

            item {
                OutlinedButton(
                    onClick = { launchPicker() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.width(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (state.hasSelection) {
                                R.string.action_add_more
                            } else {
                                R.string.action_select_files
                            },
                        ),
                    )
                }
            }

            if (state.hasSelection) {
                item {
                    ToolOptions(
                        tool = tool,
                        state = state,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolOptions(
    tool: Tool,
    state: ToolFlowUiState,
    viewModel: ToolFlowViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (tool) {
            Tool.Split -> SplitOptions(state, viewModel)
            Tool.Compress -> CompressOptions(state, viewModel)
            Tool.PdfToImages -> PdfToImagesOptions(state, viewModel)
            Tool.ExtractPages, Tool.DeletePages -> PageRangeField(state, viewModel)
            Tool.Rotate -> RotateOptions(state, viewModel)
            Tool.Protect -> ProtectOptions(state, viewModel)
            else -> Unit
        }

        OutlinedTextField(
            value = state.outputName,
            onValueChange = viewModel::setOutputName,
            singleLine = true,
            label = { Text(stringResource(R.string.action_rename)) },
            placeholder = { Text(state.resolvedOutputName()) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SplitOptions(state: ToolFlowUiState, viewModel: ToolFlowViewModel) {
    OptionSection(R.string.split_mode) {
        SplitModeChoice.entries.forEach { choice ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.splitChoice == choice,
                    onClick = { viewModel.setSplitMode(choice) },
                )
                Text(stringResource(choice.labelRes()))
            }
        }
        if (state.splitChoice == SplitModeChoice.CustomRanges) {
            PageRangeField(state, viewModel)
        }
        if (state.splitChoice == SplitModeChoice.AtPage) {
            OutlinedTextField(
                value = state.splitAtPage.toString(),
                onValueChange = { text ->
                    viewModel.setSplitAtPage(text.filter(Char::isDigit).toIntOrNull() ?: 1)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.reader_jump_to_page)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PageRangeField(state: ToolFlowUiState, viewModel: ToolFlowViewModel) {
    val invalid = state.pageRangeText.isNotBlank() &&
        com.kobe.reader.core.pdf.PageSelection
            .parse(state.pageRangeText, state.sourcePageCount) == null

    OutlinedTextField(
        value = state.pageRangeText,
        onValueChange = viewModel::setPageRangeText,
        singleLine = true,
        isError = invalid,
        label = { Text(stringResource(R.string.page_ranges_hint)) },
        supportingText = {
            Text(
                if (invalid) {
                    stringResource(R.string.page_ranges_invalid, state.sourcePageCount)
                } else {
                    stringResource(R.string.pages_count, state.sourcePageCount)
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CompressOptions(state: ToolFlowUiState, viewModel: ToolFlowViewModel) {
    OptionSection(R.string.compression_level) {
        CompressionLevel.entries.forEach { level ->
            val locked = level !in state.availableCompression
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.compressionLevel == level,
                    onClick = { viewModel.setCompressionLevel(level) },
                )
                Text(stringResource(level.labelRes), Modifier.weight(1f))
                if (locked) ProBadge()
            }
        }
    }
}

@Composable
private fun PdfToImagesOptions(state: ToolFlowUiState, viewModel: ToolFlowViewModel) {
    OptionSection(R.string.image_format) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageFormat.entries.forEach { format ->
                FilterChip(
                    selected = state.imageFormat == format,
                    onClick = { viewModel.setImageFormat(format) },
                    label = { Text(format.extension.uppercase()) },
                )
            }
        }
    }
    OptionSection(R.string.image_quality) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(96, 150, 300).forEach { dpi ->
                FilterChip(
                    selected = state.imageDpi == dpi,
                    onClick = { viewModel.setImageDpi(dpi) },
                    label = { Text("$dpi dpi") },
                )
            }
        }
    }
    PageRangeField(state, viewModel)
}

@Composable
private fun RotateOptions(state: ToolFlowUiState, viewModel: ToolFlowViewModel) {
    OptionSection(R.string.tool_rotate) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(90, 180, 270).forEach { degrees ->
                FilterChip(
                    selected = state.rotationDegrees == degrees,
                    onClick = { viewModel.setRotation(degrees) },
                    label = { Text("$degrees°") },
                )
            }
        }
    }
}

@Composable
private fun ProtectOptions(state: ToolFlowUiState, viewModel: ToolFlowViewModel) {
    OptionSection(R.string.password_set) {
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            label = { Text(stringResource(R.string.password_set)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.passwordConfirm,
            onValueChange = viewModel::setPasswordConfirm,
            singleLine = true,
            isError = state.passwordConfirm.isNotEmpty() &&
                state.password != state.passwordConfirm,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            label = { Text(stringResource(R.string.password_confirm)) },
            supportingText = {
                if (state.passwordConfirm.isNotEmpty() &&
                    state.password != state.passwordConfirm
                ) {
                    Text(stringResource(R.string.password_mismatch))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(
            labelRes = R.string.allow_printing,
            checked = state.allowPrinting,
            onChange = viewModel::setAllowPrinting,
        )
        ToggleRow(
            labelRes = R.string.allow_copying,
            checked = state.allowCopying,
            onChange = viewModel::setAllowCopying,
        )
    }
}

@Composable
private fun OptionSection(titleRes: Int, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(labelRes), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SelectedFileRow(
    name: String,
    detail: String,
    position: Int?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (position != null) {
                Text(
                    text = position.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(20.dp),
                )
            }
            PdfGlyph(size = 32)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canMoveUp) {
                IconButton(onClick = onMoveUp) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = null,
                        modifier = Modifier.width(18.dp),
                    )
                }
            }
            if (canMoveDown) {
                IconButton(onClick = onMoveDown) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.width(18.dp),
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.action_remove),
                )
            }
        }
    }
}

@Composable
private fun RunBar(enabled: Boolean, remainingToday: Int?, onRun: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        if (remainingToday != null) {
            Text(
                text = stringResource(R.string.pro_free_left, remainingToday),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }
        Button(
            onClick = onRun,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_continue)) }
    }
}

private fun SplitModeChoice.labelRes(): Int = when (this) {
    SplitModeChoice.EveryPage -> R.string.split_every_page
    SplitModeChoice.AtPage -> R.string.split_at_page
    SplitModeChoice.CustomRanges -> R.string.split_custom_ranges
}
