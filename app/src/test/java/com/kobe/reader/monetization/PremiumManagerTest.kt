package com.kobe.reader.monetization

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import com.kobe.reader.pdf.CompressionLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Gating rules, verified against a fake DataStore.
 *
 * These are the rules that decide whether someone can use a feature they may
 * have paid for, so they are worth pinning down: an off-by-one here either
 * gives away the product or blocks a paying customer.
 */
class PremiumManagerTest {

    private val store = FakePreferencesDataStore()
    private val entitlements = EntitlementRepository(store)
    private val premium = PremiumManager(entitlements)

    @Test
    fun `pro user is allowed a locked feature`() = runTest {
        premium.grantPro()

        val access = premium.checkAccess(ProFeature.PasswordProtect)

        assertThat(access).isInstanceOf(FeatureAccess.Allowed::class.java)
        assertThat((access as FeatureAccess.Allowed).remainingToday).isNull()
    }

    @Test
    fun `free user is blocked from a pro only feature`() = runTest {
        val access = premium.checkAccess(ProFeature.PdfToImages)

        assertThat(access).isEqualTo(FeatureAccess.RequiresPro(ProFeature.PdfToImages))
    }

    @Test
    fun `free user gets the full daily allowance on a limited feature`() = runTest {
        val access = premium.checkAccess(ProFeature.Merge)

        assertThat(access)
            .isEqualTo(FeatureAccess.Allowed(remainingToday = ProFeature.Merge.dailyFreeLimit))
    }

    @Test
    fun `each recorded use decrements the allowance`() = runTest {
        premium.recordUse(ProFeature.Merge)

        val access = premium.checkAccess(ProFeature.Merge)

        assertThat(access)
            .isEqualTo(FeatureAccess.Allowed(remainingToday = ProFeature.Merge.dailyFreeLimit - 1))
    }

    @Test
    fun `allowance runs out after the daily limit`() = runTest {
        repeat(ProFeature.Merge.dailyFreeLimit) { premium.recordUse(ProFeature.Merge) }

        val access = premium.checkAccess(ProFeature.Merge)

        assertThat(access)
            .isEqualTo(FeatureAccess.LimitReached(ProFeature.Merge, ProFeature.Merge.dailyFreeLimit))
    }

    @Test
    fun `usage of one feature does not consume another's allowance`() = runTest {
        repeat(ProFeature.Merge.dailyFreeLimit) { premium.recordUse(ProFeature.Merge) }

        val access = premium.checkAccess(ProFeature.Split)

        assertThat(access.isAllowed).isTrue()
    }

    @Test
    fun `unlimited features are never counted`() = runTest {
        repeat(20) { premium.recordUse(ProFeature.Rotate) }

        val access = premium.checkAccess(ProFeature.Rotate)

        assertThat(access).isEqualTo(FeatureAccess.Allowed(remainingToday = null))
    }

    @Test
    fun `pro user's usage is not recorded at all`() = runTest {
        premium.grantPro()

        repeat(10) { premium.recordUse(ProFeature.Merge) }
        premium.revokePro()

        // Back on the free tier, the allowance should be untouched.
        assertThat(premium.checkAccess(ProFeature.Merge))
            .isEqualTo(FeatureAccess.Allowed(remainingToday = ProFeature.Merge.dailyFreeLimit))
    }

    @Test
    fun `advanced compression is pro only`() = runTest {
        assertThat(premium.isCompressionLevelAllowed(CompressionLevel.Advanced)).isFalse()
        assertThat(premium.isCompressionLevelAllowed(CompressionLevel.Balanced)).isTrue()

        premium.grantPro()

        assertThat(premium.isCompressionLevelAllowed(CompressionLevel.Advanced)).isTrue()
    }

    @Test
    fun `free user is offered only the free compression levels`() = runTest {
        assertThat(premium.availableCompressionLevels())
            .containsExactly(CompressionLevel.Light, CompressionLevel.Balanced)
    }

    @Test
    fun `ads follow entitlement`() = runTest {
        assertThat(premium.shouldShowAds.first()).isTrue()

        premium.grantPro()

        assertThat(premium.shouldShowAds.first()).isFalse()
    }
}

/** Minimal in-memory [DataStore], enough for entitlement and counter tests. */
private class FakePreferencesDataStore : DataStore<Preferences> {

    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
