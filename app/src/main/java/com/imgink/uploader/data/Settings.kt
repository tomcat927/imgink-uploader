package com.imgink.uploader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

object Keys {
    val TOKEN = stringPreferencesKey("imgink_token")
    val BASE_URL = stringPreferencesKey("imgink_base_url")
    val FEISHU_WEBHOOK = stringPreferencesKey("feishu_webhook")
    val FEISHU_SECRET = stringPreferencesKey("feishu_secret")
    val AUTO_PUSH = booleanPreferencesKey("auto_push")
}

class SettingsRepo(private val context: Context) {
    val token: Flow<String> = context.dataStore.data.map { it[Keys.TOKEN].orEmpty() }
    val baseUrl: Flow<String> = context.dataStore.data.map { it[Keys.BASE_URL].orEmpty() }
    val webhook: Flow<String> = context.dataStore.data.map { it[Keys.FEISHU_WEBHOOK].orEmpty() }
    val secret: Flow<String> = context.dataStore.data.map { it[Keys.FEISHU_SECRET].orEmpty() }
    val autoPush: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_PUS] ?: false }

    suspend fun saveToken(v: String) = context.dataStore.edit { it[Keys.TOKEN] = v }
    suspend fun saveBaseUrl(v: String) = context.dataStore.edit { it[Keys.BASE_URL] = v }
    suspend fun saveWebhook(v: String) = context.dataStore.edit { it[Keys.FEISHU_WEBHOOK] = v }
    suspend fun saveSecret(v: String) = context.dataStore.edit { it[Keys.FEISHU_SECRET] = v }
    suspend fun saveAutoPush(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_PUS] = v }
    suspend fun clearToken() = context.dataStore.edit { it.remove(Keys.TOKEN) }
}
