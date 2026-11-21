package com.cjlhll.iptv

/**
 * t20：进程内**实读字节**计数器（DataSource 线程写、主线程读）。
 *
 * 为什么需要它：直播流永远不会 `onLoadCompleted`，所以 `loadEventInfo.bytesLoaded` 恒为 0，
 * 用它会得出“一个字节都没读”的错误结论。这里由 [JingcaiTsDataSource] 在每次 `read()` 里累加
 * 真实读到的字节，并记下首字节时刻，供诊断/rebuffer 分析使用。
 */
object JingcaiDiag {
    private val bytes = java.util.concurrent.atomic.AtomicLong(0L)
    private val firstByteMs = java.util.concurrent.atomic.AtomicLong(-1L)

    @Volatile var modeId: String = "off"
    @Volatile var lastCode: String = "-"
    @Volatile private var pipeline: JingcaiTsPipeline? = null

    fun reset(mode: JingcaiExperiment.Mode) {
        bytes.set(0L)
        firstByteMs.set(-1L)
        modeId = mode.id
        lastCode = "-"
        pipeline = null
    }

    /** t38：把当前流的包级解析器挂上，供 `IQTV-JINGCAI pcr/pts/offset/mode` 诊断读取。 */
    fun bindPipeline(p: JingcaiTsPipeline?) {
        pipeline = p
    }

    /** 当前流的 `pcr/pts/offset/mode` 一行（未绑定 pipeline 时为 null）。 */
    fun timelineLine(): String? = pipeline?.diagnosticLine()

    fun onBytes(n: Int, nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        if (n <= 0) return
        if (firstByteMs.get() < 0L) firstByteMs.compareAndSet(-1L, nowMs)
        bytes.addAndGet(n.toLong())
    }

    fun code(value: String) {
        lastCode = value
    }

    fun bytesRead(): Long = bytes.get()
    fun firstByteMs(): Long = firstByteMs.get()

    /** `bytesRead=.. firstByteMs=.. code=..`（供 IQTV-DIAG 行复用）。 */
    fun fields(): String = "bytesRead=${bytes.get()} firstByteMs=${firstByteMs.get()} code=$lastCode"
}
