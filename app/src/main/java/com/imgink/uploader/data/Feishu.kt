package com.imgink.uploader.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 飞书自定义机器人推送。
 *
 * 卡片使用 schema 2.0 格式（经典格式已被飞书服务端拒绝，返回 9499，实测于 2026-10）。
 * 消息标题固定包含「图床」关键词，以兼容开启"自定义关键词"安全设置的机器人。
 */
object Feishu {

    private val client = OkHttpClient()

    /** 飞书签名算法：HMAC-SHA256 的 key = "timestamp\nsecret"，对空串签名后 Base64 */
    fun sign(timestamp: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("$timestamp\n$secret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(ByteArray(0)), Base64.NO_WRAP)
    }

    /**
     * 发送卡片消息到群机器人。
     * @param rawUrl 原始链接，放在代码块中方便桌面端一键复制；可为 null
     */
    suspend fun sendCard(
        webhook: String,
        secret: String?,
        title: String,
        markdownBody: String,
        rawUrl: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
            if (!secret.isNullOrBlank()) {
                val ts = (System.currentTimeMillis() / 1000).toString()
                body.put("timestamp", ts)
                body.put("sign", sign(ts, secret))
            }
            body.put("msg_type", "interactive")

            val elements = JSONArray()
            elements.put(
                JSONObject()
                    .put("tag", "markdown")
                    .put("text_align", "left")
                    .put("content", markdownBody)
            )
            if (!rawUrl.isNullOrBlank()) {
                elements.put(
                    JSONObject()
                        .put("tag", "markdown")
                        .put("text_align", "left")
                        .put("content", "```\n$rawUrl\n```")
                )
            }
            val card = JSONObject()
                .put("schema", "2.0")
                .put("config", JSONObject().put("update_multi", true))
                .put(
                    "header",
                    JSONObject()
                        .put("template", "blue")
                        .put("title", JSONObject().put("tag", "plain_text").put("content", title))
                )
                .put("body", JSONObject().put("direction", "vertical").put("elements", elements))
            body.put("card", card)

            val req = Request.Builder()
                .url(webhook)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                val code = json?.optInt("code") ?: -1
                if (code == 0) return@runCatching
                val hint = when (code) {
                    19021 -> "签名校验失败(19021)，请检查签名密钥是否正确"
                    19022 -> "IP 不在白名单(19022)，请关闭机器人的 IP 白名单"
                    19024 -> "消息不含安全设置的关键词(19024)，关键词可设为「图床」"
                    else -> "飞书返回错误 code=$code ${json?.optString("msg").orEmpty()}"
                }
                error(hint)
            }
        }
    }
}
