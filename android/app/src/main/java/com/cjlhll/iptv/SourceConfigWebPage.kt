package com.cjlhll.iptv

import android.content.Context
import tv.iqtvlite.app.R

object SourceConfigWebPage {
    fun html(context: Context): String {
        val appName = runCatching { context.getString(R.string.app_name) }.getOrElse { "IQTV Lite" }
        val initialLang = Prefs.getAppLanguage(context).code
        return """
<!doctype html>
<html lang="${if (initialLang == "en") "en" else "zh-CN"}">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover" />
  <title>$appName</title>
  <style>
    :root {
      color-scheme: dark;
      --bg: #0A0E14;
      --bg2: #0F151F;
      --card: #131B26;
      --card2: #1A2433;
      --border: rgba(255,255,255,0.16);
      --borderStrong: rgba(255,255,255,0.28);
      --text: #F5F8FC;
      --sub: #D0DAEA;
      --muted: #A2B1C6;
      --brand: #2F6BFF;
      --brandSoft: #7EB2FF;
      --brandGlow: rgba(47,107,255,0.26);
      --ok: #38D399;
      --warn: #F5B942;
      --danger: #FF7882;
      --input: #0D141E;
    }
    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
    html, body {
      overflow-x: hidden;
      touch-action: manipulation;
      -webkit-text-size-adjust: 100%;
    }
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
      background: rgba(10,14,20,0.90);
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
      gap: 10px;
      margin-bottom: 12px;
      flex-wrap: wrap;
    }
    .brand {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 4px 12px;
      min-width: 0;
    }
    .brand-wordmark {
      height: 32px;
      width: auto;
      display: block;
      flex-shrink: 0;
    }
    .brand-sub {
      font-size: 12px;
      color: var(--muted);
      line-height: 1.3;
      white-space: nowrap;
    }
    .top-actions {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-shrink: 0;
    }
    .lang-pill {
      display: inline-flex;
      background: rgba(255,255,255,0.08);
      border: 1px solid var(--borderStrong);
      border-radius: 999px;
      padding: 2px;
      gap: 2px;
    }
    .lang-btn {
      border: 0;
      background: transparent;
      color: var(--sub);
      font-size: 11.5px;
      font-weight: 600;
      padding: 4px 9px;
      border-radius: 999px;
      cursor: pointer;
    }
    .lang-btn.active {
      background: var(--brand);
      color: #FFFFFF;
    }
    .status-pill {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 5px 10px;
      border-radius: 999px;
      background: rgba(56,211,153,0.14);
      border: 1px solid rgba(56,211,153,0.34);
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
      background: rgba(255,255,255,0.06);
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
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
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
      flex-wrap: wrap;
      gap: 10px;
      margin-bottom: 12px;
    }
    .card-title {
      font-size: 15px;
      font-weight: 700;
      color: var(--text);
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
      white-space: nowrap;
      flex-shrink: 0;
    }
    .badge-blue { background: rgba(47,107,255,0.24); color: #9FC5FF; }
    .badge-muted { background: rgba(255,255,255,0.12); color: #CBD6E6; }
    .badge-ok { background: rgba(56,211,153,0.18); color: #5AE4B0; }
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
      border-color: rgba(126,178,255,0.65);
      background: linear-gradient(135deg, rgba(47,107,255,0.16), var(--card2) 60%);
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
      color: var(--text);
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
      font-size: 16px;
      outline: none;
      font-family: inherit;
    }
    select {
      background-color: #131B26;
      color: #F5F8FC;
      cursor: pointer;
    }
    select option, select optgroup {
      background-color: #131B26;
      color: #F5F8FC;
    }
    select option:checked {
      background-color: #2F6BFF;
      color: #FFFFFF;
    }
    input::placeholder, textarea::placeholder {
      color: #8A9BB3;
      opacity: 1;
    }
    textarea {
      min-height: 92px;
      resize: vertical;
      line-height: 1.5;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 16px;
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
      background: rgba(255,255,255,0.08);
      color: #DCE6F5;
      border-radius: 999px;
      padding: 6px 11px;
      font-size: 12px;
      font-weight: 500;
      cursor: pointer;
    }
    .chip.selected, .chip:hover {
      border-color: var(--brandSoft);
      color: #fff;
      background: rgba(47,107,255,0.30);
    }
    .btn-row {
      display: flex;
      flex-wrap: wrap;
      align-items: stretch;
      gap: 10px;
      margin-top: 14px;
    }
    .btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      text-align: center;
      text-decoration: none;
      line-height: 1.25;
      margin-bottom: 0;
      flex: 1 1 140px;
      min-width: 120px;
      border: 0;
      border-radius: 11px;
      padding: 11px 14px;
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
      color: #fff;
      background: var(--brand);
      white-space: nowrap;
    }
    .btn:active { transform: scale(0.99); }
    .btn-secondary {
      background: rgba(255,255,255,0.13);
      color: #F5F8FC;
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
      background: rgba(255,120,130,0.18);
      color: #FF9BA2;
      border: 1px solid rgba(255,120,130,0.42);
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
      background: rgba(47,107,255,0.26);
      color: #fff;
    }
    .seg-card strong { display: block; font-size: 14px; color: var(--text); }
    .seg-card span { display: block; font-size: 11.5px; color: var(--muted); margin-top: 3px; }
    .seg-card.selected span { color: #D8E8FF; }
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
      background: rgba(255,255,255,0.22);
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
      background: rgba(47,107,255,0.12);
      border: 1px solid rgba(126,178,255,0.28);
      border-radius: 12px;
      padding: 12px;
      font-size: 12.5px;
      line-height: 1.65;
      color: var(--sub);
    }
    .prio-box code {
      background: rgba(255,255,255,0.12);
      color: #E2EEFF;
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
      color: var(--text);
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
      border: 1px solid rgba(126,178,255,0.55);
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
      border-color: rgba(255,120,130,0.7);
      color: #FFD5D8;
    }
    @media (max-width: 520px) {
      .topbar-inner { padding: 12px 12px 10px; }
      .wrap { padding: 12px; }
      .brand-wordmark { height: 28px; }
      .tab-btn { font-size: 12.5px; padding: 8px 2px; }
      .card { padding: 14px; border-radius: 14px; }
      .btn-row { gap: 8px; }
      .btn { flex: 1 1 100%; min-width: 0; }
      .btn.btn-sm { flex: 0 0 auto; }
      .seg-grid { gap: 6px; }
      .seg-card { padding: 10px 6px; }
    }
  </style>
</head>
<body>
  <header class="topbar">
    <div class="topbar-inner">
      <div class="brand-row">
        <div class="brand">
          <svg class="brand-wordmark" viewBox="175 272 978 274" aria-label="IQTV Lite" role="img">
            <path d="M0 0 C0.53 -0 0.53 -0 3.19 -0.02 C4.31 -0 5.44 0.01 6.61 0.02 C7.19 0.03 7.19 0.03 10.13 0.03 C12.59 0.03 15.05 0.05 17.51 0.07 C21.28 0.11 25.05 0.12 28.82 0.11 C31.21 0.12 33.59 0.14 35.98 0.15 C37.11 0.15 38.24 0.15 39.41 0.16 C40.46 0.17 41.51 0.18 42.59 0.2 C43.05 0.2 43.05 0.2 45.38 0.22 C47.73 0.49 47.73 0.49 50.73 2.49 C51.03 5.27 51.13 7.83 51.1 10.61 C51.11 11.46 51.11 12.31 51.11 13.18 C51.12 16.05 51.11 18.91 51.1 21.77 C51.1 23.82 51.11 25.87 51.11 27.91 C51.12 33.48 51.11 39.05 51.1 44.62 C51.09 50.44 51.09 56.26 51.09 62.08 C51.09 71.85 51.08 81.62 51.06 91.39 C51.04 102.71 51.04 114.02 51.05 125.33 C51.05 136.19 51.05 147.06 51.03 157.92 C51.03 162.55 51.03 167.18 51.03 171.81 C51.03 177.26 51.03 182.71 51.01 188.16 C51.01 190.17 51 192.18 51.01 194.18 C51.01 196.91 51 199.63 50.99 202.36 C50.99 202.76 50.99 202.76 51 204.79 C50.96 210.26 50.96 210.26 48.73 212.49 C46.42 212.75 46.42 212.75 43.5 212.78 C42.41 212.79 41.32 212.8 40.19 212.82 C39.01 212.82 37.83 212.82 36.61 212.82 C36 212.83 36 212.83 32.94 212.84 C30.37 212.85 27.81 212.86 25.24 212.86 C21.31 212.86 17.38 212.9 13.45 212.94 C10.96 212.94 8.47 212.94 5.98 212.95 C5.39 212.95 5.39 212.95 2.4 212.99 C1.31 212.98 0.21 212.98 -0.91 212.97 C-1.4 212.97 -1.4 212.97 -3.84 212.98 C-6.27 212.49 -6.27 212.49 -7.9 210.97 C-9.83 207.47 -9.69 204.18 -9.66 200.29 C-9.66 199.45 -9.67 198.62 -9.68 197.76 C-9.69 194.96 -9.68 192.17 -9.68 189.37 C-9.68 187.36 -9.69 185.36 -9.7 183.36 C-9.71 177.91 -9.71 172.46 -9.71 167.02 C-9.7 162.48 -9.71 157.93 -9.72 153.39 C-9.73 142.67 -9.73 131.96 -9.72 121.25 C-9.71 110.18 -9.72 99.12 -9.75 88.06 C-9.77 78.56 -9.78 69.07 -9.77 59.58 C-9.77 53.91 -9.77 48.24 -9.79 42.57 C-9.8 37.23 -9.8 31.9 -9.78 26.57 C-9.78 24.61 -9.78 22.65 -9.79 20.69 C-9.8 18.02 -9.79 15.35 -9.77 12.68 C-9.78 11.91 -9.79 11.13 -9.8 10.32 C-9.75 6.8 -9.55 4.85 -7.39 2 C-4.74 0.11 -3.24 -0.01 0 0 Z" transform="translate(198.271728515625,285.51416015625)" fill="#F5F8FC" fill-rule="evenodd"/>
            <path d="M0 0 C0.86 -0 1.72 -0.01 2.61 -0.01 C5.5 -0.02 8.4 -0.03 11.3 -0.03 C13.37 -0.04 15.44 -0.04 17.51 -0.05 C23.15 -0.07 28.79 -0.08 34.43 -0.09 C37.95 -0.09 41.47 -0.1 44.99 -0.1 C55.99 -0.12 67 -0.14 78.01 -0.15 C90.73 -0.15 103.44 -0.18 116.16 -0.22 C125.98 -0.25 135.81 -0.27 145.63 -0.27 C151.5 -0.27 157.37 -0.28 163.24 -0.31 C168.76 -0.33 174.28 -0.33 179.8 -0.32 C181.83 -0.32 183.86 -0.33 185.89 -0.34 C188.65 -0.36 191.42 -0.35 194.18 -0.34 C194.59 -0.35 194.59 -0.35 196.62 -0.37 C200.32 -0.33 202.54 -0 205.7 1.97 C208.37 4.76 209.84 7.79 211.35 11.28 C211.51 11.63 211.51 11.63 212.31 13.4 C213.33 15.69 214.34 17.98 215.35 20.27 C216.06 21.86 216.77 23.45 217.48 25.04 C218.91 28.24 220.34 31.43 221.76 34.63 C224.52 40.81 227.37 46.95 230.22 53.08 C234.06 61.38 237.86 69.69 241.58 78.04 C243.69 82.78 245.87 87.48 248.06 92.17 C251.6 99.72 255.06 107.3 258.5 114.88 C259.48 117.04 260.46 119.2 261.44 121.36 C262.68 124.09 263.92 126.83 265.16 129.56 C266.14 131.74 267.13 133.91 268.13 136.09 C270.78 141.93 270.78 141.93 270.78 144.15 C271.77 144.15 272.76 144.15 273.78 144.15 C277.89 136.55 281.93 128.94 285.78 121.21 C286.11 120.55 286.44 119.88 286.79 119.2 C288.44 115.87 290.08 112.55 291.71 109.21 C295.57 101.39 299.6 93.71 303.93 86.14 C307.02 80.7 309.82 75.13 312.6 69.52 C316.38 61.92 320.26 54.38 324.16 46.83 C324.53 46.12 324.89 45.41 325.27 44.67 C329.67 36.15 334.07 27.63 338.53 19.15 C338.72 18.8 338.72 18.8 339.65 17.02 C340.69 15.06 341.73 13.1 342.78 11.15 C343.2 10.35 343.62 9.55 344.05 8.73 C344.48 7.92 344.92 7.11 345.36 6.27 C345.75 5.53 346.14 4.79 346.55 4.03 C347.78 2.15 347.78 2.15 350.78 0.15 C353.42 -0.09 353.42 -0.09 356.63 -0.08 C357.23 -0.08 357.23 -0.08 360.27 -0.08 C361.57 -0.07 362.86 -0.06 364.2 -0.05 C365.54 -0.04 366.88 -0.04 368.22 -0.04 C371.75 -0.03 375.28 -0.01 378.8 0.01 C382.4 0.03 386 0.04 389.6 0.05 C396.66 0.07 403.72 0.11 410.78 0.15 C411.87 4.69 411.53 6.45 409.23 10.57 C408.94 11.1 408.94 11.1 407.45 13.81 C406.79 14.99 406.13 16.16 405.47 17.33 C404.79 18.57 404.11 19.8 403.44 21.03 C402.36 23 401.28 24.96 400.2 26.92 C396.29 34.01 392.52 41.17 388.75 48.34 C382.82 59.59 376.82 70.79 370.7 81.93 C367.6 87.58 364.68 93.28 361.84 99.06 C359.12 104.57 356.19 109.95 353.22 115.33 C348.4 124.1 343.74 132.94 339.16 141.83 C338.84 142.46 338.51 143.08 338.18 143.73 C336.91 146.19 335.64 148.66 334.37 151.12 C330.23 159.16 326.02 167.16 321.78 175.15 C321.18 176.28 320.58 177.41 319.97 178.57 C318.29 181.74 316.61 184.9 314.92 188.06 C314.25 189.31 313.59 190.56 312.92 191.82 C311.99 193.58 311.04 195.34 310.1 197.1 C309.56 198.12 309.02 199.15 308.46 200.2 C302.82 210.14 302.82 210.14 296.78 212.15 C292.94 212.57 289.12 212.59 285.25 212.58 C284.14 212.59 283.03 212.59 281.89 212.6 C279.55 212.6 277.21 212.6 274.87 212.59 C271.29 212.58 267.72 212.61 264.15 212.65 C261.87 212.65 259.59 212.65 257.31 212.64 C256.25 212.65 255.18 212.67 254.08 212.68 C248.01 212.62 244.37 212.27 239.78 208.15 C238.22 205.79 237 203.67 235.78 201.15 C235.41 200.4 235.04 199.65 234.65 198.88 C233.36 196.25 232.1 193.61 230.85 190.96 C230.37 189.96 229.9 188.97 229.41 187.94 C225.13 178.94 220.95 169.89 216.76 160.84 C212.99 152.7 209.13 144.6 205.22 136.52 C199.85 125.43 194.65 114.26 189.5 103.06 C186.57 96.67 183.58 90.31 180.58 83.96 C179.37 81.4 178.17 78.84 176.99 76.27 C176.69 75.63 176.39 74.99 176.08 74.33 C175.29 72.62 174.5 70.91 173.72 69.2 C170.82 63.14 167.79 57.15 164.78 51.15 C147.29 50.82 129.8 50.49 111.78 50.15 C111.8 54.95 111.81 59.75 111.83 64.7 C111.88 80.53 111.91 96.35 111.94 112.18 C111.95 121.79 111.97 131.39 112.01 140.99 C112.04 149.36 112.06 157.72 112.07 166.09 C112.07 170.52 112.08 174.95 112.1 179.38 C112.12 183.55 112.13 187.72 112.12 191.89 C112.12 193.42 112.13 194.95 112.14 196.49 C112.16 198.57 112.15 200.66 112.14 202.75 C112.15 203.92 112.15 205.09 112.15 206.29 C111.78 209.15 111.78 209.15 110.4 211.02 C108.15 212.59 106.54 212.53 103.81 212.54 C102.8 212.54 101.79 212.55 100.74 212.56 C100.2 212.56 100.2 212.56 97.44 212.55 C96.88 212.55 96.88 212.55 94.04 212.55 C91.67 212.56 89.3 212.55 86.93 212.54 C83.3 212.52 79.66 212.54 76.03 212.56 C73.73 212.56 71.43 212.55 69.13 212.55 C68.04 212.55 66.95 212.56 65.82 212.56 C65.32 212.56 65.32 212.56 62.76 212.54 C61.87 212.53 60.98 212.53 60.06 212.53 C57.19 212.05 56.5 211.48 54.78 209.15 C54.41 206.29 54.41 206.29 54.42 202.75 C54.42 202.1 54.42 201.45 54.41 200.77 C54.41 198.59 54.43 196.4 54.44 194.21 C54.44 192.64 54.44 191.08 54.44 189.51 C54.44 185.25 54.46 180.99 54.48 176.74 C54.5 172.29 54.5 167.84 54.5 163.39 C54.51 154.97 54.54 146.54 54.57 138.12 C54.6 128.52 54.62 118.93 54.63 109.34 C54.66 89.61 54.72 69.88 54.78 50.15 C53.99 50.16 53.19 50.16 52.37 50.17 C44.88 50.25 37.39 50.32 29.9 50.35 C26.05 50.38 22.2 50.4 18.35 50.45 C14.64 50.49 10.92 50.52 7.21 50.53 C5.79 50.53 4.37 50.55 2.95 50.57 C0.97 50.6 -1.02 50.6 -3 50.6 C-3.57 50.61 -3.57 50.61 -6.43 50.63 C-9.22 50.15 -9.22 50.15 -11.08 48.24 C-12.33 45.94 -12.59 44.6 -12.59 41.99 C-12.6 41.19 -12.6 40.38 -12.61 39.55 C-12.6 38.68 -12.59 37.82 -12.58 36.93 C-12.58 36.03 -12.58 35.14 -12.58 34.22 C-12.58 32.33 -12.57 30.44 -12.55 28.55 C-12.53 25.66 -12.53 22.77 -12.54 19.88 C-12.53 18.04 -12.53 16.2 -12.52 14.37 C-12.52 13.5 -12.52 12.64 -12.52 11.75 C-12.51 10.94 -12.5 10.13 -12.49 9.3 C-12.49 8.59 -12.48 7.89 -12.48 7.16 C-12.17 4.78 -11.47 3.18 -10.22 1.15 C-7.01 -0.46 -3.52 -0 0 0 Z" transform="translate(517.2172393798828,285.8528289794922)" fill="#F5F8FC" fill-rule="evenodd"/>
            <path d="M0 0 C0.67 0 1.33 0 2.02 0 C18.63 0.08 33.89 3.26 48.88 10.5 C49.81 10.9 50.74 11.31 51.7 11.73 C77.55 23.53 96.29 46.12 106.38 72.29 C119.03 108.11 113.11 145.87 98.49 180.13 C97.15 182.92 95.86 185.15 93.88 187.5 C81.06 185.13 65.61 165.64 58.17 155.62 C55.59 151.39 55.54 147.76 56.3 143 C57.55 138.89 59.01 134.98 60.8 131.07 C65.65 119.5 62.74 104.22 58.75 92.81 C56.07 86.3 52.3 80.94 47.88 75.5 C47.49 75 47.49 75 45.53 72.47 C42.77 69.38 39.91 67.12 36.44 64.94 C35.83 64.55 35.23 64.17 34.61 63.78 C19.46 54.51 1.46 52.09 -15.88 55.56 C-31.94 59.95 -45.17 69.15 -53.66 83.59 C-61.59 99.34 -64.49 117.13 -59 134.25 C-56.84 140.48 -54.02 146.17 -50.12 151.5 C-49.61 152.21 -49.1 152.91 -48.57 153.64 C-40.65 163.57 -29.49 168.75 -17.12 170.5 C0.92 171.77 17.44 167.51 34.19 161.05 C43.32 157.62 43.32 157.62 47.88 159.5 C55.35 165.2 61.82 172.29 68.44 178.93 C69.65 180.14 70.85 181.34 72.06 182.55 C74.57 185.06 77.08 187.57 79.59 190.09 C82.79 193.29 86 196.49 89.2 199.69 C91.69 202.17 94.17 204.66 96.65 207.14 C97.83 208.32 99.01 209.5 100.19 210.68 C105.81 216.29 111.39 221.87 116.52 227.93 C118.5 230.22 120.64 232.33 122.81 234.44 C124.18 235.77 125.55 237.12 126.88 238.5 C126.88 238.83 126.88 239.16 126.88 239.5 C117.76 240.04 108.64 240.44 99.51 240.69 C95.27 240.81 91.04 240.97 86.8 241.24 C57.34 243.03 57.34 243.03 48.45 235.72 C44.64 232.24 41.28 228.54 38.18 224.43 C35.87 221.44 33.85 218.84 30.88 216.5 C26.51 216.08 23 217.15 18.88 218.5 C12.19 219.57 5.68 219.72 -1.07 219.74 C-3.09 219.75 -5.11 219.78 -7.13 219.81 C-33.07 219.91 -58.14 211 -78.12 194.5 C-78.91 193.88 -79.7 193.25 -80.51 192.61 C-99.84 176.55 -112.28 150.14 -115.12 125.5 C-117 92.76 -108.74 62.69 -86.68 37.88 C-64.15 13.33 -33.37 -0.16 0 0 Z" transform="translate(392.125,290.5)" fill="#F5F8FC" fill-rule="evenodd"/>
            <path d="M0 0 C0.68 -0 1.36 -0.01 2.05 -0.01 C4.3 -0.02 6.56 -0.02 8.81 -0.02 C10.43 -0.03 12.05 -0.03 13.67 -0.04 C18.07 -0.05 22.46 -0.06 26.85 -0.06 C29.6 -0.06 32.35 -0.07 35.09 -0.07 C43.7 -0.08 52.3 -0.09 60.91 -0.1 C70.83 -0.1 80.74 -0.12 90.66 -0.15 C98.34 -0.17 106.02 -0.18 113.7 -0.18 C118.28 -0.18 122.86 -0.19 127.44 -0.21 C131.75 -0.22 136.05 -0.23 140.36 -0.22 C141.94 -0.22 143.52 -0.22 145.09 -0.23 C153.22 -0.28 160.33 -0.07 167.92 3.14 C167.92 3.8 167.92 4.46 167.92 5.14 C168.51 5.23 169.11 5.31 169.72 5.4 C172.53 6.35 173.34 7.56 175.04 9.95 C175.54 10.64 176.04 11.33 176.55 12.03 C179.56 16.68 180.21 19.68 180.23 25.33 C180.25 26.58 180.26 27.83 180.28 29.12 C180.1 32.9 179.06 36.09 177.78 39.63 C176.08 44.57 174.87 49.65 173.54 54.7 C171.22 63.46 168.75 72.12 165.96 80.74 C164.97 83.96 164.2 87.16 163.48 90.45 C162.02 96.62 159.19 101.76 155.92 107.14 C155.26 107.14 154.6 107.14 153.92 107.14 C153.92 107.8 153.92 108.46 153.92 109.14 C153.26 109.14 152.6 109.14 151.92 109.14 C151.92 109.8 151.92 110.46 151.92 111.14 C151.26 111.14 150.6 111.14 149.92 111.14 C149.92 111.8 149.92 112.46 149.92 113.14 C149.26 113.14 148.6 113.14 147.92 113.14 C147.92 113.8 147.92 114.46 147.92 115.14 C146.09 116.11 144.25 117.06 142.42 118.02 C141.4 118.55 140.37 119.08 139.32 119.63 C130.32 123.62 121.58 123.43 111.91 123.4 C110.29 123.4 108.67 123.41 107.05 123.41 C102.66 123.42 98.27 123.42 93.88 123.41 C89.28 123.4 84.68 123.41 80.07 123.41 C72.34 123.42 64.61 123.41 56.88 123.4 C47.96 123.39 39.04 123.39 30.12 123.4 C22.45 123.41 14.77 123.42 7.1 123.41 C2.52 123.41 -2.06 123.41 -6.63 123.41 C-10.94 123.42 -15.24 123.41 -19.55 123.4 C-21.12 123.4 -22.7 123.4 -24.27 123.41 C-35.28 123.43 -44.58 123.21 -54.08 117.14 C-54.08 116.48 -54.08 115.82 -54.08 115.14 C-54.74 115.14 -55.4 115.14 -56.08 115.14 C-56.9 113.68 -57.71 112.22 -58.52 110.77 C-58.97 109.95 -59.43 109.14 -59.89 108.3 C-63.49 101.77 -62.98 93.96 -61.29 86.79 C-59.96 82.76 -58.41 78.88 -56.68 75 C-54.55 70.06 -52.95 64.94 -51.27 59.83 C-50.56 57.68 -49.85 55.54 -49.15 53.4 C-48.8 52.34 -48.45 51.28 -48.09 50.2 C-46.66 45.86 -45.22 41.53 -43.77 37.2 C-43.32 35.84 -42.86 34.48 -42.41 33.11 C-42.19 32.47 -41.98 31.83 -41.76 31.17 C-41.27 29.7 -40.79 28.22 -40.32 26.75 C-40.06 25.95 -39.81 25.15 -39.54 24.32 C-39.31 23.6 -39.08 22.87 -38.84 22.12 C-38.08 20.14 -38.08 20.14 -36.08 17.14 C-35.42 17.14 -34.76 17.14 -34.08 17.14 C-33.84 16.36 -33.59 15.57 -33.33 14.77 C-31.93 11.82 -31.08 11.24 -28.08 10.14 C-28.08 9.48 -28.08 8.82 -28.08 8.14 C-18.77 2.63 -10.94 0.03 0 0 Z M43.92 25.14 C42.1 27.83 42.1 27.83 40.92 31.14 C41.6 34.45 41.6 34.45 42.92 37.14 C45.44 38.4 46.77 38.06 49.54 37.7 C50.36 37.6 51.18 37.5 52.02 37.39 C52.64 37.31 53.27 37.23 53.92 37.14 C55.24 34.42 55.94 32.56 55.79 29.52 C54.7 26.56 53.67 25.63 50.92 24.14 C46.92 24.14 46.92 24.14 43.92 25.14 Z M-6.08 28.14 C-6.49 30.05 -6.84 31.98 -7.17 33.91 C-7.38 35.11 -7.59 36.31 -7.8 37.55 C-8.02 38.82 -8.24 40.08 -8.46 41.39 C-9.88 49.71 -11.46 57.92 -13.4 66.13 C-14.38 70.46 -15.07 74.81 -15.71 79.2 C-15.95 80.82 -16.19 82.43 -16.43 84.05 C-16.54 84.74 -16.64 85.44 -16.74 86.15 C-17.08 88.14 -17.08 88.14 -17.69 90.33 C-17.75 90.63 -17.75 90.63 -18.08 92.14 C-17.08 93.14 -17.08 93.14 -13.3 93.25 C-11.61 93.25 -9.92 93.25 -8.24 93.24 C-7.35 93.24 -6.46 93.24 -5.55 93.23 C-2.71 93.23 0.14 93.22 2.98 93.2 C4.9 93.2 6.82 93.19 8.75 93.19 C13.47 93.18 18.19 93.16 22.92 93.14 C24.11 89.26 24.92 86.22 24.92 82.14 C22.25 80.81 20.23 81.03 17.25 81.04 C16.13 81.05 15.01 81.05 13.85 81.05 C13.27 81.06 13.27 81.06 10.29 81.08 C9.11 81.08 7.93 81.09 6.71 81.09 C3.78 81.1 0.85 81.12 -2.08 81.14 C-1.83 79.91 -1.58 78.67 -1.31 77.4 C0.57 68.16 2.34 58.93 3.83 49.62 C4.85 43.33 6.12 37.11 7.46 30.89 C7.61 29.98 7.76 29.07 7.92 28.14 C6.92 27.14 6.92 27.14 4.23 27.01 C3.14 27.01 2.04 27.01 0.92 27.02 C-0.18 27.01 -1.27 27.01 -2.4 27.01 C-5.08 27.14 -5.08 27.14 -6.08 28.14 Z M67.92 33.14 C67.21 35.67 67.21 35.67 66.67 38.64 C65.42 43.81 65.42 43.81 63.17 45.31 C61.23 45.83 61.23 45.83 57.92 46.14 C57.59 48.78 57.26 51.42 56.92 54.14 C58.9 54.8 60.88 55.46 62.92 56.14 C62.71 57.16 62.51 58.17 62.3 59.22 C57.5 83.48 57.5 83.48 60.92 90.14 C62.67 92.4 63.61 93.07 66.44 93.72 C67.38 93.8 68.32 93.87 69.29 93.95 C69.76 94 69.76 94 72.13 94.22 C75.15 94.13 77.19 93.42 79.92 92.14 C80.25 89.17 80.58 86.2 80.92 83.14 C78.28 82.81 75.64 82.48 72.92 82.14 C72.59 75.63 73.44 70.22 75.16 63.94 C75.86 61.35 76.42 58.77 76.92 56.14 C77.54 56.09 78.17 56.04 78.82 55.99 C79.63 55.92 80.45 55.84 81.29 55.77 C81.7 55.73 81.7 55.73 83.75 55.55 C85.92 55.14 85.92 55.14 87.92 53.14 C88.33 51.2 88.33 51.2 88.54 49.02 C88.67 47.74 88.79 46.46 88.92 45.14 C85.62 45.14 82.32 45.14 78.92 45.14 C79.08 44.48 79.25 43.82 79.42 43.14 C80.04 39.41 79.98 35.92 79.92 32.14 C72.04 30.39 72.04 30.39 67.92 33.14 Z M94.92 53.14 C89.23 61.01 87.11 68.44 87.92 78.14 C88.42 80.58 89.1 82.78 89.92 85.14 C90.58 85.14 91.24 85.14 91.92 85.14 C92.02 85.43 92.02 85.43 92.54 86.89 C95.06 91 98.85 92.72 103.23 94.45 C111.98 96.09 120.3 93.54 127.92 89.14 C129.79 86.95 129.79 86.95 130.92 85.14 C129.55 81.98 128.93 81.15 125.92 79.14 C122.59 79.46 121.31 79.75 118.92 82.14 C113.37 83.93 109.6 84.33 103.92 83.14 C103.92 82.48 103.92 81.82 103.92 81.14 C103.26 81.14 102.6 81.14 101.92 81.14 C101.59 78.83 101.26 76.52 100.92 74.14 C101.91 74.12 102.9 74.1 103.91 74.08 C107.58 74 111.24 73.92 114.91 73.82 C116.49 73.78 118.08 73.75 119.67 73.72 C121.95 73.67 124.22 73.61 126.5 73.55 C126.86 73.55 126.86 73.55 128.66 73.52 C133.69 73.37 133.69 73.37 135.92 71.14 C136.12 68.47 136.2 65.93 136.17 63.27 C136.17 62.54 136.18 61.82 136.19 61.08 C136.18 57.29 136.04 54.36 133.92 51.14 C133.26 51.14 132.6 51.14 131.92 51.14 C131.92 50.48 131.92 49.82 131.92 49.14 C131.26 49.14 130.6 49.14 129.92 49.14 C129.92 48.48 129.92 47.82 129.92 47.14 C129.26 47.14 128.6 47.14 127.92 47.14 C127.92 46.48 127.92 45.82 127.92 45.14 C116.08 39.88 103.02 43.19 94.92 53.14 Z M39.92 45.14 C37.7 50.67 37.7 50.67 37.48 54.2 C37.15 57.77 36.48 61.06 35.54 64.52 C34.09 70.04 33.11 75.57 32.23 81.2 C32.1 82.04 31.97 82.87 31.83 83.73 C31.34 86.92 30.92 89.91 30.92 93.14 C34.88 93.14 38.84 93.14 42.92 93.14 C45 85.39 46.54 77.72 47.78 69.8 C48.66 64.34 49.85 59.04 51.37 53.72 C51.92 51.14 51.92 51.14 51.92 45.14 C47.96 45.14 44 45.14 39.92 45.14 Z" transform="translate(960.0832214355469,360.85955810546875)" fill="#2F6BFF" fill-rule="evenodd"/>
            <path d="M0 0 C2.88 1.55 5.54 3.3 8.24 5.14 C10.62 6.74 12.98 8.15 15.5 9.5 C19.91 11.89 24.09 14.59 28.31 17.31 C30.67 18.8 33.05 20.17 35.5 21.5 C36.21 21.9 36.92 22.29 37.65 22.7 C39.42 23.68 41.21 24.62 43 25.55 C50.52 29.73 50.52 29.73 51.87 33.31 C52.01 35.83 51.83 37.62 51 40 C48.69 42.56 48.69 42.56 46 44 C45.01 44 44.02 44 43 44 C43 44.66 43 45.32 43 46 C42.36 46.34 41.71 46.68 41.05 47.03 C34.57 50.51 28.3 54.24 22.05 58.12 C18.88 60.08 15.67 61.93 12.39 63.7 C10.48 64.74 8.6 65.82 6.73 66.93 C5.57 67.6 4.41 68.27 3.25 68.94 C2.72 69.25 2.72 69.25 0.02 70.84 C-3 72 -3 72 -5.7 71.32 C-7.51 70.28 -9.27 69.16 -11 68 C-11.39 65.31 -11.39 65.31 -11.4 61.85 C-11.42 60.56 -11.43 59.27 -11.44 57.94 C-11.44 56.52 -11.44 55.11 -11.43 53.69 C-11.44 52.24 -11.44 50.8 -11.45 49.35 C-11.46 46.31 -11.46 43.28 -11.45 40.24 C-11.43 36.35 -11.45 32.46 -11.48 28.57 C-11.5 25.58 -11.5 22.59 -11.5 19.6 C-11.5 18.16 -11.5 16.73 -11.51 15.29 C-11.53 13.29 -11.52 11.28 -11.5 9.28 C-11.5 8.13 -11.5 6.99 -11.5 5.81 C-11 3 -11 3 -9.16 1.15 C-5.72 -0.68 -3.89 -0.62 0 0 Z" transform="translate(379,370)" fill="#2F6BFF" fill-rule="evenodd"/>
            <path d="M0 0 C3 1.62 3 1.62 5 4 C5.12 7.15 5.12 7.15 5 10 C-1.69 10.62 -8.28 11.13 -15 11 C-15.81 9.31 -15.81 9.31 -16 7 C-11.31 1.53 -7.31 -1.14 0 0 Z" transform="translate(1079,414)" fill="#2F6BFF" fill-rule="evenodd"/>
          </svg>
          <div class="brand-sub" id="brandSub">TV 远程配置中心</div>
        </div>
        <div class="top-actions">
          <div class="lang-pill" role="group" aria-label="Language">
            <button type="button" class="lang-btn" data-lang="zh">中文</button>
            <button type="button" class="lang-btn" data-lang="en">EN</button>
          </div>
          <div class="status-pill"><span class="status-dot"></span><span id="connText">已连接</span></div>
        </div>
      </div>
      <nav class="tabs" role="tablist">
        <button class="tab-btn active" data-tab="sources" id="tabSources">播放源</button>
        <button class="tab-btn" data-tab="headers" id="tabHeaders">请求头</button>
        <button class="tab-btn" data-tab="display" id="tabDisplay">显示播放</button>
        <button class="tab-btn" data-tab="remote" id="tabRemote">遥控系统</button>
      </nav>
    </div>
  </header>

  <main class="wrap">
    <!-- TAB 1: 播放源管理 -->
    <section id="pane-sources" class="pane active">
      <div class="card">
        <div class="card-hd">
          <div>
            <h2 class="card-title" id="secSourcesTitle">已配置播放源</h2>
            <p class="card-hint" id="secSourcesHint">支持多播放源切换，可独立控制是否在电视选台列表显示。</p>
          </div>
          <span class="badge badge-blue" id="srcCountBadge">0 个源</span>
        </div>
        <div id="srcList" class="src-list"></div>
      </div>

      <div class="card">
        <div class="card-hd">
          <div>
            <h2 class="card-title" id="formTitle">新增播放源</h2>
            <p class="card-hint" id="formHint">支持 M3U / TXT 列表，自动解析 x-tvg-url、#EXTINF 属性与 URL | 请求头。</p>
          </div>
          <button class="btn btn-secondary btn-sm" id="btnResetForm" style="display:none">取消编辑</button>
        </div>
        <input type="hidden" id="srcId" />
        <div class="field">
          <label for="srcName" id="lblSrcName">播放源名称</label>
          <input id="srcName" type="text" placeholder="例如：家庭宽带源 / Live TV" />
        </div>
        <div class="field">
          <label for="srcUrl" id="lblSrcUrl">直播源地址 (URL)</label>
          <input id="srcUrl" type="url" placeholder="支持 http(s):// M3U 或 TXT 播放列表地址" />
        </div>
        <div class="field">
          <label for="srcEpg" id="lblSrcEpg">EPG 节目单地址（可选，留空自动识别列表内 x-tvg-url）</label>
          <input id="srcEpg" type="url" placeholder="可选，留空自动识别 M3U 内 x-tvg-url" />
        </div>
        <div class="field">
          <label for="srcUa" id="lblSrcUa">该源专属 User-Agent（可选，留空跟随全局设置）</label>
          <input id="srcUa" type="text" placeholder="VLC/3.0.20 LibVLC/3.0.20 或 aliplayer" />
        </div>
        <div class="btn-row">
          <button class="btn btn-secondary" id="btnSaveSource">保存播放源</button>
          <button class="btn" id="btnSaveAndPlay">保存并切换播放</button>
        </div>
      </div>

      <div class="card">
        <div class="card-hd">
          <div>
            <h2 class="card-title" id="syncTitle">手动同步数据</h2>
            <p class="card-hint" id="syncHint">立即通知电视后台重新拉取当前播放列表或节目单。</p>
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
            <h2 class="card-title" id="hdrUaTitle">全局 User-Agent</h2>
            <p class="card-hint" id="hdrUaHint">当频道 URL 或 #EXTINF 未指定 User-Agent 时使用。</p>
          </div>
        </div>
        <div class="field">
          <label for="globalUa" id="lblGlobalUa">User-Agent 字符串（留空表示自动默认 VLC）</label>
          <input id="globalUa" type="text" placeholder="自动（默认 VLC/3.0.20 LibVLC/3.0.20）" />
          <div class="chips" id="uaChips">
            <button type="button" class="chip" id="chipUaAuto" data-ua="">自动 (默认 VLC)</button>
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
            <h2 class="card-title" id="hdrCustomTitle">全局自定义 HTTP Header</h2>
            <p class="card-hint" id="hdrCustomHint">每行一条，格式 Header-Name: Value，支持 Referer、Origin、Cookie 等。</p>
          </div>
        </div>
        <div class="field">
          <label for="globalHeaders" id="lblGlobalHeaders">自定义请求头列表</label>
          <textarea id="globalHeaders" placeholder="Referer: https://example.com/&#10;Origin: https://example.com&#10;Accept-Language: zh-CN,zh;q=0.9"></textarea>
          <div class="chips">
            <button type="button" class="chip" id="chipExRef" data-add-hdr="Referer: https://api.chinaaudiovisual.cn/">+ 示例 Referer</button>
            <button type="button" class="chip" id="chipExLang" data-add-hdr="Accept-Language: zh-CN,zh;q=0.9">+ Accept-Language</button>
            <button type="button" class="chip" id="btnClearHeaders">清空全部</button>
          </div>
        </div>
        <div class="btn-row">
          <button class="btn" id="btnSaveHeaders">保存请求头配置</button>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" id="prioTitle" style="margin-bottom:10px">自动识别与优先级规则</h2>
        <div class="prio-box" id="prioBody">
          <strong>匹配优先级（由高到低）：</strong><br/>
          1. 播放地址 <code>|</code> 后参数（如 <code>|User-Agent=VLC&amp;Referer=...</code>）<strong>最高优先</strong><br/>
          2. <code>#EXTINF</code> / <code>#EXTVLCOPT</code> / <code>#KODIPROP</code> 属性（如 <code>http-user-agent="VLC"</code>）<br/>
          3. 本页配置的「全局 User-Agent」与「全局自定义 Header」<br/>
          4. 内置默认兼容头（<code>VLC/3.0.20 LibVLC/3.0.20</code>）
        </div>
      </div>
    </section>

    <!-- TAB 3: 显示与播放 -->
    <section id="pane-display" class="pane">
      <div class="card">
        <h2 class="card-title" id="dispScaleTitle" style="margin-bottom:10px">文字与台标大小</h2>
        <div class="seg-grid" id="uiScaleSeg">
          <div class="seg-card" data-scale="small"><strong id="scaleSmT">小</strong><span id="scaleSmD">紧凑视野</span></div>
          <div class="seg-card" data-scale="medium"><strong id="scaleMdT">中</strong><span id="scaleMdD">默认均衡</span></div>
          <div class="seg-card" data-scale="large"><strong id="scaleLgT">大</strong><span id="scaleLgD">远距醒目</span></div>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" id="dispOkTitle" style="margin-bottom:10px">信息卡 OK 键行为</h2>
        <div class="seg-grid-2" id="infoOkSeg">
          <div class="seg-card" data-ok="two_step"><strong id="okTwoT">两段式（推荐）</strong><span id="okTwoD">先出信息卡，再按 OK 快切</span></div>
          <div class="seg-card" data-ok="direct"><strong id="okDirT">直达最近观看</strong><span id="okDirD">按一次 OK 直接聚焦最近台</span></div>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" id="dispIpTitle" style="margin-bottom:10px">网络协议优先级</h2>
        <div class="seg-grid-2" id="ipStackSeg">
          <div class="seg-card" data-ip="ipv6_first"><strong id="ipV6T">IPv6 优先（推荐）</strong><span id="ipV6D">默认优先 IPv6，不可用自动回退 IPv4</span></div>
          <div class="seg-card" data-ip="ipv4_first"><strong id="ipV4T">IPv4 优先</strong><span id="ipV4D">优先 IPv4，不可用自动回退 IPv6</span></div>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" id="dispDnsTitle" style="margin-bottom:10px">加密 DNS (DoH)</h2>
        <div class="seg-grid" id="dnsModeSeg">
          <div class="seg-card" data-dns="system"><strong id="dnsSysT">系统默认</strong><span id="dnsSysD">局域网最低延迟</span></div>
          <div class="seg-card" data-dns="ali"><strong id="dnsAliT">阿里 DoH</strong><span id="dnsAliD">223.5.5.5 防劫持</span></div>
          <div class="seg-card" data-dns="cloudflare"><strong id="dnsCfT">Cloudflare</strong><span id="dnsCfD">1.1.1.1 加密解析</span></div>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" id="dispBufTitle" style="margin-bottom:10px">起播缓冲与自动更新</h2>
        <div class="seg-grid" id="bufferSeg" style="margin-bottom:14px">
          <div class="seg-card" data-buf="300"><strong id="bufFastT">快</strong><span id="bufFastD">300ms 极速</span></div>
          <div class="seg-card" data-buf="400"><strong id="bufBalT">均衡</strong><span id="bufBalD">400ms 平衡</span></div>
          <div class="seg-card" data-buf="500"><strong id="bufStbT">稳</strong><span id="bufStbD">500ms 抗抖</span></div>
        </div>
        <div class="field">
          <label for="autoPlaylist" id="lblAutoPl">自动更新播放列表</label>
          <select id="autoPlaylist">
            <option value="OFF">关闭</option>
            <option value="STARTUP">启动时</option>
            <option value="H6">每 6 小时</option>
            <option value="H12">每 12 小时</option>
            <option value="H24">每 24 小时</option>
          </select>
        </div>
        <div class="field">
          <label for="autoEpg" id="lblAutoEpg">自动更新 EPG</label>
          <select id="autoEpg">
            <option value="OFF">关闭</option>
            <option value="STARTUP">启动时</option>
            <option value="H6">每 6 小时</option>
            <option value="H12">每 12 小时</option>
            <option value="H24">每 24 小时</option>
          </select>
        </div>
        <div class="field">
          <label for="epgOffsetMinutes" id="lblEpgOffset">EPG 节目单时区偏移</label>
          <select id="epgOffsetMinutes">
            <option value="-720">-12 小时</option>
            <option value="-480">-8 小时</option>
            <option value="-240">-4 小时</option>
            <option value="-120">-2 小时</option>
            <option value="-60">-1 小时</option>
            <option value="-30">-30 分钟</option>
            <option value="0">0（原始时间）</option>
            <option value="30">+30 分钟</option>
            <option value="60">+1 小时</option>
            <option value="120">+2 小时</option>
            <option value="240">+4 小时</option>
            <option value="480">+8 小时</option>
            <option value="720">+12 小时</option>
          </select>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" id="dispLogoTitle" style="margin-bottom:6px">台标渲染优化</h2>
        <div class="toggle-row">
          <div>
            <div style="font-size:14px;font-weight:600" id="lblLogoPrefetch">台标预取</div>
            <div class="card-hint" id="hintLogoPrefetch" style="margin:2px 0 0">关闭后仅加载当前可视行，更省低配盒子内存</div>
          </div>
          <label class="switch"><input type="checkbox" id="logoPrefetch"/><span class="slider"></span></label>
        </div>
        <div class="toggle-row">
          <div>
            <div style="font-size:14px;font-weight:600" id="lblLogoLoad">显示台标图片</div>
            <div class="card-hint" id="hintLogoLoad" style="margin:2px 0 0">关闭后仅显示文字首字占位</div>
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
            <h2 class="card-title" id="remTitle">遥控器快捷习惯</h2>
            <p class="card-hint" id="remHint">精细的单功能短按/长按按键录制可在电视端「设置 → 遥控器与操作」完成。</p>
          </div>
        </div>
        <div class="field">
          <label for="remUpDown" id="lblRemUpDown">全屏上下键</label>
          <select id="remUpDown">
            <option value="CHANNEL_STEP">上下键直接换台</option>
            <option value="OPEN_DRAWER">上下键呼出频道列表</option>
          </select>
        </div>
        <div class="field">
          <label for="remOk" id="lblRemOk">全屏 OK 键</label>
          <select id="remOk">
            <option value="CONFIRM">打开底部确认信息卡</option>
            <option value="OPEN_DRAWER">直接打开频道列表</option>
          </select>
        </div>
        <div class="field">
          <label for="remDigits" id="lblRemDigits">数字键换台</label>
          <select id="remDigits">
            <option value="DIRECT">停顿后自动跳转</option>
            <option value="NEEDS_CONFIRM">按 OK 确认后跳转</option>
          </select>
        </div>
        <div class="toggle-row" style="margin-top:8px">
          <div>
            <div style="font-size:14px;font-weight:600" id="lblRemChKeys">启用频道 +/- 实体键 (166/167)</div>
          </div>
          <label class="switch"><input type="checkbox" id="remChKeys"/><span class="slider"></span></label>
        </div>
        <div class="btn-row">
          <button class="btn" id="btnSaveRemote">保存遥控设置</button>
          <button class="btn btn-secondary" id="btnResetRemote">恢复默认</button>
        </div>
      </div>

      <div class="card">
        <h2 class="card-title" id="remMapTitle" style="margin-bottom:8px">当前功能按键映射</h2>
        <table>
          <thead><tr><th id="thFunc">功能</th><th id="thShort">短按</th><th id="thLong">长按</th></tr></thead>
          <tbody id="bindingsBody"></tbody>
        </table>
      </div>

      <div class="card">
        <h2 class="card-title" id="sysTitle" style="margin-bottom:8px">电视设备信息</h2>
        <div id="sysInfo"></div>
      </div>

      <div class="card">
        <h2 class="card-title" id="backupTitle" style="margin-bottom:8px">备份与恢复配置</h2>
        <div class="btn-row" style="margin-bottom:10px">
          <a class="btn" href="/api/backup" download="iqtvlite-backup.json" id="btnExportBackup">导出备份 JSON</a>
          <a class="btn btn-secondary" href="/api/diag" download="iqtvlite-diag.json" id="btnExportDiag">导出播放诊断日志</a>
          <label class="btn btn-secondary" style="cursor:pointer">
            <span id="btnImportFileLabel">选择备份文件恢复</span>
            <input type="file" id="backupFileInput" accept=".json,application/json" style="display:none"/>
          </label>
        </div>
        <textarea id="restoreJsonArea" rows="4" placeholder="也可直接粘贴备份 JSON 内容..." style="width:100%;margin-bottom:8px"></textarea>
        <button class="btn btn-secondary" id="btnRestoreJson" style="width:100%">从文本框恢复配置</button>
      </div>
    </section>
  </main>

  <div id="toast" class="toast"></div>

  <script>
    function byId(id) { return document.getElementById(id); }
    var state = null;
    var currentLang = "$initialLang";
    var selectedUiScale = "medium";
    var selectedInfoOk = "two_step";
    var selectedIpStack = "ipv6_first";
    var selectedDnsMode = "system";
    var selectedBuffer = 500;
    var editingSource = false;
    var editingSourceName = "";
    var toastTimer = null;

    var I18N = {
      zh: {
        brandSub: "TV 远程配置中心 · 多源与兼容管理",
        connected: "已连接 · v",
        connErr: "连接异常",
        tabSources: "播放源",
        tabHeaders: "请求头",
        tabDisplay: "显示播放",
        tabRemote: "遥控系统",
        secSourcesTitle: "已配置播放源",
        secSourcesHint: "支持多播放源切换，可独立控制是否在电视选台列表显示。",
        srcCount: function(n) { return n + " 个源"; },
        emptySources: "暂无播放源，请在下方新增。",
        badgeActive: "● 当前播放",
        badgeVisible: "选台展示",
        badgeHidden: "已隐藏",
        epgAuto: "EPG: 自动识别 / 跟随默认",
        btnReload: "重新加载播放",
        btnPlaySwitch: "切换并播放",
        btnEdit: "编辑",
        btnDelete: "删除",
        visTitle: "是否在电视选台列表显示",
        formAdd: "新增播放源",
        formEditPrefix: "编辑播放源：",
        formHint: "支持 M3U / TXT 列表，自动解析 x-tvg-url、#EXTINF 属性与 URL | 请求头。",
        btnCancelEdit: "取消编辑",
        lblSrcName: "播放源名称",
        phSrcName: "例如：家庭内网源 / 自定义播放源",
        lblSrcUrl: "直播源地址 (URL)",
        phSrcUrl: "支持 http(s):// M3U 或 TXT 播放列表地址",
        lblSrcEpg: "EPG 节目单地址（可选，留空自动识别 x-tvg-url）",
        phSrcEpg: "可选，留空自动识别 M3U 内 x-tvg-url",
        lblSrcUa: "该源专属 User-Agent（可选，留空跟随全局设置）",
        phSrcUa: "例如：VLC/3.0.20 LibVLC/3.0.20 或 aliplayer",
        btnSaveSource: "保存播放源",
        btnSaveAndPlay: "保存并切换播放",
        syncTitle: "手动同步数据",
        syncHint: "立即通知电视后台重新拉取当前播放列表或节目单。",
        btnRefreshPl: "刷新当前播放列表",
        btnRefreshEpg: "立即更新 EPG",
        hdrUaTitle: "全局 User-Agent",
        hdrUaHint: "当频道 URL 或 #EXTINF 未指定 User-Agent 时使用。",
        lblGlobalUa: "User-Agent 字符串（留空表示自动默认 VLC）",
        phGlobalUa: "自动（默认 VLC/3.0.20 LibVLC/3.0.20）",
        chipUaAuto: "自动 (默认 VLC)",
        hdrCustomTitle: "全局自定义 HTTP Header",
        hdrCustomHint: "每行一条，格式 Header-Name: Value，支持 Referer、Origin、Cookie 等。",
        lblGlobalHeaders: "自定义请求头列表",
        chipExRef: "+ 示例 Referer",
        btnClearHeaders: "清空全部",
        btnSaveHeaders: "保存请求头配置",
        prioTitle: "自动识别与优先级规则",
        prioHtml: "<strong>匹配优先级（由高到低）：</strong><br/>1. 播放地址 <code>|</code> 后参数（如 <code>|User-Agent=VLC&amp;Referer=...</code>）<strong>最高优先</strong><br/>2. <code>#EXTINF</code> / <code>#EXTVLCOPT</code> / <code>#KODIPROP</code> 属性（如 <code>http-user-agent=\"VLC\"</code>）<br/>3. 本页配置的「全局 User-Agent」与「全局自定义 Header」<br/>4. 内置默认兼容头（<code>VLC/3.0.20 LibVLC/3.0.20</code>）",
        dispScaleTitle: "文字与台标大小",
        scaleSmT: "小", scaleSmD: "紧凑视野",
        scaleMdT: "中", scaleMdD: "默认均衡",
        scaleLgT: "大", scaleLgD: "远距醒目",
        dispOkTitle: "信息卡 OK 键行为",
        okTwoT: "两段式（推荐）", okTwoD: "先出信息卡，再按 OK 快切",
        okDirT: "直达最近观看", okDirD: "按一次 OK 直接聚焦最近台",
        dispIpTitle: "网络协议优先级",
        ipV6T: "IPv6 优先（默认）", ipV6D: "优先 IPv6，失败自动回退 IPv4",
        ipV4T: "IPv4 优先", ipV4D: "优先 IPv4，失败自动回退 IPv6",
        dispDnsTitle: "加密 DNS (DoH)",
        dnsSysT: "系统默认", dnsSysD: "局域网最低延迟",
        dnsAliT: "阿里 DoH", dnsAliD: "223.5.5.5 防劫持",
        dnsCfT: "Cloudflare", dnsCfD: "1.1.1.1 加密解析",
        dispBufTitle: "起播缓冲与自动更新",
        bufFastT: "快", bufFastD: "300ms 极速",
        bufBalT: "均衡", bufBalD: "400ms 平衡",
        bufStbT: "稳", bufStbD: "500ms 抗抖",
        lblAutoPl: "自动更新播放列表",
        lblAutoEpg: "自动更新 EPG",
        lblEpgOffset: "EPG 节目单时区偏移",
        btnExportDiag: "导出播放诊断日志",
        intervals: { OFF: "关闭", STARTUP: "启动时", H6: "每 6 小时", H12: "每 12 小时", H24: "每 24 小时" },
        dispLogoTitle: "台标渲染优化",
        lblLogoPrefetch: "台标预取",
        hintLogoPrefetch: "关闭后仅加载当前可视行，更省低配盒子内存",
        lblLogoLoad: "显示台标图片",
        hintLogoLoad: "关闭后仅显示文字首字占位",
        btnSaveDisplay: "应用显示与播放设置",
        remTitle: "遥控器快捷习惯",
        remHint: "精细的单功能短按/长按按键录制可在电视端「设置 → 遥控器与操作」完成。",
        lblRemUpDown: "全屏上下键",
        optUpDownStep: "上下键直接换台",
        optUpDownDrawer: "上下键呼出频道列表",
        lblRemOk: "全屏 OK 键",
        optOkConfirm: "打开底部确认信息卡",
        optOkDrawer: "直接打开频道列表",
        lblRemDigits: "数字键换台",
        optDigDirect: "停顿后自动跳转",
        optDigConfirm: "按 OK 确认后跳转",
        lblRemChKeys: "启用频道 +/- 实体键 (166/167)",
        btnSaveRemote: "保存遥控设置",
        btnResetRemote: "恢复默认",
        remMapTitle: "当前功能按键映射",
        thFunc: "功能", thShort: "短按", thLong: "长按",
        sysTitle: "电视设备信息",
        sysVer: "应用版本", sysPkg: "应用包名", sysModel: "电视型号", sysSdk: "Android API",
        tMinOneVis: "至少保留 1 个在选台列表展示的源",
        tVisOn: "已在选台列表展示", tVisOff: "已在选台列表隐藏",
        tSwitching: "正在切换电视播放源…", tPushedPlay: "已推送到电视并开始播放",
        tDeleted: "已删除播放源", tNeedUrl: "请输入直播源地址",
        tSavedPlay: "已保存并在电视端切换播放", tSavedSrc: "播放源已保存",
        tReqPl: "已通知电视刷新播放列表", tReqEpg: "已通知电视更新 EPG",
        tSavedHdr: "全局请求头已保存，切台立即生效", tSavedDisp: "显示与播放设置已同步至电视",
        tSavedRem: "遥控设置已同步至电视", tResetRem: "已恢复默认遥控映射",
        tLoadErr: "加载电视配置失败：", tLangSwitched: "已切换为简体中文"
      },
      en: {
        brandSub: "TV Remote Config Console",
        connected: "Connected · v",
        connErr: "Disconnected",
        tabSources: "Sources",
        tabHeaders: "Headers",
        tabDisplay: "Display",
        tabRemote: "Remote",
        secSourcesTitle: "Playlist Sources",
        secSourcesHint: "Manage multiple sources and choose which appear in the TV drawer.",
        srcCount: function(n) { return n + (n === 1 ? " source" : " sources"); },
        emptySources: "No sources yet. Add one below.",
        badgeActive: "● Playing",
        badgeVisible: "In Drawer",
        badgeHidden: "Hidden",
        epgAuto: "EPG: Auto / Default",
        btnReload: "Reload & Play",
        btnPlaySwitch: "Switch & Play",
        btnEdit: "Edit",
        btnDelete: "Delete",
        visTitle: "Show in TV channel drawer",
        formAdd: "Add Playlist Source",
        formEditPrefix: "Edit Source: ",
        formHint: "Supports M3U/TXT playlists, x-tvg-url, #EXTINF attributes, and URL | headers.",
        btnCancelEdit: "Cancel",
        lblSrcName: "Source Name",
        phSrcName: "e.g. Home IPTV / Live TV",
        lblSrcUrl: "Playlist URL",
        phSrcUrl: "http(s):// M3U or TXT playlist URL",
        lblSrcEpg: "EPG URL (optional, auto-detected if empty)",
        phSrcEpg: "Optional, auto-detects M3U x-tvg-url if blank",
        lblSrcUa: "Source User-Agent (optional, uses global default if empty)",
        phSrcUa: "e.g. VLC/3.0.20 LibVLC/3.0.20 or aliplayer",
        btnSaveSource: "Save Source",
        btnSaveAndPlay: "Save & Play",
        syncTitle: "Manual Sync",
        syncHint: "Trigger TV background refresh immediately.",
        btnRefreshPl: "Refresh Playlist",
        btnRefreshEpg: "Refresh EPG",
        hdrUaTitle: "Global User-Agent",
        hdrUaHint: "Used when channel URL or #EXTINF specifies no User-Agent.",
        lblGlobalUa: "User-Agent String (blank = auto default VLC)",
        phGlobalUa: "Auto (Default VLC/3.0.20 LibVLC/3.0.20)",
        chipUaAuto: "Auto (Default VLC)",
        hdrCustomTitle: "Global Custom HTTP Headers",
        hdrCustomHint: "One per line: Header-Name: Value (e.g. Referer, Origin, Cookie).",
        lblGlobalHeaders: "Custom Headers",
        chipExRef: "+ Sample Referer",
        btnClearHeaders: "Clear All",
        btnSaveHeaders: "Save Headers",
        prioTitle: "Header Priority Rules",
        prioHtml: "<strong>Resolution Priority (High to Low):</strong><br/>1. Stream URL <code>|</code> parameters (e.g. <code>|User-Agent=VLC&amp;Referer=...</code>) <strong>highest</strong><br/>2. <code>#EXTINF</code> / <code>#EXTVLCOPT</code> / <code>#KODIPROP</code> attributes<br/>3. Global User-Agent &amp; Custom Headers configured on this page<br/>4. Built-in fallback (<code>VLC/3.0.20 LibVLC/3.0.20</code>)",
        dispScaleTitle: "Text & Logo Size",
        scaleSmT: "Small", scaleSmD: "Compact density",
        scaleMdT: "Medium", scaleMdD: "Balanced default",
        scaleLgT: "Large", scaleLgD: "Easy to read",
        dispOkTitle: "Info Card OK Action",
        okTwoT: "Two-step (Recommended)", okTwoD: "First OK shows card, second focuses recent",
        okDirT: "Direct Recent Strip", okDirD: "First OK immediately focuses recent strip",
        dispIpTitle: "IP Protocol Priority",
        ipV6T: "IPv6 Preferred (Default)", ipV6D: "Try IPv6 first, fall back to IPv4",
        ipV4T: "IPv4 Preferred", ipV4D: "Try IPv4 first, fall back to IPv6",
        dispDnsTitle: "Encrypted DNS (DoH)",
        dnsSysT: "System DNS", dnsSysD: "Lowest LAN latency",
        dnsAliT: "AliDNS DoH", dnsAliD: "223.5.5.5 encrypted",
        dnsCfT: "Cloudflare", dnsCfD: "1.1.1.1 encrypted",
        dispBufTitle: "Startup Buffer & Auto Update",
        bufFastT: "Fast", bufFastD: "300ms Quick zap",
        bufBalT: "Balanced", bufBalD: "400ms Balanced",
        bufStbT: "Stable", bufStbD: "500ms Steady",
        lblAutoPl: "Auto-update Playlist",
        lblAutoEpg: "Auto-update EPG",
        lblEpgOffset: "EPG Timezone Offset",
        btnExportDiag: "Export Playback Diagnostics",
        intervals: { OFF: "Off", STARTUP: "On startup", H6: "Every 6h", H12: "Every 12h", H24: "Every 24h" },
        dispLogoTitle: "Channel Logo Optimization",
        lblLogoPrefetch: "Prefetch Logos",
        hintLogoPrefetch: "Disable to load visible rows only and save RAM",
        lblLogoLoad: "Show Channel Logos",
        hintLogoLoad: "Disable to show channel initials only",
        btnSaveDisplay: "Apply Display & Playback",
        remTitle: "Remote Shortcuts",
        remHint: "Per-action short/long press key assignment is available on TV under Settings → Remote & Controls.",
        lblRemUpDown: "Full-screen Up / Down",
        optUpDownStep: "Zap previous / next channel",
        optUpDownDrawer: "Open channel drawer",
        lblRemOk: "Full-screen OK",
        optOkConfirm: "Show bottom info card",
        optOkDrawer: "Open channel drawer",
        lblRemDigits: "Numeric Keys",
        optDigDirect: "Auto-jump after pause",
        optDigConfirm: "Press OK to tune",
        lblRemChKeys: "Enable Channel +/- Keys (166/167)",
        btnSaveRemote: "Save Remote Settings",
        btnResetRemote: "Restore Defaults",
        remMapTitle: "Current Key Bindings",
        thFunc: "Action", thShort: "Short Press", thLong: "Long Press",
        sysTitle: "TV Device Info",
        sysVer: "Version", sysPkg: "Package", sysModel: "Device", sysSdk: "Android API",
        tMinOneVis: "Keep at least 1 visible source in drawer",
        tVisOn: "Shown in channel drawer", tVisOff: "Hidden from channel drawer",
        tSwitching: "Switching TV source…", tPushedPlay: "Pushed to TV and playing",
        tDeleted: "Source deleted", tNeedUrl: "Please enter playlist URL",
        tSavedPlay: "Saved and playing on TV", tSavedSrc: "Source saved",
        tReqPl: "Requested TV playlist refresh", tReqEpg: "Requested TV EPG refresh",
        tSavedHdr: "Global headers saved", tSavedDisp: "Display settings synced to TV",
        tSavedRem: "Remote settings synced to TV", tResetRem: "Restored default key bindings",
        tLoadErr: "Failed to load TV config: ", tLangSwitched: "Switched to English"
      }
    };

    function tr() { return I18N[currentLang === "en" ? "en" : "zh"]; }

    function applyStaticI18n() {
      var L = tr();
      document.documentElement.lang = currentLang === "en" ? "en" : "zh-CN";
      document.querySelectorAll(".lang-btn").forEach(function(b) {
        b.classList.toggle("active", b.getAttribute("data-lang") === currentLang);
      });
      byId("brandSub").textContent = L.brandSub;
      byId("tabSources").textContent = L.tabSources;
      byId("tabHeaders").textContent = L.tabHeaders;
      byId("tabDisplay").textContent = L.tabDisplay;
      byId("tabRemote").textContent = L.tabRemote;
      byId("secSourcesTitle").textContent = L.secSourcesTitle;
      byId("secSourcesHint").textContent = L.secSourcesHint;
      byId("formTitle").textContent = editingSource ? (L.formEditPrefix + editingSourceName) : L.formAdd;
      byId("formHint").textContent = L.formHint;
      byId("btnResetForm").textContent = L.btnCancelEdit;
      byId("lblSrcName").textContent = L.lblSrcName;
      byId("srcName").placeholder = L.phSrcName;
      byId("lblSrcUrl").textContent = L.lblSrcUrl;
      byId("srcUrl").placeholder = L.phSrcUrl;
      byId("lblSrcEpg").textContent = L.lblSrcEpg;
      byId("srcEpg").placeholder = L.phSrcEpg;
      byId("lblSrcUa").textContent = L.lblSrcUa;
      byId("srcUa").placeholder = L.phSrcUa;
      byId("btnSaveSource").textContent = L.btnSaveSource;
      byId("btnSaveAndPlay").textContent = L.btnSaveAndPlay;
      byId("syncTitle").textContent = L.syncTitle;
      byId("syncHint").textContent = L.syncHint;
      byId("btnRefreshPlaylist").textContent = L.btnRefreshPl;
      byId("btnRefreshEpg").textContent = L.btnRefreshEpg;
      byId("hdrUaTitle").textContent = L.hdrUaTitle;
      byId("hdrUaHint").textContent = L.hdrUaHint;
      byId("lblGlobalUa").textContent = L.lblGlobalUa;
      byId("globalUa").placeholder = L.phGlobalUa;
      byId("chipUaAuto").textContent = L.chipUaAuto;
      byId("hdrCustomTitle").textContent = L.hdrCustomTitle;
      byId("hdrCustomHint").textContent = L.hdrCustomHint;
      byId("lblGlobalHeaders").textContent = L.lblGlobalHeaders;
      byId("chipExRef").textContent = L.chipExRef;
      byId("btnClearHeaders").textContent = L.btnClearHeaders;
      byId("btnSaveHeaders").textContent = L.btnSaveHeaders;
      byId("prioTitle").textContent = L.prioTitle;
      byId("prioBody").innerHTML = L.prioHtml;
      byId("dispScaleTitle").textContent = L.dispScaleTitle;
      byId("scaleSmT").textContent = L.scaleSmT; byId("scaleSmD").textContent = L.scaleSmD;
      byId("scaleMdT").textContent = L.scaleMdT; byId("scaleMdD").textContent = L.scaleMdD;
      byId("scaleLgT").textContent = L.scaleLgT; byId("scaleLgD").textContent = L.scaleLgD;
      byId("dispOkTitle").textContent = L.dispOkTitle;
      byId("okTwoT").textContent = L.okTwoT; byId("okTwoD").textContent = L.okTwoD;
      byId("okDirT").textContent = L.okDirT; byId("okDirD").textContent = L.okDirD;
      byId("dispIpTitle").textContent = L.dispIpTitle;
      byId("ipV6T").textContent = L.ipV6T; byId("ipV6D").textContent = L.ipV6D;
      byId("ipV4T").textContent = L.ipV4T; byId("ipV4D").textContent = L.ipV4D;
      byId("dispDnsTitle").textContent = L.dispDnsTitle;
      byId("dnsSysT").textContent = L.dnsSysT; byId("dnsSysD").textContent = L.dnsSysD;
      byId("dnsAliT").textContent = L.dnsAliT; byId("dnsAliD").textContent = L.dnsAliD;
      byId("dnsCfT").textContent = L.dnsCfT; byId("dnsCfD").textContent = L.dnsCfD;
      byId("dispBufTitle").textContent = L.dispBufTitle;
      byId("bufFastT").textContent = L.bufFastT; byId("bufFastD").textContent = L.bufFastD;
      byId("bufBalT").textContent = L.bufBalT; byId("bufBalD").textContent = L.bufBalD;
      byId("bufStbT").textContent = L.bufStbT; byId("bufStbD").textContent = L.bufStbD;
      byId("lblAutoPl").textContent = L.lblAutoPl;
      byId("lblAutoEpg").textContent = L.lblAutoEpg;
      byId("lblEpgOffset").textContent = L.lblEpgOffset;
      Array.prototype.forEach.call(byId("epgOffsetMinutes").options, function(opt) {
        var mins = Number(opt.value);
        if (mins === 0) {
          opt.textContent = currentLang === "en" ? "0 (Original)" : "0（原始时间）";
        } else if (mins % 60 === 0) {
          var hrs = mins / 60;
          var signH = hrs > 0 ? "+" : "";
          opt.textContent = currentLang === "en" ? (signH + hrs + "h") : (signH + hrs + " 小时");
        } else {
          var signM = mins > 0 ? "+" : "";
          opt.textContent = currentLang === "en" ? (signM + mins + " min") : (signM + mins + " 分钟");
        }
      });
      byId("btnExportDiag").textContent = L.btnExportDiag;
      ["autoPlaylist", "autoEpg"].forEach(function(selId) {
        var sel = byId(selId);
        Array.prototype.forEach.call(sel.options, function(opt) {
          if (L.intervals[opt.value]) opt.textContent = L.intervals[opt.value];
        });
      });
      byId("dispLogoTitle").textContent = L.dispLogoTitle;
      byId("lblLogoPrefetch").textContent = L.lblLogoPrefetch;
      byId("hintLogoPrefetch").textContent = L.hintLogoPrefetch;
      byId("lblLogoLoad").textContent = L.lblLogoLoad;
      byId("hintLogoLoad").textContent = L.hintLogoLoad;
      byId("btnSaveDisplay").textContent = L.btnSaveDisplay;
      byId("remTitle").textContent = L.remTitle;
      byId("remHint").textContent = L.remHint;
      byId("lblRemUpDown").textContent = L.lblRemUpDown;
      byId("remUpDown").options[0].textContent = L.optUpDownStep;
      byId("remUpDown").options[1].textContent = L.optUpDownDrawer;
      byId("lblRemOk").textContent = L.lblRemOk;
      byId("remOk").options[0].textContent = L.optOkConfirm;
      byId("remOk").options[1].textContent = L.optOkDrawer;
      byId("lblRemDigits").textContent = L.lblRemDigits;
      byId("remDigits").options[0].textContent = L.optDigDirect;
      byId("remDigits").options[1].textContent = L.optDigConfirm;
      byId("lblRemChKeys").textContent = L.lblRemChKeys;
      byId("btnSaveRemote").textContent = L.btnSaveRemote;
      byId("btnResetRemote").textContent = L.btnResetRemote;
      byId("remMapTitle").textContent = L.remMapTitle;
      byId("thFunc").textContent = L.thFunc;
      byId("thShort").textContent = L.thShort;
      byId("thLong").textContent = L.thLong;
      byId("sysTitle").textContent = L.sysTitle;
    }

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
        headers: { "Content-Type": "application/json; charset=utf-8" },
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

    document.querySelectorAll(".lang-btn").forEach(function(btn) {
      btn.addEventListener("click", async function() {
        var next = btn.getAttribute("data-lang");
        if (!next) return;
        currentLang = next === "en" ? "en" : "zh";
        applyStaticI18n();
        if (state) renderAll(state, true);
        try {
          var res = await api("/api/language", { language: currentLang });
          renderAll(res.state, false);
          toast(tr().tLangSwitched);
        } catch (e) { toast(e.message, true); }
      });
    });

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

    function renderAll(st, keepLang) {
      state = st;
      if (!keepLang && st && st.language) {
        currentLang = st.language === "en" ? "en" : "zh";
      }
      applyStaticI18n();
      var L = tr();
      if (st.appName) document.title = st.appName;
      byId("connText").textContent = L.connected + (st.versionCode || "");

      renderSources();
      renderHeaders();
      renderDisplay();
      renderRemote();
    }

    function renderSources() {
      var L = tr();
      var list = (state && state.sources) || [];
      var activeId = state ? state.activeSourceId : "";
      byId("srcCountBadge").textContent = L.srcCount(list.length);
      var box = byId("srcList");
      if (!list.length) {
        box.innerHTML = '<div class="card-hint">' + esc(L.emptySources) + '</div>';
        return;
      }
      box.innerHTML = list.map(function(s) {
        var isAct = s.id === activeId;
        var visBadge = s.enabled
          ? '<span class="badge badge-ok">' + esc(L.badgeVisible) + '</span>'
          : '<span class="badge badge-muted">' + esc(L.badgeHidden) + '</span>';
        var actBadge = isAct ? '<span class="badge badge-blue">' + esc(L.badgeActive) + '</span>' : '';
        var epgLine = s.epgUrl ? ('EPG: ' + esc(s.epgUrl)) : esc(L.epgAuto);
        var uaLine = s.userAgent ? (' · UA: ' + esc(s.userAgent)) : '';
        return '<div class="src-item' + (isAct ? ' is-active' : '') + '">'
          + '<div class="src-top">'
          +   '<div class="src-name">' + esc(s.name) + ' ' + actBadge + ' ' + visBadge + '</div>'
          +   '<label class="switch" title="' + esc(L.visTitle) + '"><input type="checkbox" class="src-vis-toggle" data-id="' + esc(s.id) + '"' + (s.enabled ? ' checked' : '') + '/><span class="slider"></span></label>'
          + '</div>'
          + '<div class="src-url">' + esc(s.url) + '</div>'
          + '<div class="src-meta">' + epgLine + uaLine + '</div>'
          + '<div class="src-actions">'
          +   '<button class="btn btn-sm src-play-btn" data-id="' + esc(s.id) + '">' + esc(isAct ? L.btnReload : L.btnPlaySwitch) + '</button>'
          +   '<button class="btn btn-secondary btn-sm src-edit-btn" data-id="' + esc(s.id) + '">' + esc(L.btnEdit) + '</button>'
          +   (list.length > 1 ? '<button class="btn btn-danger btn-sm src-del-btn" data-id="' + esc(s.id) + '">' + esc(L.btnDelete) + '</button>' : '')
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
            toast(tr().tMinOneVis, true);
            return;
          }
          try {
            var res = await api("/api/sources/save", { sources: next, activeSourceId: state.activeSourceId });
            renderAll(res.state);
            toast(el.checked ? tr().tVisOn : tr().tVisOff);
          } catch (e) { toast(e.message, true); }
        });
      });

      box.querySelectorAll(".src-play-btn").forEach(function(el) {
        el.addEventListener("click", async function() {
          var id = el.getAttribute("data-id");
          try {
            toast(tr().tSwitching);
            var res = await api("/api/sources/save", { sources: state.sources, activeSourceId: id, playNow: true });
            renderAll(res.state);
            toast(tr().tPushedPlay);
          } catch (e) { toast(e.message, true); }
        });
      });

      box.querySelectorAll(".src-edit-btn").forEach(function(el) {
        el.addEventListener("click", function() {
          var id = el.getAttribute("data-id");
          var s = state.sources.find(function(x) { return x.id === id; });
          if (!s) return;
          editingSource = true;
          editingSourceName = s.name;
          byId("srcId").value = s.id;
          byId("srcName").value = s.name;
          byId("srcUrl").value = s.url;
          byId("srcEpg").value = s.epgUrl || "";
          byId("srcUa").value = s.userAgent || "";
          byId("formTitle").textContent = tr().formEditPrefix + s.name;
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
            toast(tr().tDeleted);
          } catch (e) { toast(e.message, true); }
        });
      });
    }

    function resetSourceForm() {
      editingSource = false;
      editingSourceName = "";
      byId("srcId").value = "";
      byId("srcName").value = "";
      byId("srcUrl").value = "";
      byId("srcEpg").value = "";
      byId("srcUa").value = "";
      byId("formTitle").textContent = tr().formAdd;
      byId("btnResetForm").style.display = "none";
    }
    byId("btnResetForm").addEventListener("click", resetSourceForm);

    async function submitSource(playNow) {
      var url = byId("srcUrl").value.trim();
      if (!url) { toast(tr().tNeedUrl, true); return; }
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
        toast(playNow ? tr().tSavedPlay : tr().tSavedSrc);
      } catch (e) { toast(e.message, true); }
    }
    byId("btnSaveSource").addEventListener("click", function() { submitSource(false); });
    byId("btnSaveAndPlay").addEventListener("click", function() { submitSource(true); });

    byId("btnRefreshPlaylist").addEventListener("click", async function() {
      try { await api("/api/refresh", { playlist: true, epg: false }); toast(tr().tReqPl); }
      catch (e) { toast(e.message, true); }
    });
    byId("btnRefreshEpg").addEventListener("click", async function() {
      try { await api("/api/refresh", { playlist: false, epg: true }); toast(tr().tReqEpg); }
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
        toast(tr().tSavedHdr);
      } catch (e) { toast(e.message, true); }
    });

    // Tab 3: Display
    function renderDisplay() {
      selectedUiScale = (state && state.uiScale) || "medium";
      selectedInfoOk = (state && state.infoOkMode) || "two_step";
      selectedIpStack = (state && state.ipStackPreference) || "ipv6_first";
      selectedDnsMode = (state && state.dnsMode) || "system";
      selectedBuffer = (state && state.playbackBufferMin) || 300;
      document.querySelectorAll("#uiScaleSeg .seg-card").forEach(function(c) {
        c.classList.toggle("selected", c.getAttribute("data-scale") === selectedUiScale);
      });
      document.querySelectorAll("#infoOkSeg .seg-card").forEach(function(c) {
        c.classList.toggle("selected", c.getAttribute("data-ok") === selectedInfoOk);
      });
      document.querySelectorAll("#ipStackSeg .seg-card").forEach(function(c) {
        c.classList.toggle("selected", c.getAttribute("data-ip") === selectedIpStack);
      });
      document.querySelectorAll("#dnsModeSeg .seg-card").forEach(function(c) {
        c.classList.toggle("selected", c.getAttribute("data-dns") === selectedDnsMode);
      });
      document.querySelectorAll("#bufferSeg .seg-card").forEach(function(c) {
        c.classList.toggle("selected", Number(c.getAttribute("data-buf")) === selectedBuffer);
      });
      byId("autoPlaylist").value = (state && state.autoUpdatePlaylist) || "STARTUP";
      byId("autoEpg").value = (state && state.autoUpdateEpg) || "STARTUP";
      byId("epgOffsetMinutes").value = String((state && state.epgOffsetMinutes) || 0);
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
    document.querySelectorAll("#ipStackSeg .seg-card").forEach(function(c) {
      c.addEventListener("click", function() {
        selectedIpStack = c.getAttribute("data-ip");
        document.querySelectorAll("#ipStackSeg .seg-card").forEach(function(x) { x.classList.toggle("selected", x === c); });
      });
    });
    document.querySelectorAll("#dnsModeSeg .seg-card").forEach(function(c) {
      c.addEventListener("click", function() {
        selectedDnsMode = c.getAttribute("data-dns");
        document.querySelectorAll("#dnsModeSeg .seg-card").forEach(function(x) { x.classList.toggle("selected", x === c); });
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
          ipStackPreference: selectedIpStack,
          dnsMode: selectedDnsMode,
          playbackBufferMin: selectedBuffer,
          autoUpdatePlaylist: byId("autoPlaylist").value,
          autoUpdateEpg: byId("autoEpg").value,
          epgOffsetMinutes: Number(byId("epgOffsetMinutes").value || 0),
          logoPrefetch: byId("logoPrefetch").checked,
          logoLoad: byId("logoLoad").checked
        });
        renderAll(res.state);
        toast(tr().tSavedDisp);
      } catch (e) { toast(e.message, true); }
    });

    // Tab 4: Remote & System
    function renderRemote() {
      var L = tr();
      byId("remUpDown").value = (state && state.remoteUpDown) || "CHANNEL_STEP";
      byId("remOk").value = (state && state.remoteOk) || "CONFIRM";
      byId("remDigits").value = (state && state.remoteDigits) || "DIRECT";
      byId("remChKeys").checked = state ? !!state.remoteChannelKeys : true;

      var rows = (state && state.remoteBindings) || [];
      byId("bindingsBody").innerHTML = rows.map(function(b) {
        var label = currentLang === "en" ? (b.labelEn || b.label) : (b.labelZh || b.label);
        var sk = currentLang === "en" ? (b.shortKeyEn || b.shortKey) : (b.shortKeyZh || b.shortKey);
        var lk = currentLang === "en" ? (b.longKeyEn || b.longKey) : (b.longKeyZh || b.longKey);
        return "<tr><td>" + esc(label) + "</td><td>" + esc(sk) + "</td><td>" + esc(lk) + "</td></tr>";
      }).join("");

      byId("sysInfo").innerHTML = [
        [L.sysVer, (state.versionName || "") + " (" + (state.versionCode || "") + ")"],
        [L.sysPkg, state.packageName || ""],
        [L.sysModel, state.deviceModel || "Android TV"],
        [L.sysSdk, "API " + (state.androidSdk || "")]
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
        toast(tr().tSavedRem);
      } catch (e) { toast(e.message, true); }
    });
    byId("btnResetRemote").addEventListener("click", async function() {
      try {
        var res = await api("/api/remote", { reset: true });
        renderAll(res.state);
        toast(tr().tResetRem);
      } catch (e) { toast(e.message, true); }
    });
    byId("backupFileInput").addEventListener("change", function(ev) {
      var file = ev.target.files && ev.target.files[0];
      if (!file) return;
      var reader = new FileReader();
      reader.onload = async function() {
        try {
          var res = await api("/api/restore", { backupJson: String(reader.result || "") });
          renderAll(res.state);
          toast(currentLang === "en" ? "Backup restored" : "已恢复备份配置");
        } catch (e) { toast(e.message, true); }
      };
      reader.readAsText(file);
    });
    byId("btnRestoreJson").addEventListener("click", async function() {
      var raw = byId("restoreJsonArea").value.trim();
      if (!raw) return toast(currentLang === "en" ? "Paste backup JSON first" : "请先粘贴备份 JSON", true);
      try {
        var res = await api("/api/restore", { backupJson: raw });
        renderAll(res.state);
        toast(currentLang === "en" ? "Backup restored" : "已恢复备份配置");
      } catch (e) { toast(e.message, true); }
    });

    // Initial load
    applyStaticI18n();
    api("/api/state").then(renderAll).catch(function(e) {
      byId("connText").textContent = tr().connErr;
      toast(tr().tLoadErr + e.message, true);
    });
  </script>
</body>
</html>
""".trimIndent()
    }
}
