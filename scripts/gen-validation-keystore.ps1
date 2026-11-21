#!/usr/bin/env pwsh
<#
.SYNOPSIS
    生成 CI 固定签名用的 validation keystore（跨 run 同证书 → 设备可一直 `adb install -r`）。

.DESCRIPTION
    维护者本机运行一次即可。产物只落在被 .gitignore 忽略的位置：
      - keystore   : local\validation-signing.jks        （*.jks / /local/ 已忽略）
      - base64 文本: local\validation-signing.jks.b64.txt （*.b64.txt 已忽略）
    然后把这四个值写进 GitHub Secrets，CI 的 validation/debug 包就永久同签名：
      VALIDATION_KEYSTORE_BASE64 / VALIDATION_KEYSTORE_PASSWORD /
      VALIDATION_KEY_ALIAS / VALIDATION_KEY_PASSWORD
    没有配置 secrets 时 CI 仍能构建，只是每个 run 用临时 debug keystore。
    完整说明（含验证方法、回滚代价）见 docs/CI_STABLE_SIGNING.md。

    需要 JDK 17+（keytool 在 PATH 或 JAVA_HOME\bin 下）。
    PKCS12 keystore 只支持 store 口令 == key 口令，脚本会强制这一点。

.PARAMETER KeystorePath
    输出 keystore 路径。默认 local\validation-signing.jks（仓库根目录下）。

.PARAMETER Alias
    密钥别名，默认 iqtvlite-validation；需与 VALIDATION_KEY_ALIAS secret 一致。

.PARAMETER Dname
    证书主体，默认 CN=IQTV Lite Validation, OU=CI, O=IQTV, C=CN。

.PARAMETER ValidityDays
    有效期天数，默认 10000（≈27 年），保证长期可覆盖升级。

.PARAMETER StorePassword
    keystore 口令；不传则读环境变量 VALIDATION_KEYSTORE_PASSWORD，再不行就交互输入。

.PARAMETER KeyPassword
    key 口令；PKCS12 下必须与 StorePassword 相同，默认沿用 StorePassword。

.PARAMETER Base64OutPath
    base64 单行文本输出路径，默认 local\validation-signing.jks.b64.txt。

.PARAMETER Force
    覆盖已有 keystore。警告：重建会换证书指纹，已装的旧包将无法覆盖升级。

.PARAMETER NoBase64
    只生成 keystore，不写 base64 文本（keystore 已在本地时用）。

.EXAMPLE
    pwsh scripts/gen-validation-keystore.ps1

.EXAMPLE
    pwsh scripts/gen-validation-keystore.ps1 -StorePassword '<强口令>' -Force
#>
[CmdletBinding()]
param(
    [string]$KeystorePath = (Join-Path $PSScriptRoot '..\local\validation-signing.jks'),
    [string]$Alias = 'iqtvlite-validation',
    [string]$Dname = 'CN=IQTV Lite Validation, OU=CI, O=IQTV, C=CN',
    [int]$ValidityDays = 10000,
    [string]$StorePassword,
    [string]$KeyPassword,
    [string]$Base64OutPath,
    [switch]$Force,
    [switch]$NoBase64
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrEmpty($Base64OutPath)) {
    $Base64OutPath = Join-Path $repoRoot 'local\validation-signing.jks.b64.txt'
}

function Resolve-Keytool {
    $candidates = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrEmpty($env:JAVA_HOME)) {
        $candidates.Add((Join-Path $env:JAVA_HOME 'bin\keytool.exe'))
    }
    $candidates.Add('keytool')
    $candidates.Add('keytool.exe')
    foreach ($candidate in $candidates) {
        $cmd = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($cmd -and $cmd.Source) { return $cmd.Source }
    }
    throw ('找不到 keytool：请安装 JDK 17+（例如 winget install Microsoft.OpenJDK.17），' +
        '或把 JAVA_HOME 指向 JDK 安装目录。')
}

