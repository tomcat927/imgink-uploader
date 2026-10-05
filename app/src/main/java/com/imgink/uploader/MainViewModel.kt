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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import okio.BufferedSink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单张图在批量队列中的状态 */
sealed interface ItemState {
    data object Pending : ItemState
    data class Uploading(val progress: Int) : ItemState
    data class Done(val url: String, val size: Long) : ItemState
    data class Failed(val reason: String) : ItemState
}

/** 队列中的一个待传项；name 在读取到元信息后回填 */
data class BatchItem(
    val uri: Uri,
    val name: String,
    val state: ItemState
)

sealed interface UploadState {
    data object Idle : UploadState
    data class BatchRunning(val items: List<BatchItem>) : UploadState {
        val total: Int get() = items.size.coerceAtLeast(1)
        val done: Int get() = items.count { it.state is ItemState.Done }
        val failed: Int get() = items.count { it.state is ItemState.Failed }
        val current: BatchItem? get() = items.firstOrNull { it.state is ItemState.Uploading }
        val pending: Int get() = items.count { it.state is ItemState.Pending }
        /** 整体进度 = 已完成张数 + 当前张的字节进度折算 */
        val overallProgress: Int
            get() = ((done + failed) * 100 + ((current?.state as? ItemState.Uploading)?.progress ?: 0)) / total
    }
    data class BatchFinished(
        val items: List<BatchItem>,
        val cancelled: Boolean = false,
        val quotaTotal: String? = null,
        val quotaUsed: String? = null,
        val pushStatus: String = "",
        /** 单张成功时自动复制是只发一次的副作用，标记防止切页后 LaunchedEffect 重放 */
        val copied: Boolean = false
    ) : UploadState {
        val done: Int get() = items.count { it.state is ItemState.Done }
        val failed: Int get() = items.count { it.state is ItemState.Failed }
        /** 未走到终态的张数（取消剩余） */
        val skipped: Int get() = items.count { it.state !is ItemState.Done && it.state !is ItemState.Failed }
        val urls: List<String> get() = items.mapNotNull { (it.state as? ItemState.Done)?.url }
        /** 恰好一张且成功：等价于旧版单张上传，享受自动复制 / 单图飞书卡 */
        val singleUrl: String?
            get() = if (done == 1 && failed == 0 && skipped == 0) urls.firstOrNull() else null
    }
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

    private val _sharedUris = MutableStateFlow<List<Uri>?>(null)
    val sharedUris: StateFlow<List<Uri>?> = _sharedUris.asStateFlow()

    private var batchJob: Job? = null

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

    fun onShared(uris: List<Uri>) {
        if (uris.isNotEmpty()) _sharedUris.value = uris
    }

    fun consumeShared() {
        _sharedUris.value = null
    }

    fun dismissUpload() {
        _uploadState.value = UploadState.Idle
    }

    /** 自动复制已完成，抑制后续重组/返回首页时的重放 */
    fun markCopied(f: UploadState.BatchFinished) {
        val cur = _uploadState.value
        if (cur is UploadState.BatchFinished && cur.items == f.items) {
            _uploadState.value = cur.copy(copied = true)
        }
    }

    /** 放弃还没开始的张，正在传的这张中断 */
    fun cancelRemaining() {
        batchJob?.cancel()
    }

    /** 把上一批失败的张数重新排队上传 */
    fun retryFailed() {
        val cur = _uploadState.value as? UploadState.BatchFinished ?: return
        val failedUris = cur.items.filter { it.state is ItemState.Failed }.map { it.uri }
        if (failedUris.isNotEmpty()) startBatch(failedUris)
    }

