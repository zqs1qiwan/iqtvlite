package com.cjlhll.iptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

object ApkDownloader {
    private const val TAG = "ApkDownloader"

    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val isComplete: Boolean
    ) {
        val progress: Float
            get() = when {
                isComplete -> 1f
                totalBytes > 0 -> (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                // Unknown Content-Length: gently creep toward 90% so user sees activity
                else -> (bytesDownloaded.toFloat() / (16f * 1024f * 1024f)).coerceIn(0.02f, 0.90f)
            }
    }

    suspend fun downloadWithProgress(context: Context, url: String, onProgress: (DownloadProgress) -> Unit): File? {
        return withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "update_${System.currentTimeMillis()}.apk")
            try {
                val client = NetUtil.ipv4OkHttpClient
                val request = Request.Builder().url(url).build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Request failed: ${response.code}")
                        file.delete()
                        return@withContext null
                    }

                    val body = response.body ?: run {
                        file.delete()
                        return@withContext null
                    }
                    val totalBytes = body.contentLength()
                    var bytesDownloaded = 0L

                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                bytesDownloaded += bytesRead

                                onProgress(DownloadProgress(
                                    bytesDownloaded = bytesDownloaded,
                                    totalBytes = totalBytes,
                                    isComplete = false
                                ))
                            }
                        }
                    }

                    if (bytesDownloaded <= 0L) {
                        file.delete()
                        return@withContext null
                    }

                    onProgress(DownloadProgress(
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = if (totalBytes > 0) totalBytes else bytesDownloaded,
                        isComplete = true
                    ))

                    file
                }
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}")
                file.delete()
                null
            }
        }
    }

    fun installApk(context: Context, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Install error: ${e.message}")
            false
        }
    }

    fun deleteApk(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete error: ${e.message}")
        }
    }
}
