#!/usr/bin/env pwsh
# iqtvlite 开发环境设置（Windows / PowerShell）
#
# 用法：  . .\scripts\dev-env.ps1
#
# 本机现实情况：
#   - 没有 Visual Studio（没有 link.exe）→ 用 MinGW-w64 + x86_64-pc-windows-gnu 工具链
#   - 没有 WSL → Android NDK 交叉编译需要额外安装 SDK/NDK，且 libmpv AAR 建议在 Linux 上构建
#
# 一次性安装（已完成）：
#   rustup toolchain install stable-x86_64-pc-windows-gnu
#   winget install BrechtSanders.WinLibs.POSIX.UCRT

$ErrorActionPreference = 'Stop'

# --- Rust ---
$env:PATH = "$env:USERPROFILE\.cargo\bin;$env:PATH"

# --- MinGW-w64 (WinLibs) ---
$mingw = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like '*WinLibs*' } |
    Select-Object -First 1
if ($mingw) {
    $bin = Join-Path $mingw.FullName 'mingw64\bin'
    if (Test-Path $bin) { $env:PATH = "$bin;$env:PATH" }
}

# --- Android (如已安装) ---
if (-not $env:ANDROID_HOME) {
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $sdk) {
        $env:ANDROID_HOME = $sdk
        $env:ANDROID_SDK_ROOT = $sdk
    }
}
if ($env:ANDROID_HOME) {
    $ndk = Get-ChildItem (Join-Path $env:ANDROID_HOME 'ndk') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($ndk) {
        $env:ANDROID_NDK_HOME = $ndk.FullName
        $env:ANDROID_NDK_ROOT = $ndk.FullName
    }
}

Write-Host "cargo   : $((cargo --version) -join ' ')"
Write-Host "rustc   : $((rustc --version) -join ' ')"
Write-Host "toolchain: $(rustup show active-toolchain)"
if ($env:ANDROID_HOME) { Write-Host "ANDROID_HOME: $env:ANDROID_HOME" }
if ($env:ANDROID_NDK_HOME) { Write-Host "ANDROID_NDK_HOME: $env:ANDROID_NDK_HOME" } else { Write-Host "ANDROID_NDK_HOME: (未安装)" }
