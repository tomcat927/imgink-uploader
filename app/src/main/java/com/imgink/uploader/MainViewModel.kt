package com.imgink.uploader

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imgink.uploader.data.ApiClient
import com.imgink.uploader.data.AppLog
import com.imgink.uploader.data.Feishu
import com.imgink.uploader.data.ImgItem
import com.imgink.uploader.data.OpenListLog
import com.imgink.uploader.data.SettingsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.BufferedSink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface UploadState {
    data object Idle : UploadState
    data class Uploading(val name: String, val progress: Int) : UploadState
    data class Success(
        val url: String,
        val name: String,
        val size: Long,
        val quotaTotal: String? = null,
        val quotaUsed: String? = null,
        val pushStatus: String = ""
    ) : UploadState
    data class Error(val message: String) : UploadState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepo(app)
    private val ctx = app

    val token: StateFlow<String> = repo.token.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val baseUrl: StateFlow<String> = repo.baseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val uploadFolder: StateFlow<String> = repo.uploadFolder.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val webhook: StateFlow<String> = repo.webhook.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val secret: StateFlow<String> = repo.secret.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val autoPush: StateFlow<Boolean> = repo.autoPush.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val rlEnabled: StateFlow<Boolean> = repo.remoteLogEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val rlBaseUrl: StateFlow<String> = repo.remoteLogBaseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val rlUsername: StateFlow<String> = repo.remoteLogUsername.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val rlPassword: StateFlow<String> = repo.remoteLogPassword.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val rlTargetPath: StateFlow<String> = repo.remoteLogTargetPath.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val rlLastUpload: StateFlow<String> = repo.remoteLogLastUpload.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    private val _sharedUri = MutableStateFlow<Uri?>(null)
    val sharedUri: StateFlow<Uri?> = _sharedUri.asStateFlow()

    data class HistoryUi(
        val loading: Boolean = false,
        val items: List<ImgItem> = emptyList(),
        val page: Int = 0,
        val lastPage: Int = 1,
        val total: Int? = null,
        val error: String? = null
    )

    private val _history = MutableStateFlow(HistoryUi())
    val history: StateFlow<HistoryUi> = _history.asStateFlow()

    // ---------- 设置 ----------

    fun saveToken(v: String) {
        viewModelScope.launch { repo.saveToken(v.trim()) }
    }

    fun saveSettings(base: String, hook: String, sec: String, auto: Boolean, folder: String) {
        viewModelScope.launch {
            repo.saveBaseUrl(base.trim())
            repo.saveWebhook(hook.trim())
            repo.saveSecret(sec.trim())
            repo.saveAutoPush(auto)
            repo.saveUploadFolder(folder.trim())
        }
    }

    fun saveRemoteLog(enabled: Boolean, base: String, user: String, pass: String, path: String) {
        viewModelScope.launch {
            repo.saveRemoteLogEnabled(enabled)
            repo.saveRemoteLogBaseUrl(base.trim())
            repo.saveRemoteLogUsername(user.trim())
            repo.saveRemoteLogPassword(pass)
            repo.saveRemoteLogTargetPath(path.trim())
            AppLog.log("rlog", "config saved (enabled=$enabled)")
        }
    }

    fun clearLogs() {
        AppLog.clear()
        AppLog.log("rlog", "local logs cleared")
    }

    fun logout() {
        viewModelScope.launch {
            repo.clearToken()
            _uploadState.value = UploadState.Idle
        }
    }

    /** 用 images 接口校验 token，成功返回账号图片总数 */
    suspend fun validateToken(candidate: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            AppLog.log("login", "validating token (${candidate.trim().length} chars)")
            val resp = ApiClient.service(baseUrl.value).images(candidate.trim(), 1, 1)
            if (resp.code == 200) {
                AppLog.log("login", "ok, total=${resp.data?.total}")
                resp.data?.total ?: 0
            } else {
                AppLog.log("login", "rejected code=${resp.code} msg=${resp.msg}")
                error(resp.msg ?: "token 无效 (code=${resp.code})")
            }
        }.onFailure {
            if (it !is IllegalStateException) {
                AppLog.log("login", "error ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    // ---------- 上传 ----------

    fun onShared(uri: Uri?) {
        uri?.let { _sharedUri.value = it }
    }

    fun consumeShared() {
        _sharedUri.value = null
    }

    fun dismissUpload() {
        _uploadState.value = UploadState.Idle
    }

    fun startUpload(uri: Uri) {
        val tk = token.value
        if (tk.isBlank()) {
            _uploadState.value = UploadState.Error("尚未登录，请先在设置中配置 API Token")
            return
        }
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { readUriInfo(uri) }
            var name = info.first
            val folderName = sanitizeFolder(uploadFolder.value).ifBlank { DEFAULT_FOLDER }
            _uploadState.value = UploadState.Uploading(name, 0)
            AppLog.log("upload", "start name=$name mime=${info.second ?: "?"} folder=$folderName")
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取图片内容（URI 已失效，请重新选择）")
                }
                if (bytes.isEmpty()) error("图片内容为空")

                val mime = info.second ?: "image/png"
                name = ensureExt(name, mime)
                AppLog.log("upload", "payload name=$name size=${bytes.size}B mime=$mime")

                // 直接向 OkHttp 提供的 sink 分块写入并回报进度；不要包装或提前关闭该 sink
                val requestBody = object : RequestBody() {
                    override fun contentType() = mime.toMediaTypeOrNull()
                    override fun contentLength(): Long = bytes.size.toLong()
                    override fun writeTo(sink: BufferedSink) {
                        val total = bytes.size.toLong()
                        var written = 0L
                        var offset = 0
                        while (offset < bytes.size) {
                            val chunk = minOf(64 * 1024, bytes.size - offset)
                            sink.write(bytes, offset, chunk)
                            offset += chunk
                            written += chunk
                            _uploadState.value = UploadState.Uploading(
                                name, (written * 100 / total).toInt().coerceIn(0, 99)
                            )
                        }
                    }
                }

                val part = MultipartBody.Part.createFormData("image", name, requestBody)
                val folderPart = folderName.toRequestBody("text/plain".toMediaTypeOrNull())
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.service(baseUrl.value).upload(tk, part, folderPart)
                }
                if (resp.code != 200 || resp.data?.url.isNullOrBlank()) {
                    AppLog.log("upload", "rejected code=${resp.code} msg=${resp.msg}")
                    _uploadState.value = UploadState.Error(resp.msg ?: "上传失败 (code=${resp.code})")
                    uploadRemoteLogIfEnabled("rejected")
                    return@launch
                }
                val d = resp.data!!
                AppLog.log("upload", "success id=${d.id} url=${d.url}")
                val size = d.size ?: bytes.size.toLong()
                var success = UploadState.Success(
                    url = d.url!!,
                    name = name,
                    size = size,
                    quotaTotal = d.quota,
                    quotaUsed = d.use_quota,
                    pushStatus = if (autoPush.value && webhook.value.isNotBlank()) "🚀 推送飞书中…"
                    else "未推送（自动推送未开启或未配置 webhook）"
                )
                _uploadState.value = success
                if (autoPush.value && webhook.value.isNotBlank()) {
                    val r = Feishu.sendCard(
                        webhook.value, secret.value.ifBlank { null },
                        CARD_TITLE, buildMarkdown(name, size, success.url), success.url
                    )
                    r.onSuccess { AppLog.log("feishu", "auto push ok") }
                        .onFailure { AppLog.log("feishu", "auto push fail: ${it.message}") }
                    success = success.copy(
                        pushStatus = r.fold({ "✅ 已推送到飞书群" }, { "⚠️ 推送失败：${it.message}" })
                    )
                    _uploadState.value = success
                }
            } catch (e: Exception) {
                AppLog.log("upload", "error ${e.javaClass.simpleName}: ${e.message}")
                _uploadState.value = UploadState.Error(e.message ?: "上传失败")
                uploadRemoteLogIfEnabled("error")
            }
        }
    }

    /** 上传失败时若已启用远程日志，自动把诊断快照推到 OpenList（静默失败不影响主流程） */
    private fun uploadRemoteLogIfEnabled(reason: String) {
        viewModelScope.launch {
            runCatching {
                val cfg = OpenListLog.loadConfig(repo)
                if (cfg.enabled && cfg.isConfigured) {
                    AppLog.log("rlog", "auto upload ($reason)")
                    OpenListLog.uploadSnapshot(ctx)
                }
            }
        }
    }

    /** 手动重新推送最近一次上传结果 */
    fun repush() {
        val s = _uploadState.value as? UploadState.Success ?: return
        if (webhook.value.isBlank()) return
        viewModelScope.launch {
            _uploadState.value = s.copy(pushStatus = "🚀 推送飞书中…")
            val r = Feishu.sendCard(
                webhook.value, secret.value.ifBlank { null },
                CARD_TITLE, buildMarkdown(s.name, s.size, s.url), s.url
            )
            r.onSuccess { AppLog.log("feishu", "manual push ok") }
                .onFailure { AppLog.log("feishu", "manual push fail: ${it.message}") }
            _uploadState.value = s.copy(
                pushStatus = r.fold({ "✅ 已推送到飞书群" }, { "⚠️ 推送失败：${it.message}" })
            )
        }
    }

    // ---------- 历史记录 ----------

    fun loadHistory(reset: Boolean = false) {
        val tk = token.value
        if (tk.isBlank()) return
        val cur = _history.value
        if (cur.loading) return
        val nextPage = if (reset) 1 else cur.page + 1
        if (!reset && cur.page >= cur.lastPage) return
        _history.value = cur.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.service(baseUrl.value).images(tk, nextPage, 20)
                }
                val d = resp.data
                if (resp.code == 200 && d != null) {
                    _history.value = HistoryUi(
                        loading = false,
                        items = if (reset) d.data.orEmpty() else _history.value.items + d.data.orEmpty(),
                        page = d.current_page ?: nextPage,
                        lastPage = (d.last_page ?: 1).coerceAtLeast(1),
                        total = d.total
                    )
                } else {
                    _history.value = _history.value.copy(loading = false, error = resp.msg ?: "加载失败")
                }
            } catch (e: Exception) {
                _history.value = _history.value.copy(loading = false, error = e.message ?: "网络错误")
            }
        }
    }

    fun deleteImage(id: Long) {
        val tk = token.value
        if (tk.isBlank()) return
        viewModelScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.service(baseUrl.value).delete(tk, id.toString())
                }
                if (resp.code == 200) loadHistory(true)
                else _history.value = _history.value.copy(error = resp.msg ?: "删除失败")
            } catch (e: Exception) {
                _history.value = _history.value.copy(error = e.message ?: "网络错误")
            }
        }
    }

    // ---------- 私有工具 ----------

    private fun readUriInfo(uri: Uri): Pair<String, String?> = runCatching {
        var name = ""
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = c.getString(i)
            }
        }
        if (name.isBlank()) name = "upload_${System.currentTimeMillis()}.png"
        name to ctx.contentResolver.getType(uri)
    }.getOrDefault("upload_${System.currentTimeMillis()}.png" to "image/png")

    private fun ensureExt(name: String, mime: String?): String {
        val ext = name.substringAfterLast('.', "")
        if (ext.length in 2..5) return name
        val suffix = when (mime?.substringAfter('/')) {
            "jpeg" -> "jpg"; "png" -> "png"; "webp" -> "webp"
            "gif" -> "gif"; "heic" -> "heic"; "bmp" -> "bmp"; else -> "png"
        }
        return "$name.$suffix"
    }

    /** img.ink 文件夹仅允许英文字母数字；非法字符直接剔除，留空时由调用方回退默认值 */
    private fun sanitizeFolder(raw: String): String =
        raw.filter { it.code < 128 && it.isLetterOrDigit() }.take(32)

    companion object {
        const val CARD_TITLE = "📤 图片上传成功 · 图床"

        /** img.ink 文件夹仅允许英文数字，App 默认归档目录（设置留空时使用） */
        const val DEFAULT_FOLDER = "imgink"

        fun buildMarkdown(name: String, size: Long, url: String): String =
            "**文件：** $name\n**大小：** ${fmtSize(size)}\n**时间：** ${now()}\n\n[查看图片]($url)"

        fun fmtSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
        }

        fun fmtQuota(total: String?, used: String?): String {
            val t = total?.toDoubleOrNull() ?: return "配额未知"
            val u = used?.toDoubleOrNull() ?: 0.0
            return "已用 ${fmtSize(u.toLong())} / 总量 ${fmtSize(t.toLong())}"
        }

        fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
    }
}
