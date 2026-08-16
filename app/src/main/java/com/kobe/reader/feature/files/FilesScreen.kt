package com.kobe.reader.feature.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.reader.R
import com.kobe.reader.core.error.KobeError
import com.kobe.reader.core.file.DocumentRef
import com.kobe.reader.core.file.FileNaming
import com.kobe.reader.data.prefs.SortOrder
import com.kobe.reader.ui.components.DocumentActions
import com.kobe.reader.ui.components.DocumentRow
import com.kobe.reader.ui.components.DocumentRowPlaceholder
import com.kobe.reader.ui.components.EmptyState
import com.kobe.reader.ui.components.ErrorDialog
import com.kobe.reader.ui.components.TextInputDialog

/**
 * The file browser: All / Recent / Favorites, with search, sort and the full
 * document action set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onOpenDocument: (String) -> Unit,
    onOrganize: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var error by remember { mutableStateOf<KobeError?>(null) }
    var renaming by remember { mutableStateOf<DocumentRef?>(null) }
    var deleting by remember { mutableStateOf<DocumentRef?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::openPicked) }

    val openFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::addFolder) }

    LaunchedEffect(event) {
        when (val current = event) {
            is FilesEvent.OpenDocument -> {
                onOpenDocument(current.uri)
                viewModel.consumeEvent()
            }

            is FilesEvent.Share -> {
                context.startActivity(current.intent)
                viewModel.consumeEvent()
            }

            is FilesEvent.ShowError -> {
                error = current.error
                viewModel.consumeEvent()
            }

            null -> Unit
        }
    }

    error?.let { current -> ErrorDialog(error = current, onDismiss = { error = null }) }

    renaming?.let { document ->
        TextInputDialog(
            titleRes = R.string.action_rename,
            initialValue = document.baseName,
            onConfirm = { newName ->
                viewModel.rename(document, newName)
                renaming = null
            },
            onDismiss = { renaming = null },
            validate = { value ->
                if (FileNaming.sanitiseBase(value).isEmpty()) R.string.error_empty_name else null
            },
        )
    }

    deleting?.let { document ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(document.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(document)
                        deleting = null
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.library_title)) },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = stringResource(R.string.cd_search),
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_sort),
                                    contentDescription = stringResource(R.string.sort_by),
                                )
                            }
                            SortMenu(
                                expanded = sortMenuOpen,
                                current = state.sortOrder,
                                onSelect = {
                                    viewModel.setSortOrder(it)
                                    sortMenuOpen = false
                                },
                                onDismiss = { sortMenuOpen = false },
                            )
                        }
                        IconButton(onClick = { openFolder.launch(null) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder_add),
                                contentDescription = stringResource(R.string.add_folder),
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                painter = painterResource(R.drawable.ic_settings),
                                contentDescription = stringResource(R.string.settings_title),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )

                if (searchActive) {
                    SearchField(
                        query = state.query,
                        onQueryChange = viewModel::search,
                        onClose = {
                            viewModel.search("")
                            searchActive = false
                        },
                    )
                }

                TabRow(
                    selectedTabIndex = state.tab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    FilesTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == state.tab,
                            onClick = { viewModel.selectTab(tab) },
                            text = { Text(stringResource(tab.labelRes())) },
                        )
                    }
                }

                if (state.isRefreshing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { openDocument.launch(arrayOf("application/pdf")) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = stringResource(R.string.open_pdf),
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            when {
                state.isLoading -> LazyColumn {
                    items(6) { DocumentRowPlaceholder() }
                }

                state.documents.isEmpty() -> EmptyState(
                    iconRes = if (state.isSearching) {
                        R.drawable.ic_search
                    } else {
                        R.drawable.ic_pdf
                    },
                    titleRes = state.emptyTitleRes(),
                    bodyRes = state.emptyBodyRes(),
                    actionLabel = if (state.isSearching) {
                        null
                    } else {
                        stringResource(R.string.add_folder)
                    },
                    onAction = if (state.isSearching) null else ({ openFolder.launch(null) }),
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.documents, key = { it.uri.toString() }) { document ->
                        DocumentRow(
                            document = document,
                            actions = DocumentActions(
                                onOpen = { onOpenDocument(document.uri.toString()) },
                                onToggleFavorite = { viewModel.toggleFavorite(document) },
                                onShare = { viewModel.share(document) },
                                onRename = { renaming = document },
                                onDuplicate = { viewModel.duplicate(document) },
                                onDelete = { deleting = document },
                                onOrganize = { onOrganize(document.uri.toString()) },
                                onDetails = { onOpenDocument(document.uri.toString()) },
                            ),
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 70.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                placeholder = { Text(stringResource(R.string.search_documents)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.cd_close),
                        )
                    }
                },
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        content = {},
    )
}

@Composable
private fun SortMenu(
    expanded: Boolean,
    current: SortOrder,
    onSelect: (SortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortOrder.entries.forEach { order ->
            DropdownMenuItem(
                text = { Text(stringResource(order.labelRes()) + order.directionSuffix()) },
                leadingIcon = {
                    RadioButton(selected = order == current, onClick = null)
                },
                onClick = { onSelect(order) },
            )
        }
    }
}

private fun FilesTab.labelRes(): Int = when (this) {
    FilesTab.All -> R.string.tab_all
    FilesTab.Recent -> R.string.tab_recent
    FilesTab.Favorites -> R.string.tab_favorites
}

private fun SortOrder.labelRes(): Int = when (this) {
    SortOrder.NameAscending, SortOrder.NameDescending -> R.string.sort_name
    SortOrder.DateAscending, SortOrder.DateDescending -> R.string.sort_date
    SortOrder.SizeAscending, SortOrder.SizeDescending -> R.string.sort_size
}

private fun SortOrder.directionSuffix(): String = if (isDescending) "  ↓" else "  ↑"

private fun FilesUiState.emptyTitleRes(): Int = when {
    isSearching -> R.string.empty_search_title
    tab == FilesTab.Favorites -> R.string.empty_favorites_title
    tab == FilesTab.Recent -> R.string.empty_recent_title
    else -> R.string.empty_library_title
}

private fun FilesUiState.emptyBodyRes(): Int = when {
    isSearching -> R.string.empty_search_body
    tab == FilesTab.Favorites -> R.string.empty_favorites_body
    tab == FilesTab.Recent -> R.string.empty_recent_body
    else -> R.string.empty_library_body
}
