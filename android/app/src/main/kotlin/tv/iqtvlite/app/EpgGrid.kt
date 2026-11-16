package tv.iqtvlite.app

import org.json.JSONObject

/** EPG 网格中的一个节目格子（与 Rust GridProgramme 对齐）。 */
data class EpgProgramme(
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val col: Int,
    val span: Int,
    val progress: Int,
    val catchupId: String?,
)

/** 一行（频道）。 */
data class EpgRow(
    val tvgId: String,
    val name: String,
    val icon: String?,
    val programmes: List<EpgProgramme>,
)

/** 完整网格。 */
data class EpgGridData(
    val windowStart: Long,
    val windowEnd: Long,
    val slotMinutes: Int,
    val now: Long,
    val rows: List<EpgRow>,
    val requested: Int,
    val matched: Int,
    val guideChannels: Int,
) {
    companion object {
        fun fromJson(json: String): EpgGridData {
            val o = JSONObject(json)
            val rows = mutableListOf<EpgRow>()
            val ra = o.getJSONArray("rows")
            for (i in 0 until ra.length()) {
                val r = ra.getJSONObject(i)
                val programmes = mutableListOf<EpgProgramme>()
                val pa = r.getJSONArray("programmes")
                for (j in 0 until pa.length()) {
                    val p = pa.getJSONObject(j)
                    programmes.add(
                        EpgProgramme(
                            start = p.getLong("start"),
                            stop = p.getLong("stop"),
                            title = p.optString("title", ""),
                            description = p.optString("description", "").ifEmpty { null },
                            category = p.optString("category", "").ifEmpty { null },
                            col = p.getInt("col"),
                            span = p.getInt("span"),
                            progress = p.getInt("progress"),
                            catchupId = p.optString("catchup_id", "").ifEmpty { null },
                        )
                    )
                }
                rows.add(
                    EpgRow(
                        tvgId = r.getString("tvg_id"),
                        name = r.getString("name"),
                        icon = r.optString("icon", "").ifEmpty { null },
                        programmes = programmes,
                    )
                )
            }
            val stats = o.getJSONObject("stats")
            return EpgGridData(
                windowStart = o.getLong("window_start"),
                windowEnd = o.getLong("window_end"),
                slotMinutes = o.getInt("slot_minutes"),
                now = o.getLong("now"),
                rows = rows,
                requested = stats.optInt("requested", 0),
                matched = stats.optInt("matched", 0),
                guideChannels = stats.optInt("guide_channels", 0),
            )
        }
    }
}
