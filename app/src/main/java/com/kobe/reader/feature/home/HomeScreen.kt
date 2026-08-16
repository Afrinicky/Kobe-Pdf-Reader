package com.kobe.reader.feature.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.reader.R
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.file.DocumentRef
import com.kobe.reader.feature.tools.Tool
import com.kobe.reader.ui.components.DocumentActions
import com.kobe.reader.ui.components.DocumentRow
import com.kobe.reader.ui.components.EmptyState
import com.kobe.reader.ui.components.ErrorDialog
import com.kobe.reader.ui.components.SectionHeader
import com.kobe.reader.ui.components.ToolCard

/**
 * Home.
 *
 * Ordered by what people actually came to do: get back into the document they
 * were reading, then run a tool, then browse. Acrobat's home does the same, and
 * the ordering matters more than any individual widget - recents at the top is
 * why the app opens straight into useful state rather than an empty file list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (String) -> Unit,
    onOpenTool: (String) -> Unit,
    onSeeAllFiles: () -> Unit,
    onOrganize: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onUpgrade: () -> Unit,
    onResult: (List<String>, String, Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<KobeError?>(null) }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::openPicked) }

    val openFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::addFolder) }

    LaunchedEffect(event) {
        when (val current = event) {
            is HomeEvent.OpenDocument -> {
                onOpenDocument(current.document.uri.toString())
                viewModel.consumeEvent()
            }

            is HomeEvent.ShowError -> {
                error = current.error
                viewModel.consumeEvent()
            }

            null -> Unit
        }
    }

    error?.let { current ->
        ErrorDialog(error = current, onDismiss = { error = null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openDocument.launch(arrayOf(PDF_MIME)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.open_pdf)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                )
            }

            item { SectionHeader(titleRes = R.string.section_quick_tools) }
            item { QuickToolsRow(onOpenTool = onOpenTool) }

            if (state.needsFolderAccess) {
                item { FolderAccessCard(onChooseFolder = { openFolder.launch(null) }) }
            }

            if (state.recent.isNotEmpty()) {
                item {
                    SectionHeader(
                        titleRes = R.string.section_recent,
                        actionLabel = stringResource(R.string.see_all),
                        onAction = onSeeAllFiles,
                    )
                }
                items(state.recent, key = { it.uri.toString() }) { document ->
                    DocumentRow(
                        document = document,
                        actions = document.homeActions(
                            onOpenDocument = onOpenDocument,
                            onOrganize = onOrganize,
                            onToggleFavorite = { viewModel.toggleFavorite(document) },
                        ),
                    )
                }
            }

            if (state.favorites.isNotEmpty()) {
                item { SectionHeader(titleRes = R.string.tab_favorites) }
                items(state.favorites, key = { "fav_${it.uri}" }) { document ->
                    DocumentRow(
                        document = document,
                        actions = document.homeActions(
                            onOpenDocument = onOpenDocument,
                            onOrganize = onOrganize,
                            onToggleFavorite = { viewModel.toggleFavorite(document) },
                        ),
                    )
                }
            }

            if (!state.isLoading && state.recent.isEmpty() && state.favorites.isEmpty()) {
                item {
                    EmptyState(
                        iconRes = R.drawable.ic_pdf,
                        titleRes = R.string.empty_recent_title,
                        bodyRes = R.string.empty_recent_body,
                        modifier = Modifier.height(280.dp),
                        actionLabel = stringResource(R.string.open_pdf),
                        onAction = { openDocument.launch(arrayOf(PDF_MIME)) },
                    )
                }
            }

            if (!state.isPro) {
                item { UpgradeStrip(onUpgrade = onUpgrade) }
            }
        }
    }
}

@Composable
private fun QuickToolsRow(onOpenTool: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(Tool.quickTools, key = { it.key }) { tool ->
            ToolCard(
                iconRes = tool.iconRes,
                labelRes = tool.titleRes,
                onClick = { onOpenTool(tool.key) },
                showProBadge = tool.feature.isProOnly,
                modifier = Modifier.width(148.dp),
            )
        }
    }
}

@Composable
private fun FolderAccessCard(onChooseFolder: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.storage_grant_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.storage_grant_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onChooseFolder) {
            Icon(
                painter = painterResource(R.drawable.ic_folder_add),
                contentDescription = null,
                modifier = Modifier.width(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.storage_grant_action))
        }
    }
}

@Composable
private fun UpgradeStrip(onUpgrade: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.pro_headline),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.pro_benefit_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onUpgrade) { Text(stringResource(R.string.pro_upgrade)) }
    }
}

/** Home offers the safe subset of row actions; destructive ones live in Files. */
private fun DocumentRef.homeActions(
    onOpenDocument: (String) -> Unit,
    onOrganize: (String) -> Unit,
    onToggleFavorite: () -> Unit,
) = DocumentActions(
    onOpen = { onOpenDocument(uri.toString()) },
    onToggleFavorite = onToggleFavorite,
    onShare = { onOpenDocument(uri.toString()) },
    onRename = {},
    onDuplicate = {},
    onDelete = {},
    onOrganize = { onOrganize(uri.toString()) },
    onDetails = { onOpenDocument(uri.toString()) },
)

private const val PDF_MIME = "application/pdf"
