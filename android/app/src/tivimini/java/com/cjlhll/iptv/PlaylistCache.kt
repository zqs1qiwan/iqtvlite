package com.cjlhll.iptv

import android.content.Context
import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object PlaylistCache {
    fun fileNameForSource(sourceUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceUrl.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            for (b in digest) {
                append(((b.toInt() shr 4) and 0xF).toString(16))
                append((b.toInt() and 0xF).toString(16))
            }
        }
        return "playlist_$hex.m3u"
    }

    fun write(context: Context, fileName: String, content: String): File {
        val file = File(context.filesDir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    /**
     * 使用 Android AtomicFile 原子替换（R03）：唯一 .new/.bak，写入失败保留最后可用文件，
     * 不共用固定 tmp、不先删除旧目标。
     */
    fun writeAtomic(context: Context, fileName: String, content: String): Boolean {
        val dir = context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val atomic = AtomicFile(File(dir, fileName))
        var stream: FileOutputStream? = null
        return try {
            stream = atomic.startWrite()
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
            true
        } catch (e: Exception) {
            android.util.Log.w("PlaylistCache", "atomic write failed: ${e.message}")
            if (stream != null) atomic.failWrite(stream)
            false
        }
    }

    fun read(context: Context, fileName: String): String? {
        val file = File(context.filesDir, fileName)
        if (!file.exists()) return null
        return file.readText(Charsets.UTF_8)
    }
}
