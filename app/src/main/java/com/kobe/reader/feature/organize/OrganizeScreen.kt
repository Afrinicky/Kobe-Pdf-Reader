package com.kobe.reader.feature.organize

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.reader.R
import com.kobe.reader.ui.components.ErrorDialog
import com.kobe.reader.ui.components.OperationProgressDialog

/**
 * Thumbnail grid page editor.
 *
 * Reordering is done with explicit move buttons on the selected page rather
 * than free drag-and-drop. Dragging inside a lazy grid that is simultaneously
 * rendering thumbnails is fiddly on a phone and easy to trigger by accident;
 * "select, then nudge" is slower per move but never loses a page by mistake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(
    documentUri: Uri,
    onBack: () -> Unit,
    onDone: (List<String>, String, Long) -> Unit,
    onUpgrade: (String?) -> Unit,
    viewModel: OrganizeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(documentUri) { viewModel.load(documentUri) }

    LaunchedEffect(state.results) {
        if (state.results.isNotEmpty()) {
            onDone(
                state.results.map { it.absolutePath },
                context.getString(R.string.organize_title),
                0L,
            )
        }
    }

    state.blockedFeature?.let { feature ->
        LaunchedEffect(feature) {
            viewModel.dismissBlock()
            onUpgrade(feature)
        }
    }

    state.error?.let { error ->
        ErrorDialog(error = error, onDismiss = viewModel::dismissError)
    }

    if (state.isSaving) {
        OperationProgressDialog(
            progress = state.progress,
            titleRes = R.string.organize_title,
            onCancel = viewModel::cancel,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.hasSelection) {
                            stringResource(R.string.organize_selected, state.selectionCount)
                        } else {
                            stringResource(R.string.organize_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (state.hasSelection) {
                        TextButton(onClick = viewModel::clearSelection) {
                            Text(stringResource(R.string.organize_clear))
                        }
                    } else {
                        TextButton(onClick = viewModel::selectAll) {
                            Text(stringResource(R.string.organize_select_all))
                        }
                    }
                    TextButton(onClick = viewModel::save, enabled = state.canSave) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (state.hasSelection) {
                SelectionToolbar(
                    onRotateLeft = { viewModel.rotateSelected(-90) },
                    onRotateRight = { viewModel.rotateSelected(90) },
                    onExtract = viewModel::extractSelected,
                    onDelete = viewModel::deleteSelected,
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding(),
                ),
        ) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            } else {
                Column {
                    Text(
                        text = stringResource(R.string.organize_drag_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(
                            items = state.pages,
                            key = { _, page -> page.sourcePage },
                        ) { index, page ->
                            PageThumbnail(
                                pageNumber = index + 1,
                                sourcePage = page.sourcePage,
                                rotation = page.rotation,
                                isSelected = page.sourcePage in state.selected,
                                canMoveLeft = index > 0,
                                canMoveRight = index < state.pages.lastIndex,
                                onClick = { viewModel.toggleSelection(page.sourcePage) },
                                onMoveLeft = { viewModel.move(index, index - 1) },
                                onMoveRight = { viewModel.move(index, index + 1) },
                                loadThumbnail = viewModel::thumbnail,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageThumbnail(
    pageNumber: Int,
    sourcePage: Int,
    rotation: Int,
    isSelected: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onClick: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    loadThumbnail: suspend (Int, Int) -> Bitmap?,
) {
    var bitmap by remember(sourcePage) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(sourcePage) {
        bitmap = loadThumbnail(sourcePage, THUMBNAIL_WIDTH_PX)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(THUMBNAIL_ASPECT)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .border(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(6.dp),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            if (current == null) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .width(20.dp)
                        .height(20.dp),
                )
            } else {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_page_thumbnail, pageNumber),
                    modifier = Modifier
                        .fillMaxSize()
                        // Rotation is previewed here rather than re-rendered:
                        // a rotate is instant this way, and the real rotation is
                        // applied once on save.
                        .graphicsLayer { rotationZ = rotation.toFloat() },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSelected && canMoveLeft) {
                IconButton(onClick = onMoveLeft, modifier = Modifier.width(28.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = null,
                        modifier = Modifier.width(16.dp),
                    )
                }
            }
            Text(
                text = pageNumber.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(vertical = 4.dp),
            )
            if (isSelected && canMoveRight) {
                IconButton(onClick = onMoveRight, modifier = Modifier.width(28.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        modifier = Modifier.width(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionToolbar(
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onExtract: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarAction(R.drawable.ic_rotate_left, R.string.organize_rotate_left, onRotateLeft)
        ToolbarAction(R.drawable.ic_rotate_right, R.string.organize_rotate_right, onRotateRight)
        ToolbarAction(R.drawable.ic_extract, R.string.organize_extract_selected, onExtract)
        ToolbarAction(R.drawable.ic_delete, R.string.organize_delete_selected, onDelete)
    }
}

@Composable
private fun ToolbarAction(iconRes: Int, labelRes: Int, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(painter = painterResource(iconRes), contentDescription = null)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall)
    }
}

private const val THUMBNAIL_WIDTH_PX = 220
private const val THUMBNAIL_ASPECT = 0.72f
