package com.imgink.uploader

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.imgink.uploader.data.ApiClient
import com.imgink.uploader.data.Feishu
import com.imgink.uploader.data.ImgItem
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
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
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

    fun logout() {
        viewModelScope.launch {
            repo.clearToken()
            _uploadState.value = UploadState.Idle
        }
    }

    /** 用 images 接口校验 token，成功返回账号图片总数 */
    suspend fun validateToken(candidate: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = ApiClient.service(baseUrl.value).images(candidate.trim(), 1, 1)
            if (resp.code == 200) resp.data?.total ?: 0
            else error(resp.msg ?: "token 无效 (code=${resp.code})")
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
            _uploadState.value = UploadState.Uploading(name, 0)
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取图片内容（URI 已失效，请重新选择）")
                }
                if (bytes.isEmpty()) error("图片内容为空")

                val mime = info.second ?: "image/png"
                name = ensureExt(name, mime)

                val requestBody = object : RequestBody() {
                    override fun contentType() = mime.toMediaTypeOrNull()
                    override fun contentLength(): Long = bytes.size.toLong()
                    override fun writeTo(sink: BufferedSink) {
                        val total = bytes.size.toLong()
                        var done = 0L
                        val counting = object : ForwardingSink(sink) {
                            override fun write(source: Buffer, byteCount: Long) {
                                super.write(source, byteCount)
                                done += byteCount
                                if (total > 0) {
                                    _uploadState.value =
                                        UploadState.Uploading(name, (done * 100 / total).toInt().coerceIn(0, 99))
                                }
                            }
                        }
                        counting.buffer().use { bs ->
                            bs.write(bytes)
                            bs.flush()
                        }
                    }
                }

                val part = MultipartBody.Part.createFormData("image", name, requestBody)
                val folderName = sanitizeFolder(uploadFolder.value).ifBlank { DEFAULT_FOLDER }
                val folderPart = folderName.toRequestBody("text/plain".toMediaTypeOrNull())
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.service(baseUrl.value).upload(tk, part, folderPart)
                }
                if (resp.code != 200 || resp.data?.url.isNullOrBlank()) {
                    _uploadState.value = UploadState.Error(resp.msg ?: "上传失败 (code=${resp.code})")
                    return@launch
                }
                val d = resp.data!!
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
                    success = success.copy(
                        pushStatus = r.fold({ "✅ 已推送到飞书群" }, { "⚠️ 推送失败：${it.message}" })
                    )
                    _uploadState.value = success
                }
            } catch (e: Exception) {
                _uploadState.value = UploadState.Error(e.message ?: "上传失败")
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
