package com.kobe.reader.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kobe.reader.R
import com.kobe.reader.pdf.render.PdfPageRenderer
import com.kobe.reader.ui.theme.PageBorder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A single rendered page.
 *
 * Two details do the heavy lifting:
 *
 *  - **Render at the drawn width.** The composable measures itself first, then
 *    asks for a bitmap that many pixels wide. Rendering at a fixed size and
 *    letting the GPU scale is what makes most PDF apps look soft.
 *  - **Re-render on zoom, don't upscale.** Past [ZOOM_RERENDER_THRESHOLD] the
 *    page is re-rasterised at the zoomed width, so text stays sharp instead of
 *    turning into a blurry bitmap. Below it, the cheap `graphicsLayer` scale is
 *    used so pinching stays at 60fps.
 */
@Composable
fun PdfPageView(
    pageIndex: Int,
    zoom: Float,
    loadPage: suspend (Int, Int) -> Bitmap?,
    prefetch: (Int, Int) -> Unit,
    onToggleChrome: () -> Unit,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var containerWidthPx by remember { mutableIntStateOf(0) }
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var renderedAtWidth by remember(pageIndex) { mutableIntStateOf(0) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Reset the transform when the page changes; carrying a pan across pages
    // leaves the next page mysteriously off-screen.
    LaunchedEffect(pageIndex) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    val requestedWidth = remember(containerWidthPx, scale) {
        if (containerWidthPx == 0) {
            0
        } else {
            val zoomed = if (scale > ZOOM_RERENDER_THRESHOLD) scale else 1f
            (containerWidthPx * zoomed)
                .roundToInt()
                .coerceAtMost(PdfPageRenderer.MAX_BITMAP_EDGE)
        }
    }

    LaunchedEffect(pageIndex, requestedWidth) {
        if (requestedWidth <= 0) return@LaunchedEffect
        // Skip a re-render for a trivial width change - a few pixels of
        // difference is invisible and a full rasterise is not cheap.
        if (bitmap != null && abs(requestedWidth - renderedAtWidth) < WIDTH_TOLERANCE_PX) {
            return@LaunchedEffect
        }
        loadPage(pageIndex, requestedWidth)?.let {
            bitmap = it
            renderedAtWidth = requestedWidth
        }
        prefetch(pageIndex, containerWidthPx)
    }

    val current = bitmap

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .onSizeChanged { containerWidthPx = it.width }
            .pointerInput(pageIndex) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = {
                        // Double tap toggles between fit-width and 2.5x, the
                        // behaviour every PDF viewer has trained people to expect.
                        val target = if (scale > 1.05f) 1f else DOUBLE_TAP_ZOOM
                        scale = target
                        offsetX = 0f
                        offsetY = 0f
                        onZoom(target)
                    },
                )
            }
            .pointerInput(pageIndex) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val next = (scale * gestureZoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    scale = next
                    if (next > 1f) {
                        // Clamp the pan so the page can't be dragged off-screen.
                        val maxX = (size.width * (next - 1f)) / 2f
                        val maxY = (size.height * (next - 1f)) / 2f
                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                    onZoom(next)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (current == null) {
            PagePlaceholder()
        } else {
            // The rendered bitmap already accounts for zoom past the threshold,
            // so only apply the residual scale here.
            val residual = if (scale > ZOOM_RERENDER_THRESHOLD) {
                scale / (renderedAtWidth.toFloat() / containerWidthPx.coerceAtLeast(1))
            } else {
                scale
            }

            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_page_thumbnail, pageIndex + 1),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = residual
                        scaleY = residual
                        translationX = offsetX
                        translationY = offsetY
                    }
                    .border(1.dp, PageBorder)
                    .background(Color.White),
            )
        }
    }
}

/** Keeps the scroll position stable while a page is still rasterising. */
@Composable
private fun PagePlaceholder() {
    Box(
        Modifier
            .fillMaxWidth()
            // A4 portrait, the overwhelmingly common case. Reserving roughly the
            // right height stops the list jumping as pages arrive.
            .aspectRatio(A4_ASPECT)
            .background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = Color.White.copy(alpha = 0.5f),
            strokeWidth = 2.dp,
        )
    }
}

private const val A4_ASPECT = 0.707f
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Below this, GPU scaling is indistinguishable from a re-render. */
private const val ZOOM_RERENDER_THRESHOLD = 1.2f
private const val WIDTH_TOLERANCE_PX = 24
