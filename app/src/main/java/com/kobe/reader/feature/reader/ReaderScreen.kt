package com.kobe.reader.feature.reader

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.reader.R
import com.kobe.reader.data.prefs.KobeSettings
import com.kobe.reader.ui.components.ErrorDialog
import com.kobe.reader.ui.theme.ReaderColors
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The reading surface.
 *
 * Chrome auto-hides on tap so a page can use the whole screen, matching every
 * serious reader. The page list is a plain [LazyColumn] rather than a pager by
 * default: continuous vertical scrolling is what people expect from a phone
 * PDF reader, with horizontal paging available as a setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    documentUri: Uri,
    settings: KobeSettings,
    onBack: () -> Unit,
    onOrganize: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(documentUri) { viewModel.open(documentUri) }

    // Persist the position on the way out rather than on every scroll: a write
    // per frame would be absurd, and leaving is the only moment that matters.
    DisposableEffect(Unit) {
        onDispose { if (settings.rememberPosition) viewModel.savePosition() }
    }

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = state.searchVisible) { viewModel.closeSearch() }

    if (state.needsPassword) {
        PasswordPrompt(
            wasWrong = state.passwordWasWrong,
            onSubmit = viewModel::submitPassword,
            onCancel = onBack,
        )
        return
    }

    state.error?.let { error ->
        ErrorDialog(error = error, onDismiss = { viewModel.dismissError(); onBack() })
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ReaderColors.backdrop),
    ) {
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }

            state.hasDocument -> PageSurface(
                state = state,
                horizontalPaging = settings.horizontalPaging,
                onPageChanged = viewModel::onPageChanged,
                onToggleChrome = viewModel::toggleChrome,
                onZoom = viewModel::setZoom,
                loadPage = viewModel::pageBitmap,
                prefetch = viewModel::prefetchAround,
            )
        }

        AnimatedVisibility(
            visible = state.chromeVisible,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = state.documentInfo?.title
                    ?: documentUri.lastPathSegment?.substringAfterLast('/').orEmpty(),
                searchVisible = state.searchVisible,
                searchQuery = state.searchQuery,
                hitLabel = state.activeHitLabel,
                isSearching = state.isSearching,
                onBack = onBack,
                onOpenSearch = viewModel::openSearch,
                onCloseSearch = viewModel::closeSearch,
                onQueryChanged = viewModel::onSearchQueryChanged,
                onNextHit = viewModel::nextHit,
                onPreviousHit = viewModel::previousHit,
                onOrganize = onOrganize,
            )
        }

        AnimatedVisibility(
            visible = state.chromeVisible && state.hasDocument,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PageIndicator(current = state.currentPage + 1, total = state.pageCount)
        }
    }
}

@Composable
private fun PageSurface(
    state: ReaderUiState,
    horizontalPaging: Boolean,
    onPageChanged: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onZoom: (Float) -> Unit,
    loadPage: suspend (Int, Int) -> android.graphics.Bitmap?,
    prefetch: (Int, Int) -> Unit,
) {
    if (horizontalPaging) {
        val pagerState = rememberPagerState(
            initialPage = state.currentPage,
            pageCount = { state.pageCount },
        )
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect(onPageChanged)
        }
        LaunchedEffect(state.currentPage) {
            if (pagerState.currentPage != state.currentPage) {
                pagerState.animateScrollToPage(state.currentPage)
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            PdfPageView(
                pageIndex = page,
                zoom = state.zoom,
                loadPage = loadPage,
                prefetch = prefetch,
                onToggleChrome = onToggleChrome,
                onZoom = onZoom,
            )
        }
        return
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(120)
            .distinctUntilChanged()
            .collect(onPageChanged)
    }
    // Search results and the page jumper drive the list from state.
    LaunchedEffect(state.currentPage) {
        if (listState.firstVisibleItemIndex != state.currentPage) {
            listState.animateScrollToItem(state.currentPage)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(count = state.pageCount, key = { it }) { page ->
            PdfPageView(
                pageIndex = page,
                zoom = state.zoom,
                loadPage = loadPage,
                prefetch = prefetch,
                onToggleChrome = onToggleChrome,
                onZoom = onZoom,
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    searchVisible: Boolean,
    searchQuery: String,
    hitLabel: String,
    isSearching: Boolean,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onNextHit: () -> Unit,
    onPreviousHit: () -> Unit,
    onOrganize: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.systemBars)) {
            if (searchVisible) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onCloseSearch) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.cd_close),
                        )
                    }
                    TextField(
                        value = searchQuery,
                        onValueChange = onQueryChanged,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.reader_search_hint)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        isSearching -> CircularProgressIndicator(
                            modifier = Modifier
                                .width(20.dp)
                                .height(20.dp),
                            strokeWidth = 2.dp,
                        )

                        hitLabel.isNotEmpty() -> {
                            Text(hitLabel, style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = onPreviousHit) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron_left),
                                    contentDescription = stringResource(
                                        R.string.cd_previous_match,
                                    ),
                                )
                            }
                            IconButton(onClick = onNextHit) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron_right),
                                    contentDescription = stringResource(R.string.cd_next_match),
                                )
                            }
                        }

                        searchQuery.isNotBlank() -> Text(
                            text = stringResource(R.string.reader_no_matches),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
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
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = stringResource(R.string.cd_search),
                            )
                        }
                        IconButton(onClick = onOrganize) {
                            Icon(
                                painter = painterResource(R.drawable.ic_organize),
                                contentDescription = stringResource(R.string.tool_reorder),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                    windowInsets = WindowInsets(0),
                )
            }
        }
    }
}

@Composable
private fun PageIndicator(current: Int, total: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.reader_page_of, current, total),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
