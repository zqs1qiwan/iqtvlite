window.WIKI_META = {
  zh: {
    navHeading: "使用指南目录",
    searchPlaceholder: "搜索功能、按键、DoH、AFR、组播...",
    switchToLabel: "English",
    downloadLabel: "下载 APK",
    readmeLabel: "GitHub 中文主页",
    readmeHref: "https://github.com/zqs1qiwan/iqtvlite/blob/main/README.zh-CN.md",
    prevLabel: "上一节",
    nextLabel: "下一节",
    searchHitsTitle: "搜索结果",
    noSearchHits: "未找到匹配的 Wiki 章节，请尝试其他关键词。",
    disclaimer: "IQTV Lite 是开源、纯本地播放内核工具，不内置任何直播源、订阅链接或影视内容（GPLv3）。"
  },
  en: {
    navHeading: "Documentation",
    searchPlaceholder: "Search features, keys, DoH, AFR, multicast...",
    switchToLabel: "简体中文",
    downloadLabel: "Download APK",
    readmeLabel: "English README",
    readmeHref: "https://github.com/zqs1qiwan/iqtvlite/blob/main/README.md",
    prevLabel: "Previous",
    nextLabel: "Next",
    searchHitsTitle: "Search Results",
    noSearchHits: "No matching wiki pages found.",
    disclaimer: "IQTV Lite is an open-source, player-only Android TV app (GPLv3). It bundles no channels, playlists, or streams."
  }
};

