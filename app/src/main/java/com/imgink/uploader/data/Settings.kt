package com.imgink.uploader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

object Keys {
    val TOKEN = stringPreferencesKey("imgink_token")
    val BASE_URL = stringPreferencesKey("imgink_base_url")
    val UPLOAD_FOLDER = stringPreferencesKey("upload_folder")
    val FEISHU_WEBHOOK = stringPreferencesKey("feishu_webhook")
    val FEISHU_SECRET = stringPreferencesKey("feishu_secret")
    val AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
    val DIRECT_DOWNLOAD = booleanPreferencesKey("direct_download")
    val AUTO_PUSH = booleanPreferencesKey("auto_push")
    val RL_ENABLED = booleanPreferencesKey("rl_enabled")
    val RL_BASE_URL = stringPreferencesKey("rl_base_url")
    val RL_USERNAME = stringPreferencesKey("rl_username")
    val RL_PASSWORD = stringPreferencesKey("rl_password")
    val RL_TARGET_PATH = stringPreferencesKey("rl_target_path")
    val RL_INSTALL_ID = stringPreferencesKey("rl_install_id")
    val RL_LAST_UPLOAD = stringPreferencesKey("rl_last_upload")
}

/** 一次性读取全部配置的快照，供远程日志等非流式场景使用 */
data class SettingsSnapshot(
    val remoteLogEnabled: Boolean,
    val remoteLogBaseUrl: String,
    val remoteLogUsername: String,
    val remoteLogPassword: String,
    val remoteLogTargetPath: String,
    val remoteLogInstallId: String
)

class SettingsRepo(private val context: Context) {
    val token: Flow<String> = context.dataStore.data.map { it[Keys.TOKEN].orEmpty() }
    val baseUrl: Flow<String> = context.dataStore.data.map { it[Keys.BASE_URL].orEmpty() }
    val uploadFolder: Flow<String> =
        context.dataStore.data.map { it[Keys.UPLOAD_FOLDER].orEmpty() }
    val webhook: Flow<String> = context.dataStore.data.map { it[Keys.FEISHU_WEBHOOK].orEmpty() }
    val secret: Flow<String> = context.dataStore.data.map { it[Keys.FEISHU_SECRET].orEmpty() }
    val autoPush: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_PUSH] ?: false }
    val remoteLogEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.RL_ENABLED] ?: false }
    val remoteLogBaseUrl: Flow<String> =
        context.dataStore.data.map { it[Keys.RL_BASE_URL].orEmpty() }
    val remoteLogUsername: Flow<String> =
        context.dataStore.data.map { it[Keys.RL_USERNAME].orEmpty() }
    val remoteLogPassword: Flow<String> =
        context.dataStore.data.map { it[Keys.RL_PASSWORD].orEmpty() }
    val remoteLogTargetPath: Flow<String> =
        context.dataStore.data.map { it[Keys.RL_TARGET_PATH].orEmpty() }
    val remoteLogLastUpload: Flow<String> =
        context.dataStore.data.map { it[Keys.RL_LAST_UPLOAD].orEmpty() }

    suspend fun snapshot(): SettingsSnapshot {
        val p = context.dataStore.data.first()
        return SettingsSnapshot(
            remoteLogEnabled = p[Keys.RL_ENABLED] ?: false,
            remoteLogBaseUrl = p[Keys.RL_BASE_URL].orEmpty(),
            remoteLogUsername = p[Keys.RL_USERNAME].orEmpty(),
            remoteLogPassword = p[Keys.RL_PASSWORD].orEmpty(),
            remoteLogTargetPath = p[Keys.RL_TARGET_PATH].orEmpty(),
            remoteLogInstallId = p[Keys.RL_INSTALL_ID].orEmpty()
        )
    }
    val autoCheckUpdate: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_CHECK_UPDATE] ?: true }
    val directDownload: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DIRECT_DOWNLOAD] ?: true }

    suspend fun saveToken(v: String) = context.dataStore.edit { it[Keys.TOKEN] = v }
    suspend fun saveBaseUrl(v: String) = context.dataStore.edit { it[Keys.BASE_URL] = v }
    suspend fun saveUploadFolder(v: String) =
        context.dataStore.edit { it[Keys.UPLOAD_FOLDER] = v }
    suspend fun saveWebhook(v: String) = context.dataStore.edit { it[Keys.FEISHU_WEBHOOK] = v }
    suspend fun saveSecret(v: String) = context.dataStore.edit { it[Keys.FEISHU_SECRET] = v }
    suspend fun saveAutoPush(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_PUSH] = v }
    suspend fun saveAutoCheckUpdate(v: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_CHECK_UPDATE] = v }

    suspend fun saveDirectDownload(v: Boolean) =
        context.dataStore.edit { it[Keys.DIRECT_DOWNLOAD] = v }

    suspend fun saveRemoteLogEnabled(v: Boolean) =
        context.dataStore.edit { it[Keys.RL_ENABLED] = v }

    suspend fun saveRemoteLogBaseUrl(v: String) =
        context.dataStore.edit { it[Keys.RL_BASE_URL] = v }

    suspend fun saveRemoteLogUsername(v: String) =
        context.dataStore.edit { it[Keys.RL_USERNAME] = v }

    suspend fun saveRemoteLogPassword(v: String) =
        context.dataStore.edit { it[Keys.RL_PASSWORD] = v }

    suspend fun saveRemoteLogTargetPath(v: String) =
        context.dataStore.edit { it[Keys.RL_TARGET_PATH] = v }

    suspend fun saveRemoteLogInstallId(v: String) =
        context.dataStore.edit { it[Keys.RL_INSTALL_ID] = v }

    suspend fun saveRemoteLogLastUpload(v: String) =
        context.dataStore.edit { it[Keys.RL_LAST_UPLOAD] = v }

    suspend fun clearToken() = context.dataStore.edit { it.remove(Keys.TOKEN) }
}
