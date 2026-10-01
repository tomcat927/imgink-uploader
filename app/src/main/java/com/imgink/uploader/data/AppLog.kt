package com.imgink.uploader.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地诊断日志：环形缓冲（超限砍掉最旧一半）。
 * 全量内容可在设置页查看，或经 [OpenListLog] 脱敏后上传远程。
 */
object AppLog {

    private const val MAX_BYTES = 512 * 1024
    private val lock = Any()
    private var file: File? = null
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        synchronized(lock) {
            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            file = File(dir, "diag.log")
        }
    }

    fun log(tag: String, msg: String) {
        val line = "${ts.format(Date())} $tag: $msg\n"
        synchronized(lock) {
            val f = file ?: return
            runCatching {
                if (f.length() > MAX_BYTES) truncate(f)
                f.appendText(line)
            }
        }
    }

    /** 超限时砍掉最旧一半，并对齐到行边界 */
    private fun truncate(f: File) {
        val bytes = f.readBytes()
        val keep = bytes.copyOfRange(bytes.size / 2, bytes.size)
        var start = 0
        while (start < keep.size && keep[start] != '\n'.code.toByte()) start++
        f.writeBytes(if (start < keep.size - 1) keep.copyOfRange(start + 1, keep.size) else keep)
    }

    fun readAll(): String = synchronized(lock) {
        file?.takeIf { it.exists() }?.readText().orEmpty()
    }

    fun clear() = synchronized(lock) { file?.delete() }
}