window.WIKI_DATA = {
  zh: [
    {
      id: "getting-started",
      icon: "🚀",
      title: "快速上手与安装",
      html: `
        <h1>快速上手 IQTV Lite</h1>
        <p class="lead">IQTV Lite 是一款专为 <strong>Android TV 与电视盒子（含 512MB–1GB 入门老盒子）</strong> 打造的轻量、纯净直播电视播放器，主打开机直达播放、秒级换台、遥控器优先交互与家庭局域网组播优化。</p>
        <div class="callout warn">
          <strong>纯播放器声明：</strong>IQTV Lite 不内置任何电视频道、播放源或订阅服务，用户需自行添加合法直播源（M3U / TXT / 组播 / XMLTV EPG）。
        </div>
        <h2>系统要求</h2>
        <ul>
          <li><strong>系统版本：</strong>Android 7.0（API 24）及以上（覆盖绝大部分智能电视、广电/运营商电视盒子与外贸盒）。</li>
          <li><strong>CPU 架构：</strong>官方单包同时包含 <code>armeabi-v7a</code>（32 位老盒子）与 <code>arm64-v8a</code>，正式版体积约 <strong>8.7 MB</strong>。</li>
          <li><strong>交互方式：</strong>完整适配红外/蓝牙电视遥控器方向键（D-Pad）、实体频道 +/- 键与数字直达选台。</li>
        </ul>
        <h2>安装与在线更新</h2>
        <ol>
          <li>前往 <a href="https://github.com/zqs1qiwan/iqtvlite/releases/latest" target="_blank" rel="noopener">GitHub Releases 最新版本</a> 下载 <code>IQTV-Lite-v0.3.2.apk</code>（或 <code>IQTV-Lite-latest.apk</code>）。</li>
          <li>通过 U 盘安装或使用 <code>adb install -r IQTV-Lite-latest.apk</code> 推送到电视/盒子。</li>
          <li>安装后首次启动可通过底部「设置 → 通用 → 手机扫码管理与备份」在同一 Wi-Fi / 局域网内免打字导入源。</li>
          <li>后续更新可直接在电视端进入 <strong>「设置 → 关于与更新 → 检查更新」</strong>，自动在应用内下载并拉起系统安装。</li>
        </ol>
      `
    },
    {
      id: "ui-remote",
      icon: "📺",
      title: "界面导览与遥控按键",
      html: `
        <h1>界面导览与遥控按键</h1>
        <p class="lead">所有浮层与抽屉采用电视端高对比曜石暗色卡片与真居中焦点设计，点击下方任意截图可放大查看细节。</p>
        <div class="shot-grid">
          <figure class="shot-card">
            <img src="assets/screenshots/player-infocard.png" alt="底部信息栏与快捷控制" loading="lazy" />
            <figcaption>底部两段式信息栏 · 最近观看 + 8 项本台快捷控制（含纯音频模式）</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/channel-drawer.png" alt="频道列表与节目单" loading="lazy" />
            <figcaption>左侧分栏选台抽屉 · 频道号、实时台标与当前/预告 EPG 进度</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/drawer-groups-sources.png" alt="分组与多源切换" loading="lazy" />
            <figcaption>按 ← 逐级展开分组列与多播放源列表</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/settings.png" alt="设置中心首页" loading="lazy" />
            <figcaption>7 大分类设置中心与首页实时摘要</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/settings-playback.png" alt="播放与解码设置" loading="lazy" />
            <figcaption>播放与解码设置 · 单频道解码覆盖、AFR、音量增益与延迟</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/stream-diag.png" alt="实时码流诊断" loading="lazy" />
            <figcaption>实时码流诊断视图 · 编码格式、分辨率、解码器、DoH 与组播丢包</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/webui-qr.png" alt="手机扫码控制台" loading="lazy" />
            <figcaption>手机扫码直连局域网 Web 控制台 · 多源管理与配置备份</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/about-updates.png" alt="关于与更新" loading="lazy" />
            <figcaption>关于与在线更新 · 内联检查版本与一键下载安装包</figcaption>
          </figure>
        </div>
        <h2>默认遥控器快捷键</h2>
        <table>
          <thead><tr><th>按键</th><th>全屏默认行为</th><th>说明</th></tr></thead>
          <tbody>
            <tr><td><code>↑</code> / <code>↓</code></td><td>上一台 / 下一台</td><td>切换频道并在底部弹出信息卡（可自定义改作打开频道抽屉）</td></tr>
            <tr><td><code>OK</code> / <code>确认</code></td><td>呼出两段式信息栏</td><td>第 1 次看台标与节目；第 2 次或按 <code>↓</code> 展开最近频道与快捷栏（收藏 / 换源 / 画面比例 / 音轨 / 仅听音频 / 字幕 / 多画面 / 设置）</td></tr>
            <tr><td><code>←</code></td><td>逐级打开选台抽屉</td><td>第 1 次进频道与 EPG；第 2 次进分组；第 3 次展开多播放源</td></tr>
            <tr><td><code>→</code></td><td>一键返回上一频道</td><td>在应用内双画面（画中画）开启时自动用于「互换主副画面」</td></tr>
            <tr><td><code>0–9</code></td><td>数字直达选台</td><td>右上角弹出实时编号联想匹配，停顿或按 OK 跳转</td></tr>
            <tr><td><code>Back</code></td><td>逐级收起 / 双击退出</td><td>有弹窗先关闭最上层浮层；全屏双击 Back 退出应用</td></tr>
          </tbody>
        </table>
        <p>所有功能均可在 <strong>「设置 → 遥控设置」</strong> 自由录制独立<strong>短按</strong>或<strong>长按</strong>实体键绑定。</p>
      `
    },
    {
      id: "sources-multicast",
      icon: "📡",
      title: "播放源与组播配置",
      html: `
        <h1>播放源格式、请求头与原生组播</h1>
        <h2>1. 同台多线路聚合 vs 独立频道（4K 不混淆）</h2>
        <p>在同一个 <code>#EXTINF</code> 下连续写入多个 URL，IQTV Lite 会合并为单一频道下的「线路 1/N」（支持自动容灾与手动切换）；独立编写 <code>#EXTINF</code> 的频道（即使 <code>tvg-id</code> 相同，如 4K 与普通高清）会严格保留为独立条目。</p>
<pre><code>#EXTINF:-1 tvg-id="cctv1" group-title="央视",CCTV-1 综合
https://cdn1.example.com/cctv1.m3u8|User-Agent=VLC
https://cdn2.example.com/cctv1.m3u8|Referer=https://example.com/

#EXTINF:-1 tvg-id="btv4k" group-title="4K频道",北京卫视4K HDR
rtp://239.3.1.240:8000</code></pre>
        <h2>2. HTTP 请求头生效优先级</h2>
        <ol>
          <li><strong>单地址后缀 <code>|</code> 参数（最高优先级）</strong>：例如 <code>https://live.example.com/a.m3u8|User-Agent=VLC&amp;Referer=https://a.com/&amp;Cookie=sid=1</code></li>
          <li><strong><code>#EXTINF</code> / <code>#EXTVLCOPT</code> / <code>#KODIPROP</code> 行内声明</strong>：如 <code>http-user-agent</code>、<code>http-referer</code></li>
          <li><strong>源级或全局设置</strong>：在扫码 Web 控制台「请求头」标签页配置的全局 UA 与多行自定义 Header</li>
          <li><strong>内置兜底默认 UA</strong>：<code>VLC/3.0.20 LibVLC/3.0.20</code></li>
        </ol>
        <h2>3. 原生直连 RTP / UDP 组播引擎</h2>
        <ul>
          <li>支持直接填写原生 <code>rtp://239.x.x.x:port</code> 或 <code>udp://@239.x.x.x:port</code>（无需依赖路由器 udpxy，同时也兼容 <code>http://ip:port/rtp/...</code>）。</li>
          <li>底层内置非阻塞独立收包线程 + <strong>4 MB 环形缓冲区</strong> + 动态 <strong>WifiLock / MulticastLock</strong>，专治内网 Wi-Fi 4K HDR 组播抖动丢包与花屏卡顿。</li>
          <li>如果您的家庭宽带仅通过网线连接 IPTV VLAN 路由器，在支持原生组播的网络下可实现零转换开销极速换台。</li>
        </ul>
      `
    },
    {
      id: "playback-audio",
      icon: "🎛️",
      title: "解码策略、AFR 与音频调校",
      html: `
        <h1>播放解码、自动帧率与音频 DSP</h1>
        <h2>1. 硬件优先、逐级回退解码梯队 + 单频道覆盖</h2>
        <ul>
          <li>默认所有频道均优先调度设备 <strong>MediaCodec 硬件解码</strong>（含对部分 Android 9 机顶盒 <code>media_codecs.xml</code> 上限误判的 4K HEVC/HDR 自动纠偏）。</li>
          <li>若切台出现异常，8 秒看门狗自动执行「重建解码器 → 软解（含 FFmpeg 音视频扩展）」，但下一台仍自动重置回硬解，绝不污染后续 4K 频道。</li>
          <li>在 <strong>「设置 → 播放解码 → 本频道解码」</strong> 可针对特定顽固频道记住「强制硬解」或「强制软解」，下次切入零等待立即生效。</li>
          <li>老旧盒子切换 4K 频道出现短暂花屏/绿屏，可开启 <strong>4K 切台防花屏</strong>。</li>
        </ul>
        <h2>2. 自动帧率匹配（AFR / Auto Frame Rate）</h2>
        <p>国内直播常混合 <code>25fps</code>（1080i50 卫视）与 <code>50fps</code>（4K HDR）流。开启 <strong>「自动帧率匹配 (AFR)」</strong> 后：</p>
        <ul>
          <li>在流帧率稳定 <strong>1,200 ms</strong> 后才向显示设备请求切换最匹配的刷新率（优先 1:1 匹配如 23.976Hz，或精确整数倍如 <code>25fps → 50Hz</code>），不会因快速按上下键导致 HDMI 频繁黑屏。</li>
          <li>始终保持当前分辨率，绝不擅自把 4K 降回 1080p；退出全屏或关闭开关自动恢复电视默认模式。</li>
        </ul>
        <h2>3. 分设备音频延迟（±500ms）与 150% 响度增益</h2>
        <ul>
          <li>通过零额外第三方依赖的原生 Media3 <code>AudioSyncBoostProcessor</code> 实现。</li>
          <li>自动识别当前音频输出路由（<strong>本机扬声器 / 蓝牙音频 / HDMI 功放</strong>），各自独立记忆 <code>-500ms ~ +500ms</code> 音画同步延迟。</li>
          <li>提供 <code>100% ~ 150%</code> 软限幅人声增益（避免破音爆音）；处于默认 <code>0ms / 100%</code> 时处理器完全旁路，<strong>0 CPU 与 0 内存分配开销</strong>。</li>
        </ul>
        <h2>4. 纯音频收听与多画面</h2>
        <ul>
          <li>在信息卡快捷栏点击 <strong>「仅听音频」</strong> 可直接禁用视频轨解码并显示熄画电台提示卡，省电防电视烧屏。</li>
          <li>点击 <strong>「多画面」</strong> 可在右下角同屏监看第二个频道，按 <code>→</code> 一键互换大小窗。</li>
        </ul>
      `
    },
    {
      id: "network-epg",
      icon: "🌐",
      title: "DoH、双栈优先与 EPG 偏移",
      html: `
        <h1>网络优先级、加密 DNS (DoH) 与 EPG 节目单</h1>
        <h2>1. IPv6 / IPv4 双栈优先级</h2>
        <p>默认采用 <strong>IPv6 优先（自动回退 IPv4）</strong>，也可在「设置 → 通用 → 网络协议优先级」或手机 Web 控制台切为「IPv4 优先」。无论何种偏好，主栈失败均可无缝连接备用协议栈地址。</p>
        <h2>2. 内置零依赖 RFC 8484 加密 DNS (DoH)</h2>
        <p>针对部分地区运营商本地 DNS 劫持或污染导致公网播放源 / 台标 / EPG 域名无法解析的问题，在 <strong>「设置 → 通用 → 加密 DNS (DoH)」</strong> 或手机端「显示播放」中可选：</p>
        <table>
          <thead><tr><th>模式选项</th><th>说明</th><th>适用场景</th></tr></thead>
          <tbody>
            <tr><td><strong>系统默认（默认）</strong></td><td>直接调用系统 DNS 解析，零额外 HTTPS 请求</td><td>内网组播源、普通正常网络、最低首播延迟</td></tr>
            <tr><td><strong>阿里 DoH</strong></td><td><code>https://dns.alidns.com/dns-query</code> (223.5.5.5)</td><td>国内宽带 DNS 污染、域名直播源解析失败时推荐</td></tr>
            <tr><td><strong>腾讯 DoH</strong></td><td><code>https://doh.pub/dns-query</code> (DNSPod)</td><td>国内多运营商 BGP 加速、备用 DoH 防劫持</td></tr>
            <tr><td><strong>Cloudflare DoH</strong></td><td><code>https://cloudflare-dns.com/dns-query</code> (1.1.1.1)</td><td>海外自建源或国际线路解析</td></tr>
          </tbody>
        </table>
        <div class="callout">
          <strong>设计细节：</strong>内置 RFC 8484 二进制报文编解码，带 <strong>256 条 TTL 缓存</strong>；遇到纯 IP 地址、<code>localhost</code>、<code>.local</code> / <code>.lan</code> 等内网主机名会自动跳过 DoH，且 DoH 超时（2.5s）会自动无感回退系统 DNS，绝不卡死播放。
        </div>
        <h2>3. SQLite EPG 与 ±12 小时时区偏移</h2>
        <ul>
          <li>XMLTV 节目单异步流式解析并写入本地 <code>epg_index_v2.db</code> 索引库，首帧渲染前零阻塞，上千台换台查询仅需毫秒级。</li>
          <li>部分第三方节目单可能带有 UTC 或海外时区偏移。前往 <strong>「设置 → 节目预告 → EPG 时区偏移」</strong>（或手机 Web 控制台）即可调节 <code>-12 小时 ~ +12 小时</code>，SQL 查询窗口实时偏移生效，无需重新下载或重写数据库。</li>
        </ul>
      `
    },
    {
      id: "webui-diag",
      icon: "🩺",
      title: "扫码 WebUI 与流诊断导出",
      html: `
        <h1>手机局域网 WebUI 与实时码流诊断</h1>
        <h2>1. 免密局域网 Web 控制台</h2>
        <p>针对家庭局域网日常使用场景，点击设置内的「手机扫码管理与备份」即可开启本地 HTTP 服务：</p>
        <ul>
          <li>手机微信或相机扫码，无需任何配对 PIN 码即可直接编辑多播放源、控制哪些源在电视抽屉展示。</li>
          <li>一站式调整全局 UA / Header、语言、文字大小、起播缓冲、IPv6/v4 优先级、DoH（系统 / 阿里 / 腾讯 / Cloudflare）及 EPG 时区偏移。</li>
          <li>支持 <strong>JSON 完整配置导出 / 导入恢复</strong>，重装设备或更换新电视一秒还原所有订阅、分组排序、遥控器映射与偏好。</li>
        </ul>
        <h2>2. 实时码流诊断与日志导出</h2>
        <ul>
          <li><strong>电视端实时查看：</strong>进入 <code>设置 → 播放解码 → 实时码流诊断与日志</code>，每秒自动刷新当前视频编码/分辨率/实测帧率、正在使用的硬/软解码器名称、AFR 目标刷新率、音频路由增益/延迟、DoH 命中/回退计数以及 <strong>RTP 组播收包/丢包率</strong>，并附带最近关键事件流。</li>
          <li><strong>手机端一键导出诊断：</strong>在手机 WebUI「遥控系统」页底部点击 <strong>「导出播放诊断日志」</strong>（对应接口 <code>GET /api/diag</code>），即可直接下载结构化 JSON 诊断包用于反馈与排查，完全无需电脑或 ADB。</li>
        </ul>
      `
    },
    {
      id: "faq",
      icon: "❓",
      title: "常见问题排查",
      html: `
        <h1>常见问题排查 (FAQ)</h1>
        <h3>Q1：4K 频道画面卡顿或提示组播丢包较高？</h3>
        <ul>
          <li>在电视「实时码流诊断」或导出 <code>/api/diag</code> 查看 <code>decoder</code> 与 <code>rtp.lossPercent</code>：如果确认走的是硬解（非 <code>OMX.google.*</code> / <code>c2.android.*</code>），且 <code>lostPackets</code> 持续增长，多半是 Wi-Fi 弱信号或路由器组播转发丢包。建议电视盒子优先接有线千兆网线、路由器开启 IGMP Snooping / 组播增强，或使用路由器的单播代理（udpxy）。</li>
        </ul>
        <h3>Q2：某个频道需要等待好几秒才出画面或只有声音没有画面？</h3>
        <ul>
          <li>进入正在播放该频道的 <strong>「设置 → 播放解码 → 本频道解码」</strong> 设为「仅此频道强制软解」测试对比；或尝试在「码流兼容模式」切换 <code>A/B/AUTO</code>（针对部分 PCR/PTS 时间戳异常广电台）。</li>
        </ul>
        <h3>Q3：为什么节目单显示“当前节目”全部提前或延后了 8 小时？</h3>
        <ul>
          <li>说明你的 EPG XMLTV 文件采用 UTC+0 时间戳且未正确带时区后缀。只需进入「设置 → 节目预告 → EPG 时区偏移」选择 <code>+8 小时</code>（或手机 Web 控制台直接下拉选中 <code>+8 小时</code>）即可秒级全局对齐。</li>
        </ul>
        <h3>Q4：某些 HTTPS 直播源无法连接，电脑却能正常打开？</h3>
        <ul>
          <li>请排查电视所在宽带是否存在 DNS 劫持，或该源仅提供 IPv4 / 仅提供 IPv6 异常响应：尝试在「设置 → 通用」切换 <strong>「阿里 DoH」</strong> 或 <strong>「腾讯 DoH」</strong>，并在「网络协议优先级」核对 IPv6 / IPv4 偏好。</li>
        </ul>
      `
    }
  ],
  en: [
    {
      id: "getting-started",
      icon: "🚀",
      title: "Getting Started",
      html: `
        <h1>Getting Started with IQTV Lite</h1>
        <p class="lead">IQTV Lite is a lightweight, fast-zapping <strong>Live TV player for Android TV and set-top boxes</strong> (Android 7.0+ / API 24+, built for low-RAM TV hardware as well as modern 4K HDR sets).</p>
        <div class="callout warn">
          <strong>Player Only:</strong> IQTV Lite includes no built-in TV channels, playlists, EPG feeds, or subscriptions. You bring your own legal sources.
        </div>
        <h2>Requirements</h2>
        <ul>
          <li><strong>OS:</strong> Android TV / Google TV / AOSP TV box running <strong>Android 7.0 (API 24)</strong> or newer.</li>
          <li><strong>ABIs:</strong> Universal APK bundling both <code>armeabi-v7a</code> and <code>arm64-v8a</code> (~<strong>8.7 MB</strong> signed release APK).</li>
          <li><strong>Input:</strong> D-Pad remote first, with full short/long-press remapping, numeric keypad direct tuning, and hardware Channel +/- keys.</li>
        </ul>
        <h2>Installation & Updates</h2>
        <ol>
          <li>Download <code>IQTV-Lite-v0.3.2.apk</code> (or <code>IQTV-Lite-latest.apk</code>) from <a href="https://github.com/zqs1qiwan/iqtvlite/releases/latest" target="_blank" rel="noopener">GitHub Releases</a>.</li>
          <li>Sideload via USB drive or run <code>adb install -r IQTV-Lite-latest.apk</code>.</li>
          <li>Open <strong>Settings → General → Phone QR Setup &amp; Backup</strong> to push M3U/TXT/EPG sources straight from your phone over home Wi-Fi.</li>
          <li>Later releases can be checked and installed directly inside <strong>Settings → About &amp; Updates → Check for Updates</strong>.</li>
        </ol>
      `
    },
    {
      id: "ui-remote",
      icon: "📺",
      title: "UI Tour & Remote Controls",
      html: `
        <h1>UI Tour &amp; Remote Shortcuts</h1>
        <p class="lead">Designed for 10-foot living room viewing with an obsidian dark theme and centered D-pad focus. Click any screenshot below to zoom.</p>
        <div class="shot-grid">
          <figure class="shot-card">
            <img src="assets/screenshots/player-infocard.png" alt="Player Info Card & Quick Actions" loading="lazy" />
            <figcaption>Two-step Info Banner · Recent Strip + 8 Quick Actions (including Audio Only)</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/channel-drawer.png" alt="Channel List & EPG Timeline" loading="lazy" />
            <figcaption>Sidebar Browser · Channel List &amp; SQLite EPG Schedule</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/drawer-groups-sources.png" alt="Groups & Multi-Source Columns" loading="lazy" />
            <figcaption>Groups &amp; Multi-Source Switcher (press Left repeatedly)</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/settings.png" alt="Settings Root" loading="lazy" />
            <figcaption>7-Category Settings Center with Live Root Summaries</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/settings-playback.png" alt="Playback Settings" loading="lazy" />
            <figcaption>Playback Settings · Per-Channel Decoder, AFR, Boost &amp; Delay</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/stream-diag.png" alt="Live Stream Diagnostics" loading="lazy" />
            <figcaption>Live Stream Diagnostics · Codec, FPS, AFR, DoH &amp; RTP Multicast Stats</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/webui-qr.png" alt="Phone QR WebUI Setup" loading="lazy" />
            <figcaption>Zero-PIN Home LAN WebUI QR Screen</figcaption>
          </figure>
          <figure class="shot-card">
            <img src="assets/screenshots/about-updates.png" alt="About & Updates" loading="lazy" />
            <figcaption>About &amp; In-App Updater</figcaption>
          </figure>
        </div>
        <h2>Default Remote Keys</h2>
        <table>
          <thead><tr><th>Key</th><th>Default Action</th><th>Notes</th></tr></thead>
          <tbody>
            <tr><td><code>↑</code> / <code>↓</code></td><td>Previous / Next Channel</td><td>Tunes immediately and peeks the bottom info bar (can be switched to open drawer)</td></tr>
            <tr><td><code>OK</code> / <code>Enter</code></td><td>Two-step Info Console</td><td>1st press shows channel &amp; Now/Next EPG; 2nd press (or <code>↓</code>) focuses Recent &amp; Quick Controls</td></tr>
            <tr><td><code>←</code></td><td>Open Browser Columns</td><td>1st press: Channels + EPG · 2nd press: Groups · 3rd press: Sources</td></tr>
            <tr><td><code>→</code></td><td>Previous Channel / Swap PiP</td><td>Quick-returns to last watched channel, or swaps screens during Multi-view</td></tr>
            <tr><td><code>0–9</code></td><td>Numeric Tuning</td><td>Instant channel number match overlay in the top-right corner</td></tr>
            <tr><td><code>Back</code></td><td>Close / Double-Back Exit</td><td>Dismisses overlays step by step; press twice during playback to exit</td></tr>
          </tbody>
        </table>
      `
    },
    {
      id: "sources-multicast",
      icon: "📡",
      title: "Playlists & Multicast",
      html: `
        <h1>Playlists, HTTP Headers &amp; Native Multicast</h1>
        <h2>1. Multi-Variant Aggregation vs. Separate Channels</h2>
        <p>Multiple URLs stacked directly beneath one <code>#EXTINF</code> entry are grouped into a single channel with <code>Src 1/N</code> failover. Separate <code>#EXTINF</code> entries always stay distinct (so 4K and HD feeds never merge).</p>
<pre><code>#EXTINF:-1 tvg-id="cctv1" group-title="National",CCTV-1
https://cdn1.example.com/cctv1.m3u8|User-Agent=VLC
https://cdn2.example.com/cctv1.m3u8|Referer=https://example.com/

#EXTINF:-1 tvg-id="btv4k" group-title="4K",Beijing TV 4K HDR
rtp://239.3.1.240:8000</code></pre>
        <h2>2. HTTP Header Priority</h2>
        <ol>
          <li><strong>Per-URL <code>|</code> suffixes (highest)</strong>: <code>url|User-Agent=VLC&amp;Referer=https://example.com/</code></li>
          <li><strong>Inline playlist directives</strong>: <code>#EXTINF</code> / <code>#EXTVLCOPT</code> / <code>#KODIPROP</code></li>
          <li><strong>Per-source UA or Global Headers</strong> configured in Settings / WebUI</li>
          <li><strong>Default fallback</strong>: <code>VLC/3.0.20 LibVLC/3.0.20</code></li>
        </ol>
        <h2>3. Direct <code>rtp://</code> / <code>udp://</code> Multicast Engine</h2>
        <ul>
          <li>Plays raw IPTV LAN multicast streams directly without needing a separate udpxy proxy (while still supporting HTTP udpxy URLs).</li>
          <li>Dedicated non-blocking packet reader thread + <strong>4 MB lock-free ring buffer</strong> + automatic Wi-Fi multicast locks minimize jitter on high-bitrate 4K feeds.</li>
        </ul>
      `
    },
    {
      id: "playback-audio",
      icon: "🎛️",
      title: "Decoders, AFR & Audio DSP",
      html: `
        <h1>Decoder Ladder, AFR &amp; Audio Tuning</h1>
        <h2>1. Hardware-First Ladder &amp; Per-Channel Override</h2>
        <ul>
          <li>Hardware <code>MediaCodec</code> is prioritized by default (including firmware quirks handling on Android 9 boxes for 4K HEVC).</li>
          <li>If hardware decode stalls, an 8-second watchdog escalates to decoder rebuild and then software/FFmpeg fallback, automatically resetting to hardware on the next zap.</li>
          <li>Use <strong>Settings → Playback → Channel Decoder</strong> to pin a specific problematic channel to <em>Force Hardware</em> or <em>Force Software</em> permanently.</li>
        </ul>
        <h2>2. Auto Frame Rate (AFR)</h2>
        <p>When enabled in <strong>Settings → Playback → Auto Frame Rate (AFR)</strong>, IQTV Lite waits <strong>1,200 ms</strong> for stream FPS stabilization and selects the lowest-jitter exact 1x or integer-multiple (2x..5x, e.g., <code>25fps → 50Hz</code>) display mode without ever downscaling resolution.</p>
        <h2>3. Per-Route Audio Delay (±500ms) &amp; 150% Boost</h2>
        <ul>
          <li>Powered by zero-dependency Media3 <code>AudioSyncBoostProcessor</code>.</li>
          <li>Stores separate A/V sync delays (<code>-500ms .. +500ms</code>) per output route: <strong>Speaker</strong>, <strong>Bluetooth Audio</strong>, and <strong>HDMI / ARC</strong>.</li>
          <li>Soft-clipped linear PCM boost up to <strong>150%</strong>; completely bypassed (<code>0% CPU overhead</code>) when at <code>0ms / 100%</code>.</li>
        </ul>
        <h2>4. Audio-Only Mode &amp; Dual-Screen Multi-view</h2>
        <p>Toggle <strong>Audio Only</strong> in the info-card action bar to disable video decoding and listen like radio, or launch <strong>Multi-view</strong> PiP to watch two live channels side-by-side.</p>
      `
    },
    {
      id: "network-epg",
      icon: "🌐",
      title: "DNS (DoH), IP Stack & EPG",
      html: `
        <h1>Dual-Stack Networking, Encrypted DoH &amp; EPG Offset</h1>
        <h2>1. IPv6 / IPv4 Stack Preference</h2>
        <p>Defaults to <strong>IPv6 Preferred</strong> with automatic fallback to IPv4 (configurable to IPv4-first in General Settings or WebUI).</p>
        <h2>2. Zero-Dependency RFC 8484 Encrypted DNS (DoH)</h2>
        <table>
          <thead><tr><th>Mode</th><th>Endpoint</th><th>Recommended For</th></tr></thead>
          <tbody>
            <tr><td><strong>System DNS (Default)</strong></td><td>OS resolver</td><td>LAN multicast, standard home broadband, lowest overhead</td></tr>
            <tr><td><strong>AliDNS DoH</strong></td><td><code>https://dns.alidns.com/dns-query</code></td><td>Bypassing ISP DNS poisoning inside China</td></tr>
            <tr><td><strong>Tencent DNSPod DoH</strong></td><td><code>https://doh.pub/dns-query</code></td><td>High-availability multi-ISP DoH inside China</td></tr>
            <tr><td><strong>Cloudflare DoH</strong></td><td><code>https://cloudflare-dns.com/dns-query</code></td><td>Global / overseas streams</td></tr>
          </tbody>
        </table>
        <p>Includes a 256-entry TTL cache, automatic bypass for IP literals, <code>localhost</code>, and <code>.local/.lan/.internal</code> hosts, plus automatic fallback to System DNS on timeout.</p>
        <h2>3. SQLite EPG &amp; ±12h Timezone Offset</h2>
        <p>EPG XMLTV schedules are indexed into SQLite (<code>epg_index_v2.db</code>) off the main thread. Adjust <strong>EPG Timezone Offset</strong> (<code>-12h .. +12h</code>) on TV or WebUI to shift program times instantaneously at query time.</p>
      `
    },
    {
      id: "webui-diag",
      icon: "🩺",
      title: "LAN WebUI & Diagnostics",
      html: `
        <h1>Zero-PIN LAN WebUI &amp; Stream Diagnostics</h1>
        <h2>1. Phone Web Console</h2>
        <p>Designed for fast home LAN setup—scan the QR code under <strong>Settings → General → Phone QR Setup &amp; Backup</strong> to manage playlists, visibility toggles, custom headers, IP stack, DoH provider, EPG offset, and full JSON settings backup/restore.</p>
        <h2>2. Live Stream Diagnostics &amp; <code>/api/diag</code> Export</h2>
        <ul>
          <li><strong>On TV:</strong> Open <strong>Settings → Playback → Live Stream Diagnostics</strong> for 1-second live telemetry (codec, resolution, FPS, decoder name/HW status, AFR mode, audio route/boost/delay, DoH hits/fallbacks, and RTP packet loss %).</li>
          <li><strong>Over LAN:</strong> Click <strong>Export Playback Diagnostics</strong> in the WebUI Remote &amp; System tab (or fetch <code>GET /api/diag</code>) to download a complete JSON bundle without needing ADB.</li>
        </ul>
      `
    },
    {
      id: "faq",
      icon: "❓",
      title: "Troubleshooting (FAQ)",
      html: `
        <h1>Troubleshooting</h1>
        <h3>4K multicast stream stutters or shows packet loss</h3>
        <p>Inspect <strong>Live Stream Diagnostics</strong> on TV or export <code>/api/diag</code>. Verify hardware decode is active and check <code>rtp.lossPercent</code>. Prefer wired Ethernet or IGMP Snooping on Wi-Fi routers for 25+ Mbps 4K multicast streams.</p>
        <h3>One specific channel always freezes before video appears</h3>
        <p>While watching that channel, set <strong>Settings → Playback → Channel Decoder</strong> to <em>Force Software</em> so future zaps skip hardware retry timeouts.</p>
        <h3>EPG programs are shifted by fixed hours</h3>
        <p>Set <strong>EPG Timezone Offset</strong> under <strong>Settings → EPG</strong> or the Phone WebUI to match your local timezone offset.</p>
      `
    }
  ]
};
