package com.bartixxx.oneplusarbchecker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val CHECK_INTERVAL_HOURS = longPreferencesKey("check_interval_hours")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val FIRST_RUN = booleanPreferencesKey("first_run")
        val LAST_CHECK_TIMESTAMP = longPreferencesKey("last_check_timestamp")
        val ROOT_MODE_ENABLED = booleanPreferencesKey("root_mode_enabled")
        val LAST_KNOWN_ARB = longPreferencesKey("last_known_arb")
        val LAST_KNOWN_BUILD_ID = stringPreferencesKey("last_known_build_id")
        val INSTALLATION_ID = stringPreferencesKey("installation_id")
        val TELEMETRY_ENABLED = booleanPreferencesKey("telemetry_enabled")
        val APP_UPDATES_ENABLED = booleanPreferencesKey("app_updates_enabled")
        val CACHED_ALERTS_JSON = stringPreferencesKey("cached_alerts_json")
        val DISMISSED_UPDATE_VERSION = stringPreferencesKey("dismissed_update_version")
        val ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
        val NOTIFIED_ALERT_IDS = stringPreferencesKey("notified_alert_ids")
    }

    val installationIdFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[INSTALLATION_ID]
        }

    suspend fun setInstallationId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[INSTALLATION_ID] = id
        }
    }

    val lastKnownBuildIdFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_KNOWN_BUILD_ID]
        }

    suspend fun setLastKnownBuildId(buildId: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_KNOWN_BUILD_ID] = buildId
        }
    }

    val lastKnownArbFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_KNOWN_ARB]?.toInt() ?: -1
        }

    suspend fun setLastKnownArb(arb: Int) {
        context.dataStore.edit { preferences ->
            preferences[LAST_KNOWN_ARB] = arb.toLong()
        }
    }

    val rootModeEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[ROOT_MODE_ENABLED] ?: false
        }
    
    val telemetryEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[TELEMETRY_ENABLED] ?: true
        }

    val appUpdatesEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[APP_UPDATES_ENABLED] ?: true
        }

    suspend fun setRootModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ROOT_MODE_ENABLED] = enabled
        }
    }

    val checkIntervalFlow: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[CHECK_INTERVAL_HOURS] ?: 1L // Default 1 hour
        }

    val notificationsEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[NOTIFICATIONS_ENABLED] ?: false // Default false until enabled
        }

    val firstRunFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[FIRST_RUN] ?: true
        }

    val lastCheckTimestampFlow: Flow<Long> = context.dataStore.data
        .map { preferences ->
            preferences[LAST_CHECK_TIMESTAMP] ?: 0L
        }

    suspend fun setCheckInterval(hours: Long) {
        context.dataStore.edit { preferences ->
            preferences[CHECK_INTERVAL_HOURS] = hours
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NOTIFICATIONS_ENABLED] = enabled
        }
    }

    suspend fun setFirstRunCompleted() {
        context.dataStore.edit { preferences ->
            preferences[FIRST_RUN] = false
        }
    }

    suspend fun setLastCheckTimestamp(timestamp: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_CHECK_TIMESTAMP] = timestamp
        }
    }

    suspend fun setTelemetryEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TELEMETRY_ENABLED] = enabled
        }
    }

    suspend fun setAppUpdatesEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[APP_UPDATES_ENABLED] = enabled
        }
    }

    val cachedAlertsJsonFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[CACHED_ALERTS_JSON]
        }

    suspend fun setCachedAlertsJson(json: String) {
        context.dataStore.edit { preferences ->
            preferences[CACHED_ALERTS_JSON] = json
        }
    }

    val dismissedUpdateVersionFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[DISMISSED_UPDATE_VERSION]
        }

    suspend fun setDismissedUpdateVersion(version: String) {
        context.dataStore.edit { preferences ->
            preferences[DISMISSED_UPDATE_VERSION] = version
        }
    }

    val alertsEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[ALERTS_ENABLED] ?: true
        }

    suspend fun setAlertsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ALERTS_ENABLED] = enabled
        }
    }

    val notifiedAlertIdsFlow: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[NOTIFIED_ALERT_IDS]
        }

    suspend fun setNotifiedAlertIds(json: String) {
        context.dataStore.edit { preferences ->
            preferences[NOTIFIED_ALERT_IDS] = json
        }
    }
}
