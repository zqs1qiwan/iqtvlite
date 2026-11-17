package tv.iqtvlite.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 手机扫码推源页面。内置 HTTP 使用轻量纯 Java ServerSocket，不依赖 NanoHTTPD。 */
class SourcePushActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val finished = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var selectedPort: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(18, 20, 25))
        }
        val title = TextView(this).apply {
            text = "用手机扫码推送直播源"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val qr = ImageView(this).apply {
            contentDescription = "手机扫码推送直播源二维码"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val address = TextView(this).apply {
            text = "正在启动局域网服务…"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(qr, LinearLayout.LayoutParams(dp(280), dp(280)).apply { topMargin = dp(28); bottomMargin = dp(22) })
        root.addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        worker.execute {
            try {
                val ip = findPrivateIpv4() ?: throw IllegalStateException("未找到局域网 IPv4 地址")
                val socket = bindServer()
                serverSocket = socket
                selectedPort = socket.localPort
                val url = "http://$ip:$selectedPort/"
                val bitmap = createQr(url, 560)
                runOnUiThread {
                    if (!isFinishing) {
                        qr.setImageBitmap(bitmap)
                        address.text = url
                    }
                }
                while (!finished.get()) {
                    val client = try { socket.accept() } catch (e: Exception) { if (finished.get()) break else throw e }
                    handleClient(client)
                }
            } catch (e: Exception) {
                runOnUiThread { if (!isFinishing) address.text = "局域网服务启动失败：${e.message ?: "未知错误"}" }
            }
        }
    }

    private fun bindServer(): ServerSocket {
        var lastError: Exception? = null
        for (port in 8899..8999) {
            try { return ServerSocket(port, 8) } catch (e: Exception) { lastError = e }
        }
        throw lastError ?: IllegalStateException("端口 8899–8999 均不可用")
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val headerBytes = readHeaders(input) ?: return
            val headerText = String(headerBytes, Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val request = lines.firstOrNull()?.split(' ').orEmpty()
            val method = request.getOrNull(0).orEmpty()
            val path = request.getOrNull(1)?.substringBefore('?').orEmpty()
            val contentLength = lines.drop(1).firstOrNull { it.substringBefore(':').equals("Content-Length", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0

            if (method == "GET" && (path == "/" || path == "/index.html")) {
                respond(output, 200, "text/html; charset=utf-8", HTML.toByteArray(Charsets.UTF_8))
            } else if (method == "OPTIONS") {
                respond(output, 204, "text/plain; charset=utf-8", byteArrayOf())
            } else if (method == "POST" && path == "/api/push") {
                val body = ByteArray(contentLength)
                var offset = 0
                while (offset < body.size) {
                    val count = input.read(body, offset, body.size - offset)
                    if (count < 0) break
                    offset += count
                }
                try {
                    val json = JSONObject(String(body, 0, offset, Charsets.UTF_8))
                    val live = json.optString("live", "").trim()
                    val epg = json.optString("epg", "").trim()
                    if (live.isEmpty()) {
                        respond(output, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"直播源地址不能为空\"}".toByteArray())
                    } else {
                        getSharedPreferences("iqtv_prefs", MODE_PRIVATE).edit()
                            .putString("push_live_url", live).putString("push_epg_url", epg).commit()
                        respond(output, 200, "application/json; charset=utf-8", "{\"ok\":true}".toByteArray())
                        if (finished.compareAndSet(false, true)) runOnUiThread { finish() }
                    }
                } catch (_: Exception) {
                    respond(output, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"JSON 格式错误\"}".toByteArray())
                }
            } else {
                respond(output, 404, "text/plain; charset=utf-8", "Not Found".toByteArray())
            }
        }
    }

    private fun readHeaders(input: BufferedInputStream): ByteArray? {
        val bytes = ArrayList<Byte>(1024)
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (bytes.size < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            bytes.add(b.toByte())
            matched = when {
                b == end[matched].toInt() -> matched + 1
                b == 13 -> 1
                else -> 0
            }
            if (matched == end.size) return ByteArray(bytes.size) { bytes[it] }
        }
        throw IllegalArgumentException("请求头过长")
    }

    private fun respond(output: BufferedOutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; else -> "Not Found" }
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nCache-Control: no-store\r\n\r\n"
        output.write(headers.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun createQr(content: String, size: Int): Bitmap {
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    private fun findPrivateIpv4(): String? {
        // 首选：通过 ConnectivityManager 拿当前活跃（正在上网）网络的 IP——这是手机能访问的那个
        try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java)
            val active = cm?.activeNetwork
            if (active != null) {
                val link = cm.getLinkProperties(active)
                val ip = link?.linkAddresses?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.map { it.hostAddress }
                    ?.firstOrNull { isPrivateIp(it) }
                if (ip != null) return ip
            }
        } catch (_: Exception) {}
        // 兜底：遍历网卡
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        for (network in interfaces) {
            if (!runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)) continue
            for (address in Collections.list(network.inetAddresses)) {
                if (address !is Inet4Address || address.isLoopbackAddress) continue
                val ip = address.hostAddress ?: continue
                if (isPrivateIp(ip)) return ip
            }
        }
        return null
    }

    private fun isPrivateIp(ip: String?): Boolean {
        if (ip == null) return false
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return (parts[0] == 192 && parts[1] == 168) || parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        finished.set(true)
        runCatching { serverSocket?.close() }
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        val HTML = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>推送直播源</title><style>
:root{color-scheme:dark;--bg:#10131b;--card:#1a2030;--line:#30394d;--muted:#aab4c8;--accent:#53d6b0}*{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(ellipse at top,#202c43,var(--bg) 60%);color:#f4f7fc;font-family:system-ui,-apple-system,"Noto Sans CJK SC",sans-serif;display:grid;place-items:center;padding:22px}.card{width:min(100%,520px);background:rgba(26,32,48,.96);border:1px solid var(--line);border-radius:24px;padding:clamp(22px,6vw,38px);box-shadow:0 20px 70px #0006}h1{font-size:clamp(24px,6vw,32px);margin:0 0 8px}.sub{color:var(--muted);line-height:1.6;margin:0 0 28px}label{display:block;font-size:14px;color:#c8d1e0;margin:20px 0 9px}input{width:100%;padding:16px;border-radius:13px;border:1px solid var(--line);background:#111725;color:white;font-size:16px;outline:none}input:focus{border-color:var(--accent);box-shadow:0 0 0 3px #53d6b022}button{margin-top:26px;width:100%;border:0;border-radius:14px;padding:17px;background:linear-gradient(100deg,#42cda5,#5bd6df);color:#071b1b;font-size:18px;font-weight:750;cursor:pointer}button:disabled{opacity:.6}#msg{min-height:24px;margin:16px 0 0;text-align:center;color:var(--muted)}.ok{color:#7be8c4!important}.err{color:#ff9c9c!important}
</style></head><body><main class="card"><h1>推送直播源</h1><p class="sub">请填写电视要使用的地址，EPG 地址可以留空。提交后配置将保存到电视。</p>
<label for="live">直播源地址</label><input id="live" type="url" inputmode="url" autocomplete="url" placeholder="https://example.com/live.m3u">
<label for="epg">EPG 地址（选填）</label><input id="epg" type="url" inputmode="url" autocomplete="url" placeholder="https://example.com/epg.xml">
<button id="send">推送到电视</button><p id="msg" role="status"></p></main><script>
const btn=document.getElementById('send'),msg=document.getElementById('msg');btn.onclick=async()=>{const live=document.getElementById('live').value.trim(),epg=document.getElementById('epg').value.trim();if(!live){msg.textContent='请先填写直播源地址';msg.className='err';return}btn.disabled=true;msg.textContent='正在推送…';msg.className='';try{const r=await fetch('/api/push',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({live,epg})});const d=await r.json();if(r.ok&&d.ok){msg.textContent='推送成功，电视已保存';msg.className='ok'}else{msg.textContent=d.error||'推送失败';msg.className='err'}}catch(e){msg.textContent='连接失败，请确认手机与电视在同一局域网';msg.className='err'}finally{btn.disabled=false}};
</script></body></html>"""
    }
}
