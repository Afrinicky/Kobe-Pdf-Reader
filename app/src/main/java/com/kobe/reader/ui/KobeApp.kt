package com.kobe.reader.ui

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kobe.reader.data.prefs.KobeSettings
import com.kobe.reader.ui.navigation.KobeNavHost
import com.kobe.reader.ui.navigation.ReaderRoute
import com.kobe.reader.ui.navigation.TopLevelDestination
import kotlinx.coroutines.flow.StateFlow

/**
 * App shell: bottom navigation plus the nav host.
 *
 * The bottom bar hides on anything that isn't a top-level destination. The
 * reader in particular needs the whole screen - Acrobat does the same, and a
 * persistent bar eating 80dp of a page is the difference between one screenful
 * of text and two.
 */
@Composable
fun KobeApp(
    settings: KobeSettings,
    incomingDocument: StateFlow<Uri?>,
    onDocumentConsumed: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val topLevel = TopLevelDestination.entries.firstOrNull { entry ->
        currentDestination?.hierarchy?.any { node ->
            node.hasRoute(entry.route::class)
        } == true
    }

    val incoming by incomingDocument.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    // A PDF arriving from another app should land in the reader immediately,
    // whatever screen the user was last on.
    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        navController.navigate(ReaderRoute(uri.toString()))
        onDocumentConsumed()
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = topLevel != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                KobeBottomBar(
                    selected = topLevel,
                    onSelect = { destination ->
                        navController.navigate(destination.route) {
                            // Standard bottom-bar behaviour: one entry per tab,
                            // state preserved, and back always returns to Home
                            // rather than walking the tab history.
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                // Full-bleed screens (the reader) opt out of top insets
                // themselves; every other screen wants the bar accounted for.
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            KobeNavHost(
                navController = navController,
                settings = settings,
                activityProvider = { activity },
            )
        }
    }
}

@Composable
private fun KobeBottomBar(
    selected: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        painter = painterResource(destination.iconRes),
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

/** `NavDestination.hierarchy` lives in an import that reads badly inline. */
private val androidx.navigation.NavDestination.hierarchy: Sequence<androidx.navigation.NavDestination>
    get() = generateSequence(this) { it.parent }
