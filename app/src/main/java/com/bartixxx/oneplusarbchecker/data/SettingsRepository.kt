package com.bartixxx.oneplusarbchecker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
}
