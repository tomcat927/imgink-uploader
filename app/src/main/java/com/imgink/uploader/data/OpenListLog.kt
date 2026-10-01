package com.imgink.uploader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 远程诊断日志：上传到自建 OpenList（alist 系）网盘（参照 notion-app-android 的 RemoteLogService 移植）。
 *
 * 协议：POST /api/auth/login/hash（密码 = SHA256(password-salt)）拿 token
 *      → POST /api/fs/mkdir 建目录（已存在视为成功）
 *      → PUT /api/fs/put（Authorization + urlencode 的 File-Path 头）
 * 配置存 App 内 DataStore；日志快照上传前做脱敏；401 自动重登录重试一次。
 */
object OpenListLog {

    private const val ALIST_SALT = "https://github.com/alist-org/alist"
    private const val MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024
    private const val DEFAULT_TARGET = "/D-h/imgink-uploader/logs"

    data class Config(
        val enabled: Boolean,
        val baseUrl: String,
        val username: String,
        val password: String,
        val targetPath: String
    ) {
        val isConfigured: Boolean
            get() = baseUrl.isNotBlank() && username.isNotBlank() && targetPath.isNotBlank()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .proxy(Proxy.NO_PROXY) // 内网地址直连，绕过手机系统代理
        .build()

    // ---------- 配置 ----------

    suspend fun loadConfig(repo: SettingsRepo): Config {
        val p = repo.snapshot()
        return Config(
            enabled = p.remoteLogEnabled,
            baseUrl = p.remoteLogBaseUrl,
            username = p.remoteLogUsername,
            password = p.remoteLogPassword,
            targetPath = p.remoteLogTargetPath.ifBlank { DEFAULT_TARGET }
        )
    }

    suspend fun testConnection(baseUrl: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = normalizeBaseUrl(baseUrl)
                login(base, username.trim(), password)
            }.map { }
        }

    // ---------- 上传 ----------

