package com.kobe.reader.di

import com.kobe.reader.monetization.ads.AdMobAdsController
import com.kobe.reader.monetization.ads.AdsController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Monetization wiring.
 *
 * To ship an ad-free build, change the single binding below to
 * [com.kobe.reader.monetization.ads.NoOpAdsController] and drop
 * `play-services-ads` from `app/build.gradle.kts`. Nothing else in the app
 * references the ad SDK.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MonetizationModule {

    @Binds
    @Singleton
    abstract fun bindsAdsController(implementation: AdMobAdsController): AdsController
}
