package com.cjlhll.iptv

import java.io.File
import org.json.JSONObject
import tv.iqtvlite.app.RustBridge

/** File-based Rust XMLTV parse; gzip detection and timestamps stay in native core. */
object XmlTvParser {
    fun parseFile(file: File): EpgData {
        val root = JSONObject(RustBridge.parseXmltvFile(file.absolutePath))
        require(root.optBoolean("ok")) { root.optString("error", "XMLTV解析失败") }
        val displayNames = HashMap<String, String>()
        val channels = root.getJSONArray("channels")
        for (i in 0 until channels.length()) {
            val channel = channels.getJSONArray(i)
            val id = channel.getString(0)
            val names = channel.getJSONArray(1)
            for (j in 0 until names.length()) {
                for (key in EpgNormalize.keys(names.getString(j))) displayNames[key] = id
            }
        }
        val result = HashMap<String, List<EpgProgram>>()
        val programmes = root.getJSONObject("programmes")
        for (id in programmes.keys()) {
            val rows = programmes.getJSONArray(id)
            result[id] = List(rows.length()) { index ->
                val row = rows.getJSONArray(index)
                EpgProgram(id, row.getLong(0), row.getLong(1), row.getString(2), if (row.isNull(3)) null else row.getInt(3))
            }
        }
        return EpgData(result, displayNames)
    }
}
