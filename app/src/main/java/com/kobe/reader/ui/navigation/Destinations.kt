package com.kobe.reader.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.kobe.reader.R
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes.
 *
 * Every destination is a `@Serializable` data class or object, so arguments are
 * checked at compile time and survive process death without any manual
 * bundle plumbing. Nothing here holds a lambda or a composable - a route
 * describes *where you are*, not what is drawn.
 */
sealed interface Destination

// --- Top level (bottom bar) -------------------------------------------------

@Serializable
data object HomeRoute : Destination

@Serializable
data object FilesRoute : Destination

@Serializable
data object ToolsRoute : Destination

/**
 * Acrobat's bottom bar: Home, Files, Tools. Three destinations is the right
 * number - it keeps every top-level surface one tap away without a hamburger.
 */
enum class TopLevelDestination(
    val route: Destination,
    @get:StringRes val labelRes: Int,
    @get:DrawableRes val iconRes: Int,
) {
    Home(HomeRoute, R.string.nav_home, R.drawable.ic_home),
    Files(FilesRoute, R.string.nav_files, R.drawable.ic_files),
    Tools(ToolsRoute, R.string.nav_tools, R.drawable.ic_tools),
}

// --- Detail screens ---------------------------------------------------------

@Serializable
data class ReaderRoute(val uri: String) : Destination

@Serializable
data class OrganizeRoute(val uri: String) : Destination

@Serializable
data object SettingsRoute : Destination

@Serializable
data class PaywallRoute(
    /** Name of the [com.kobe.reader.monetization.ProFeature] that triggered it. */
    val triggeredBy: String? = null,
) : Destination

/**
 * One route for every tool flow, distinguished by [tool].
 *
 * A single parameterised route rather than eight near-identical ones: the tool
 * screens share a picker, a progress state and a result state, and differ only
 * in their options panel.
 */
@Serializable
data class ToolRoute(val tool: String) : Destination

@Serializable
data class ResultRoute(
    /** Absolute paths of the produced files. */
    val paths: List<String>,
    val title: String,
    /** Original size in bytes, for the "N% smaller" line. 0 when not relevant. */
    val originalBytes: Long = 0L,
) : Destination