function Read-SecretValue {
    param(
        [string]$EnvName,
        [string]$Prompt
    )
    $fromEnv = [Environment]::GetEnvironmentVariable($EnvName)
    if (-not [string]::IsNullOrEmpty($fromEnv)) { return $fromEnv }
    if ([Console]::IsInputRedirected) {
        throw "未提供口令且环境变量 $EnvName 为空；非交互环境请显式传 -StorePassword。"
    }
    $secure = Read-Host -Prompt $Prompt -AsSecureString
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

# ── 口令 ─────────────────────────────────────────────────────────────────
if ([string]::IsNullOrEmpty($StorePassword)) {
    $StorePassword = Read-SecretValue -EnvName 'VALIDATION_KEYSTORE_PASSWORD' `
        -Prompt 'validation keystore 口令（用于 store 和 key，建议 16+ 位）'
}
if ([string]::IsNullOrEmpty($KeyPassword)) { $KeyPassword = $StorePassword }
if ($KeyPassword -ne $StorePassword) {
    throw ('PKCS12 keystore 只支持 store/key 同一口令；请让 VALIDATION_KEY_PASSWORD 与 ' +
        'VALIDATION_KEYSTORE_PASSWORD 保持一致。')
}

# ── keytool ──────────────────────────────────────────────────────────────
$keytool = Resolve-Keytool

# ── 路径 ─────────────────────────────────────────────────────────────────
$fullPath = [System.IO.Path]::GetFullPath($KeystorePath)
if ($fullPath -notmatch '\.(jks|keystore)$') {
    throw "KeystorePath 必须以 .jks 或 .keystore 结尾（当前：$fullPath）"
}
$keystoreDir = [System.IO.Path]::GetDirectoryName($fullPath)
if (-not (Test-Path -LiteralPath $keystoreDir)) {
    New-Item -ItemType Directory -Path $keystoreDir -Force | Out-Null
}
if (Test-Path -LiteralPath $fullPath) {
    if (-not $Force) {
        throw ("已存在 $fullPath。重建会改变证书指纹、导致已装包无法覆盖升级；" +
            '确认要重建请加 -Force。')
    }
    Remove-Item -LiteralPath $fullPath -Force
}

# ── 生成 ─────────────────────────────────────────────────────────────────
Write-Host "==> keytool -genkeypair（alias=$Alias, RSA 2048, SHA256withRSA, 有效期 $ValidityDays 天）"
Write-Host "    keystore: $fullPath"
$keytoolArgs = @(
    '-genkeypair', '-v',
    '-keystore', $fullPath,
    '-storetype', 'PKCS12',
    '-alias', $Alias,
    '-keyalg', 'RSA',
    '-keysize', '2048',
    '-sigalg', 'SHA256withRSA',
    '-validity', $ValidityDays,
    '-dname', $Dname,
    '-storepass', $StorePassword,
    '-keypass', $StorePassword
)
& $keytool @keytoolArgs
if ($LASTEXITCODE -ne 0) { throw "keytool -genkeypair 失败（exit $LASTEXITCODE）" }

# ── 证书指纹（后续每个 CI run 都必须一致） ───────────────────────────────
$listed = (& $keytool -list -v -keystore $fullPath -storepass $StorePassword -alias $Alias 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "keytool -list 失败（exit $LASTEXITCODE）" }
$match = [regex]::Match($listed, 'SHA256:\s*([0-9A-Fa-f:]{64,})')
$fingerprint = if ($match.Success) {
    $match.Groups[1].Value.Replace(':', '').ToLowerInvariant()
}
else {
    '(未从 keytool 输出解析到 SHA256，请手动运行 keytool -list -v 查看)'
}

# ── base64（单行，供 VALIDATION_KEYSTORE_BASE64 secret） ─────────────────
$base64Written = $false
if (-not $NoBase64) {
    $base64Dir = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($Base64OutPath))
    if (-not (Test-Path -LiteralPath $base64Dir)) {
        New-Item -ItemType Directory -Path $base64Dir -Force | Out-Null
    }
    $base64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($fullPath))
    Set-Content -LiteralPath $Base64OutPath -Value $base64 -NoNewline -Encoding ascii
    $base64Written = $true
}

# ── 结果与后续步骤 ───────────────────────────────────────────────────────
Write-Host ''
Write-Host '=== 生成完成 ==='
Write-Host "keystore       : $fullPath"
Write-Host "alias          : $Alias"
Write-Host "SHA-256 指纹   : $fingerprint"
if ($base64Written) {
    Write-Host "base64（单行）: $([System.IO.Path]::GetFullPath($Base64OutPath))"
}
Write-Host ''
Write-Host '确认以上文件都在 .gitignore 覆盖范围（*.jks / /local/ / *.b64.txt），不要提交。'
Write-Host ''
Write-Host '配置 GitHub Secrets（仓库根目录执行，需 gh 已登录；<owner>/<repo> 换成实际值）：'
if ($base64Written) {
    Write-Host ('  Get-Content -Raw local\validation-signing.jks.b64.txt | ' +
        'gh secret set VALIDATION_KEYSTORE_BASE64 -R <owner>/<repo>')
}
else {
    Write-Host ('  （用本机已有 keystore 时自行 base64：' +
        '[Convert]::ToBase64String([IO.File]::ReadAllBytes("<keystore>")) | ' +
        'gh secret set VALIDATION_KEYSTORE_BASE64 -R <owner>/<repo>）')
}
Write-Host '  gh secret set VALIDATION_KEYSTORE_PASSWORD -R <owner>/<repo> --body "<store 口令>"'
Write-Host "  gh secret set VALIDATION_KEY_ALIAS -R <owner>/<repo> --body `"$Alias`""
Write-Host '  gh secret set VALIDATION_KEY_PASSWORD -R <owner>/<repo> --body "<与 store 口令相同>"'
Write-Host ''
Write-Host '验证签名稳定（两个不同 run 的 app-debug.apk 必须给出同一个 SHA-256）：'
Write-Host '  apksigner verify --print-certs app-debug.apk | grep "certificate SHA-256 digest"'
Write-Host "本次生成的期望值：$fingerprint"
Write-Host ''
Write-Host '注意：keystore / 口令丢失不可恢复；换证书等同于换应用身份，已装包只能卸载重装。'
