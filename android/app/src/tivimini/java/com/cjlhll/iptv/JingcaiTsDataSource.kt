package com.cjlhll.iptv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * t15：包一层 [DataSource]，做两件事：
 * 1. **实读字节计数**（`JingcaiDiag`）修掉直播流 `bytes=0` 的误判（直播永不 `onLoadCompleted`）；
 * 2. 当睛彩时间轴实验（A/B/AUTO）开启时，把字节流交给 [JingcaiTsPipeline] 做原地包级变换。
 *
 * 其余模式（OFF/E1/E2/E3）只计数、**零改写**。上游读取仍在它自己的线程上（Media3 loader），
 * 这里不新增线程、不做 IO 缓冲以外的额外复制（仅多一层 memory copy）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class JingcaiTsDataSource(
    private val upstream: DataSource,
    private val pipeline: JingcaiTsPipeline?,
) : DataSource {

    private val scratch = ByteArray(64 * 1024)

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        JingcaiDiag.code("open")
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (pipeline == null) {
            val n = upstream.read(buffer, offset, length)
            if (n > 0) JingcaiDiag.onBytes(n)
            return n
        }
        var written = pipeline.pop(buffer, offset, length)
        while (written == 0) {
            val n = upstream.read(scratch, 0, scratch.size)
            if (n == C.RESULT_END_OF_INPUT) {
                JingcaiDiag.code("eof")
                if (pipeline.pendingBytes() > 0) return pipeline.drainRaw(buffer, offset, length)
                return C.RESULT_END_OF_INPUT
            }
            if (n <= 0) return 0
            JingcaiDiag.onBytes(n)
            pipeline.push(scratch, 0, n)
            written = pipeline.pop(buffer, offset, length)
        }
        return written
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    /** 供诊断/测试读取（null = 纯计数模式）。 */
    fun pipelineOrNull(): JingcaiTsPipeline? = pipeline
}
