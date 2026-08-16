package com.kobe.reader.monetization

import com.kobe.reader.pdf.CompressionLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that answers "can this user do that?".
 *
 * Every tool asks [checkAccess] before starting work and calls [recordUse] after
 * it succeeds. Nothing else in the app reads entitlement state directly, so the
 * free/Pro split stays a policy decision rather than something smeared across
 * a dozen ViewModels.
 */
@Singleton
class PremiumManager @Inject constructor(
    private val entitlements: EntitlementRepository,
) {

    val isPro: Flow<Boolean> = entitlements.isPro

    /** Ads only ever show to free users. */
    val shouldShowAds: Flow<Boolean> = entitlements.isPro.map { !it }

    suspend fun checkAccess(feature: ProFeature): FeatureAccess {
        if (entitlements.isPro.first()) return FeatureAccess.Allowed(remainingToday = null)
        if (feature.isUnlimited) return FeatureAccess.Allowed(remainingToday = null)
        if (feature.isProOnly) return FeatureAccess.RequiresPro(feature)

        val used = entitlements.usedToday(feature)
        val remaining = feature.dailyFreeLimit - used
        return if (remaining > 0) {
            FeatureAccess.Allowed(remainingToday = remaining)
        } else {
            FeatureAccess.LimitReached(feature, feature.dailyFreeLimit)
        }
    }

    /**
     * Call only after the operation actually produced a file. Charging a free
     * user's daily allowance for an attempt that failed would be indefensible.
     */
    suspend fun recordUse(feature: ProFeature) {
        if (entitlements.isPro.first()) return
        if (feature.isUnlimited) return
        entitlements.recordUse(feature)
    }

    /** Compression levels a free user may pick. */
    suspend fun availableCompressionLevels(): List<CompressionLevel> {
        val pro = entitlements.isPro.first()
        return CompressionLevel.entries.filter { pro || !it.isPremium }
    }

    suspend fun isCompressionLevelAllowed(level: CompressionLevel): Boolean =
        !level.isPremium || entitlements.isPro.first()

    /** Applied when Play reports a purchase, and when a restore succeeds. */
    suspend fun grantPro() = entitlements.setPro(true)

    /** Applied when Play reports the entitlement is gone (refund, expiry). */
    suspend fun revokePro() = entitlements.setPro(false)
}
