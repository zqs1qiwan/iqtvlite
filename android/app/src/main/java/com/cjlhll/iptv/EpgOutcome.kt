package com.cjlhll.iptv

/** EPG 加载结果：区分真实网络成功、旧缓存回退、彻底失败，并携带源 ID（R04）。 */
sealed class EpgLoadResult {
    abstract val sourceId: String

    data class Success(override val sourceId: String, val data: EpgData) : EpgLoadResult()
    data class CacheFallback(override val sourceId: String, val data: EpgData) : EpgLoadResult()
    data class Failure(override val sourceId: String, val reason: String?) : EpgLoadResult()

    val dataOrNull: EpgData?
        get() = when (this) {
            is Success -> data
            is CacheFallback -> data
            is Failure -> null
        }

    val isNetworkSuccess: Boolean get() = this is Success
}

/** 按源隔离的 EPG 内存缓存（R04）。 */
class EpgSourceCache {
    private val map = HashMap<String, EpgData>()

    @Synchronized fun get(sourceId: String): EpgData? = map[sourceId]
    @Synchronized fun put(sourceId: String, data: EpgData) { map[sourceId] = data }
    @Synchronized fun remove(sourceId: String) { map.remove(sourceId) }
    @Synchronized fun clear() = map.clear()
}

/** 纯分类：网络解析结果优先，其次旧缓存回退，否则失败（不会被误记为成功）。 */
object EpgOutcome {
    fun classify(network: EpgData?, cached: EpgData?, sourceId: String, reason: String? = null): EpgLoadResult =
        when {
            network != null -> EpgLoadResult.Success(sourceId, network)
            cached != null -> EpgLoadResult.CacheFallback(sourceId, cached)
            else -> EpgLoadResult.Failure(sourceId, reason)
        }
}
