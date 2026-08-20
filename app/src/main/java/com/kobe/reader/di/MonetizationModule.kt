package com.kobe.reader.di

import com.kobe.reader.monetization.ads.AdsController
import com.kobe.reader.monetization.ads.NoOpAdsController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Monetization wiring.
 *
 * Currently bound to [NoOpAdsController] - no ads run at all. This was set while
 * chasing a launch crash, to take the Google Mobile Ads SDK entirely out of the
 * runtime picture. To re-enable ads, switch the binding to
 * [com.kobe.reader.monetization.ads.AdMobAdsController] (the SDK dependency and
 * AdMobAdsController are still in the project). Nothing else references the ad SDK.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MonetizationModule {

    @Binds
    @Singleton
    abstract fun bindsAdsController(implementation: NoOpAdsController): AdsController
}
