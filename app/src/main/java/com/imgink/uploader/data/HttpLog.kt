package com.imgink.uploader.data

import okhttp3.Interceptor

/**
 * HTTP 自动埋点：每个请求在诊断日志留一行（方法/主机/路径/状态码/耗时），
 * 失败时附响应体片段和异常类名，保证"失败必有因可查"。
 * 不记录任何请求头（避免 token/密码入日志）。
 */
object HttpLog {

    fun interceptor(tag: String) = Interceptor { chain ->
        val req = chain.request()
        val start = System.currentTimeMillis()
        val target = "${req.url.host}:${req.url.port}${req.url.encodedPath}"
        try {
            val resp = chain.proceed(req)
            val ms = System.currentTimeMillis() - start
            AppLog.log(tag, "${req.method} $target → ${resp.code} (${ms}ms)")
            if (resp.code >= 400) {
                val body = runCatching { resp.peekBody(1024).string() }.getOrNull()
                AppLog.log(tag, "http ${resp.code} body: ${body?.take(500) ?: "(no body)"}")
            }
            resp
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - start
            AppLog.log(
                tag,
                "${req.method} $target ✗ ${e.javaClass.simpleName}: ${e.message} (${ms}ms)"
            )
            throw e
        }
    }
}
