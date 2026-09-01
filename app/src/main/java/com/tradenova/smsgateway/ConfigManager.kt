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

/**
 * Sensitive fields (API key, device secret) are encrypted with CryptoUtil
 * before they touch disk - DataStore's preferences file is plain XML/proto
 * under the hood and is not itself encrypted, and this app carries a live
 * credential capable of triggering financial-notification SMS.
 */
class ConfigManager(private val context: Context) {
    companion object {
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val API_KEY_ENC = stringPreferencesKey("api_key_enc")
        val DEVICE_SECRET_ENC = stringPreferencesKey("device_secret_enc")
        val FCM_TOKEN = stringPreferencesKey("fcm_token")
        val IS_ACTIVE = booleanPreferencesKey("is_active")
    }

    val backendUrlFlow: Flow<String?> = context.dataStore.data.map { it[BACKEND_URL] }
    val deviceIdFlow: Flow<String?> = context.dataStore.data.map { it[DEVICE_ID] }
    val apiKeyFlow: Flow<String?> = context.dataStore.data.map {
        it[API_KEY_ENC]?.let { enc -> CryptoUtil.decrypt(enc) }
    }
    val deviceSecretFlow: Flow<String?> = context.dataStore.data.map {
        it[DEVICE_SECRET_ENC]?.let { enc -> CryptoUtil.decrypt(enc) }
    }
    val fcmTokenFlow: Flow<String?> = context.dataStore.data.map { it[FCM_TOKEN] }
    val isActiveFlow: Flow<Boolean> = context.dataStore.data.map { it[IS_ACTIVE] ?: false }

    suspend fun saveConfig(url: String, deviceId: String, apiKey: String, deviceSecret: String, isActive: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[BACKEND_URL] = url
            prefs[DEVICE_ID] = deviceId
            prefs[API_KEY_ENC] = CryptoUtil.encrypt(apiKey)
            prefs[DEVICE_SECRET_ENC] = CryptoUtil.encrypt(deviceSecret)
            prefs[IS_ACTIVE] = isActive
        }
    }

    /** Called whenever FCM hands us a fresh token, including on first install. */
    suspend fun saveFcmToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[FCM_TOKEN] = token
        }
    }
}
