package website.msdnna.budget_app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import website.msdnna.budget_app.notifications.NotificationPrefs

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "msdnna_budget")

class AppPreferences(private val context: Context) {
    companion object {
        val SERVER_URL   = stringPreferencesKey("server_url")
        val THEME_KEY    = stringPreferencesKey("theme_key")
        val AUTH_TOKEN   = stringPreferencesKey("auth_token")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val AVATAR_URL   = stringPreferencesKey("avatar_url")
        val USER_ID      = stringPreferencesKey("user_id")

        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val SERVER_HISTORY = stringPreferencesKey("server_history")

        val NOTIF_EXPENSES_ENABLED = booleanPreferencesKey("notif_expenses_enabled")
        val NOTIF_EXPENSES_HOUR    = intPreferencesKey("notif_expenses_hour")
        val NOTIF_EXPENSES_MINUTE  = intPreferencesKey("notif_expenses_minute")
        val NOTIF_INCOME_ENABLED   = booleanPreferencesKey("notif_income_enabled")
        val NOTIF_INCOME_HOUR      = intPreferencesKey("notif_income_hour")
        val NOTIF_INCOME_MINUTE    = intPreferencesKey("notif_income_minute")
        val NOTIF_INCOME_DAY       = intPreferencesKey("notif_income_day")
    }

    val serverUrl: Flow<String?> = context.dataStore.data
        .map { prefs -> prefs[SERVER_URL]?.takeIf { it.isNotBlank() } }

    val serverHistory: Flow<List<String>> = context.dataStore.data
        .map { prefs -> prefs[SERVER_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList() }

    val themeKey: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[THEME_KEY] ?: "blue" }

    val darkMode: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[DARK_MODE] ?: false }

    val authToken: Flow<String?> = context.dataStore.data
        .map { prefs -> prefs[AUTH_TOKEN]?.takeIf { it.isNotBlank() } }

    val displayName: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[DISPLAY_NAME] ?: "" }

    val avatarUrl: Flow<String?> = context.dataStore.data
        .map { prefs -> prefs[AVATAR_URL]?.takeIf { it.isNotBlank() } }

    val notifPrefs: Flow<NotificationPrefs> = context.dataStore.data.map { prefs ->
        NotificationPrefs(
            expensesEnabled = prefs[NOTIF_EXPENSES_ENABLED] ?: false,
            expensesHour    = prefs[NOTIF_EXPENSES_HOUR]    ?: 21,
            expensesMinute  = prefs[NOTIF_EXPENSES_MINUTE]  ?: 0,
            incomeEnabled   = prefs[NOTIF_INCOME_ENABLED]   ?: false,
            incomeHour      = prefs[NOTIF_INCOME_HOUR]      ?: 12,
            incomeMinute    = prefs[NOTIF_INCOME_MINUTE]    ?: 0,
            incomeDay       = prefs[NOTIF_INCOME_DAY]       ?: 30,
        )
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[SERVER_URL] = url }
    }

    suspend fun clearServerUrl() {
        context.dataStore.edit { it.remove(SERVER_URL) }
    }

    suspend fun addToServerHistory(url: String) {
        if (url.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = prefs[SERVER_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val updated = (listOf(url) + current.filter { it != url }).take(5)
            prefs[SERVER_HISTORY] = updated.joinToString("|")
        }
    }

    suspend fun setThemeKey(key: String) {
        context.dataStore.edit { it[THEME_KEY] = key }
    }

    suspend fun setDarkMode(dark: Boolean) {
        context.dataStore.edit { it[DARK_MODE] = dark }
    }

    suspend fun setAuth(token: String, userId: String, displayName: String, avatarUrl: String?) {
        context.dataStore.edit { prefs ->
            prefs[AUTH_TOKEN]   = token
            prefs[USER_ID]      = userId
            prefs[DISPLAY_NAME] = displayName
            if (avatarUrl != null) prefs[AVATAR_URL] = avatarUrl else prefs.remove(AVATAR_URL)
        }
    }

    suspend fun clearAuth() {
        context.dataStore.edit { prefs ->
            prefs.remove(AUTH_TOKEN)
            prefs.remove(USER_ID)
            prefs.remove(DISPLAY_NAME)
            prefs.remove(AVATAR_URL)
        }
    }

    suspend fun setNotifPrefs(np: NotificationPrefs) {
        context.dataStore.edit { prefs ->
            prefs[NOTIF_EXPENSES_ENABLED] = np.expensesEnabled
            prefs[NOTIF_EXPENSES_HOUR]    = np.expensesHour
            prefs[NOTIF_EXPENSES_MINUTE]  = np.expensesMinute
            prefs[NOTIF_INCOME_ENABLED]   = np.incomeEnabled
            prefs[NOTIF_INCOME_HOUR]      = np.incomeHour
            prefs[NOTIF_INCOME_MINUTE]    = np.incomeMinute
            prefs[NOTIF_INCOME_DAY]       = np.incomeDay
        }
    }
}