    /**
     * 上传当前诊断日志快照。
     * @return Result<远程路径>
     */
    suspend fun uploadSnapshot(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val repo = SettingsRepo(context)
            val cfg = loadConfig(repo)
            if (!cfg.enabled) error("远程诊断日志未启用")
            if (!cfg.isConfigured) error("请先完成 OpenList 配置")
            require(cfg.password.isNotBlank()) { "OpenList 密码未配置" }

            val base = normalizeBaseUrl(cfg.baseUrl)
            var token = login(base, cfg.username, cfg.password)

            val snapshot = buildSnapshot()
            val bytes = snapshot.toByteArray(Charsets.UTF_8)
            check(bytes.size <= MAX_SNAPSHOT_BYTES) { "脱敏日志超过 2MB" }

            val installId = installId(context)
            val dir = "${cfg.targetPath.trimEnd('/')}/install-$installId"
            val fileTs = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val remotePath = "$dir/diag-$fileTs.txt"

            try {
                ensureDir(base, token, cfg.targetPath)
                ensureDir(base, token, dir)
                put(base, token, remotePath, bytes)
            } catch (e: AuthException) {
                // token 过期 → 重登录重试一次
                token = login(base, cfg.username, cfg.password)
                ensureDir(base, token, cfg.targetPath)
                ensureDir(base, token, dir)
                put(base, token, remotePath, bytes)
            }

            repo.saveRemoteLogLastUpload(
                "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())} → $remotePath"
            )
            AppLog.log("rlog", "uploaded ${bytes.size}B → $remotePath")
            remotePath
        }.onFailure {
            AppLog.log("rlog", "upload failed: ${it.message}")
        }
    }

    private suspend fun installId(context: Context): String {
        val repo = SettingsRepo(context)
        val existing = repo.snapshot().remoteLogInstallId
        if (existing.isNotBlank()) return existing
        val id = (1..6).map { (0..255).random().toString(16).padStart(2, '0') }.joinToString("")
        repo.saveRemoteLogInstallId(id)
        return id
    }

    private suspend fun buildSnapshot(): String {
        val logs = AppLog.readAll().ifBlank { "(empty)" }
        return buildString {
            appendLine("ImgInk Uploader diagnostic log")
            appendLine(
                "Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}"
            )
            appendLine("Privacy: redacted snapshot; credentials and personal content removed")
            appendLine()
            append(redact(logs))
        }
    }

    /** 参照参考实现的脱敏规则：凭据/邮箱/URL/本地路径全部打码 */
    fun redact(input: String): String {
        var v = input
        v = Pattern.compile(
            "(authorization|cookie|set-cookie|password|access[_-]?token|token)\\s*[:=]\\s*[^\\s,;]+",
            Pattern.CASE_INSENSITIVE
        ).matcher(v).replaceAll("$1=[REDACTED]")
        v = Pattern.compile(
            "bearer\\s+[a-z0-9._~+/-]+=*", Pattern.CASE_INSENSITIVE
        ).matcher(v).replaceAll("Bearer [REDACTED]")
        v = v.replace(Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"), "[UUID]")
        v = v.replace(Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOption.IGNORE_CASE), "[EMAIL]")
        v = v.replace(Regex("https?://[^\\s]+"), "[URL]")
        v = v.replace(Regex("([a-z]:\\\\|/storage/|/data/)[^\\r\\n\\s]+", RegexOption.IGNORE_CASE), "[LOCAL_PATH]")
        return v
    }

    // ---------- OpenList API ----------

    private fun login(base: String, username: String, password: String): String {
        val digest = sha256Hex("$password-$ALIST_SALT")
        val body = JSONObject()
            .put("username", username)
            .put("password", digest)
            .put("otp_code", "")
        val resp = post(base, "/api/auth/login/hash", body, token = null)
        if (resp.code != 200 || resp.dataToken.isBlank()) {
            error(resp.message.ifBlank { "OpenList 登录失败" })
        }
        return resp.dataToken
    }

    private fun ensureDir(base: String, token: String, path: String) {
        val resp = post(base, "/api/fs/mkdir", JSONObject().put("path", path), token)
        if (resp.httpCode == 401 || resp.code == 401) throw AuthException(resp.message)
        if (resp.code != 200 && !resp.message.lowercase().contains("exist")) {
            error(resp.message.ifBlank { "无法创建远程日志目录" })
        }
    }

    private fun put(base: String, token: String, remotePath: String, bytes: ByteArray) {
        val req = Request.Builder()
            .url("$base/api/fs/put")
            .header("Authorization", token)
            .header("File-Path", java.net.URLEncoder.encode(remotePath, "UTF-8"))
            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        client.newCall(req).execute().use { http ->
            val json = runCatching { JSONObject(http.body?.string().orEmpty()) }.getOrNull()
            val code = json?.optInt("code") ?: -1
            val msg = json?.optString("message").orEmpty()
            if (http.code == 401 || code == 401) throw AuthException(msg)
            if (code != 200) error(msg.ifBlank { "上传诊断日志失败（HTTP ${http.code}）" })
        }
    }

    private class AuthException(msg: String) : Exception(msg.ifBlank { "OpenList 认证失败" })

    private class Resp(val httpCode: Int, val code: Int, val message: String, val dataToken: String)

    private fun post(base: String, path: String, body: JSONObject, token: String?): Resp {
        val req = Request.Builder()
            .url("$base$path")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { token?.let { header("Authorization", it) } }
            .build()
        client.newCall(req).execute().use { http ->
            val text = http.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: return Resp(http.code, -1, "OpenList 请求失败（HTTP ${http.code}）", "")
            val data = json.optJSONObject("data")
            return Resp(
                http.code,
                json.optInt("code", -1),
                json.optString("message", ""),
                data?.optString("token", "").orEmpty()
            )
        }
    }

    fun normalizeBaseUrl(value: String): String {
        val v = value.trim()
        require(v.startsWith("http://") || v.startsWith("https://")) {
            "OpenList 地址需以 http:// 或 https:// 开头"
        }
        return v.trimEnd('/')
    }

    private fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