    /**
     * 批量上传编排：串行逐张调 uploadOne，全部结束后汇总一次飞书推送。
     * 拍照/系统分享等单张入口同样走这里（队列长度 1）。
     */
    fun startBatch(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (token.value.isBlank()) {
            _uploadState.value = UploadState.Error("尚未登录，请先在设置中配置 API Token")
            return
        }
        batchJob?.cancel()
        val items = uris.take(MAX_BATCH).map { BatchItem(it, "", ItemState.Pending) }
        AppLog.log("batch", "start count=${items.size}")
        _uploadState.value = UploadState.BatchRunning(items)
        batchJob = viewModelScope.launch {
            var cancelled = false
            var quotaTotal: String? = null
            var quotaUsed: String? = null
            try {
                for (i in items.indices) {
                    mutateItem(i) { it.copy(state = ItemState.Uploading(0)) }
                    uploadOne(
                        uri = items[i].uri,
                        onStart = { name -> mutateItem(i) { it.copy(name = name) } },
                        onProgress = { p -> mutateItem(i) { it.copy(state = ItemState.Uploading(p)) } }
                    ).onSuccess { one ->
                        quotaTotal = one.quotaTotal
                        quotaUsed = one.quotaUsed
                        mutateItem(i) { it.copy(name = one.name, state = ItemState.Done(one.url, one.size)) }
                    }.onFailure { e ->
                        AppLog.log("upload", "error ${e.javaClass.simpleName}: ${e.message}")
                        mutateItem(i) {
                            it.copy(
                                name = it.name.ifBlank { "第 ${i + 1} 张" },
                                state = ItemState.Failed("${e.javaClass.simpleName}: ${e.message ?: "上传失败"}")
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                cancelled = true
            }
            val finalItems = (_uploadState.value as? UploadState.BatchRunning)?.items ?: items
            val finished = UploadState.BatchFinished(
                items = finalItems,
                cancelled = cancelled,
                quotaTotal = quotaTotal,
                quotaUsed = quotaUsed
            )
            _uploadState.value = finished
            AppLog.log("batch", "finish done=${finished.done} failed=${finished.failed} skipped=${finished.skipped} cancelled=$cancelled")
            if (finished.failed > 0) uploadRemoteLogIfEnabled("error")
            if (finished.done > 0) {
                if (autoPush.value && webhook.value.isNotBlank()) doPush(finished, manual = false)
                else setPushStatus("未推送（自动推送未开启或未配置 webhook）")
            }
        }
    }

    /** 仅在批量进行中生效的逐项更新；收尾后迟到的进度回调直接丢弃 */
    private fun mutateItem(index: Int, transform: (BatchItem) -> BatchItem) {
        val cur = _uploadState.value as? UploadState.BatchRunning ?: return
        _uploadState.value = cur.copy(
            items = cur.items.mapIndexed { i, it -> if (i == index) transform(it) else it }
        )
    }

    private data class OneResult(
        val url: String,
        val name: String,
        val size: Long,
        val quotaTotal: String?,
        val quotaUsed: String?
    )

    /**
     * 单张上传原语：读元信息 → 报进度 → POST /api/upload。
     * 只回调状态、不做飞书推送（推送由批量编排层汇总成一张卡）。
     */
    private suspend fun uploadOne(
        uri: Uri,
        onStart: (name: String) -> Unit,
        onProgress: (percent: Int) -> Unit
    ): Result<OneResult> = runCatching {
        val tk = token.value
        val folderName = sanitizeFolder(uploadFolder.value).ifBlank { DEFAULT_FOLDER }
        val info = withContext(Dispatchers.IO) { readUriInfo(uri) }
        val mime = info.second ?: "image/png"
        val name = ensureExt(info.first, mime)
        onStart(name)
        AppLog.log("upload", "start name=$name mime=${info.second ?: "?"} folder=$folderName")
        val bytes = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("无法读取图片内容（URI 已失效，请重新选择）")
        }
        if (bytes.isEmpty()) error("图片内容为空")
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
                    onProgress((written * 100 / total).toInt().coerceIn(0, 99))
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
            error(resp.msg ?: "上传失败 (code=${resp.code})")
        }
        val d = resp.data!!
        AppLog.log("upload", "success id=${d.id} url=${d.url}")
        OneResult(
            url = d.url!!,
            name = name,
            size = d.size ?: bytes.size.toLong(),
            quotaTotal = d.quota,
            quotaUsed = d.use_quota
        )
    }.onFailure {
        // runCatching 会连协程取消一起吞掉，这里必须放行，否则「取消剩余」会被记成上传失败
        if (it is CancellationException) throw it
    }

    /** 批量结束后的飞书汇总推送；独立协程，不随批量协程取消而中断 */
    private fun doPush(f: UploadState.BatchFinished, manual: Boolean) {
        viewModelScope.launch {
            setPushStatus("🚀 推送飞书中…")
            val r = sendCardFor(f)
            r.onSuccess { AppLog.log("feishu", "${if (manual) "manual" else "auto"} push ok") }
                .onFailure { AppLog.log("feishu", "${if (manual) "manual" else "auto"} push fail: ${it.message}") }
            setPushStatus(r.fold({ "✅ 已推送到飞书群" }, { "⚠️ 推送失败：${it.message}" }))
        }
    }

    /** 单张成功退化为旧版单图卡；多张时一张汇总卡（代码块逐行列 URL），避免刷屏 */
    private suspend fun sendCardFor(f: UploadState.BatchFinished): Result<Unit> {
        val single = f.singleUrl
        val title = if (single != null) CARD_TITLE else CARD_TITLE_BATCH
        val body = if (single != null) {
            val item = f.items.first { (it.state as? ItemState.Done)?.url == single }
            val st = item.state as ItemState.Done
            buildMarkdown(item.name, st.size, single)
        } else {
            buildBatchMarkdown(f)
        }
        val raw = if (single != null) {
            single
        } else {
            val shown = f.urls.take(FEISHU_URL_LIMIT).joinToString("\n")
            if (f.urls.size > FEISHU_URL_LIMIT) "$shown\n… 其余 ${f.urls.size - FEISHU_URL_LIMIT} 条见 App 内" else shown
        }
        return Feishu.sendCard(webhook.value, secret.value.ifBlank { null }, title, body, raw)
    }

    private fun setPushStatus(text: String) {
        val cur = _uploadState.value
        if (cur is UploadState.BatchFinished) _uploadState.value = cur.copy(pushStatus = text)
    }

    /** 手动重新推送最近一次上传结果 */
    fun repush() {
        val f = _uploadState.value as? UploadState.BatchFinished ?: return
        if (webhook.value.isBlank() || f.done == 0) return
        doPush(f, manual = true)
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

        /** 批量汇总卡标题，同样固定含「图床」关键词兼容自定义关键词安全设置 */
        const val CARD_TITLE_BATCH = "📤 批量上传完成 · 图床"

        /** 相册一次可选的最大张数（Photo Picker 上限 100，30 已覆盖日常批量） */
        const val MAX_BATCH = 30

        /** 飞书卡片文本长度有限，代码块最多列这么多条 URL，超出只显示计数 */
        const val FEISHU_URL_LIMIT = 20

        /** img.ink 文件夹仅允许英文数字，App 默认归档目录（设置留空时使用） */
        const val DEFAULT_FOLDER = SettingsRepo.DEFAULT_UPLOAD_FOLDER

        fun buildMarkdown(name: String, size: Long, url: String): String =
            "**文件：** $name\n**大小：** ${fmtSize(size)}\n**时间：** ${now()}\n\n[查看图片]($url)"

        fun buildBatchMarkdown(f: UploadState.BatchFinished): String = buildString {
            append("**数量：** ").append(f.done).append(" 张成功")
            if (f.failed > 0) append(" · ").append(f.failed).append(" 张失败")
            if (f.skipped > 0) append(" · ").append(f.skipped).append(" 张已取消")
            append("\n**时间：** ").append(now())
            val failedNames = f.items.filter { it.state is ItemState.Failed }
                .map { it.name.ifBlank { "未命名" } }
            if (failedNames.isNotEmpty()) {
                append("\n**失败：** ").append(failedNames.take(FEISHU_URL_LIMIT).joinToString("、"))
                if (failedNames.size > FEISHU_URL_LIMIT) append(" 等 ${failedNames.size} 张")
            }
        }

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
