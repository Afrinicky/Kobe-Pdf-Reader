package com.kobe.reader.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** User-visible settings, plus the small amount of state the library needs. */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /**
     * DataStore surfaces read failures through the flow itself. Without this
     * catch, a single corrupt-file `IOException` tears down every collector and
     * the app looks frozen. Falling back to defaults is the right call for
     * preferences - nothing here is worth an error screen.
     */
    val settings: Flow<KobeSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences ->
            KobeSettings(
                theme = preferences[Keys.THEME]?.let(ThemePreference::fromKey)
                    ?: ThemePreference.System,
                useDynamicColor = preferences[Keys.DYNAMIC_COLOR] ?: false,
                keepScreenOn = preferences[Keys.KEEP_SCREEN_ON] ?: true,
                rememberPosition = preferences[Keys.REMEMBER_POSITION] ?: true,
                horizontalPaging = preferences[Keys.HORIZONTAL_PAGING] ?: false,
                sortOrder = preferences[Keys.SORT_ORDER]?.let(SortOrder::fromKey)
                    ?: SortOrder.DateDescending,
                grantedFolders = preferences[Keys.GRANTED_FOLDERS] ?: emptySet(),
                hasSeenStorageIntro = preferences[Keys.SEEN_STORAGE_INTRO] ?: false,
            )
        }

    suspend fun setTheme(theme: ThemePreference) = edit { it[Keys.THEME] = theme.key }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }

    suspend fun setKeepScreenOn(enabled: Boolean) = edit { it[Keys.KEEP_SCREEN_ON] = enabled }

    suspend fun setRememberPosition(enabled: Boolean) =
        edit { it[Keys.REMEMBER_POSITION] = enabled }

    suspend fun setHorizontalPaging(enabled: Boolean) =
        edit { it[Keys.HORIZONTAL_PAGING] = enabled }

    suspend fun setSortOrder(order: SortOrder) = edit { it[Keys.SORT_ORDER] = order.key }

    suspend fun setSeenStorageIntro() = edit { it[Keys.SEEN_STORAGE_INTRO] = true }

    suspend fun addGrantedFolder(uri: String) = edit { preferences ->
        preferences[Keys.GRANTED_FOLDERS] = (preferences[Keys.GRANTED_FOLDERS] ?: emptySet()) + uri
    }

    suspend fun removeGrantedFolder(uri: String) = edit { preferences ->
        preferences[Keys.GRANTED_FOLDERS] = (preferences[Keys.GRANTED_FOLDERS] ?: emptySet()) - uri
    }

    private suspend inline fun edit(crossinline block: (MutablePreferences) -> Unit) {
        // A failed write is not worth crashing over; the setting simply doesn't
        // stick and the user can try again. The lambda receives MutablePreferences
        // (the only Preferences type with a `set` operator).
        runCatching { dataStore.edit { block(it) } }
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val REMEMBER_POSITION = booleanPreferencesKey("remember_position")
        val HORIZONTAL_PAGING = booleanPreferencesKey("horizontal_paging")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val GRANTED_FOLDERS = stringSetPreferencesKey("granted_folders")
        val SEEN_STORAGE_INTRO = booleanPreferencesKey("seen_storage_intro")
    }
}

data class KobeSettings(
    val theme: ThemePreference = ThemePreference.System,
    val useDynamicColor: Boolean = false,
    val keepScreenOn: Boolean = true,
    val rememberPosition: Boolean = true,
    val horizontalPaging: Boolean = false,
    val sortOrder: SortOrder = SortOrder.DateDescending,
    val grantedFolders: Set<String> = emptySet(),
    val hasSeenStorageIntro: Boolean = false,
)

enum class ThemePreference(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromKey(key: String): ThemePreference =
            entries.firstOrNull { it.key == key } ?: System
    }
}

enum class SortOrder(val key: String) {
    NameAscending("name_asc"),
    NameDescending("name_desc"),
    DateDescending("date_desc"),
    DateAscending("date_asc"),
    SizeDescending("size_desc"),
    SizeAscending("size_asc"),
    ;

    val isDescending: Boolean get() = key.endsWith("_desc")

    companion object {
        fun fromKey(key: String): SortOrder = entries.firstOrNull { it.key == key }
            ?: DateDescending
    }
}
