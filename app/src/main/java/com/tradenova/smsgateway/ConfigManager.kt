package com.tradenova.smsgateway

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class ConfigManager(private val context: Context) {
    companion object {
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val API_KEY = stringPreferencesKey("api_key")
        val IS_ACTIVE = booleanPreferencesKey("is_active")
    }

    val backendUrlFlow: Flow<String?> = context.dataStore.data.map { it[BACKEND_URL] }
    val deviceIdFlow: Flow<String?> = context.dataStore.data.map { it[DEVICE_ID] }
    val apiKeyFlow: Flow<String?> = context.dataStore.data.map { it[API_KEY] }
    val isActiveFlow: Flow<Boolean> = context.dataStore.data.map { it[IS_ACTIVE] ?: false }

    suspend fun saveConfig(url: String, deviceId: String, apiKey: String, isActive: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[BACKEND_URL] = url
            prefs[DEVICE_ID] = deviceId
            prefs[API_KEY] = apiKey
            prefs[IS_ACTIVE] = isActive
        }
    }
}
