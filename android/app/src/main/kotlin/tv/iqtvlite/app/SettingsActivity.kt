package tv.iqtvlite.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 设置页（TiviMate 风格功能）：
 * - 家长控制（PIN / 锁定频道）
 * - 备份 / 恢复
 * - 提醒管理
 * - 隐藏频道 / 自定义分组 / 排序
 */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // UX-1：设置以右侧半透明抽屉呈现，宽度 62% 屏宽，视频从左侧透出
        val dm = resources.displayMetrics
        window.setLayout((dm.widthPixels * 0.62).toInt(), dm.heightPixels)
        val params = window.attributes
        params.gravity = android.view.Gravity.END or android.view.Gravity.FILL_VERTICAL
        params.x = 0
        window.attributes = params
        setContentView(R.layout.activity_settings)

        setupRow(R.id.row_pin, R.id.sub_pin, "家长控制 PIN", "设置或清除 PIN 锁") {
            val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            AlertDialog.Builder(this)
                .setTitle("设置家长控制 PIN（4 位）")
                .setMessage("当前: ${if (TiviMatePrefs.isParentalEnabled(this)) "已启用" else "未启用"}\n留空则清除 PIN")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val pin = input.text.toString().trim()
                    TiviMatePrefs.setPin(this, pin.ifEmpty { null })
                    Toast.makeText(this, if (pin.isEmpty()) "PIN 已清除" else "PIN 已设置", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null).show()
        }

        setupRow(R.id.row_backup, R.id.sub_backup, "备份设置", "导出全部配置到 JSON 文件") {
            try {
                val json = TiviMatePrefs.exportBackup(this).toString(2)
                val f = TiviMatePrefs.backupFile(this)
                f.writeText(json)
                Toast.makeText(this, "已备份到 ${f.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "备份失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        setupRow(R.id.row_restore, R.id.sub_restore, "恢复设置", "从备份文件导入配置") {
            val f = TiviMatePrefs.backupFile(this)
            if (!f.exists()) {
                Toast.makeText(this, "没有找到备份文件", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    val ok = TiviMatePrefs.importBackup(this, org.json.JSONObject(f.readText()))
                    Toast.makeText(this, if (ok) "恢复成功，重启应用生效" else "恢复失败", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "备份损坏: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        setupRow(R.id.row_diagnostics, R.id.sub_diagnostics, "播放诊断", "首帧耗时/缓冲/分辨率，可复制") {
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                putExtra("show_diagnostics", true)
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
        setupRow(R.id.row_reminders, R.id.sub_reminders, "提醒管理", "查看 / 删除已设置的节目提醒") {
            val list = TiviMatePrefs.reminders(this)
            val names = if (list.isEmpty()) arrayOf("暂无提醒") else
                list.map { "${it.title} @ ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it.startTs * 1000))}" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("节目提醒 (${list.size})")
                .setItems(names) { _, which ->
                    if (list.isNotEmpty()) {
                        TiviMatePrefs.removeReminder(this, list[which])
                        Toast.makeText(this, "已删除提醒", Toast.LENGTH_SHORT).show()
                    }
                }
                .setPositiveButton("关闭", null).show()
        }

        setupRow(R.id.row_epg_sources, R.id.sub_epg_sources, "EPG 源管理", "添加 / 设置优先级") {
            showEpgSourceManager()
        }

        setupRow(R.id.row_playback, R.id.sub_playback, "播放设置", "自动帧率 / 音频直通 / 外部播放器") {
            showPlaybackPrefs()
        }

        setupRow(R.id.row_hidden, R.id.sub_hidden, "隐藏频道", "当前隐藏 ${TiviMatePrefs.hiddenChannels(this).size} 个") {
            Toast.makeText(this, "在频道列表长按可隐藏/显示频道", Toast.LENGTH_SHORT).show()
        }

        setupRow(R.id.row_locked, R.id.sub_locked, "锁定频道", "当前锁定 ${TiviMatePrefs.lockedChannels(this).size} 个") {
            Toast.makeText(this, "在频道列表长按可锁定/解锁频道", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
    }

    private fun showPlaybackPrefs() {
        val p = TiviMatePrefs.playbackPrefs(this)
        val opts = arrayOf(
            "${if (p.autoFrameRate) "✓" else "✗"} 自动帧率 (AFR)",
            "${if (p.audioPassthrough) "✓" else "✗"} 音频直通",
            "${if (p.externalPlayer) "✓" else "✗"} 外部播放器兜底",
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("播放设置")
            .setItems(opts) { _, which ->
                val np = when (which) {
                    0 -> p.copy(autoFrameRate = !p.autoFrameRate)
                    1 -> p.copy(audioPassthrough = !p.audioPassthrough)
                    else -> p.copy(externalPlayer = !p.externalPlayer)
                }
                TiviMatePrefs.savePlaybackPrefs(this, np)
                showPlaybackPrefs()
            }
            .setPositiveButton("完成", null).show()
    }

    private fun showEpgSourceManager() {
        val sources = TiviMatePrefs.epgSources(this).toMutableList()
        fun refreshDialog() {
            val labels = if (sources.isEmpty()) arrayOf("（使用播放列表自带的 EPG 源）") else
                sources.map { "P${it.priority} ${it.url}" }.toTypedArray()
            val actions = arrayOf("添加 EPG 源", "调整优先级 / 删除", "完成")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("EPG 源管理（数字越小优先级越高）")
                .setItems(labels) { _, _ -> }
                .setPositiveButton("添加") { _, _ ->
                    val input = EditText(this).apply { hint = "https://.../xmltv.php" }
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("添加 EPG 源")
                        .setView(input)
                        .setPositiveButton("添加") { _, _ ->
                            val url = input.text.toString().trim()
                            if (url.isNotEmpty()) {
                                sources.add(TiviMatePrefs.EpgSource(url, (sources.maxOfOrNull { it.priority } ?: 0) + 10))
                                TiviMatePrefs.saveEpgSources(this, sources)
                                refreshDialog()
                            }
                        }
                        .setNegativeButton("取消") { _, _ -> refreshDialog() }.show()
                }
                .setNeutralButton("管理") { _, _ ->
                    if (sources.isEmpty()) { refreshDialog(); return@setNeutralButton }
                    val opts = sources.mapIndexed { i, s -> "P${s.priority} ${s.url}" }.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("点击删除 / 降低优先级")
                        .setItems(opts) { _, which ->
                            val s = sources[which]
                            if (s.priority > 10) sources[which] = s.copy(priority = s.priority - 10)
                            else sources.removeAt(which)
                            TiviMatePrefs.saveEpgSources(this, sources)
                            refreshDialog()
                        }.show()
                }
                .setNegativeButton("完成", null)
                .show()
        }
        refreshDialog()
    }

    private fun setupRow(rowId: Int, subId: Int, title: String, subtitle: String, onClick: () -> Unit) {
        findViewById<LinearLayout>(rowId).setOnClickListener { onClick() }
        findViewById<TextView>(subId).text = subtitle
    }
}
