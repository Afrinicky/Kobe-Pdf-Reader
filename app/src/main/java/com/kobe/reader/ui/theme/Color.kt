package com.kobe.reader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Acrobat-derived palette.
 *
 * The identity is the red: a saturated, slightly warm crimson used sparingly on
 * actions and the PDF glyph, over near-neutral greys. Adobe's own UI is
 * deliberately quiet - the document is the content, and chrome that competes
 * with it is chrome in the way - so everything except the accent stays low
 * chroma.
 */

// --- Brand -----------------------------------------------------------------
val KobeRed = Color(0xFFD0271D)
val KobeRedDark = Color(0xFFA31A12)
val KobeRedLight = Color(0xFFFF6E63)
val KobeRedContainer = Color(0xFFFFDAD5)
val KobeRedContainerDark = Color(0xFF8C1409)

// --- Light scheme ----------------------------------------------------------
val LightPrimary = KobeRed
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = KobeRedContainer
val LightOnPrimaryContainer = Color(0xFF410001)

val LightSecondary = Color(0xFF775651)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFFFDAD5)
val LightOnSecondaryContainer = Color(0xFF2C1512)

val LightTertiary = Color(0xFF705C2E)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFCDFA6)
val LightOnTertiaryContainer = Color(0xFF261A00)

val LightBackground = Color(0xFFFFFBFF)
val LightOnBackground = Color(0xFF201A19)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF201A19)
val LightSurfaceVariant = Color(0xFFF5DDDA)
val LightOnSurfaceVariant = Color(0xFF534341)
val LightOutline = Color(0xFF857371)
val LightOutlineVariant = Color(0xFFE5E1E6)
val LightSurfaceContainer = Color(0xFFF7F2F2)
val LightSurfaceContainerHigh = Color(0xFFF1ECEC)
val LightSurfaceContainerLow = Color(0xFFFBF6F6)

val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)

// --- Dark scheme -----------------------------------------------------------
val DarkPrimary = KobeRedLight
val DarkOnPrimary = Color(0xFF690002)
val DarkPrimaryContainer = KobeRedContainerDark
val DarkOnPrimaryContainer = Color(0xFFFFDAD5)

val DarkSecondary = Color(0xFFE7BDB6)
val DarkOnSecondary = Color(0xFF442925)
val DarkSecondaryContainer = Color(0xFF5D3F3B)
val DarkOnSecondaryContainer = Color(0xFFFFDAD5)

val DarkTertiary = Color(0xFFDFC38C)
val DarkOnTertiary = Color(0xFF3E2E04)
val DarkTertiaryContainer = Color(0xFF564419)
val DarkOnTertiaryContainer = Color(0xFFFCDFA6)

val DarkBackground = Color(0xFF141212)
val DarkOnBackground = Color(0xFFEDE0DE)
val DarkSurface = Color(0xFF1A1717)
val DarkOnSurface = Color(0xFFEDE0DE)
val DarkSurfaceVariant = Color(0xFF534341)
val DarkOnSurfaceVariant = Color(0xFFD8C2BE)
val DarkOutline = Color(0xFFA08C8A)
val DarkOutlineVariant = Color(0xFF3A3535)
val DarkSurfaceContainer = Color(0xFF231F1F)
val DarkSurfaceContainerHigh = Color(0xFF2E2929)
val DarkSurfaceContainerLow = Color(0xFF1D1A1A)

val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)

// --- Reader surface --------------------------------------------------------
/**
 * The backdrop pages sit on. Acrobat, Preview and Chrome's viewer all use a
 * mid-dark neutral for this rather than the app's surface colour: a white page
 * needs a darker frame to read as a sheet of paper, and it stays the same in
 * both themes so a document looks identical whichever theme you're in.
 */
val ReaderBackdrop = Color(0xFF303234)
val ReaderBackdropDark = Color(0xFF141516)

/** Thin border drawn around each page so white pages don't bleed into grey. */
val PageBorder = Color(0x1F000000)
