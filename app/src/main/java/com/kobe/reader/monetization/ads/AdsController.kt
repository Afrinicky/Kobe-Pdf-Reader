package com.kobe.reader.monetization.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.kobe.reader.R
import com.kobe.reader.di.ApplicationScope
import com.kobe.reader.monetization.PremiumManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ad placement policy, kept behind one interface so the free tier's advertising
 * can be changed - or removed entirely for an ad-free build - without touching
 * a single screen.
 */
interface AdsController {
    /** Ad unit for the banner slot, or null when no banner should show. */
    suspend fun bannerUnitId(): String?

    /** Called at points where an interstitial is acceptable. May do nothing. */
    fun onInterstitialOpportunity(activity: Activity)

    /** Warms up the SDK. Safe to call more than once. */
    fun prepare()
}

/**
 * The default: no ads at all.
 *
 * Bound in place of [AdMobAdsController] for an ad-free build, and used
 * automatically for Pro users.
 */
@Singleton
class NoOpAdsController @Inject constructor() : AdsController {
    override suspend fun bannerUnitId(): String? = null
    override fun onInterstitialOpportunity(activity: Activity) = Unit
    override fun prepare() = Unit
}

/**
 * AdMob implementation.
 *
 * Two rules encoded here, both about not being obnoxious:
 *  - Interstitials appear **after** a completed operation, never before or
 *    during one, and never in the reader. Interrupting someone mid-document is
 *    the fastest route to a one-star review.
 *  - There is a hard floor between interstitials. Frequency capping in the
 *    AdMob console is best-effort; this is not.
 *
 * The SDK is initialised lazily on first use, so a Pro user never pays the
 * cold-start cost of a library they will never see.
 */
@Singleton
class AdMobAdsController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val premium: PremiumManager,
    @param:ApplicationScope private val scope: CoroutineScope,
) : AdsController {

    private val initialised = AtomicBoolean(false)
    private var interstitial: InterstitialAd? = null
    private var lastInterstitialAt = 0L
    private var operationsSinceLastAd = 0

    override fun prepare() {
        scope.launch {
            if (!premium.shouldShowAds.first()) return@launch
            initialise()
            loadInterstitial()
        }
    }

    override suspend fun bannerUnitId(): String? =
        if (premium.shouldShowAds.first()) {
            initialise()
            context.getString(R.string.admob_unit_banner)
        } else {
            null
        }

    override fun onInterstitialOpportunity(activity: Activity) {
        scope.launch {
            if (!premium.shouldShowAds.first()) return@launch

            operationsSinceLastAd++
            val elapsed = System.currentTimeMillis() - lastInterstitialAt
            val eligible = operationsSinceLastAd >= OPERATIONS_BETWEEN_ADS &&
                elapsed >= MIN_INTERVAL_MILLIS

            if (!eligible) {
                if (interstitial == null) loadInterstitial()
                return@launch
            }

            val ad = interstitial
            if (ad == null) {
                loadInterstitial()
                return@launch
            }

            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitial = null
                    loadInterstitial()
                }

                override fun onAdFailedToShowFullScreenContent(error: AdError) {
                    Log.w(TAG, "interstitial failed to show: ${error.message}")
                    interstitial = null
                }
            }
            lastInterstitialAt = System.currentTimeMillis()
            operationsSinceLastAd = 0
            ad.show(activity)
        }
    }

    private fun initialise() {
        if (initialised.compareAndSet(false, true)) {
            MobileAds.initialize(context) { Log.i(TAG, "AdMob ready") }
        }
    }

    private fun loadInterstitial() {
        if (interstitial != null) return
        initialise()
        InterstitialAd.load(
            context,
            context.getString(R.string.admob_unit_interstitial),
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // No fill is routine. Not retrying here avoids hammering the
                    // network on a device that simply has no ads available.
                    Log.i(TAG, "no interstitial: ${error.message}")
                    interstitial = null
                }
            },
        )
    }

    private companion object {
        const val TAG = "AdMobAdsController"
        const val OPERATIONS_BETWEEN_ADS = 3
        const val MIN_INTERVAL_MILLIS = 3 * 60 * 1000L
    }
}
