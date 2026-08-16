package com.kobe.reader.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * The only place in the UI that touches the ad SDK.
 *
 * Uses an *anchored adaptive* banner rather than a fixed 320x50: adaptive
 * banners size themselves to the device width, which both fills better on
 * tablets and earns noticeably more than a stretched fixed banner.
 */
@Composable
fun AdBannerView(unitId: String, modifier: Modifier = Modifier) {
    val widthDp = LocalConfiguration.current.screenWidthDp

    AndroidView(
        modifier = modifier,
        factory = { context: Context ->
            AdView(context).apply {
                setAdSize(
                    AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp),
                )
                adUnitId = unitId
                loadAd(AdRequest.Builder().build())
            }
        },
        // AdView owns a WebView; releasing it here stops it leaking the Activity
        // when the composable leaves the tree.
        onRelease = { adView -> adView.destroy() },
    )
}
