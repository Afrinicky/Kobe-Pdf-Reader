package com.kobe.reader.ui.navigation

import android.app.Activity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.kobe.reader.data.prefs.KobeSettings
import com.kobe.reader.feature.files.FilesScreen
import com.kobe.reader.feature.home.HomeScreen
import com.kobe.reader.feature.organize.OrganizeScreen
import com.kobe.reader.feature.paywall.PaywallScreen
import com.kobe.reader.feature.reader.ReaderScreen
import com.kobe.reader.feature.result.ResultScreen
import com.kobe.reader.feature.settings.SettingsScreen
import com.kobe.reader.feature.tools.ToolFlowScreen
import com.kobe.reader.feature.tools.ToolsScreen
import java.io.File

/**
 * Every route in one place.
 *
 * Screens receive plain lambdas rather than the [NavHostController] itself, so
 * no feature can navigate somewhere the graph doesn't know about, and each
 * screen stays previewable without a nav graph.
 */
@Composable
fun KobeNavHost(
    navController: NavHostController,
    settings: KobeSettings,
    activityProvider: () -> Activity?,
) {
    val navigator = remember(navController) { KobeNavigator(navController) }

    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        // Slide for lateral moves, fade for the tab bar. Kept short: 220ms is
        // about the point where an animation stops feeling like feedback and
        // starts feeling like a wait.
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(140)) },
    ) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenDocument = navigator::toReader,
                onOpenTool = navigator::toTool,
                onSeeAllFiles = navigator::toFiles,
                onOrganize = navigator::toOrganize,
                onOpenSettings = navigator::toSettings,
                onUpgrade = { navigator.toPaywall(null) },
                onResult = navigator::toResult,
            )
        }

        composable<FilesRoute> {
            FilesScreen(
                onOpenDocument = navigator::toReader,
                onOrganize = navigator::toOrganize,
                onOpenSettings = navigator::toSettings,
                onResult = navigator::toResult,
            )
        }

        composable<ToolsRoute> {
            ToolsScreen(
                onOpenTool = navigator::toTool,
                onUpgrade = { feature -> navigator.toPaywall(feature) },
            )
        }

        composable<ToolRoute>(
            enterTransition = { slideIn() },
            exitTransition = { fadeOut(tween(140)) },
        ) { entry ->
            val route = entry.toRoute<ToolRoute>()
            ToolFlowScreen(
                toolKey = route.tool,
                onBack = navigator::back,
                onDone = navigator::toResult,
                onUpgrade = { feature -> navigator.toPaywall(feature) },
                activityProvider = activityProvider,
            )
        }

        composable<ReaderRoute>(
            enterTransition = { slideIn() },
            exitTransition = { fadeOut(tween(140)) },
        ) { entry ->
            val route = entry.toRoute<ReaderRoute>()
            ReaderScreen(
                documentUri = route.uri.toUri(),
                settings = settings,
                onBack = navigator::back,
                onOrganize = { navigator.toOrganize(route.uri) },
            )
        }

        composable<OrganizeRoute>(
            enterTransition = { slideIn() },
            exitTransition = { fadeOut(tween(140)) },
        ) { entry ->
            val route = entry.toRoute<OrganizeRoute>()
            OrganizeScreen(
                documentUri = route.uri.toUri(),
                onBack = navigator::back,
                onDone = navigator::toResult,
                onUpgrade = { feature -> navigator.toPaywall(feature) },
            )
        }

        composable<ResultRoute> { entry ->
            val route = entry.toRoute<ResultRoute>()
            ResultScreen(
                files = route.paths.map(::File),
                title = route.title,
                originalBytes = route.originalBytes,
                onOpenDocument = navigator::toReader,
                onDone = navigator::backToHome,
                activityProvider = activityProvider,
            )
        }

        composable<SettingsRoute>(
            enterTransition = { slideIn() },
        ) {
            SettingsScreen(
                settings = settings,
                onBack = navigator::back,
                onUpgrade = { navigator.toPaywall(null) },
            )
        }

        composable<PaywallRoute> { entry ->
            val route = entry.toRoute<PaywallRoute>()
            PaywallScreen(
                triggeredBy = route.triggeredBy,
                onDismiss = navigator::back,
                activityProvider = activityProvider,
            )
        }
    }
}

private fun AnimatedContentTransitionScope<*>.slideIn() = slideIntoContainer(
    towards = AnimatedContentTransitionScope.SlideDirection.Start,
    animationSpec = tween(220),
)

/**
 * Wraps the controller so screens depend on named intentions rather than on
 * navigation mechanics.
 */
class KobeNavigator(private val controller: NavHostController) {

    fun back() {
        controller.popBackStack()
    }

    fun backToHome() {
        controller.popBackStack(route = HomeRoute, inclusive = false)
    }

    fun toReader(uri: String) = controller.navigate(ReaderRoute(uri))

    fun toOrganize(uri: String) = controller.navigate(OrganizeRoute(uri))

    fun toTool(toolKey: String) = controller.navigate(ToolRoute(toolKey))

    fun toFiles() = controller.navigate(FilesRoute) {
        popUpTo(HomeRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    fun toSettings() = controller.navigate(SettingsRoute)

    fun toPaywall(featureName: String?) = controller.navigate(PaywallRoute(featureName))

    /**
     * Replaces the tool screen rather than stacking on it: pressing back from a
     * result should return to where the user started the tool, not re-enter the
     * form they just submitted.
     */
    fun toResult(paths: List<String>, title: String, originalBytes: Long) {
        controller.navigate(ResultRoute(paths, title, originalBytes)) {
            popUpTo<ToolRoute> { inclusive = true }
            launchSingleTop = true
        }
    }
}
