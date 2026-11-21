package com.cjlhll.iptv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * t20：给上游 [DataSource] 加一层**实读字节计数**（[JingcaiDiag]）。
 *
 * 零改写：字节原样透传，只在 `read()` 后累加计数（一次 AtomicLong 加法），用于
 * `IQTV-DIAG bytesRead=.. firstByteMs=..` 与 rebuffer 前后的补流速率分析。
 * 上游线程不变（仍是 Media3 loader 线程）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CountingDataSource(
    private val upstream: DataSource,
) : DataSource {

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        JingcaiDiag.code("open")
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) JingcaiDiag.onBytes(n) else if (n == C.RESULT_END_OF_INPUT) JingcaiDiag.code("eof")
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }
}
