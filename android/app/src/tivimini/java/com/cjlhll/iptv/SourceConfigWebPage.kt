package com.cjlhll.iptv

import android.content.Context
import tv.iqtvlite.app.R

object SourceConfigWebPage {
    fun html(context: Context): String {
        val appName = runCatching { context.getString(R.string.app_name) }.getOrElse { "IQTV Lite" }
        return """
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover" />
  <title>$appName · 远程控制台</title>
  <style>
    :root {
      --bg: #0A0E14;
      --bg2: #0F151F;
      --card: #131B26;
      --card2: #182230;
      --border: rgba(255,255,255,0.08);
      --borderStrong: rgba(255,255,255,0.16);
      --text: #F5F8FC;
      --sub: #B7C3D6;
      --muted: #7F8FA6;
      --brand: #2F6BFF;
      --brandSoft: #5C98FF;
      --brandGlow: rgba(47,107,255,0.22);
      --ok: #38D399;
      --warn: #F5B942;
      --danger: #F0626C;
      --input: #0D131C;
    }
    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
    body {
      margin: 0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Noto Sans CJK SC", "Microsoft YaHei", sans-serif;
      background: radial-gradient(circle at 18% 0%, rgba(47,107,255,0.14), transparent 46%), var(--bg);
      color: var(--text);
      min-height: 100vh;
      padding-bottom: 68px;
    }
    .topbar {
      position: sticky;
      top: 0;
      z-index: 20;
      backdrop-filter: blur(14px);
      -webkit-backdrop-filter: blur(14px);
      background: rgba(10,14,20,0.86);
      border-bottom: 1px solid var(--border);
    }
    .topbar-inner {
      max-width: 780px;
      margin: 0 auto;
      padding: 14px 16px 10px;
    }
    .brand-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 12px;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .logo-mark {
      width: 36px;
      height: 36px;
      border-radius: 10px;
      background: linear-gradient(135deg, #2F6BFF 0%, #5C98FF 100%);
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 6px 18px rgba(47,107,255,0.35);
      flex-shrink: 0;
    }
    .brand-title {
      font-size: 17px;
      font-weight: 700;
      letter-spacing: 0.3px;
      line-height: 1.15;
    }
    .brand-sub {
      font-size: 12px;
      color: var(--muted);
      margin-top: 2px;
    }
    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 5px 10px;
      border-radius: 999px;
      background: rgba(56,211,153,0.12);
      border: 1px solid rgba(56,211,153,0.28);
      color: var(--ok);
      font-size: 12px;
      font-weight: 600;
      white-space: nowrap;
    }
    .status-dot {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      background: var(--ok);
      box-shadow: 0 0 8px var(--ok);
    }
    .tabs {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 6px;
      background: rgba(255,255,255,0.04);
      padding: 4px;
      border-radius: 12px;
      border: 1px solid var(--border);
    }
    .tab-btn {
      border: 0;
      background: transparent;
      color: var(--sub);
      font-size: 13px;
      font-weight: 600;
      padding: 9px 4px;
      border-radius: 9px;
      cursor: pointer;
      transition: all .16s ease;
    }
    .tab-btn.active {
      background: var(--brand);
      color: #fff;
      box-shadow: 0 4px 14px rgba(47,107,255,0.35);
    }
    .wrap {
      max-width: 780px;
      margin: 0 auto;
      padding: 16px;
    }
    .pane { display: none; }
    .pane.active { display: block; }
    .card {
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 16px;
      padding: 16px;
      margin-bottom: 14px;
    }
    .card-hd {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      margin-bottom: 12px;
    }
    .card-title {
      font-size: 15px;
      font-weight: 700;
      margin: 0;
    }
    .card-hint {
      font-size: 12px;
      color: var(--muted);
      margin: 4px 0 0;
      line-height: 1.5;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 999px;
      font-size: 11px;
      font-weight: 600;
    }
    .badge-blue { background: rgba(47,107,255,0.2); color: var(--brandSoft); }
    .badge-muted { background: rgba(255,255,255,0.08); color: var(--muted); }
    .badge-ok { background: rgba(56,211,153,0.16); color: var(--ok); }
    .src-list {
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .src-item {
      background: var(--card2);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 12px;
    }
    .src-item.is-active {
      border-color: rgba(92,152,255,0.55);
      background: linear-gradient(135deg, rgba(47,107,255,0.12), var(--card2) 60%);
    }
    .src-top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .src-name {
      font-size: 15px;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }
    .src-url {
      font-size: 12px;
      color: var(--sub);
      word-break: break-all;
      margin-top: 6px;
      line-height: 1.4;
    }
    .src-meta {
      font-size: 11.5px;
      color: var(--muted);
      margin-top: 4px;
    }
    .src-actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin-top: 10px;
      padding-top: 10px;
      border-top: 1px solid var(--border);
    }
    .field { margin-top: 12px; }
    .field:first-child { margin-top: 0; }
    label {
      display: block;
      font-size: 12.5px;
      color: var(--sub);
      margin-bottom: 6px;
      font-weight: 500;
    }
    input[type="text"], input[type="url"], textarea, select {
      width: 100%;
      background: var(--input);
      border: 1px solid var(--borderStrong);
      color: var(--text);
      border-radius: 10px;
      padding: 11px 12px;
      font-size: 14px;
      outline: none;
      font-family: inherit;
    }
    textarea {
      min-height: 92px;
      resize: vertical;
      line-height: 1.5;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 13px;
    }
    input:focus, textarea:focus, select:focus {
      border-color: var(--brandSoft);
      box-shadow: 0 0 0 3px var(--brandGlow);
    }
    .chips {
      display: flex;
      flex-wrap: wrap;
      gap: 7px;
      margin-top: 8px;
    }
    .chip {
      border: 1px solid var(--borderStrong);
      background: rgba(255,255,255,0.04);
      color: var(--sub);
      border-radius: 999px;
      padding: 6px 11px;
      font-size: 12px;
      cursor: pointer;
    }
    .chip.selected, .chip:hover {
      border-color: var(--brandSoft);
      color: #fff;
      background: rgba(47,107,255,0.22);
    }
    .btn-row {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      margin-top: 14px;
    }
    .btn {
      flex: 1;
      min-width: 120px;
      border: 0;
      border-radius: 11px;
      padding: 11px 14px;
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
      color: #fff;
      background: var(--brand);
    }
    .btn:active { transform: scale(0.99); }
    .btn-secondary {
      background: rgba(255,255,255,0.08);
      color: var(--text);
      border: 1px solid var(--borderStrong);
    }
    .btn-sm {
      flex: 0 0 auto;
      min-width: 0;
      padding: 7px 11px;
      font-size: 12.5px;
      border-radius: 8px;
    }
    .btn-danger {
      background: rgba(240,98,108,0.16);
      color: var(--danger);
      border: 1px solid rgba(240,98,108,0.32);
    }
    .seg-grid {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 8px;
    }
    .seg-grid-2 {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 8px;
    }
    .seg-card {
      background: var(--card2);
      border: 1px solid var(--border);
      border-radius: 11px;
      padding: 11px 10px;
      text-align: center;
      cursor: pointer;
      color: var(--sub);
    }
    .seg-card.selected {
      border-color: var(--brandSoft);
      background: rgba(47,107,255,0.2);
      color: #fff;
    }
    .seg-card strong { display: block; font-size: 14px; }
    .seg-card span { display: block; font-size: 11.5px; color: var(--muted); margin-top: 3px; }
    .seg-card.selected span { color: #CBE0FF; }
    .toggle-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 0;
      border-bottom: 1px solid var(--border);
    }
    .toggle-row:last-child { border-bottom: 0; padding-bottom: 0; }
    .switch {
      position: relative;
      display: inline-block;
      width: 44px;
      height: 24px;
      flex-shrink: 0;
    }
    .switch input { opacity: 0; width: 0; height: 0; }
    .slider {
      position: absolute;
      inset: 0;
      background: rgba(255,255,255,0.18);
      border-radius: 24px;
      cursor: pointer;
      transition: .18s;
    }
    .slider:before {
      content: "";
      position: absolute;
      width: 18px;
      height: 18px;
      left: 3px;
      top: 3px;
      background: #fff;
      border-radius: 50%;
      transition: .18s;
    }
    .switch input:checked + .slider { background: var(--brand); }
    .switch input:checked + .slider:before { transform: translateX(20px); }
    .prio-box {
      background: rgba(47,107,255,0.08);
      border: 1px solid rgba(92,152,255,0.22);
      border-radius: 12px;
      padding: 12px;
      font-size: 12.5px;
      line-height: 1.65;
      color: var(--sub);
    }
    .prio-box code {
      background: rgba(255,255,255,0.08);
      color: #D7E7FF;
      padding: 1px 5px;
      border-radius: 5px;
      font-size: 11.5px;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 13px;
    }
    th, td {
      text-align: left;
      padding: 9px 6px;
      border-bottom: 1px solid var(--border);
    }
    th { color: var(--muted); font-weight: 600; font-size: 12px; }
    .kv-row {
      display: flex;
      justify-content: space-between;
      padding: 8px 0;
      border-bottom: 1px solid var(--border);
      font-size: 13px;
      gap: 10px;
    }
    .kv-row:last-child { border-bottom: 0; }
    .kv-k { color: var(--muted); }
    .kv-v { color: var(--text); text-align: right; word-break: break-all; }
    .toast {
      position: fixed;
      left: 50%;
      bottom: 18px;
      transform: translateX(-50%) translateY(20px);
      opacity: 0;
      pointer-events: none;
      background: #1B2738;
      border: 1px solid rgba(92,152,255,0.45);
      color: #fff;
      padding: 10px 16px;
      border-radius: 999px;
      font-size: 13.5px;
      font-weight: 600;
      box-shadow: 0 10px 28px rgba(0,0,0,0.45);
      transition: all .2s ease;
      z-index: 99;
      max-width: 92vw;
      text-align: center;
    }
    .toast.show {
      opacity: 1;
      transform: translateX(-50%) translateY(0);
    }
    .toast.err {
      border-color: rgba(240,98,108,0.6);
      color: #FFD5D8;
    }
  </style>
</head>
<body>
  <header class="topbar">
    <div class="topbar-inner">
      <div class="brand-row">
        <div class="brand">
          <div class="logo-mark" aria-hidden="true">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
              <rect x="2.5" y="4.5" width="19" height="13" rx="3" stroke="#fff" stroke-width="2"/>
              <path d="M10 9L15 11L10 13V9Z" fill="#fff"/>
              <path d="M8 20.5H16" stroke="#fff" stroke-width="2" stroke-linecap="round"/>
            </svg>
          </div>
          <div>
            <div class="brand-title" id="brandName">$appName</div>
            <div class="brand-sub">TV 远程配置中心 · 多源与兼容管理</div>
          </div>
        </div>
        <div class="status-pill"><span class="status-dot"></span><span id="connText">已连接</span></div>
      </div>
      <nav class="tabs" role="tablist">
        <button class="tab-btn active" data-tab="sources">播放源</button>
        <button class="tab-btn" data-tab="headers">请求头</button>
        <button class="tab-btn" data-tab="display">显示播放</button>
        <button class="tab-btn" data-tab="remote">遥控系统</button>
      </nav>
    </div>
  </header>

  <main class="wrap">
    <!-- TAB 1: 播放源管理 -->
    <section id="pane-sources" class="pane active">
      <div class="card">
        <div class="card-hd">
          <div>
            <h2 class="card-title">已配置播放源</h2>
            <p class="card-hint">可管理多个源并控制是否在电视选台列表显示（对齐 TiviMate 多源体验）。</p>
          </div>
          <span class="badge badge-blue" id="srcCountBadge">0 个源</span>
        </div>
        <div id="srcList" class="src-list"></div>
      </div>

      <div class="card">
        <div class="card-hd">
          <div>
            <h2 class="card-title" id="formTitle">新增播放源</h2>
            <p class="card-hint">支持 M3U / TXT 列表，自动解析 <code>#EXTM3U x-tvg-url</code>、<code>#EXTINF</code> 与 URL <code>|</code> 请求头。</p>
          </div>
          <button class="btn btn-secondary btn-sm" id="btnResetForm" style="display:none">取消编辑</button>
        </div>
        <input type="hidden" id="srcId" />
        <div class="field">
          <label for="srcName">播放源名称</label>
          <input id="srcName" type="text" placeholder="例如：老白直播源 / 家庭内网源" />
        </div>
        <div class="field">
          <label for="srcUrl">直播源地址 (URL)</label>
          <input id="srcUrl" type="url" placeholder="https://example.com/live.m3u" />
          <div class="chips">
            <button type="button" class="chip" data-preset-name="老白直播源" data-preset-url="https://iptv.laobaitv.net" data-preset-epg="https://epg.laobaitv.net">填入：老白直播源</button>
            <button type="button" class="chip" data-preset-name="老白专享源" data-preset-url="http://lbzs.laobaitv.net" data-preset-epg="https://epg.laobaitv.net">填入：老白专享源 (lbzs)</button>
          </div>
        </div>
        <div class="field">
          <label for="srcEpg">EPG 节目单地址（可选，留空自动识别列表内 x-tvg-url）</label>
          <input id="srcEpg" type="url" placeholder="https://epg.laobaitv.net" />
        </div>
        <div class="field">
          <label for="srcUa">该源专属 User-Agent（可选，留空跟随全局设置）</label>
          <input id="srcUa" type="text" placeholder="例如：VLC/3.0.20 LibVLC/3.0.20 或 aliplayer" />
        </div>
        <div class="btn-row">
          <button class="btn btn-secondary" id="btnSaveSource">保存播放源</button>
          <button class="btn" id="btnSaveAndPlay">保存并切换播放</button>
        </div>
      </div>

      <div class="card">
        <div class="card-hd">
          <div>
            <h2 class="card-title">手动同步数据</h2>
            <p class="card-hint">立即通知电视后台重新拉取当前播放列表或节目单。</p>
          </div>
        </div>
        <div class="btn-row" style="margin-top:0">
          <button class="btn btn-secondary" id="btnRefreshPlaylist">刷新当前播放列表</button>
          <button class="btn btn-secondary" id="btnRefreshEpg">立即更新 EPG</button>
        </div>
      </div>
    </section>

    <!-- TAB 2: 请求头与兼容 -->
    <section id="pane-headers" class="pane">
      <div class="card">
        <div class="card-hd">
          <div>
            <h2 class="card-title">全局 User-Agent</h2>
            <p class="card-hint">当频道 URL 或 #EXTINF 未指定 User-Agent 时使用。</p>
          </div>
        </div>
        <div class="field">
          <label for="globalUa">User-Agent 字符串（留空表示自动默认 VLC）</label>
          <input id="globalUa" type="text" placeholder="自动（默认 VLC/3.0.20 LibVLC/3.0.20）" />
          <div class="chips" id="uaChips">
            <button type="button" class="chip" data-ua="">自动 (默认 VLC)</button>
            <button type="button" class="chip" data-ua="VLC/3.0.20 LibVLC/3.0.20">VLC 3.0</button>
            <button type="button" class="chip" data-ua="aliplayer">aliplayer</button>
            <button type="button" class="chip" data-ua="AptvPlayer/1.4.2">APTV</button>
            <button type="button" class="chip" data-ua="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36">Chrome</button>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-hd">
          <div>
            <h2 class="card-title">全局自定义 HTTP Header</h2>
            <p class="card-hint">每行一条，格式 <code>Header-Name: Value</code>，支持 Referer、Origin、Cookie、Accept-Language 等。</p>
          </div>
        </div>
        <div class="field">
          <label for="globalHeaders">自定义请求头列表</label>
          <textarea id="globalHeaders" placeholder="Referer: https://example.com/&#10;Origin: https://example.com&#10;Accept-Language: zh-CN,zh;q=0.9"></textarea>
          <div class="chips">
            <button type="button" class="chip" data-add-hdr="Referer: https://api.chinaaudiovisual.cn/">+ 示例 Referer</button>
            <button type="button" class="chip" data-add-hdr="Accept-Language: zh-CN,zh;q=0.9">+ Accept-Language</button>
            <button type="button" class="chip" id="btnClearHeaders">清空全部</button>
          </div>
        </div>
        <div class="btn-row">
          <button class="btn" id="btnSaveHeaders">保存请求头配置</button>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" style="margin-bottom:10px">自动识别与优先级规则</h2>
        <div class="prio-box">
          <strong>匹配优先级（由高到低）：</strong><br/>
          1. 播放地址 <code>|</code> 后参数（如 <code>|User-Agent=aliplayer&amp;Referer=...</code>）<strong>最高优先</strong><br/>
          2. <code>#EXTINF</code> / <code>#EXTVLCOPT</code> / <code>#KODIPROP</code> 属性（如 <code>http-user-agent="VLC"</code>、<code>http-referer="..."</code>）<br/>
          3. 本页配置的「全局 User-Agent」与「全局自定义 Header」<br/>
          4. 内置默认兼容头（<code>VLC/3.0.20 LibVLC/3.0.20</code>）<br/>
          已针对 <code>lbzs.laobaitv.net</code> 等常见多头源做完整自动解析与冲突优先处理。
        </div>
      </div>
    </section>

    <!-- TAB 3: 显示与播放 -->
    <section id="pane-display" class="pane">
      <div class="card">
        <h2 class="card-title" style="margin-bottom:10px">文字与台标大小</h2>
        <div class="seg-grid" id="uiScaleSeg">
          <div class="seg-card" data-scale="small"><strong>小</strong><span>紧凑视野</span></div>
          <div class="seg-card" data-scale="medium"><strong>中</strong><span>默认均衡</span></div>
          <div class="seg-card" data-scale="large"><strong>大</strong><span>远距醒目</span></div>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" style="margin-bottom:10px">信息卡 OK 键行为</h2>
        <div class="seg-grid-2" id="infoOkSeg">
          <div class="seg-card" data-ok="two_step"><strong>两段式（推荐）</strong><span>先出信息卡，再按 OK 快切</span></div>
          <div class="seg-card" data-ok="direct"><strong>直达最近观看</strong><span>按一次 OK 直接聚焦最近台</span></div>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" style="margin-bottom:10px">起播缓冲与自动更新</h2>
        <div class="seg-grid" id="bufferSeg" style="margin-bottom:14px">
          <div class="seg-card" data-buf="150"><strong>快</strong><span>150ms 极速</span></div>
          <div class="seg-card" data-buf="300"><strong>均衡</strong><span>300ms 平衡</span></div>
          <div class="seg-card" data-buf="500"><strong>稳</strong><span>500ms 抗抖</span></div>
        </div>
        <div class="field">
          <label for="autoPlaylist">自动更新播放列表</label>
          <select id="autoPlaylist">
            <option value="OFF">关闭</option>
            <option value="H6">每 6 小时</option>
            <option value="H12">每 12 小时</option>
            <option value="H24">每 24 小时</option>
            <option value="D3">每 3 天</option>
            <option value="D7">每 7 天</option>
          </select>
        </div>
        <div class="field">
          <label for="autoEpg">自动更新 EPG</label>
          <select id="autoEpg">
            <option value="OFF">关闭</option>
            <option value="H6">每 6 小时</option>
            <option value="H12">每 12 小时</option>
            <option value="H24">每 24 小时</option>
            <option value="D3">每 3 天</option>
            <option value="D7">每 7 天</option>
          </select>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" style="margin-bottom:6px">台标渲染优化</h2>
        <div class="toggle-row">
          <div>
            <div style="font-size:14px;font-weight:600">台标预取</div>
            <div class="card-hint" style="margin:2px 0 0">关闭后仅加载当前可视行，更省低配盒子内存</div>
          </div>
          <label class="switch"><input type="checkbox" id="logoPrefetch"/><span class="slider"></span></label>
        </div>
        <div class="toggle-row">
          <div>
            <div style="font-size:14px;font-weight:600">显示台标图片</div>
            <div class="card-hint" style="margin:2px 0 0">关闭后仅显示文字首字占位</div>
          </div>
          <label class="switch"><input type="checkbox" id="logoLoad"/><span class="slider"></span></label>
        </div>
        <div class="btn-row">
          <button class="btn" id="btnSaveDisplay">应用显示与播放设置</button>
        </div>
      </div>
    </section>

    <!-- TAB 4: 遥控与系统 -->
    <section id="pane-remote" class="pane">
      <div class="card">
        <div class="card-hd">
          <div>
            <h2 class="card-title">遥控器快捷习惯</h2>
            <p class="card-hint">精细的单功能短按/长按按键录制可在电视端「设置 → 遥控器与操作」完成。</p>
          </div>
        </div>
        <div class="field">
          <label for="remUpDown">全屏上下键</label>
          <select id="remUpDown">
            <option value="CHANNEL_STEP">上下键直接换台</option>
            <option value="OPEN_DRAWER">上下键呼出频道列表</option>
          </select>
        </div>
        <div class="field">
          <label for="remOk">全屏 OK 键</label>
          <select id="remOk">
            <option value="CONFIRM">打开底部确认信息卡</option>
            <option value="OPEN_DRAWER">直接打开频道列表</option>
          </select>
        </div>
        <div class="field">
          <label for="remDigits">数字键换台</label>
          <select id="remDigits">
            <option value="DIRECT">停顿后自动跳转</option>
            <option value="CONFIRM_OK">按 OK 确认后跳转</option>
          </select>
        </div>
        <div class="toggle-row" style="margin-top:8px">
          <div>
            <div style="font-size:14px;font-weight:600">启用频道 +/- 实体键 (166/167)</div>
          </div>
          <label class="switch"><input type="checkbox" id="remChKeys"/><span class="slider"></span></label>
        </div>
        <div class="btn-row">
          <button class="btn" id="btnSaveRemote">保存遥控设置</button>
          <button class="btn btn-secondary" id="btnResetRemote">恢复默认</button>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" style="margin-bottom:8px">当前功能按键映射</h2>
        <table>
          <thead><tr><th>功能</th><th>短按</th><th>长按</th></tr></thead>
          <tbody id="bindingsBody"></tbody>
        </table>
      </div>

      <div class="card">
        <h2 class="card-title" style="margin-bottom:8px">电视设备信息</h2>
        <div id="sysInfo"></div>
      </div>
    </section>
  </main>

  <div id="toast" class="toast"></div>

  <script>
    function byId(id) { return document.getElementById(id); }
    var state = null;
    var selectedUiScale = "medium";
    var selectedInfoOk = "two_step";
    var selectedBuffer = 500;
    var toastTimer = null;

    function toast(msg, isErr) {
      var el = byId("toast");
      el.textContent = msg;
      el.className = "toast show" + (isErr ? " err" : "");
      if (toastTimer) clearTimeout(toastTimer);
      toastTimer = setTimeout(function() { el.className = "toast"; }, 2600);
    }

    async function api(path, body) {
      var opt = body ? {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
      } : { method: "GET" };
      var res = await fetch(path, opt);
      var data = await res.json().catch(function() { return {}; });
      if (!res.ok || data.ok === false) {
        throw new Error(data.error || ("HTTP " + res.status));
      }
      return data;
    }

    function esc(s) {
      return String(s || "").replace(/[&<>"]/g, function(c) {
        return { "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" }[c];
      });
    }

    // Tabs
    document.querySelectorAll(".tab-btn").forEach(function(btn) {
      btn.addEventListener("click", function() {
        var tab = btn.getAttribute("data-tab");
        document.querySelectorAll(".tab-btn").forEach(function(b) {
          b.classList.toggle("active", b === btn);
        });
        document.querySelectorAll(".pane").forEach(function(p) {
          p.classList.toggle("active", p.id === "pane-" + tab);
        });
      });
    });

    function renderAll(st) {
      state = st;
      if (st.appName) byId("brandName").textContent = st.appName;
      byId("connText").textContent = "已连接 · v" + (st.versionCode || "");

      renderSources();
      renderHeaders();
      renderDisplay();
      renderRemote();
    }

    function renderSources() {
      var list = (state && state.sources) || [];
      var activeId = state ? state.activeSourceId : "";
      byId("srcCountBadge").textContent = list.length + " 个源";
      var box = byId("srcList");
      if (!list.length) {
        box.innerHTML = '<div class="card-hint">暂无播放源，请在下方新增。</div>';
        return;
      }
      box.innerHTML = list.map(function(s) {
        var isAct = s.id === activeId;
        var visBadge = s.enabled
          ? '<span class="badge badge-ok">选台展示</span>'
          : '<span class="badge badge-muted">已隐藏</span>';
        var actBadge = isAct ? '<span class="badge badge-blue">● 当前播放</span>' : '';
        var epgLine = s.epgUrl ? ('EPG: ' + esc(s.epgUrl)) : 'EPG: 自动识别 / 跟随默认';
        var uaLine = s.userAgent ? (' · UA: ' + esc(s.userAgent)) : '';
        return '<div class="src-item' + (isAct ? ' is-active' : '') + '">'
          + '<div class="src-top">'
          +   '<div class="src-name">' + esc(s.name) + ' ' + actBadge + ' ' + visBadge + '</div>'
          +   '<label class="switch" title="是否在电视选台列表显示"><input type="checkbox" class="src-vis-toggle" data-id="' + esc(s.id) + '"' + (s.enabled ? ' checked' : '') + '/><span class="slider"></span></label>'
          + '</div>'
          + '<div class="src-url">' + esc(s.url) + '</div>'
          + '<div class="src-meta">' + epgLine + uaLine + '</div>'
          + '<div class="src-actions">'
          +   '<button class="btn btn-sm src-play-btn" data-id="' + esc(s.id) + '">' + (isAct ? '重新加载播放' : '切换并播放') + '</button>'
          +   '<button class="btn btn-secondary btn-sm src-edit-btn" data-id="' + esc(s.id) + '">编辑</button>'
          +   (list.length > 1 ? '<button class="btn btn-danger btn-sm src-del-btn" data-id="' + esc(s.id) + '">删除</button>' : '')
          + '</div>'
          + '</div>';
      }).join("");

      box.querySelectorAll(".src-vis-toggle").forEach(function(el) {
        el.addEventListener("change", async function() {
          var id = el.getAttribute("data-id");
          var next = state.sources.map(function(s) {
            return s.id === id ? Object.assign({}, s, { enabled: el.checked }) : s;
          });
          if (!next.some(function(s) { return s.enabled; })) {
            el.checked = true;
            toast("至少保留 1 个在选台列表展示的源", true);
            return;
          }
          try {
            var res = await api("/api/sources/save", { sources: next, activeSourceId: state.activeSourceId });
            renderAll(res.state);
            toast(el.checked ? "已在选台列表展示" : "已在选台列表隐藏");
          } catch (e) { toast(e.message, true); }
        });
      });

      box.querySelectorAll(".src-play-btn").forEach(function(el) {
        el.addEventListener("click", async function() {
          var id = el.getAttribute("data-id");
          try {
            toast("正在切换电视播放源…");
            var res = await api("/api/sources/save", { sources: state.sources, activeSourceId: id, playNow: true });
            renderAll(res.state);
            toast("已推送到电视并开始播放");
          } catch (e) { toast(e.message, true); }
        });
      });

      box.querySelectorAll(".src-edit-btn").forEach(function(el) {
        el.addEventListener("click", function() {
          var id = el.getAttribute("data-id");
          var s = state.sources.find(function(x) { return x.id === id; });
          if (!s) return;
          byId("srcId").value = s.id;
          byId("srcName").value = s.name;
          byId("srcUrl").value = s.url;
          byId("srcEpg").value = s.epgUrl || "";
          byId("srcUa").value = s.userAgent || "";
          byId("formTitle").textContent = "编辑播放源：" + s.name;
          byId("btnResetForm").style.display = "inline-block";
          byId("srcName").scrollIntoView({ behavior: "smooth", block: "center" });
        });
      });

      box.querySelectorAll(".src-del-btn").forEach(function(el) {
        el.addEventListener("click", async function() {
          var id = el.getAttribute("data-id");
          try {
            var res = await api("/api/sources/delete", { id: id });
            renderAll(res.state);
            toast("已删除播放源");
          } catch (e) { toast(e.message, true); }
        });
      });
    }

    function resetSourceForm() {
      byId("srcId").value = "";
      byId("srcName").value = "";
      byId("srcUrl").value = "";
      byId("srcEpg").value = "";
      byId("srcUa").value = "";
      byId("formTitle").textContent = "新增播放源";
      byId("btnResetForm").style.display = "none";
    }
    byId("btnResetForm").addEventListener("click", resetSourceForm);

    document.querySelectorAll("[data-preset-url]").forEach(function(btn) {
      btn.addEventListener("click", function() {
        byId("srcName").value = btn.getAttribute("data-preset-name");
        byId("srcUrl").value = btn.getAttribute("data-preset-url");
        byId("srcEpg").value = btn.getAttribute("data-preset-epg");
      });
    });

    async function submitSource(playNow) {
      var url = byId("srcUrl").value.trim();
      if (!url) { toast("请输入直播源地址", true); return; }
      var src = {
        id: byId("srcId").value.trim(),
        name: byId("srcName").value.trim(),
        url: url,
        epgUrl: byId("srcEpg").value.trim(),
        userAgent: byId("srcUa").value.trim(),
        enabled: true
      };
      try {
        var res = await api("/api/sources/save", { source: src, activate: playNow, playNow: playNow });
        renderAll(res.state);
        resetSourceForm();
        toast(playNow ? "已保存并在电视端切换播放" : "播放源已保存");
      } catch (e) { toast(e.message, true); }
    }
    byId("btnSaveSource").addEventListener("click", function() { submitSource(false); });
    byId("btnSaveAndPlay").addEventListener("click", function() { submitSource(true); });

    byId("btnRefreshPlaylist").addEventListener("click", async function() {
      try { await api("/api/refresh", { playlist: true, epg: false }); toast("已通知电视刷新播放列表"); }
      catch (e) { toast(e.message, true); }
    });
    byId("btnRefreshEpg").addEventListener("click", async function() {
      try { await api("/api/refresh", { playlist: false, epg: true }); toast("已通知电视更新 EPG"); }
      catch (e) { toast(e.message, true); }
    });

    // Tab 2: Headers
    function renderHeaders() {
      var ua = (state && state.globalUserAgent) || "";
      byId("globalUa").value = ua;
      byId("globalHeaders").value = (state && state.globalCustomHeaders) || "";
      document.querySelectorAll("#uaChips .chip").forEach(function(c) {
        c.classList.toggle("selected", (c.getAttribute("data-ua") || "") === ua);
      });
    }
    document.querySelectorAll("#uaChips .chip").forEach(function(c) {
      c.addEventListener("click", function() {
        var val = c.getAttribute("data-ua") || "";
        byId("globalUa").value = val;
        document.querySelectorAll("#uaChips .chip").forEach(function(x) { x.classList.toggle("selected", x === c); });
      });
    });
    document.querySelectorAll("[data-add-hdr]").forEach(function(c) {
      c.addEventListener("click", function() {
        var line = c.getAttribute("data-add-hdr");
        var ta = byId("globalHeaders");
        ta.value = ta.value.trim() ? (ta.value.trim() + "\n" + line) : line;
      });
    });
    byId("btnClearHeaders").addEventListener("click", function() { byId("globalHeaders").value = ""; });
    byId("btnSaveHeaders").addEventListener("click", async function() {
      try {
        var res = await api("/api/headers", {
          globalUserAgent: byId("globalUa").value.trim(),
          globalCustomHeaders: byId("globalHeaders").value
        });
        renderAll(res.state);
        toast("全局请求头已保存，切台立即生效");
      } catch (e) { toast(e.message, true); }
    });

    // Tab 3: Display
    function renderDisplay() {
      selectedUiScale = (state && state.uiScale) || "medium";
      selectedInfoOk = (state && state.infoOkMode) || "two_step";
      selectedBuffer = (state && state.playbackBufferMin) || 500;
      document.querySelectorAll("#uiScaleSeg .seg-card").forEach(function(c) {
        c.classList.toggle("selected", c.getAttribute("data-scale") === selectedUiScale);
      });
      document.querySelectorAll("#infoOkSeg .seg-card").forEach(function(c) {
        c.classList.toggle("selected", c.getAttribute("data-ok") === selectedInfoOk);
      });
      document.querySelectorAll("#bufferSeg .seg-card").forEach(function(c) {
        c.classList.toggle("selected", Number(c.getAttribute("data-buf")) === selectedBuffer);
      });
      byId("autoPlaylist").value = (state && state.autoUpdatePlaylist) || "H24";
      byId("autoEpg").value = (state && state.autoUpdateEpg) || "H24";
      byId("logoPrefetch").checked = state ? !!state.logoPrefetch : true;
      byId("logoLoad").checked = state ? !!state.logoLoad : true;
    }
    document.querySelectorAll("#uiScaleSeg .seg-card").forEach(function(c) {
      c.addEventListener("click", function() {
        selectedUiScale = c.getAttribute("data-scale");
        document.querySelectorAll("#uiScaleSeg .seg-card").forEach(function(x) { x.classList.toggle("selected", x === c); });
      });
    });
    document.querySelectorAll("#infoOkSeg .seg-card").forEach(function(c) {
      c.addEventListener("click", function() {
        selectedInfoOk = c.getAttribute("data-ok");
        document.querySelectorAll("#infoOkSeg .seg-card").forEach(function(x) { x.classList.toggle("selected", x === c); });
      });
    });
    document.querySelectorAll("#bufferSeg .seg-card").forEach(function(c) {
      c.addEventListener("click", function() {
        selectedBuffer = Number(c.getAttribute("data-buf"));
        document.querySelectorAll("#bufferSeg .seg-card").forEach(function(x) { x.classList.toggle("selected", x === c); });
      });
    });
    byId("btnSaveDisplay").addEventListener("click", async function() {
      try {
        var res = await api("/api/display", {
          uiScale: selectedUiScale,
          infoOkMode: selectedInfoOk,
          playbackBufferMin: selectedBuffer,
          autoUpdatePlaylist: byId("autoPlaylist").value,
          autoUpdateEpg: byId("autoEpg").value,
          logoPrefetch: byId("logoPrefetch").checked,
          logoLoad: byId("logoLoad").checked
        });
        renderAll(res.state);
        toast("显示与播放设置已同步至电视");
      } catch (e) { toast(e.message, true); }
    });

    // Tab 4: Remote & System
    function renderRemote() {
      byId("remUpDown").value = (state && state.remoteUpDown) || "CHANNEL_STEP";
      byId("remOk").value = (state && state.remoteOk) || "CONFIRM";
      byId("remDigits").value = (state && state.remoteDigits) || "DIRECT";
      byId("remChKeys").checked = state ? !!state.remoteChannelKeys : true;

      var rows = (state && state.remoteBindings) || [];
      byId("bindingsBody").innerHTML = rows.map(function(b) {
        return "<tr><td>" + esc(b.label) + "</td><td>" + esc(b.shortKey) + "</td><td>" + esc(b.longKey) + "</td></tr>";
      }).join("");

      byId("sysInfo").innerHTML = [
        ["应用版本", (state.versionName || "") + " (" + (state.versionCode || "") + ")"],
        ["应用包名", state.packageName || ""],
        ["电视型号", state.deviceModel || "Android TV"],
        ["Android API", "API " + (state.androidSdk || "")]
      ].map(function(kv) {
        return '<div class="kv-row"><span class="kv-k">' + esc(kv[0]) + '</span><span class="kv-v">' + esc(kv[1]) + '</span></div>';
      }).join("");
    }
    byId("btnSaveRemote").addEventListener("click", async function() {
      try {
        var res = await api("/api/remote", {
          upDown: byId("remUpDown").value,
          ok: byId("remOk").value,
          digits: byId("remDigits").value,
          channelKeys: byId("remChKeys").checked
        });
        renderAll(res.state);
        toast("遥控设置已同步至电视");
      } catch (e) { toast(e.message, true); }
    });
    byId("btnResetRemote").addEventListener("click", async function() {
      try {
        var res = await api("/api/remote", { reset: true });
        renderAll(res.state);
        toast("已恢复默认遥控映射");
      } catch (e) { toast(e.message, true); }
    });

    // Initial load
    api("/api/state").then(renderAll).catch(function(e) {
      byId("connText").textContent = "连接异常";
      toast("加载电视配置失败：" + e.message, true);
    });
  </script>
</body>
</html>
""".trimIndent()
    }
}
