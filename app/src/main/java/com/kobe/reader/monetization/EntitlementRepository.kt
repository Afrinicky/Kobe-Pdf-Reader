package com.kobe.reader.monetization

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local record of Pro status and free-tier usage.
 *
 * This is a **cache**, not the source of truth - Google Play is. It exists so
 * the app knows what to show before the billing client has connected, and so
 * that an offline user who already paid isn't shown a paywall. Anyone
 * determined enough to edit it has rooted their phone; a server-side check
 * would need an account and a backend, which this app deliberately doesn't have.
 */
@Singleton
class EntitlementRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val isPro: Flow<Boolean> = preferences().map { it[Keys.IS_PRO] ?: false }

    /** Usage counters, reset lazily when the calendar day rolls over. */
    val usage: Flow<DailyUsage> = preferences().map { preferences ->
        val day = preferences[Keys.USAGE_DAY] ?: 0L
        if (day != currentDay()) {
            DailyUsage(day = currentDay(), counts = emptyMap())
        } else {
            DailyUsage(
                day = day,
                counts = ProFeature.entries.associateWith { feature ->
                    preferences[Keys.usageKey(feature)] ?: 0
                }.filterValues { it > 0 },
            )
        }
    }

    suspend fun setPro(active: Boolean) {
        runCatching { dataStore.edit { it[Keys.IS_PRO] = active } }
    }

    /** How many times [feature] has run today. */
    suspend fun usedToday(feature: ProFeature): Int = usage.first().counts[feature] ?: 0

    /**
     * Records one use of [feature], rolling the counters over if the day changed
     * since the last write.
     */
    suspend fun recordUse(feature: ProFeature) {
        runCatching {
            dataStore.edit { preferences ->
                val today = currentDay()
                if (preferences[Keys.USAGE_DAY] != today) {
                    ProFeature.entries.forEach { preferences.remove(Keys.usageKey(it)) }
                    preferences[Keys.USAGE_DAY] = today
                }
                val key = Keys.usageKey(feature)
                preferences[key] = (preferences[key] ?: 0) + 1
            }
        }
    }

    /** Used by the debug build's developer options to try the Pro experience. */
    suspend fun resetUsage() {
        runCatching {
            dataStore.edit { preferences ->
                ProFeature.entries.forEach { preferences.remove(Keys.usageKey(it)) }
                preferences[Keys.USAGE_DAY] = currentDay()
            }
        }
    }

    private fun preferences(): Flow<Preferences> = dataStore.data.catch { throwable ->
        // A read failure must not look like "user lost their purchase", but it
        // also must not silently grant Pro. Defaults win: free tier, zero usage.
        if (throwable is IOException) emit(emptyPreferences()) else throw throwable
    }

    /** Days since the epoch, in the device's current time zone. */
    private fun currentDay(): Long {
        val offsetMillis = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
        return TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() + offsetMillis)
    }

    private object Keys {
        val IS_PRO = booleanPreferencesKey("is_pro")
        val USAGE_DAY = longPreferencesKey("usage_day")
        fun usageKey(feature: ProFeature) = intPreferencesKey("usage_${feature.name}")
    }
}

data class DailyUsage(
    val day: Long,
    val counts: Map<ProFeature, Int>,
) {
    fun countFor(feature: ProFeature): Int = counts[feature] ?: 0
}
